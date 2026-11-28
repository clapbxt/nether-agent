package dev.nether.ov.menu.title;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.nether.ov.features.screenshots.Screenshots;
import dev.nether.ov.features.social.Party;
import dev.nether.ov.features.social.WorldShare;
import dev.nether.ov.framework.Input;
import dev.nether.ov.framework.Modules;
import dev.nether.ov.game.Leave;
import dev.nether.ov.menu.Menu;
import dev.nether.ov.ui.Gfx;
import dev.nether.ov.ui.ModIcons;
import dev.nether.ov.ui.Vanilla;

/**
 * The pause screen with any mix of mods, on every version and loader. Every widget on the screen is read without
 * names (Leave: the widget base class's width / height / x / y and its public active / visible flags) and sorted:
 * <ul>
 * <li>vanilla buttons we know by their text key: Back to Game and Save and Quit / Disconnect stay real and frame the
 *   column; Options and the small ones (Advancements, Statistics, LAN, Feedback, Bugs, Reporting, Friends, Server
 *   Links) become our square buttons and are pressed through their callbacks (disabled ones stay disabled);</li>
 * <li>the loader's mod list button (Mod Menu, Forge / NeoForge) is the Mods row under Nether;</li>
 * <li>everything else a mod or a newer game adds is left a real widget, drawn and clicked by the game itself (its own
 *   icon, text, tooltip and behaviour), and flowed into the column between Mods and the leave button: wide ones a row
 *   each, halves in pairs, icons in rows;</li>
 * <li>widgets that are too big to move, the screen title, and widgets that move themselves (a mod that positions its
 *   buttons every frame, FancyMenu layouts) are fixed obstacles: nothing of ours is drawn over them.</li>
 * </ul>
 * Our own square buttons go down the column's sides and fall back to free cells in the bottom left when their spot is
 * taken or off screen. When the column can't be built cleanly (no Back / Quit, they move themselves, an obstacle sits
 * in the column, the window is too short) or the "Nether pause screen" setting is off, every widget goes back where
 * the game and its mods put it and only our chips are added, in a free corner: never worse than the game's own screen.
 * 1.8.9 / 1.12.2 always use that mode. Coordinates: widgets in GUI px, our drawing in units (2 per GUI px).
 */
final class PauseMenu {
    private PauseMenu() { }

    private static final int OTHER = 0, BACK = 1, QUIT = 2, OPTIONS = 3, MODS = 4, SMALL = 5, TITLE = 6;
    private static final float SZ = 40, GAP = 8;

    /** the pause screen the state below belongs to */
    private static Object screen;
    /** its widgets when we last looked (identity), to notice a rebuild (resize, back from another screen, a mod) */
    private static final List<Object> sig = new ArrayList<>();
    /** where the game / its mods put each widget, where we put it last frame, where it was before a Nether page hid it */
    private static final Map<Object, int[]> home = new IdentityHashMap<>(), put = new IdentityHashMap<>(), hid = new IdentityHashMap<>();
    /** widgets that moved away from where we put them: they position themselves, so we never move them again */
    private static final Set<Object> pinned = Collections.newSetFromMap(new IdentityHashMap<>());
    /** {kind, icon, tooltip} per widget (text keys never change, so read once) */
    private static final Map<Object, Object[]> kinds = new IdentityHashMap<>();
    /** the column failed for this build of the screen: the game's own layout until it is rebuilt */
    private static boolean gaveUp;
    /** where the game's tooltip for the hovered widget goes (units); our squares under it are skipped */
    private static float[] tipZone;
    /** rects (units) already used this frame: obstacles, the column, our controls */
    private static final List<float[]> taken = new ArrayList<>();

    /** No pause screen any more: drop every reference. */
    static void forget() {
        screen = null;
        sig.clear();
        home.clear(); put.clear(); hid.clear(); pinned.clear(); kinds.clear();
        gaveUp = false;
    }

    /** A Nether page opened over the pause screen: its widgets go off screen, remembering where they were. */
    static void hide(Object s) {
        if (s != screen) { forget(); screen = s; }
        for (Object w : Leave.widgets(s)) {
            if (hid.containsKey(w)) continue;
            int[] r = Leave.bounds(w);
            if (r == null || r[0] <= -3000) continue;
            if (Leave.place(w, -4000, -4000)) hid.put(w, new int[] {r[0], r[1]});
        }
    }

