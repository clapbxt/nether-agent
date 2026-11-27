package dev.nether.ov.menu.title;

import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import dev.nether.ov.features.social.Party;
import dev.nether.ov.framework.Config;
import dev.nether.ov.framework.Input;
import dev.nether.ov.platform.Plat;
import dev.nether.ov.ui.Gfx;
import dev.nether.ov.ui.Icons;
import dev.nether.ov.ui.ModIcons;
import dev.nether.ov.ui.Sfx;
import dev.nether.ov.ui.Th;

/**
 * SELECT MODE (2026-11-27, Fortnite / Valorant style): one full-screen picker for where to play. WORLDS lists the
 * saves as cards (icon.png, game mode, version, last played); SERVERS lists the multiplayer list (favicon, MOTD, ping,
 * players) and takes a typed address too (direct connect). Click a card, then SELECT (or double-click / Enter).
 *
 * In a party the leader picks here and the pick goes to the party at once (everyone sees it on the party card and
 * readies up; the launcher starts it 5 s after all are ready). Solo, the pick is what PLAY starts. Replaces the party's
 * pick mode on the game's own world / server lists, so it works the same on 1.8.9 through 26.x.
 */
public final class PlaySelect {
    private PlaySelect() {}

    public static final int WORLDS = 0, SERVERS = 1;

    private static boolean open, party;
    private static int tab;
    private static float openA;
    private static String search = "";
    /** the highlighted card: "world:<folder>" or "srv:<address>" */
    private static String sel;
    private static float scroll, scrollT;
    /** mouse wheel while open (Input routes it here) */
    public static float wheel;
    private static String lastKey;
    private static long lastAt;
    private static Object screen;

    public static boolean isOpen() { return open; }

    /**
     * Open on a tab (WORLDS / SERVERS, or -1 for the tab of the current pick). forParty: the pick goes to the party
     * (only its leader opens this), else it sets what PLAY starts.
     */
    public static void open(int t, boolean forParty, Object scr) {
        open = true;
        party = forParty;
        screen = scr;
        search = "";
        scroll = scrollT = 0;
        sel = forParty ? Party.picked() : Config.str("menu", "playMode", "");
        if (sel != null && !sel.startsWith("world:") && !sel.startsWith("srv:")) sel = null;
        tab = t >= 0 ? t : sel != null && sel.startsWith("srv:") ? SERVERS : WORLDS;
        lastKey = null;
        scanAt = 0;
        scan();
    }

    public static void close() { open = false; }

    // ---------------------------------------------------------------- worlds (saves/, read in the background)

    static final class World {
        String folder = "", name = "", mode = "", ver = "";
        long played, iconM = -1;
        boolean hardcore;
        volatile BufferedImage img;
        String tex;
    }

    private static volatile List<World> worlds = Collections.emptyList();
    private static volatile boolean scanning, scanned;
    private static long scanAt;

    private static Path saves() { return Paths.get("saves"); }

    static boolean isSave(String n) {
        if (n == null || n.isEmpty() || n.length() > 200 || n.contains("/") || n.contains("\\") || n.equals(".") || n.equals("..")) return false;
        try { return Files.isRegularFile(saves().resolve(n).resolve("level.dat")); } catch (Throwable t) { return false; }
    }

    /** At most every 5 s (and at once when opened): reread saves/ on a background thread. */
    private static void scan() {
        long now = System.currentTimeMillis();
        if (scanning || now < scanAt) return;
        scanAt = now + 5000;
        scanning = true;
        Thread t = new Thread(() -> {
            try { worlds = read(worlds); scanned = true; }
            catch (Throwable e) { System.out.println("[nether-agent] play select: " + e); }
            finally { scanning = false; }
        }, "nether-playselect");
        t.setDaemon(true);
        t.start();
    }