    /** Back from the Nether page: every hidden widget returns to where it was (the game rebuilds its own right after). */
    static void unhide() {
        for (Map.Entry<Object, int[]> e : hid.entrySet()) Leave.place(e.getKey(), e.getValue()[0], e.getValue()[1]);
        hid.clear();
    }

    /** The pause screen this frame (units at MainMenu's scale, W x H). */
    static void draw(Object s, boolean legacy, float W, float H) {
        if (s != screen) { forget(); screen = s; }
        if (TEST && !legacy) devMods(s);
        taken.clear();
        tipZone = null;
        List<Object> ws = widgets(s);
        track(ws);
        if (!legacy && !gaveUp && MainMenu.MODULE.bool("pauseLayout")) {
            if (column(ws, W, H)) return;
            gaveUp = true;
            taken.clear();
        }
        corner(s, ws, W, H);
    }

    // ---------------------------------------------------------------- widgets

    /** The screen's widgets: everything sharing the most common widget base class (name-free, any version). */
    private static List<Object> widgets(Object s) {
        List<Object> all = Leave.widgets(s);
        Map<Class<?>, Integer> count = new HashMap<>();
        Class<?> best = null;
        for (Object w : all) {
            Class<?> b = Leave.base(w);
            if (b == null) continue;
            int n = count.merge(b, 1, Integer::sum);
            if (best == null || n > count.get(best)) best = b;
        }
        List<Object> ws = new ArrayList<>();
        if (best != null) for (Object w : all) if (Leave.base(w) == best) ws.add(w);
        return ws;
    }

    /** Notice rebuilds (new widgets: take where they are as home) and widgets that moved themselves (pin them). */
    private static void track(List<Object> ws) {
        boolean same = ws.size() == sig.size();
        for (int i = 0; same && i < ws.size(); i++) same = ws.get(i) == sig.get(i);
        if (!same) {
            Set<Object> now = Collections.newSetFromMap(new IdentityHashMap<>());
            now.addAll(ws);
            home.keySet().retainAll(now); put.keySet().retainAll(now); pinned.retainAll(now); kinds.keySet().retainAll(now);
            for (Object w : ws) {
                int[] r = Leave.bounds(w), p = put.get(w);
                if (r == null) continue;
                // where we left it: still ours (home unchanged); anywhere else: the game or a mod (re)placed it
                if (p == null || p[0] != r[0] || p[1] != r[1]) { home.put(w, new int[] {r[0], r[1]}); put.remove(w); pinned.remove(w); }
            }
            sig.clear();
            sig.addAll(ws);
            gaveUp = false;
            return;
        }
        for (Object w : ws) {
            int[] p = put.get(w);
            if (p == null) continue;
            int[] r = Leave.bounds(w);
            if (r != null && (r[0] != p[0] || r[1] != p[1])) { pinned.add(w); put.remove(w); home.put(w, new int[] {r[0], r[1]}); }
        }
    }

    private static Object[] kind(Object w) {
        Object[] k = kinds.get(w);
        if (k == null) kinds.put(w, k = classify(w));
        return k;
    }

    private static final String[][] SMALLS = {{"gui.advancements", "advancements", "Advancements"}, {"gui.stats", "stats", "Statistics"},
        {"menu.shareToLan", "lan", "Open to LAN"}, {"options.worldOptions.button", "home", "World options"}, {"menu.sendFeedback", "chat", "Give feedback"},
        {"menu.reportBugs", "bug", "Report bugs"}, {"menu.feedback", "chat", "Feedback"}, {"menu.playerReporting", "report", "Player reporting"},
        {"menu.server_links", "server", "Server links"}};

    private static Object[] classify(Object w) {
        boolean btn = Leave.isButton(w);
        if (btn) {
            if (Leave.says(w, "'menu.returnToGame'")) return new Object[] {BACK};
            if (Leave.says(w, "'menu.returnToMenu'") || Leave.says(w, "'menu.disconnect'")) return new Object[] {QUIT};
            if (Leave.says(w, "'menu.options'")) return new Object[] {OPTIONS};
            if (modList(w)) return new Object[] {MODS};
            for (String[] e : SMALLS) if (Leave.says(w, "'" + e[0] + "'")) return new Object[] {SMALL, e[1], e[2]};
            // 26.x's icon-only Friends button (vanilla's own class; a mod's "Friends" button stays the mod's)
            Class<?> c = w.getClass();
            if (c.getName().startsWith("net.minecraft.") && c.getSimpleName().contains("Friends")) return new Object[] {SMALL, "friends", "Friends"};
            return new Object[] {OTHER};
        }
        if (Leave.says(w, "'menu.game'") || Leave.says(w, "'menu.paused'")) return new Object[] {TITLE};
        return new Object[] {OTHER};
    }

    /** The loader's mod list button: Mod Menu (its class or key), Forge / NeoForge (fml keys). */
    private static boolean modList(Object w) {
        return w.getClass().getName().toLowerCase(Locale.ROOT).contains("modmenu") || Leave.says(w, "'modmenu.title'")
            || Leave.says(w, "'fml.menu.mods'") || Leave.says(w, "'fml.menu.modoptions'");
    }

    /**
     * The widget has a game tooltip (1.19.3+): one of its base class's objects (the Tooltip, 1.19.3-1.20.4) or one of
     * theirs (the Tooltip inside WidgetTooltipHolder, 1.20.5+) holds a text, i.e. a value of the type the widget's own
     * message has. Name-free; older versions draw tooltips in code we can't see, so false there.
     */
    private static boolean hasTip(Object w) {
        Object[] k = kind(w);
        if (k.length > 3) return (Boolean) k[3];
        boolean tip = false;
        try {
            Class<?> b = Leave.base(w), text = null;
            for (java.lang.reflect.Field f : b.getDeclaredFields()) if (f.getType().isInterface() && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) { text = f.getType(); break; }
            if (text != null) for (java.lang.reflect.Field f : b.getDeclaredFields()) {
                Object v = val(w, f, text);
                if (v == null) continue;
                if (holdsText(v, text)) { tip = true; break; }
                for (Class<?> c = v.getClass(); !tip && c != null && c != Object.class; c = c.getSuperclass())
                    for (java.lang.reflect.Field g : c.getDeclaredFields()) {
                        Object u = val(v, g, text);
                        if (u != null && holdsText(u, text)) { tip = true; break; }
                    }
                if (tip) break;
            }
        } catch (Throwable ignored) { }
        kinds.put(w, new Object[] {k[0], k.length > 1 ? k[1] : null, k.length > 2 ? k[2] : null, tip});
        return tip;
    }