    @SuppressWarnings("unchecked")
    private static List<World> read(List<World> prev) throws Exception {
        Map<String, World> old = new HashMap<>();
        for (World w : prev) old.put(w.folder, w);
        List<World> out = new ArrayList<>();
        List<Path> dirs = new ArrayList<>();
        try (java.util.stream.Stream<Path> st = Files.list(saves())) { st.forEach(dirs::add); }
        catch (java.nio.file.NoSuchFileException e) { return out; }
        for (Path d : dirs) {
            String folder = d.getFileName().toString();
            if (!isSave(folder)) continue;
            World w = new World();
            w.folder = w.name = folder;
            Path lvl = d.resolve("level.dat");
            try {
                if (Files.size(lvl) < (16 << 20)) try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(lvl)))) {
                    if (in.readByte() == 10) {
                        in.readUTF();
                        Object root = QuickJoin.nbt(in, 10, 0);
                        Object data = root instanceof Map ? ((Map<String, Object>) root).get("Data") : null;
                        if (data instanceof Map) {
                            Map<String, Object> m = (Map<String, Object>) data;
                            Object n = m.get("LevelName");
                            if (n instanceof String && !((String) n).trim().isEmpty()) w.name = ((String) n).trim();
                            if (m.get("LastPlayed") instanceof Long) w.played = (Long) m.get("LastPlayed");
                            Object gt = m.get("GameType");
                            int g = gt instanceof Number ? ((Number) gt).intValue() : 0;
                            w.hardcore = m.get("hardcore") instanceof Number && ((Number) m.get("hardcore")).intValue() != 0;
                            w.mode = w.hardcore ? "Hardcore" : g == 1 ? "Creative" : g == 2 ? "Adventure" : g == 3 ? "Spectator" : "Survival";
                            Object v = m.get("Version");
                            Object vn = v instanceof Map ? ((Map<String, Object>) v).get("Name") : null;
                            if (vn instanceof String) w.ver = (String) vn;
                        }
                    }
                }
            } catch (Throwable ignored) { } // a broken level.dat still lists, by its folder
            if (w.played <= 0) try { w.played = Files.getLastModifiedTime(lvl).toMillis(); } catch (Throwable ignored) { }
            Path icon = d.resolve("icon.png");
            try { w.iconM = Files.isRegularFile(icon) ? Files.getLastModifiedTime(icon).toMillis() : -1; } catch (Throwable ignored) { }
            World o = old.get(folder);
            if (o != null && o.iconM == w.iconM) { w.img = o.img; w.tex = o.tex; }
            else if (w.iconM >= 0 && Files.size(icon) < (1 << 20)) {
                try (InputStream in = Files.newInputStream(icon)) { w.img = Icons.decode(in, 256L * 256); } catch (Throwable ignored) { }
            }
            out.add(w);
        }
        out.sort((a, b) -> Long.compare(b.played, a.played));
        return out;
    }

    /** A save's display name (level.dat LevelName), else its folder. */
    public static String worldName(String folder) {
        if (!scanned) scan();
        for (World w : worlds) if (w.folder.equals(folder)) return w.name;
        return folder;
    }

    // ---------------------------------------------------------------- the screen

    private static final class Card {
        String key, title, line1, line2, letter, addr;
        String tex;
        QuickJoin.Srv srv;
        boolean direct;
    }

    /** The cards of the open tab, search applied. */
    private static List<Card> cards() {
        List<Card> out = new ArrayList<>();
        String q = search.trim().toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        if (tab == WORLDS) {
            for (World w : worlds) {
                if (!q.isEmpty() && !w.name.toLowerCase(Locale.ROOT).contains(q) && !w.folder.toLowerCase(Locale.ROOT).contains(q)) continue;
                if (w.img != null && (w.tex == null || !Gfx.hasTexture(w.tex))) { w.tex = "ps.w." + Integer.toHexString((w.folder + w.iconM).hashCode()); Gfx.texture(w.tex, w.img, true); }
                Card c = new Card();
                c.key = "world:" + w.folder;
                c.title = w.name;
                c.line1 = w.mode + (w.ver.isEmpty() ? "" : " · " + w.ver);
                c.line2 = w.played > 0 ? "Played " + ago(now - w.played) : w.folder;
                c.letter = w.name;
                c.tex = w.tex;
                out.add(c);
            }
        } else {
            boolean exact = false;
            for (QuickJoin.Srv s : QuickJoin.all()) {
                if (same(s.ip, search.trim())) exact = true;
                if (!q.isEmpty() && !s.name.toLowerCase(Locale.ROOT).contains(q) && !s.ip.toLowerCase(Locale.ROOT).contains(q)) continue;
                Card c = new Card();
                c.key = "srv:" + s.ip;
                c.addr = s.ip;
                c.title = s.name.isEmpty() ? s.ip : s.name;
                c.line1 = s.state == 2 && s.motd != null && !s.motd.isEmpty() ? s.motd : s.state == 3 ? "Can't reach server" : s.state == 2 ? "Online" : "Pinging...";
                c.line2 = s.ip;
                c.letter = c.title;
                c.srv = s;
                out.add(c);
            }
            String a = search.trim();
            if (!exact && address(a)) { // direct connect: a typed address that isn't in the list
                Card c = new Card();
                c.key = "srv:" + a;
                c.addr = a;
                c.title = a;
                c.line1 = "Join by address";
                c.line2 = party ? "Your party joins this server" : "Not in your server list";
                c.letter = a;
                c.direct = true;
                out.add(0, c);
            }
        }
        return out;
    }

    /** host[:port] the party's launcher accepts (letters, digits, . - _). */
    private static boolean address(String a) {
        if (a.isEmpty() || a.length() > 90 || !a.matches("[A-Za-z0-9._-]+(:\\d{1,5})?")) return false;
        String h = a.indexOf(':') >= 0 ? a.substring(0, a.indexOf(':')) : a;
        if (a.indexOf(':') >= 0 && Integer.parseInt(a.substring(a.indexOf(':') + 1)) > 65535) return false;
        return h.contains(".") || h.equalsIgnoreCase("localhost");
    }

    private static String norm(String a) {
        String s = a == null ? "" : a.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() || s.indexOf(':') >= 0 ? s : s + ":25565";
    }

    /** Same server (case and default port aside). */
    static boolean same(String a, String b) { return !norm(a).isEmpty() && norm(a).equals(norm(b)); }

    private static boolean selected(String key) {
        if (sel == null || key == null) return false;
        if (key.startsWith("srv:") && sel.startsWith("srv:")) return same(key.substring(4), sel.substring(4));
        return key.equals(sel);
    }

    private static String ago(long ms) {
        long m = ms / 60_000;
        if (m < 1) return "just now";
        if (m < 60) return m + (m == 1 ? " minute ago" : " minutes ago");
        long h = m / 60;
        if (h < 24) return h + (h == 1 ? " hour ago" : " hours ago");
        long d = h / 24;
        if (d < 30) return d + (d == 1 ? " day ago" : " days ago");
        long mo = d / 30;
        if (mo < 12) return mo + (mo == 1 ? " month ago" : " months ago");
        return (d / 365) + (d / 365 == 1 ? " year ago" : " years ago");
    }

    private static Card selCard;

    /** Draw it over everything (MainMenu units). */
    static void draw(float W, float H) {
        if (!open) { openA = 0; return; }
        if (party && !Party.leads()) { open = false; return; } // left the party or lost the lead
        scan();
        openA = Config.calm() ? 1 : Math.min(1, openA + Gfx.dt * 8);
        float e = 1 - (float) Math.pow(1 - openA, 3);
        MainMenu.inModal = true;
        float a0 = Gfx.alpha;
        Gfx.alpha = a0 * e;
        Gfx.rect(0, 0, W, H, 0, Gfx.a(Gfx.BG, 0.72f));
        float pw = Math.min(W - 32, 940), ph = Math.min(H - 32, 620);
        float px = (W - pw) / 2, py = (H - ph) / 2 + (1 - e) * 12;
        Gfx.rect(px, py + 6, pw, ph, 14, Gfx.a(Gfx.BLACK, 0.45f));
        Gfx.rect(px, py, pw, ph, 14, Gfx.a(Gfx.CARD, 0.97f));
        Gfx.ring(px, py, pw, ph, 14, 1, Gfx.LN2);

        // header
        Gfx.text(Gfx.HEAD, 20, px + 24, py + 30, Gfx.ICE, "SELECT MODE", false, 2f);
        String sub = party ? "Pick where your party plays. Everyone joins once they're all ready." : "Pick what PLAY starts.";
        Gfx.text(Gfx.UI, 10.5f, px + 24, py + 52, Gfx.SUB, MainMenu.fit(Gfx.UI, 10.5f, sub, 0, pw - 90));
        float cx = px + pw - 16 - 28;
        boolean ch = MainMenu.over(cx, py + 14, 28, 28);
        Gfx.rect(cx, py + 14, 28, 28, 7, Gfx.a(Gfx.ICE, ch ? 0.1f : 0.04f));
        ModIcons.draw("close", cx + 7, py + 21, 14, ch ? Gfx.ICE : Gfx.SUB);
        if (ch) MainMenu.tip("Close (Esc)");
        boolean closeNow = MainMenu.hit(cx, py + 14, 28, 28);

        // tabs + search
        float ty = py + 70, th = 32;
        float tx = px + 16;
        String[] tabs = {"WORLDS", "SERVERS"};
        int[] counts = {worlds.size(), QuickJoin.all().size()};
        for (int i = 0; i < 2; i++) {
            String n = String.valueOf(counts[i]);
            float lw = Gfx.width(Gfx.SEMI, 11, tabs[i], 1.4f), nw = Gfx.width(Gfx.MONO, 9, n) + 12, w = 12 + 18 + lw + 8 + nw + 12;
            boolean on = tab == i, hv = MainMenu.over(tx, ty, w, th);
            float t = MainMenu.an("pst" + i, hv);
            Gfx.rect(tx, ty, w, th, 8, on ? Gfx.a(Gfx.ICE, 0.09f) : Gfx.a(Gfx.ICE, 0.04f * t));
            ModIcons.draw(i == WORLDS ? "world" : "server", tx + 12, ty + th / 2 - 8, 16, on ? Th.ac2 : Gfx.mix(Gfx.SUB, Gfx.ICE, t));
            Gfx.text(Gfx.SEMI, 11, tx + 34, ty + th / 2, on ? Gfx.ICE : Gfx.mix(Gfx.DIM, Gfx.ICE, t), tabs[i], false, 1.4f);
            float bx = tx + 34 + lw + 8;
            Gfx.rect(bx, ty + th / 2 - 8, nw, 16, 8, on ? Gfx.a(Th.ac, 0.35f) : Gfx.a(Gfx.ICE, 0.08f));
            Gfx.textC(Gfx.MONO, 9, bx + nw / 2, ty + th / 2, on ? 0xFFFFFFFF : Gfx.SUB, n, 0);
            if (on) Gfx.rect(tx + 10, ty + th + 6, w - 20, 2, 1, Th.ac);
            if (MainMenu.hit(tx, ty, w, th) && tab != i) { tab = i; scroll = scrollT = 0; }
            tx += w + 8;
        }
        float sw = Math.min(280, pw - (tx - px) - 24), sx = px + pw - 16 - sw;
        if (sw > 120) {
            Gfx.rect(sx, ty, sw, th, 8, Gfx.a(Gfx.ICE, 0.06f));
            Gfx.ring(sx, ty, sw, th, 8, 1, Gfx.a(Gfx.ICE, 0.28f));
            ModIcons.draw("search", sx + 10, ty + th / 2 - 7, 14, Gfx.SUB);
            boolean caret = (System.currentTimeMillis() / 500) % 2 == 0;
            if (search.isEmpty()) {
                Gfx.text(Gfx.UI, 10.5f, sx + 32, ty + th / 2, Gfx.MUT, MainMenu.fit(Gfx.UI, 10.5f, tab == SERVERS ? "Search, or type an address to join" : "Search your worlds", 0, sw - 44));
                if (caret) Gfx.rect(sx + 31, ty + 9, 1, th - 18, 0, Gfx.ICE);
            } else {
                String shown = search;
                while (shown.length() > 0 && Gfx.width(Gfx.UI, 11, shown) > sw - 50) shown = shown.substring(1);
                Gfx.text(Gfx.UI, 11, sx + 32, ty + th / 2, Gfx.ICE, shown + (caret ? "|" : ""));
            }
        }
        Gfx.rect(px + 16, ty + th + 8, pw - 32, 1, 0, Gfx.LN);

        // grid
        float gx = px + 16, gy = ty + th + 16, gw = pw - 32, gh = py + ph - 64 - gy;
        List<Card> list = cards();
        int cols = Math.max(1, (int) ((gw + 10) / (270 + 10)));
        float cw = (gw - (cols - 1) * 10) / cols, chh = 78, gap = 10;
        int rows = (list.size() + cols - 1) / cols;
        float content = rows * (chh + gap) - gap, maxS = Math.max(0, content - gh);
        if (MainMenu.over(gx, gy, gw, gh)) scrollT -= wheel * 46;
        wheel = 0;
        scrollT = Math.max(0, Math.min(maxS, scrollT));
        scroll += (scrollT - scroll) * (Config.calm() ? 1 : Math.min(1, Gfx.dt * 16));
        selCard = null;
        for (Card c : list) if (selected(c.key)) selCard = c;
        boolean confirm = false;
        if (list.isEmpty()) {
            String m1, m2;
            if (!search.trim().isEmpty()) { m1 = "Nothing matches \"" + search.trim() + "\""; m2 = tab == SERVERS ? "Type a full address (like play.example.net) to join it" : "Try another name"; }
            else if (tab == WORLDS) { m1 = scanning ? "Looking for your worlds..." : "No worlds yet"; m2 = party ? "Create one in Singleplayer first, then pick it here" : "Create one with MANAGE WORLDS below"; }
            else { m1 = "No servers in your list"; m2 = "Type an address in the search box to join one"; }
            Gfx.textC(Gfx.SEMI, 13, gx + gw / 2, gy + gh / 2 - 10, Gfx.DIM, m1, 0);
            Gfx.textC(Gfx.UI, 10.5f, gx + gw / 2, gy + gh / 2 + 10, Gfx.MUT, m2, 0);
        } else {
            Gfx.clip(gx - 2, gy - 2, gw + 4, gh + 4);
            boolean inGrid = MainMenu.over(gx, gy, gw, gh);
            for (int i = 0; i < list.size(); i++) {
                float x = gx + (i % cols) * (cw + gap), y = gy + (i / cols) * (chh + gap) - scroll;
                if (y + chh < gy - 4 || y > gy + gh + 4) continue;
                if (card(list.get(i), x, y, cw, chh, inGrid)) confirm = true;
            }
            Gfx.unclip();
            if (maxS > 0) { // scrollbar
                float bh = Math.max(30, gh * gh / content), by = gy + (gh - bh) * (scroll / maxS);
                Gfx.rect(px + pw - 10, by, 3, bh, 1.5f, Gfx.a(Gfx.ICE, 0.25f));
            }
        }

        // footer: what is selected, MANAGE (solo), CANCEL, SELECT
        float fy = py + ph - 52;
        Gfx.rect(px + 16, fy, pw - 32, 1, 0, Gfx.LN);
        float by = fy + 11, bh = 30;
        float pbw = 150, cbw = 96;
        float pbx = px + pw - 16 - pbw, cbx = pbx - 8 - cbw;
        boolean can = selCard != null;
        if (button("psok", party ? "SELECT FOR PARTY" : "SELECT", pbx, by, pbw, bh, true, can)) confirm = true;
        if (can && MainMenu.over(pbx, by, pbw, bh)) MainMenu.tip(party ? "Your party sees it and readies up" : "PLAY starts it");
        if (button("pscn", "CANCEL", cbx, by, cbw, bh, false, true)) closeNow = true;
        float lx = px + 24;
        if (!party) {
            String ml = tab == WORLDS ? "MANAGE WORLDS" : "MANAGE SERVERS";
            float mw = Gfx.width(Gfx.SEMI, 10.5f, ml, 1.2f) + 26;
            if (button("psmg", ml, px + 16, by, mw, bh, false, true)) { open = false; MainMenu.openRaw(tab == WORLDS ? MainMenu.SINGLE : MainMenu.MULTI, screen); }
            if (MainMenu.over(px + 16, by, mw, bh)) MainMenu.tip(tab == WORLDS ? "The game's world list: create, edit or delete worlds" : "The game's server list: add, edit or remove servers");
            lx = px + 16 + mw + 14;
        }
        String info = selCard == null ? "Click a card to select it" : selCard.title;
        String info2 = selCard == null ? "Double-click or Enter selects too" : selCard.key.startsWith("world:") ? "World · " + selCard.line1 : "Server · " + selCard.addr;
        float iw = cbx - 14 - lx;
        Gfx.text(Gfx.SEMI, 11.5f, lx, by + 9, selCard == null ? Gfx.SUB : Gfx.ICE, MainMenu.fit(Gfx.SEMI, 11.5f, info, 0, iw));
        Gfx.text(Gfx.UI, 9.5f, lx, by + 23, Gfx.MUT, MainMenu.fit(Gfx.UI, 9.5f, info2, 0, iw));

        Gfx.alpha = a0;
        boolean outside = MainMenu.clickNow() && !MainMenu.overRaw(px, py, pw, ph);
        MainMenu.inModal = false;
        if (outside) { MainMenu.eat(); closeNow = true; }
        if (confirm) confirm();
        else if (closeNow) open = false;
    }

    /** One card; true = double-clicked (select it). */
    private static boolean card(Card c, float x, float y, float w, float h, boolean inGrid) {
        boolean on = selected(c.key), hv = inGrid && MainMenu.over(x, y, w, h);
        float t = MainMenu.an("psc" + c.key, hv);
        Gfx.rect(x, y, w, h, 10, on ? Gfx.a(Th.ac, 0.16f + 0.04f * t) : Gfx.a(Gfx.ICE, 0.035f + 0.045f * t));
        Gfx.ring(x, y, w, h, 10, on ? 1.5f : 1, on ? Th.ac2 : Gfx.mix(Gfx.LN, Gfx.LN2, t));
        float is = h - 24, ix = x + 12, iy = y + 12;
        if (c.srv != null) {
            QuickJoin.icon(c.srv, ix, iy, is);
        } else if (c.tex != null && Gfx.hasTexture(c.tex)) {
            Gfx.image(c.tex, ix, iy, is, is, 0xFFFFFFFF, 6);
        } else {
            Gfx.rect(ix, iy, is, is, 6, Gfx.S3);
            if (c.direct) ModIcons.draw("plus", ix + is / 2 - 10, iy + is / 2 - 10, 20, Th.ac2);
            else Gfx.textC(Gfx.HEAD, 20, ix + is / 2, iy + is / 2, Gfx.DIM, c.letter.isEmpty() ? "?" : c.letter.substring(0, 1).toUpperCase(Locale.ROOT), 0);
        }
        float tx = ix + is + 12, right = x + w - 12;
        if (c.srv != null) { // signal bars + players, top right
            QuickJoin.bars(right - 21, y + 12, c.srv);
            String pl = c.srv.state == 2 && c.srv.online >= 0 ? c.srv.online + "/" + c.srv.max : "";
            if (!pl.isEmpty()) Gfx.text(Gfx.MONO, 9, right - Gfx.width(Gfx.MONO, 9, pl), y + 34, Gfx.SUB, pl);
            right -= 52;
        } else if (on) {
            ModIcons.draw("check", right - 16, y + 12, 16, Th.ac2);
            right -= 22;
        }
        Gfx.text(Gfx.SEMI, 12.5f, tx, y + 23, Gfx.mix(Gfx.ICE, 0xFFFFFFFF, t), MainMenu.fit(Gfx.SEMI, 12.5f, c.title, 0, right - tx));
        Gfx.text(Gfx.UI, 10, tx, y + 41, c.direct ? Th.ac2 : Gfx.DIM, MainMenu.fit(Gfx.UI, 10, c.line1, 0, x + w - 12 - tx));
        Gfx.text(Gfx.UI, 9.5f, tx, y + 57, Gfx.MUT, MainMenu.fit(Gfx.UI, 9.5f, c.line2, 0, x + w - 12 - tx));
        if (!hv || !MainMenu.hit(x, y, w, h)) return false;
        long now = System.currentTimeMillis();
        boolean dbl = c.key.equals(lastKey) && now - lastAt < 400;
        lastKey = c.key;
        lastAt = now;
        sel = c.key;
        selCard = c;
        return dbl;
    }

    private static boolean button(String id, String label, float x, float y, float w, float h, boolean primary, boolean enabled) {
        boolean hv = enabled && MainMenu.over(x, y, w, h), down = hv && Input.btn[0];
        float t = MainMenu.an(id, hv);
        if (!enabled) Gfx.rect(x, y, w, h, 7, Gfx.a(Gfx.ICE, 0.05f));
        else if (primary) Gfx.rect(x, y, w, h, 7, down ? Gfx.mix(Th.ac, Gfx.BLACK, 0.2f) : Gfx.mix(Th.ac, Th.ac2, t));
        else { Gfx.rect(x, y, w, h, 7, Gfx.a(Gfx.ICE, 0.05f + 0.06f * t)); Gfx.ring(x, y, w, h, 7, 1, Gfx.mix(Gfx.LN2, Gfx.a(Gfx.ICE, 0.35f), t)); }
        Gfx.textC(Gfx.SEMI, 10.5f, x + w / 2, y + h / 2 + (down ? 1 : 0), !enabled ? Gfx.MUT : primary ? 0xFFFFFFFF : Gfx.mix(Gfx.DIM, Gfx.ICE, t), label, 1.2f);
        return enabled && MainMenu.hit(x, y, w, h);
    }

    /** SELECT: the pick goes to the party (leader) or becomes what PLAY starts. */
    private static void confirm() {
        Card c = selCard;
        if (c == null) { Sfx.error(); return; }
        if (party) {
            if (c.key.startsWith("world:")) Party.pickWorld(c.key.substring(6));
            else Party.pickServer(c.addr, c.direct ? c.addr : c.title);
        } else Config.set("menu", "playMode", c.key);
        open = false;
    }

    // ---------------------------------------------------------------- keys (Input routes them here while open)

    public static boolean key(int k, int action) {
        if (!open) return false;
        if (action == Input.RELEASE) return true;
        boolean ctrl = Input.keys[341] || Input.keys[345];
        if (k == 256) { if (action == Input.PRESS) open = false; return true; }
        if (k == 259) { if (!search.isEmpty()) { search = ctrl ? "" : search.substring(0, search.length() - 1); scrollT = 0; } return true; }
        if (k == 257 || k == 335) {
            if (action != Input.PRESS) return true;
            List<Card> l = cards();
            if (selCard == null && !l.isEmpty() && !search.trim().isEmpty()) { selCard = l.get(0); sel = selCard.key; } // Enter takes the top match
            confirm();
            return true;
        }
        if (k == 258 && action == Input.PRESS) { tab = tab == WORLDS ? SERVERS : WORLDS; scroll = scrollT = 0; return true; } // Tab
        if (k == 86 && ctrl) { chars(Plat.cur.clipboard()); return true; }
        return true;
    }

    public static void chars(String s) {
        if (!open || s == null) return;
        StringBuilder b = new StringBuilder(search);
        for (char c : s.toCharArray()) if (c >= 32 && c != 127 && !Character.isISOControl(c)) b.append(c);
        search = b.length() > 90 ? b.substring(0, 90) : b.toString();
        scrollT = 0;
    }

    // ---------------------------------------------------------------- the pick, for the cards that show it

    /** Icon of a pick ("world:<folder>" / "srv:<address>"), a letter tile when it has none. */
    static void icon(String key, String name, float x, float y, float s) {
        if (key != null && key.startsWith("world:")) {
            if (!scanned) scan();
            String f = key.substring(6);
            for (World w : worlds) if (w.folder.equals(f)) {
                if (w.img != null && (w.tex == null || !Gfx.hasTexture(w.tex))) { w.tex = "ps.w." + Integer.toHexString((w.folder + w.iconM).hashCode()); Gfx.texture(w.tex, w.img, true); }
                if (w.tex != null && Gfx.hasTexture(w.tex)) { Gfx.image(w.tex, x, y, s, s, 0xFFFFFFFF, 5); return; }
            }
        } else if (key != null && key.startsWith("srv:")) {
            QuickJoin.Srv v = QuickJoin.find(key.substring(4));
            if (v != null) { QuickJoin.icon(v, x, y, s); return; }
        }
        Gfx.rect(x, y, s, s, 5, Gfx.S3);
        Gfx.textC(Gfx.HEAD, s * 0.5f, x + s / 2, y + s / 2, Gfx.DIM, name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT), 0);
    }

    /** Draw a pick's icon from outside this package (the party card). */
    public static void pickIcon(String key, String name, float x, float y, float s) { icon(key, name, x, y, s); }
}