    /** f's value on o when it is one of the game's own objects (not a text, collection or primitive), else null. */
    private static Object val(Object o, java.lang.reflect.Field f, Class<?> text) {
        if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType().isArray()) return null;
        try {
            f.setAccessible(true);
            Object v = f.get(o);
            return v == null || text.isInstance(v) || v instanceof java.util.Collection || v instanceof String || !v.getClass().getName().startsWith("net.minecraft.") ? null : v;
        } catch (Throwable t) { return null; }
    }

    private static boolean holdsText(Object o, Class<?> text) {
        for (java.lang.reflect.Field f : o.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !text.isAssignableFrom(f.getType())) continue;
            try { f.setAccessible(true); if (f.get(o) != null) return true; } catch (Throwable ignored) { }
        }
        return false;
    }

    /** Puts a widget at (x, y) GUI px and remembers it as ours; false when it can't be moved. */
    private static boolean move(Object w, int x, int y) {
        if (!Leave.place(w, x, y)) return false;
        put.put(w, new int[] {x, y});
        return true;
    }

    /** Every widget we moved goes back where the game / its mods put it. */
    private static void restore() {
        for (Object w : new ArrayList<>(put.keySet())) {
            int[] h = home.get(w);
            if (h != null) Leave.place(w, h[0], h[1]);
        }
        put.clear();
    }

    // ---------------------------------------------------------------- the column

    /** One thing in the column: a real widget, or one of ours (label != null) w x h GUI px. */
    private static final class Item {
        final Object w; final String label; final int iw, ih;
        int x, y;
        Item(Object w, String label, int iw, int ih) { this.w = w; this.label = label; this.iw = iw; this.ih = ih; }
    }

    private static final class Sq {
        final String id, icon, tip; final Runnable act; final boolean on, party;
        Sq(String id, String icon, String tip, Runnable act, boolean on, boolean party) { this.id = id; this.icon = icon; this.tip = tip; this.act = act; this.on = on; this.party = party; }
    }

    private static boolean column(List<Object> ws, float W, float H) {
        Object back = null, quit = null, opts = null, mods = null;
        List<Object> free = new ArrayList<>(), small = new ArrayList<>(), fixed = new ArrayList<>();
        for (Object w : ws) {
            if (!Leave.flags(w)[1]) continue; // invisible: nothing to place or avoid
            int[] r = Leave.bounds(w);
            if (r == null) continue;
            int k = (Integer) kind(w)[0];
            if (pinned.contains(w)) {
                if (k == BACK || k == QUIT) return false; // something lays the screen out every frame: leave it to it
                fixed.add(w);
                continue;
            }
            if (k == BACK && back == null) back = w;
            else if (k == QUIT && quit == null) quit = w;
            else if (k == OPTIONS && opts == null) opts = w;
            else if (k == MODS && mods == null) mods = w;
            else if (k == SMALL) small.add(w);
            else if (k == TITLE || r[2] > 400 || r[3] > 30 || r[2] < 8 || r[3] < 8) fixed.add(w);
            else free.add(w);
        }
        int[] hb = back == null ? null : home.get(back), rb = back == null ? null : Leave.bounds(back);
        if (hb == null || rb == null || quit == null || Leave.bounds(quit) == null) return false;
        int cx = hb[0], cw = rb[2];
        float gw = W / 2, gh = H / 2;

        // rows: Back, Nether, Mods, everything else in reading order (wide, then halves, then icons), Leave
        List<List<Item>> rows = new ArrayList<>();
        rows.add(one(new Item(back, null, cw, rb[3])));
        rows.add(one(new Item(null, "Nether", cw, 20)));
        int[] rm = mods == null ? null : Leave.bounds(mods);
        boolean modsReal = rm != null && rm[2] >= 150;
        if (mods != null) rows.add(one(modsReal ? new Item(mods, null, rm[2], rm[3]) : new Item(null, "Mods", cw, 20)));
        free.sort((p, q) -> {
            int[] a = Leave.bounds(p), b = Leave.bounds(q), ha = home.get(p), hq = home.get(q);
            int ca = size(a[2], cw), cb = size(b[2], cw);
            if (ca != cb) return Integer.compare(ca, cb);
            int ya = ha == null ? a[1] : ha[1], yb = hq == null ? b[1] : hq[1];
            if (ya != yb) return Integer.compare(ya, yb);
            return Integer.compare(ha == null ? a[0] : ha[0], hq == null ? b[0] : hq[0]);
        });
        List<Item> cur = new ArrayList<>();
        int curW = 0;
        for (Object w : free) {
            int[] r = Leave.bounds(w);
            if (!cur.isEmpty() && curW + 4 + r[2] > cw) { rows.add(cur); cur = new ArrayList<>(); curW = 0; }
            curW += (cur.isEmpty() ? 0 : 4) + r[2];
            cur.add(new Item(w, null, r[2], r[3]));
        }
        if (!cur.isEmpty()) rows.add(cur);
        int[] rq = Leave.bounds(quit);
        rows.add(one(new Item(quit, null, rq[2], rq[3])));

        int total = -4;
        for (List<Item> row : rows) total += rowH(row);
        int minTop = 4;
        for (Object w : fixed) {
            int[] r = Leave.bounds(w);
            if (r[1] + r[3] <= hb[1]) minTop = Math.max(minTop, r[1] + r[3] + 4);
        }
        // where the game put Back to Game; a column too tall for that is centred between the title and the bottom
        int top = hb[1];
        if (top + total > gh - 16) top = (int) Math.max(minTop, Math.min(top, (minTop + gh - 4 - total) / 2));
        if (top + total > gh - 2) return false; // window too short for everything

        // place the rows; each centred in the column, items centred in their row
        int y = top;
        for (List<Item> row : rows) {
            int rh = rowH(row), rw = -4;
            for (Item it : row) rw += it.iw + 4;
            int x = cx + (cw - rw) / 2;
            for (Item it : row) {
                it.x = x;
                it.y = y + (rh - 4 - it.ih) / 2;
                x += it.iw + 4;
                if (it.x < 0 || it.x + it.iw > gw) return false;
                for (Object f : fixed) if (overlaps(Leave.bounds(f), it.x, it.y, it.iw, it.ih)) return false;
            }
            y += rh;
        }
        for (List<Item> row : rows) for (Item it : row) if (it.w != null && !move(it.w, it.x, it.y)) return false;
        // what becomes ours goes off screen (pressed through its callback); one that won't move stays a real widget
        boolean optsOff = opts != null && move(opts, -4000, -4000), modsOff = mods != null && !modsReal && move(mods, -4000, -4000);
        if (mods != null && !modsReal && !modsOff) return false;
        List<Object> smallOff = new ArrayList<>();
        for (Object w : small) if (move(w, -4000, -4000)) smallOff.add(w);

        for (Object f : fixed) take(Leave.bounds(f), 4);
        for (List<Item> row : rows) for (Item it : row) take(new int[] {it.x, it.y, it.iw, it.ih}, 4);
        take(new int[] {cx, top, cw, total}, 2);

        // the game draws the hovered mod widget's tooltip over everything of ours: keep our squares out of its way
        float MX = MainMenu.mx(), MY = MainMenu.my();
        if (!Input.menuOpen) for (Object w : free) {
            int[] r = Leave.bounds(w);
            if (MX >= r[0] * 2 && MX < (r[0] + r[2]) * 2 && MY >= r[1] * 2 && MY < (r[1] + r[3]) * 2 && hasTip(w)) tipZone = new float[] {MX - 4, MY, 380, 150};
        }

        // ours in the column
        for (List<Item> row : rows) for (Item it : row) {
            if (it.label == null) continue;
            boolean nether = it.label.equals("Nether");
            if (wideButton(it.x * 2, it.y * 2, it.iw * 2, it.label, nether)) {
                if (nether) Menu.openAt(Menu.lastCat);
                else Leave.click(mods);
            }
        }

        // squares: left of the column down from Back, right of it up from Leave; the rest in free cells bottom left
        float lx = cx * 2 - GAP - SZ, rx = (cx + cw) * 2 + GAP, ty = top * 2, ly = (y - 4 - rq[3]) * 2;
        List<Sq> left = new ArrayList<>(), right = new ArrayList<>(), grid = new ArrayList<>();
        final Object o = opts;
        if (optsOff) left.add(new Sq("po", "options", "Options", () -> Leave.click(o), Leave.flags(o)[0], false));
        left.add(new Sq("pw", "wardrobe", "Wardrobe", () -> Menu.openAt(Menu.T_WARDROBE), true, false));
        if (Modules.SHOTS.enabled()) right.add(new Sq("pg", "screenshots", "Screenshots", Screenshots::show, true, false));
        right.add(new Sq("ph", "hudeditor", "Edit HUD", Menu::openHudEditor, true, false));
        if (WorldShare.server() != null) right.add(new Sq("pi", "invite", WorldShare.sharing() ? "Sharing this world" : "Invite friends", WorldShare::show, true, false));
        if (Party.ready()) right.add(new Sq("pp", "party", Party.size() > 1 ? "Party  ·  " + Party.size() : "Party", Party::toggleDrawer, true, true));
        boolean friends = false;
        int n = 0;
        for (Object w : smallOff) {
            Object[] k = kind(w);
            friends |= "friends".equals(k[1]);
            grid.add(new Sq("px" + n++, (String) k[1], (String) k[2], () -> Leave.click(w), Leave.flags(w)[0], false));
        }
        if (!friends) grid.add(new Sq("pf", "friends", "Friends", () -> Menu.openAt(Menu.T_FRIENDS), true, false));
        List<Sq> spill = new ArrayList<>();
        for (int i = 0; i < left.size(); i++) if (!square(left.get(i), lx, ty + i * (SZ + GAP), W, H)) spill.add(left.get(i));
        for (int i = 0; i < right.size(); i++) if (!square(right.get(i), rx, ly - i * (SZ + GAP), W, H)) spill.add(right.get(i));
        spill.addAll(grid);
        cells(spill, cx * 2 - GAP, rx + SZ + GAP, W, H);
        return true;
    }

    private static List<Item> one(Item it) { List<Item> l = new ArrayList<>(); l.add(it); return l; }

    /** 0 wide, 1 half, 2 icon: the column's reading order. */
    private static int size(int w, int cw) { return w >= cw * 0.7f ? 0 : w >= 60 ? 1 : 2; }

    private static int rowH(List<Item> row) {
        int h = 20;
        for (Item it : row) h = Math.max(h, it.ih);
        return h + 4;
    }

    private static boolean overlaps(int[] r, int x, int y, int w, int h) {
        return r != null && x < r[0] + r[2] && x + w > r[0] && y < r[1] + r[3] && y + h > r[1];
    }

    /** Marks a widget rect (GUI px) taken, grown by pad units. */
    private static void take(int[] r, float pad) {
        if (r != null) taken.add(new float[] {r[0] * 2 - pad, r[1] * 2 - pad, r[2] * 2 + pad * 2, r[3] * 2 + pad * 2});
    }

    private static boolean free(float x, float y, float w, float h, float W, float H) {
        if (x < 4 || y < 4 || x + w > W - 4 || y + h > H - 4) return false;
        for (float[] t : taken) if (x < t[0] + t[2] && x + w > t[0] && y < t[1] + t[3] && y + h > t[1]) return false;
        return true;
    }

    /** Draws q at (x, y) when the spot is free; false when it isn't (it goes to the free cells instead). */
    private static boolean square(Sq q, float x, float y, float W, float H) {
        if (!free(x, y, SZ, SZ, W, H)) return false;
        taken.add(new float[] {x, y, SZ, SZ});
        if (q.party) Party.anchor(x, y, SZ, SZ);
        if (squareButton(q.id, x, y, SZ, q.icon, q.tip, q.on)) q.act.run();
        return true;
    }

    /**
     * Free cells left of the column (x < leftEnd) from the bottom left, rightward then up; what doesn't fit there goes
     * right of the column's squares (x >= rightStart) from the bottom right, leftward then up. Taken spots are skipped.
     */
    private static void cells(List<Sq> qs, float leftEnd, float rightStart, float W, float H) {
        int i = 0;
        for (float y = H - 16 - SZ; y >= 16 && i < qs.size(); y -= SZ + GAP)
            for (float x = 16; x + SZ <= leftEnd && i < qs.size(); x += SZ + GAP)
                if (square(qs.get(i), x, y, W, H)) i++;
        for (float y = H - 16 - SZ; y >= 16 && i < qs.size(); y -= SZ + GAP)
            for (float x = W - 16 - SZ; x >= rightStart && i < qs.size(); x -= SZ + GAP)
                if (square(qs.get(i), x, y, W, H)) i++;
    }

    // ---------------------------------------------------------------- the game's own layout + our chips

    /**
     * The screen exactly as the game and its mods lay it out; NETHER, INVITE and PARTY chips in the first free corner,
     * Screenshots / Edit HUD beside Save and Quit when there is room there, else after the chips.
     */
    private static void corner(Object s, List<Object> ws, float W, float H) {
        restore();
        for (Object w : ws) if (Leave.flags(w)[1]) take(Leave.bounds(w), 4);
        String key = MainMenu.keyName();
        boolean share = WorldShare.server() != null, on = share && WorldShare.sharing();
        String inv = on ? "SHARING" : "INVITE";
        float nw = MainMenu.chipW("NETHER", key, true), iw = share ? MainMenu.chipW(inv, null, false) + 18 : 0, pw = Party.chipW();
        // gallery squares beside the leave button (where vanilla's language / accessibility squares sit on the title screen)
        boolean shots = Modules.SHOTS.enabled();
        int[] q = Leave.quitRect(s);
        float gx = -1, gy = 0;
        if (q != null) {
            float h2 = shots ? SZ * 2 + GAP : SZ, y0 = q[1] * 2 + q[3] * 2 - h2;
            float[] xs = {(q[0] + q[2]) * 2 + GAP, q[0] * 2 - GAP - SZ};
            for (float x : xs) if (gx < 0 && free(x, y0, SZ, h2, W, H)) { gx = x; gy = y0; }
        }
        int sqs = gx < 0 ? (shots ? 2 : 1) : 0;
        float rowW = nw + (iw > 0 ? GAP + iw : 0) + (pw > 0 ? GAP + pw : 0) + sqs * (GAP + SZ), rh = SZ;
        float[][] at = {{12, 12}, {12, H - 12 - rh}, {W - 12 - rowW, H - 12 - rh}, {W - 12 - rowW, 12}};
        float x = 12, y = 12;
        for (float[] a : at) if (free(a[0], a[1], rowW, rh, W, H)) { x = a[0]; y = a[1]; break; }
        float cy = y + (SZ - 32) / 2;
        if (MainMenu.chip("pause", x, cy, 32, "NETHER", key, true, false, false)) Menu.openAt(Menu.lastCat);
        x += nw + GAP;
        if (share) {
            if (MainMenu.chip("invite", x, cy, 32, inv, null, false, on, true)) WorldShare.show();
            x += iw + GAP;
        }
        if (pw > 0) x += Party.chip(x, cy, 32) + GAP;
        // beside the leave button: Screenshots level with it, Edit HUD above; in the chip row: side by side
        boolean beside = gx >= 0;
        if (!beside) { gx = x; gy = y; }
        float hx = gx, hy = gy;
        if (shots) {
            float px = gx, py = beside ? gy + SZ + GAP : gy;
            if (!beside) hx = gx + SZ + GAP;
            if (squareButton("pg", px, py, SZ, "screenshots", "Screenshots", true)) Screenshots.show();
        }
        if (squareButton("ph", hx, hy, SZ, "hudeditor", "Edit HUD", true)) Menu.openHudEditor();
    }

    // ---------------------------------------------------------------- dev: fake mod widgets

    /**
     * Dev: -Dnether.pauseTest=1 adds what mods add to the pause screen, cloned from its Advancements button: a wide
     * button, two halves, three 20 px icons, and a half button that puts itself in the top-left corner every frame
     * (a mod laying out its own button). Their press is Advancements'.
     */
    private static final boolean TEST = System.getProperty("nether.pauseTest") != null;
    private static final List<Object> devW = new ArrayList<>();

    private static void devMods(Object s) {
        try {
            if (!devW.isEmpty() && Leave.bounds(devW.get(6)) != null && Leave.widgets(s).contains(devW.get(6))) {
                Leave.place(devW.get(6), 4, 4); // the self-positioning one, as its mod would in render()
                return;
            }
            Object src = null;
            for (Object w : Leave.widgets(s)) if (Leave.says(w, "'gui.advancements'")) src = w;
            if (src == null) return;
            devW.clear();
            String[] names = {"Test Wide", "Test Half 1", "Test Half 2", "A", "B", "C", "Pinned"};
            int[][] sz = {{200, 20}, {98, 20}, {98, 20}, {20, 20}, {20, 20}, {20, 20}, {60, 20}};
            java.lang.reflect.Field uf = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            Object unsafe = uf.get(null);
            java.lang.reflect.Method alloc = unsafe.getClass().getMethod("allocateInstance", Class.class);
            for (int i = 0; i < names.length; i++) {
                Object c = alloc.invoke(unsafe, src.getClass());
                for (Class<?> k = src.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                    for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        f.set(c, f.get(src));
                    }
                devText(c, names[i]);
                devSize(c, sz[i][0], sz[i][1]);
                // anything else still naming Advancements (narration, tooltip caches) would make it look vanilla
                for (Class<?> k = c.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                    for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                        f.setAccessible(true);
                        Object v = f.get(c);
                        if (v != null && String.valueOf(v).contains("gui.advancements")) {
                            if (!f.getType().isInterface() || !devText(c, f, names[i])) try { f.set(c, null); } catch (Throwable ignored) { }
                        }
                    }
                Leave.place(c, 10 + i * 30, 150);
                devW.add(c);
            }
            for (Class<?> k = s.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object v = f.get(s);
                    if (v instanceof List && ((List<?>) v).contains(src)) {
                        @SuppressWarnings("unchecked") List<Object> l = (List<Object>) v;
                        l.addAll(devW);
                    }
                }
            System.out.println("[nether-agent] pause test: added " + devW.size() + " fake mod widgets");
        } catch (Throwable t) {
            System.out.println("[nether-agent] pause test: " + t);
        }
    }

    /** The widget's message (its base class's field of the text type) as a new text from the type's static (String) factory. */
    private static void devText(Object w, String text) throws Exception {
        for (java.lang.reflect.Field f : Leave.base(w).getDeclaredFields())
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) && devText(w, f, text)) return;
    }

    private static boolean devText(Object w, java.lang.reflect.Field f, String text) throws Exception {
        Class<?> t = f.getType();
        if (!t.isInterface()) return false;
        for (java.lang.reflect.Method m : t.getMethods())
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class && t.isAssignableFrom(m.getReturnType())) {
                f.setAccessible(true);
                f.set(w, m.invoke(null, text));
                return true;
            }
        return false;
    }

    private static void devSize(Object w, int ww, int hh) throws Exception {
        List<java.lang.reflect.Field> ints = new ArrayList<>();
        for (java.lang.reflect.Field f : Leave.base(w).getDeclaredFields()) if (f.getType() == int.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) ints.add(f);
        ints.get(0).setAccessible(true); ints.get(0).setInt(w, ww);
        ints.get(1).setAccessible(true); ints.get(1).setInt(w, hh);
    }

    // ---------------------------------------------------------------- controls (units)

    /** A square button in the game's own style with an icon; true when clicked. Disabled: dimmed, not clickable. */
    private static boolean squareButton(String id, float x, float y, float sz, String icon, String tipText, boolean enabled) {
        float[] z = tipZone;
        if (z != null && x < z[0] + z[2] && x + sz > z[0] && y < z[1] + z[3] && y + sz > z[1]) return false; // under the game's tooltip
        float a0 = Gfx.alpha;
        Gfx.alpha = 1; // drawn like the game's own buttons, never faded
        boolean over = MainMenu.over(x, y, sz, sz), hov = over && enabled;
        if (!Vanilla.button(x, y, sz, hov)) {
            Gfx.rect(x, y, sz, sz, 0, hov ? 0xFF6F6F6F : 0xFF4A4A4A);
            Gfx.ring(x, y, sz, sz, 0, 2, hov ? 0xFFFFFFFF : 0xFF000000);
        }
        if (!enabled) Gfx.rect(x, y, sz, sz, 0, 0x99000000);
        ModIcons.draw(icon, x + sz / 2 - 12, y + sz / 2 - 12, 24, !enabled ? 0xFF8A8A8A : MainMenu.legacy && hov ? 0xFFFFFFA0 : 0xFFFFFFFF);
        MainMenu.box(x, y, sz, sz);
        Gfx.alpha = a0;
        if (over && tipText != null) MainMenu.tip(tipText);
        return MainMenu.hit(x, y, sz, sz) && enabled;
    }

    /** A full-width button in the game's style, the fireball mark left of the label when mark; true when clicked. */
    private static boolean wideButton(float x, float y, float w, String label, boolean mark) {
        float a0 = Gfx.alpha;
        Gfx.alpha = 1;
        boolean hov = MainMenu.over(x, y, w, 40), vf = Vanilla.font();
        if (!Vanilla.button(x, y, w, hov)) {
            Gfx.rect(x, y, w, 40, 0, hov ? 0xFF6F6F6F : 0xFF4A4A4A);
            Gfx.ring(x, y, w, 40, 0, 2, hov ? 0xFFFFFFFF : 0xFF000000);
        }
        float tw = vf ? Vanilla.width(label) : Gfx.width(Gfx.SEMI, 11, label), mw = mark ? 24 : 0;
        float cx = x + (w - (mw + tw)) / 2;
        if (mark) MainMenu.mark(cx, y + (40 - 18.9f) / 2, 16);
        int col = MainMenu.legacy && hov ? 0xFFFFFFA0 : 0xFFFFFFFF;
        if (vf) Vanilla.text(label, cx + mw, y + 12, col, true);
        else Gfx.text(Gfx.SEMI, 11, cx + mw, y + 20, col, label);
        MainMenu.box(x, y, w, 40);
        Gfx.alpha = a0;
        return MainMenu.hit(x, y, w, 40);
    }
}
