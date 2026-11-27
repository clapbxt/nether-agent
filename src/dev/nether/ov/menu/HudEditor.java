package dev.nether.ov.menu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.nether.ov.framework.Config;
import dev.nether.ov.framework.Input;
import dev.nether.ov.framework.Module;
import dev.nether.ov.framework.Modules;
import dev.nether.ov.ui.ColorPicker;
import dev.nether.ov.ui.Gfx;
import dev.nether.ov.ui.ModIcons;
import dev.nether.ov.ui.Sfx;
import dev.nether.ov.ui.Th;

/**
 * HUD editor: the widgets on the screen and where they sit. ADD WIDGET lists every widget type (FPS, ping, clock, Media
 * Player, Karaoke...) and puts the one clicked on the screen; a widget's settings open in a sheet beside it
 * (double-click, or right-click > Settings), live as they change; right-click > Remove or Delete takes it off the screen
 * (it keeps its settings). Drag a box (it snaps to the screen edges, the centre lines and the other boxes' edges and
 * centres, shown by red guides; hold Alt to place it freely); Shift/Ctrl-click or drag a frame on empty space to select
 * several and move them together; arrow keys nudge 1 GUI pixel (Shift: 10); the wheel scales; Ctrl+Z / Ctrl+Y undo and
 * redo every add, move, scale, removal and reset (50 steps). "On screen" is the widget module's enabled flag. A moved
 * box is pinned to the screen anchor nearest its centre ({@link Modules#setPos}); a reset box goes back to its corner's
 * auto layout. Box positions here are framebuffer pixels; the toolbar, list, sheet and menus use the menu's logical
 * units (Menu.sc).
 */
final class HudEditor {
    private HudEditor() {}

    private static final String[] KEYS = {"anchor", "ox", "oy", "x", "y", "scale", "enabled"};
    private static final int MAX_UNDO = 50;
    private static final ArrayDeque<Map<String, Object>> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private static final Set<String> sel = new LinkedHashSet<>();
    private static final Map<String, float[]> start = new HashMap<>();
    /** box being dragged, the right-click menu's box, box under the mouse, the last merged undo step */
    private static String dragMod, ctx, hovId, merge;
    /** layout before the drag (becomes an undo step once something moves) */
    private static Map<String, Object> pending;
    private static boolean marquee, pushed, devHold;
    private static float mx0, my0, ctxX, ctxY, nudgeX, nudgeY, guideX = -1, guideY = -1;
    private static long mergeAt, guideUntil;
    private static String dev = System.getProperty("nether.editDrag"), devPick = System.getProperty("nether.hudPick");
    private static String[] devParts;

    /** Editor closed: selection and gestures end (undo history stays for the session). */
    static void reset() {
        sel.clear();
        dragMod = ctx = null;
        pending = null;
        marquee = devHold = false;
        nudgeX = nudgeY = 0;
        picker = false;
        sheet = null;
    }

    // ---------------------------------------------------------------- widgets: add, remove, settings sheet

    /** the ADD WIDGET list is open; its scroll and content height */
    private static boolean picker;
    private static float pickScroll, pickH;
    /** widget whose settings sheet is open (null = none), the side it opened on, its scroll */
    private static String sheet;
    private static boolean sheetLeft;
    private static float sheetScroll;
    /** a second click on the same box this soon opens its settings */
    private static String lastId;
    private static long lastAt;

    /** Puts widget m on the screen (centred when it has no place of its own yet), selected; one undo step. */
    static void add(Module m) {
        reset();
        push((String) null);
        Config.set(m.id, "enabled", true);
        if (Modules.AUTO.equals(Modules.anchor(m))) { Config.set(m.id, "anchor", "mc"); Config.set(m.id, "ox", 0.0); Config.set(m.id, "oy", 0.0); }
        sel.add(m.id);
        Sfx.toggle(true);
    }

    /** Takes widgets off the screen (their settings stay); one undo step. */
    private static void remove(Collection<String> ids) {
        if (ids.isEmpty()) return;
        push((String) null);
        for (String id : ids) Config.set(id, "enabled", false);
        if (sheet != null && ids.contains(sheet)) sheet = null;
        sel.removeAll(new ArrayList<>(ids));
        Sfx.toggle(false);
    }

    private static void openSheet(String id, int fbW) {
        Module m = Modules.byId(id);
        if (m == null) return;
        ColorPicker.close();
        Menu.commitEdit();
        sheet = id;
        sheetScroll = 0;
        sheetLeft = m.placed && m.bx + m.w * m.lk / 2 > fbW / 2f; // opposite the widget
    }

    /** Opens with module id selected (a settings page's EDIT LAYOUT). */
    static void select(String id) { reset(); sel.add(id); }

    /** The layout was replaced (profile applied): old undo steps would bring it back piecemeal. */
    static void forget() { undo.clear(); redo.clear(); merge = null; reset(); }

    // ---------------------------------------------------------------- undo / redo

    private static Map<String, Object> snap() {
        Map<String, Object> s = new HashMap<>();
        for (Module m : Modules.all) if (m.hud) for (String k : KEYS) s.put(m.id + '\0' + k, Config.raw(m.id, k));
        return s;
    }

    private static void restore(Map<String, Object> s) {
        for (Map.Entry<String, Object> e : s.entrySet()) {
            int i = e.getKey().indexOf('\0');
            String id = e.getKey().substring(0, i), k = e.getKey().substring(i + 1);
            Object now = Config.raw(id, k), v = e.getValue();
            if (v == null ? now != null : !v.equals(now)) Config.set(id, k, v);
        }
    }

    /** Remember the layout before a change; repeated nudges / wheel steps on the same boxes merge into one step. */
    private static void push(String mergeKey) {
        long now = System.currentTimeMillis();
        boolean same = mergeKey != null && mergeKey.equals(merge) && now - mergeAt < 900;
        merge = mergeKey;
        mergeAt = now;
        if (!same) push(snap());
    }

    private static void push(Map<String, Object> s) {
        undo.push(s);
        while (undo.size() > MAX_UNDO) undo.removeLast();
        redo.clear();
    }

    private static void undo() { if (undo.isEmpty()) return; redo.push(snap()); restore(undo.pop()); merge = null; closeGone(); }
    private static void redo() { if (redo.isEmpty()) return; undo.push(snap()); restore(redo.pop()); merge = null; closeGone(); }

    /** The sheet's widget left the screen (undo of an add): the sheet closes with it. */
    private static void closeGone() { Module m = sheet == null ? null : Modules.byId(sheet); if (m != null && !m.enabled()) sheet = null; }

    // ---------------------------------------------------------------- keys

    static void key(int k, boolean press, boolean shift, boolean ctrl) {
        if (ctrl) {
            if (!press) return;
            if (k == 90) { if (shift) redo(); else undo(); }                                              // Ctrl+Z / Ctrl+Shift+Z
            else if (k == 89) redo();                                                                     // Ctrl+Y
            else if (k == 65) { sel.clear(); for (Module m : Modules.all) if (m.hud && m.enabled()) sel.add(m.id); } // Ctrl+A
            return;
        }
        if (k == 261 && press && !picker) { remove(new ArrayList<>(sel)); return; } // Delete: off the screen
        float step = shift ? 10 : 1;
        if (k == 263) nudgeX -= step;
        else if (k == 262) nudgeX += step;
        else if (k == 265) nudgeY -= step;
        else if (k == 264) nudgeY += step;
    }

    /** Esc inside the editor: closes the menu, cancels a drag, clears the selection; false = nothing left (leave the editor). */
    static boolean esc() {
        if (ctx != null) { ctx = null; return true; }
        if (picker) { picker = false; return true; }
        if (dragMod != null || marquee) {
            if (dragMod != null && pushed && !undo.isEmpty()) restore(undo.pop()); // put the boxes back where the drag began
            dragMod = null;
            marquee = devHold = false;
            return true;
        }
        if (ColorPicker.id != null) { ColorPicker.close(); return true; }
        if (sheet != null) { sheet = null; return true; }
        if (!sel.isEmpty()) { sel.clear(); return true; }
        return false;
    }

    // ---------------------------------------------------------------- snapping

    /** {dx, dy, guide x, guide y} pulling a w x h box at (x, y) onto the nearest target within thr pixels (guide -1: none). */
    private static float[] snapTo(List<Module> els, float x, float y, float w, float h, int fbW, int fbH, float thr) {
        float e = Modules.edge(), g = Gfx.gui;
        List<Float> tx = new ArrayList<>(), ty = new ArrayList<>();
        tx.add(0f); tx.add(e); tx.add(fbW / 2f); tx.add(fbW - e); tx.add((float) fbW);
        ty.add(0f); ty.add(e); ty.add(fbH / 2f); ty.add(fbH - e); ty.add((float) fbH);
        tx.add(fbW / 2f - 91 * g); tx.add(fbW / 2f + 91 * g); ty.add(fbH - 22 * g); // the hotbar's sides and top
        for (Module m : els) {
            if (sel.contains(m.id)) continue;
            float bw = m.w * m.lk, bh = m.h * m.lk;
            tx.add(m.bx); tx.add(m.bx + bw / 2); tx.add(m.bx + bw);
            ty.add(m.by); ty.add(m.by + bh / 2); ty.add(m.by + bh);
        }
        float[] r = {0, 0, -1, -1};
        float bx = thr + 1, by = thr + 1;
        for (float t : tx) for (float off : new float[] {0, w / 2, w}) { float d = t - (x + off); if (Math.abs(d) < Math.abs(bx)) { bx = d; r[2] = t; } }
        for (float t : ty) for (float off : new float[] {0, h / 2, h}) { float d = t - (y + off); if (Math.abs(d) < Math.abs(by)) { by = d; r[3] = t; } }
        if (Math.abs(bx) <= thr) r[0] = bx; else r[2] = -1;
        if (Math.abs(by) <= thr) r[1] = by; else r[3] = -1;
        return r;
    }

    /** Moves every box in ids by (dx, dy) pixels from its start position, as one group kept on screen. */
    private static void moveGroup(List<Module> els, Map<String, float[]> from, float dx, float dy, int fbW, int fbH) {
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (Module m : els) {
            float[] p = from.get(m.id);
            if (p == null) continue;
            x0 = Math.min(x0, p[0]); y0 = Math.min(y0, p[1]);
            x1 = Math.max(x1, p[0] + m.w * m.lk); y1 = Math.max(y1, p[1] + m.h * m.lk);
        }
        if (x0 == Float.MAX_VALUE) return;
        dx = Modules.clamp(dx, -x0, fbW - x1);
        dy = Modules.clamp(dy, -y0, fbH - y1);
        for (Module m : els) { float[] p = from.get(m.id); if (p != null) Modules.setPos(m, p[0] + dx, p[1] + dy, fbW, fbH, true); }
    }

    // ---------------------------------------------------------------- frame

    static void draw(int fbW, int fbH, float wheel, float e) {
        float W = Menu.W, H = Menu.H, sc = Menu.sc;
        boolean shift = Input.keys[340] || Input.keys[344], ctrl = Input.keys[341] || Input.keys[345], alt = Input.keys[342] || Input.keys[346];
        Gfx.s = 1;
        Gfx.rect(0, 0, fbW, fbH, 0, Gfx.a(0xFF050507, 0.55f * e));
        Gfx.rect(fbW / 2f, 0, 1, fbH, 0, Gfx.a(Gfx.ICE, 0.07f));
        Gfx.rect(0, fbH / 2f, fbW, 1, 0, Gfx.a(Gfx.ICE, 0.07f));

        // toolbar clicks first so it wins them; it is drawn last, over the boxes (a bottom-centre box sat on its buttons)
        Gfx.s = sc;
        Gfx.minPx = Menu.MIN_PX;
        if (devPick != null) { // dev: -Dnether.hudPick=1 opens the ADD WIDGET list, =<id> that widget's settings sheet
            if (devPick.equals("1")) picker = true; else if (Modules.byId(devPick) != null) openSheet(devPick, fbW);
            devPick = null;
        }
        if (sheet != null) SettingsPage.before(); // the sheet's open dropdown takes the click first
        float tbw = Math.min(W - 16, 600f), by = H - 52, tx = W / 2 - tbw / 2;
        float bx0 = W / 2 - 278;
        boolean addW = Menu.hit(bx0, by, 132, 32);
        boolean un = Menu.hit(bx0 + 140, by, 84, 32) && !undo.isEmpty(), re = Menu.hit(bx0 + 232, by, 84, 32) && !redo.isEmpty();
        boolean done = Menu.hit(bx0 + 446, by, 110, 32);
        boolean resetAll = Menu.hit(bx0 + 328, by, 110, 32);
        boolean overBar = Menu.L > tx && Menu.L < tx + tbw && Menu.T > by - 12 && Menu.T < by + 44;
        if (addW) { picker = !picker; pickScroll = 0; ctx = null; }
        if (un) undo();
        if (re) redo();
        if (done) { Menu.leaveHudEdit(); return; }
        if (resetAll) {
            push((String) null);
            for (Module m : Modules.all) if (m.hud) { Config.set(m.id, "anchor", Modules.AUTO); Config.set(m.id, "scale", 1.0); }
        }

        // this frame's boxes
        Modules.place(fbW, fbH);
        List<Module> els = new ArrayList<>();
        for (Module m : Modules.all) if (m.hud && m.placed) els.add(m);
        sel.retainAll(ids(els));
        float mxp = (float) (Input.mx * fbW / Input.ww), myp = (float) (Input.my * fbH / Input.wh);
        devDrag(els, fbW);
        if (devHold) {
            Module dm = Modules.byId(devParts[0]);
            if (dm != null) { mxp = mx0 + devOff(devParts[1], dm, fbW) * Gfx.gui; myp = my0 + Float.parseFloat(devParts[2]) * Gfx.gui; }
        }

        // right-click menu: its own clicks first
        float[] cm = ctx == null ? null : ctxRect();
        boolean overCtx = cm != null && Menu.L >= cm[0] && Menu.L < cm[0] + cm[2] && Menu.T >= cm[1] && Menu.T < cm[1] + cm[3];
        if (cm != null) {
            String[] items = ctxItems();
            for (int i = 0; i < items.length; i++) if (Menu.hit(cm[0], cm[1] + 6 + i * 28, cm[2], 28)) { ctxRun(i, items[i], els, fbW, fbH); break; }
            if (Menu.click || Menu.clickRt) { ctx = null; Menu.click = Menu.clickRt = false; }
        }

        Module sm = sheet == null ? null : Modules.byId(sheet);
        if (sheet != null && (sm == null || !sm.enabled())) { sheet = null; sm = null; }
        float[] sr = sm == null ? null : sheetRect();
        boolean overSheet = sr != null && Menu.in(sr[0], sr[1], sr[2], sr[3]);
        float[] pr = picker ? pickRect() : null;
        boolean modal = picker || overSheet; // the list (all of the screen) or the sheet takes the mouse

        int hov = -1;
        if (!overBar && !overCtx && !modal) for (int i = els.size() - 1; i >= 0; i--) {
            Module m = els.get(i);
            if (mxp >= m.bx - 4 && mxp < m.bx + m.w * m.lk + 4 && myp >= m.by - 4 && myp < m.by + m.h * m.lk + 4) { hov = i; break; }
        }
        hovId = hov >= 0 ? els.get(hov).id : null;
        if (Menu.click && !overBar && !modal) {
            Menu.click = false;
            if (hov >= 0) {
                String id = hovId;
                long now = System.currentTimeMillis();
                boolean twice = id.equals(lastId) && now - lastAt < 350;
                lastId = id;
                lastAt = twice ? 0 : now;
                if (shift || ctrl) { if (!sel.remove(id)) sel.add(id); }
                else {
                    if (!sel.contains(id)) { sel.clear(); sel.add(id); }
                    beginDrag(id, els, mxp, myp);
                    if (twice || sheet != null && !sheet.equals(id)) openSheet(id, fbW); // double-click; an open sheet follows the clicked box
                }
            } else {
                marquee = true;
                mx0 = mxp;
                my0 = myp;
                if (!shift && !ctrl) sel.clear();
            }
        }
        if (Menu.clickRt && !overBar && !modal && hov >= 0 && dragMod == null) {
            Menu.clickRt = false;
            if (!sel.contains(hovId)) { sel.clear(); sel.add(hovId); }
            ctx = hovId;
            ctxX = Menu.L;
            ctxY = Menu.T;
        }

        // drag (the grabbed box snaps; the rest of the selection follows it)
        float gx = -1, gy = -1;
        if (dragMod != null) {
            Module dm = Modules.byId(dragMod);
            float[] s0 = start.get(dragMod);
            if (dm == null || s0 == null || !dm.placed || (!Input.btn[0] && !devHold)) { dragMod = null; pending = null; }
            else {
                float dx = mxp - mx0, dy = myp - my0;
                if (!pushed && (Math.abs(dx) >= 1 || Math.abs(dy) >= 1)) { push(pending); merge = null; pushed = true; }
                if (pushed) {
                    if (!alt) {
                        float[] sn = snapTo(els, s0[0] + dx, s0[1] + dy, dm.w * dm.lk, dm.h * dm.lk, fbW, fbH, 4 * Gfx.gui);
                        dx += sn[0];
                        dy += sn[1];
                        gx = sn[2];
                        gy = sn[3];
                    }
                    moveGroup(els, start, dx, dy, fbW, fbH);
                }
            }
        }

        // frame selection
        if (marquee && !Input.btn[0]) {
            float x0 = Math.min(mx0, mxp), y0 = Math.min(my0, myp), x1 = Math.max(mx0, mxp), y1 = Math.max(my0, myp);
            if (x1 - x0 > 2 || y1 - y0 > 2)
                for (Module m : els) if (m.bx < x1 && m.bx + m.w * m.lk > x0 && m.by < y1 && m.by + m.h * m.lk > y0) sel.add(m.id);
            marquee = false;
        }

        // arrow keys: the selection (or the box under the mouse) by whole GUI pixels; guides show what it lines up with
        if (nudgeX != 0 || nudgeY != 0) {
            Collection<String> ids = !sel.isEmpty() ? sel : hovId != null ? Collections.singleton(hovId) : Collections.<String>emptySet();
            Map<String, float[]> from = new HashMap<>();
            for (Module m : els) if (ids.contains(m.id)) from.put(m.id, new float[] {m.bx, m.by});
            if (!from.isEmpty() && dragMod == null) {
                push("nudge" + ids);
                moveGroup(els, from, nudgeX * Gfx.gui, nudgeY * Gfx.gui, fbW, fbH);
                Module f = Modules.byId(ids.iterator().next());
                if (f != null) {
                    Set<String> keep = new LinkedHashSet<>(sel);
                    sel.addAll(ids);
                    float[] sn = snapTo(els, f.bx, f.by, f.w * f.lk, f.h * f.lk, fbW, fbH, 0.5f);
                    sel.retainAll(keep);
                    guideX = sn[2];
                    guideY = sn[3];
                    guideUntil = System.currentTimeMillis() + 900;
                }
            }
            nudgeX = nudgeY = 0;
        }
        if (gx < 0 && gy < 0 && System.currentTimeMillis() < guideUntil) { gx = guideX; gy = guideY; }

        // wheel: scale the box under the mouse (and the rest of the selection with it); the list and the sheet scroll
        float pickWheel = picker ? wheel : 0, sheetWheel = overSheet ? wheel : 0;
        if (modal) wheel = 0;
        if (wheel != 0 && hovId != null) {
            Collection<String> ids = sel.contains(hovId) ? sel : Collections.singleton(hovId);
            push("scale" + ids);
            for (String id : ids) {
                Module m = Modules.byId(id);
                if (m != null) Config.set(id, "scale", Math.max(0.5, Math.min(2.5, Math.round((m.scale() + wheel * 0.05) / 0.05) * 0.05)));
            }
        }

        // the HUD at its real size, then guides and frames over it
        Gfx.s = 1;
        Gfx.minPx = 0;
        Modules.drawHud(fbW, fbH);
        Gfx.minPx = Menu.MIN_PX;
        Gfx.s = 1;
        if (gx >= 0) Gfx.rect(gx - 0.5f, 0, 1, fbH, 0, Gfx.a(Gfx.RED2, 0.9f));
        if (gy >= 0) Gfx.rect(0, gy - 0.5f, fbW, 1, 0, Gfx.a(Gfx.RED2, 0.9f));
        for (Module m : els) {
            boolean s = sel.contains(m.id), act = m.id.equals(dragMod) || (dragMod == null && m.id.equals(hovId));
            float t = Menu.an("box" + m.id, act || s), bw = m.w * m.lk, bh = m.h * m.lk;
            Gfx.s = 1;
            if (s) Gfx.rect(m.bx - 3, m.by - 3, bw + 6, bh + 6, 0, Gfx.a(Gfx.RED, 0.10f));
            Gfx.ring(m.bx - 3, m.by - 3, bw + 6, bh + 6, 5, s ? 2f : 1.5f, s ? Gfx.RED2 : Gfx.mix(Gfx.a(Gfx.ICE, 0.35f), Gfx.RED2, t));
            String a = Modules.anchor(m);
            boolean auto = Modules.AUTO.equals(a);
            if ((act || s) && !auto && a != null) { // the corner this box keeps when it or the window changes size
                float ax = m.bx - 3 + Modules.af(a, true) * (bw + 6), ay = m.by - 3 + Modules.af(a, false) * (bh + 6);
                Gfx.rect(ax - 3, ay - 3, 6, 6, 0, Gfx.RED2);
            }
            Gfx.s = sc * 0.9f;
            String label = m.name.toUpperCase(Locale.ROOT);
            if (t > 0.5f) label += "   " + String.format(Locale.ROOT, "%.2fx", m.scale()) + "   " + (auto ? "AUTO" : a == null ? "" : Modules.ANCHOR_NAMES[Modules.anchorIdx(a)]);
            float lw = Gfx.width(Gfx.MONO, 9, label, 1.2f) + 14;
            // name tag above the box; at the top edge beside it (so it never sits on the module below), else under it
            float lx = m.bx - 3, ly = m.by - 3 - 18 * Gfx.s;
            if (ly < 4) {
                if (m.bx + bw + 8 + lw * Gfx.s < fbW) { lx = m.bx + bw + 8; ly = m.by - 3; }
                else if (m.bx - 8 - lw * Gfx.s > 0) { lx = m.bx - 8 - lw * Gfx.s; ly = m.by - 3; }
                else ly = m.by + bh + 6;
            }
            Gfx.rect(lx / Gfx.s, ly / Gfx.s, lw, 15, 4, Gfx.mix(Gfx.a(0xFF0B0B0F, 0.9f), Gfx.RED, t));
            Gfx.text(Gfx.MONO, 9, lx / Gfx.s + 7, ly / Gfx.s + 7.5f, Gfx.mix(Gfx.DIM, 0xFFFFFFFF, t), label, false, 1.2f);
        }
        if (marquee) {
            Gfx.s = 1;
            float x0 = Math.min(mx0, mxp), y0 = Math.min(my0, myp);
            Gfx.rect(x0, y0, Math.abs(mxp - mx0), Math.abs(myp - my0), 0, Gfx.a(Gfx.ICE, 0.06f));
            Gfx.ring(x0, y0, Math.abs(mxp - mx0), Math.abs(myp - my0), 0, 1, Gfx.a(Gfx.ICE, 0.5f));
        }
        Gfx.s = sc;
        if (els.isEmpty() && !picker) Gfx.textC(Gfx.SEMI, 13, W / 2, H / 2, Gfx.DIM, "NO WIDGETS ON SCREEN  -  PRESS ADD WIDGET", 1.6f);
        { // the toolbar, over the boxes (its clicks were taken at the top)
            boolean c0 = Menu.click;
            Menu.click = false;
            Gfx.rect(tx, by - 12, tbw, 56, 10, Gfx.a(Gfx.BG, 0.92f));
            Gfx.ring(tx, by - 12, tbw, 56, 10, 1, Gfx.LN);
            addButton(bx0, by, 132);
            tool("hundo", "UNDO", bx0 + 140, by, 84, !undo.isEmpty());
            tool("hredo", "REDO", bx0 + 232, by, 84, !redo.isEmpty());
            Menu.button("hreset", "RESET ALL", bx0 + 328, by, 110, 32, false);
            Menu.button("done", "DONE", bx0 + 446, by, 110, 32, true);
            if (Menu.in(bx0, by, 132, 32)) Menu.tip = "Put FPS, ping, the clock, Media Player, Karaoke and more on your screen";
            if (Menu.in(bx0 + 328, by, 110, 32)) Menu.tip = "Every box back to its corner at scale 1 (undo brings it back)";
            Menu.click = c0;
        }
        if (sm != null) drawSheet(sm, sr, sheetWheel);
        if (pr != null) {
            drawPicker(pr, pickWheel);
            if ((Menu.click || Menu.clickRt) && !Menu.in(pr[0], pr[1], pr[2], pr[3]) && !overBar) { picker = false; Menu.click = Menu.clickRt = false; } // a click beside the list closes it
        }
        if (sheet != null) SettingsPage.after();
        if (cm != null && ctx != null) drawCtx(cm);
        if (Menu.tip != null && !overCtx) {
            float tw = Gfx.width(Gfx.UI, 11, Menu.tip) + 24, bx = Math.min(Menu.L + 14, W - tw - 8), ty = Menu.T - 34;
            Gfx.rect(bx, ty, tw, 26, 6, 0xF2101016);
            Gfx.ring(bx, ty, tw, 26, 6, 1, Gfx.LN2);
            Gfx.text(Gfx.UI, 11, bx + 12, ty + 13, Gfx.DIM, Menu.tip);
        }
    }

    private static Set<String> ids(List<Module> els) {
        Set<String> s = new LinkedHashSet<>();
        for (Module m : els) s.add(m.id);
        return s;
    }

    private static void beginDrag(String id, List<Module> els, float mx, float my) {
        dragMod = id;
        mx0 = mx;
        my0 = my;
        start.clear();
        for (Module m : els) if (sel.contains(m.id)) start.put(m.id, new float[] {m.bx, m.by});
        pending = snap();
        pushed = false;
    }

    /** Toolbar button that greys out when it has nothing to do. */
    private static boolean tool(String id, String label, float x, float y, float w, boolean on) {
        float a0 = Gfx.alpha;
        if (!on) Gfx.alpha = a0 * 0.4f;
        boolean r = Menu.button(id, label, x, y, w, 32, false);
        Gfx.alpha = a0;
        return r && on;
    }

    // ---------------------------------------------------------------- right-click menu

    private static String[] ctxItems() {
        return sel.size() > 1 ? new String[] {"RESET POSITION", "REMOVE"} : new String[] {"SETTINGS", "RESET POSITION", "REMOVE"};
    }

    private static float[] ctxRect() {
        float w = 168, h = 12 + ctxItems().length * 28;
        return new float[] {Math.min(ctxX, Menu.W - w - 4), Math.min(ctxY, Menu.H - h - 4), w, h};
    }

    private static void ctxRun(int i, String item, List<Module> els, int fbW, int fbH) {
        Collection<String> ids = sel.isEmpty() ? Collections.singleton(ctx) : new ArrayList<>(sel);
        String id0 = ctx;
        ctx = null;
        if (item.equals("SETTINGS")) { openSheet(id0, fbW); return; } // beside the widget, in the editor
        if (item.equals("REMOVE")) { remove(ids); return; }
        push((String) null);
        for (String id : ids) { Config.set(id, "anchor", Modules.AUTO); Config.set(id, "scale", 1.0); }
    }

    private static void drawCtx(float[] r) {
        Gfx.rect(r[0], r[1], r[2], r[3], 8, 0xF50D0D11);
        Gfx.ring(r[0], r[1], r[2], r[3], 8, 1, Gfx.LN2);
        String[] items = ctxItems();
        for (int i = 0; i < items.length; i++) {
            float y = r[1] + 6 + i * 28;
            float t = Menu.an("ctx" + i, Menu.in(r[0], y, r[2], 28));
            if (t > 0.02f) Gfx.rect(r[0] + 4, y, r[2] - 8, 28, 4, Gfx.a(Gfx.RED, 0.85f * t));
            Gfx.text(Gfx.SEMI, 11, r[0] + 16, y + 14, Gfx.mix(Gfx.DIM, 0xFFFFFFFF, t), items[i] + (sel.size() > 1 ? " (" + sel.size() + ")" : ""), false, 1.2f);
        }
    }

    // ---------------------------------------------------------------- ADD WIDGET: the button and the list

    /** The toolbar's ADD WIDGET button, in the HUD tab's blue (open: filled). */
    private static boolean addButton(float x, float y, float w) {
        float h = 32, t = Menu.an("haddw", Menu.in(x, y, w, h)), o = Menu.an("haddo", picker);
        boolean down = Menu.in(x, y, w, h) && Input.btn[0];
        int tint = Menu.hudTint();
        Gfx.rect(x, y, w, h, 6, down ? Gfx.a(tint, 0.55f) : Gfx.mix(Gfx.a(tint, 0.22f + 0.12f * t), tint, o));
        Gfx.ring(x, y, w, h, 6, 1, Gfx.a(tint, 0.75f + 0.25f * t));
        float lw = Gfx.width(Gfx.SEMI, 11, "ADD WIDGET", 1.2f), lx = x + w / 2 - (lw + 16) / 2, cy = y + h / 2;
        int c = Gfx.mix(Gfx.mix(tint, 0xFFFFFFFF, 0.55f + 0.45f * t), 0xFFFFFFFF, o);
        Gfx.rect(lx + 1, cy - 0.75f, 9, 1.5f, 0.75f, c); // plus
        Gfx.rect(lx + 4.75f, cy - 4.5f, 1.5f, 9, 0.75f, c);
        Gfx.text(Gfx.SEMI, 11, lx + 16, cy, c, "ADD WIDGET", false, 1.2f);
        return Menu.hit(x, y, w, h);
    }

    private static float[] pickRect() {
        float W = Menu.W, H = Menu.H, pw = Math.min(W - 40, 680), ph = Math.min(H - 150, 560);
        return new float[] {(W - pw) / 2, Math.max(16, (H - 112 - ph) / 2), pw, ph};
    }

    private static final String[] SECTIONS = {"Info", "PvP", "Music"};
    private static final String[] SECTION_SUB = {"Numbers and readouts", "For fights", "The song playing"};

    /** Every widget type by group as tiles; click puts one on the screen (or selects it when it is there already). */
    private static void drawPicker(float[] r, float wheel) {
        float x = r[0], y = r[1], w = r[2], h = r[3], top = 66;
        boolean r0 = Gfx.round;
        Gfx.round = Th.round();
        try {
            Gfx.rect(x - 2, y + 6, w + 4, h + 4, 14, Gfx.a(0xFF000000, 0.35f));
            Gfx.rect(x, y, w, h, 12, Gfx.a(Th.s1, 0.97f));
            Gfx.ring(x, y, w, h, 12, 1, Th.ln2);
            Gfx.rect(x + 22, y + 20, 7, 7, 4, Menu.hudTint());
            Gfx.text(Gfx.UI, 10, x + 36, y + 24, Gfx.mix(Menu.hudTint(), 0xFFFFFFFF, 0.3f), "HUD", false, 1.4f);
            Gfx.text(Gfx.HEAD, 16, x + 22, y + 44, Th.tx, "Add a widget");
            Gfx.text(Gfx.UI, 11, x + 22 + Gfx.width(Gfx.HEAD, 16, "Add a widget") + 12, y + 44, Th.mut, Menu.fit("Click one to put it on your screen, then drag it into place", Gfx.UI, 11, w - 220, 0));
            float cx = x + w - 16 - 28, cyx = y + 32;
            float xt = Menu.an("hpclose", Menu.in(cx, cyx - 14, 28, 28));
            Gfx.rect(cx, cyx - 14, 28, 28, 4, Gfx.a(Th.ov, 0.07f * xt));
            Menu.drawX(cx + 14, cyx, 4.2f, Gfx.mix(Th.mut, Th.tx, xt));
            if (Menu.hit(cx, cyx - 14, 28, 28)) { picker = false; return; }
            Gfx.rect(x + 1, y + top - 1, w - 2, 1, 0, Th.ln);

            float view = h - top - 10, maxS = Math.max(0, pickH - view);
            pickScroll = Math.max(0, Math.min(maxS, pickScroll - wheel * 40));
            int cols = Math.max(2, (int) ((w - 40 + 10) / (200 + 10)));
            float cw = (w - 40 - 10 * (cols - 1)) / cols, th = 56;
            Gfx.clip(x, y + top, w, view);
            float cy = y + top + 10 - pickScroll, y0 = cy;
            for (int si = 0; si < SECTIONS.length; si++) {
                List<Module> l = new ArrayList<>();
                for (Module m : Modules.all) if (Modules.widget(m) && Menu.widgetSection(m).equals(SECTIONS[si])) l.add(m);
                if (l.isEmpty()) continue;
                float tw = Gfx.text(Gfx.SEMI, 12, x + 22, cy + 9, Th.dim, SECTIONS[si]);
                Gfx.text(Gfx.UI, 10.5f, x + 22 + tw + 10, cy + 9, Th.mut, SECTION_SUB[si]);
                cy += 24;
                for (int i = 0; i < l.size(); i++)
                    tile(l.get(i), x + 20 + (i % cols) * (cw + 10), cy + (i / cols) * (th + 8), cw, th, y + top, view);
                cy += ((l.size() + cols - 1) / cols) * (th + 8) + 10;
            }
            pickH = cy - y0 + 4;
            Gfx.unclip();
            if (maxS > 0) {
                float sh = Math.max(30, view * view / pickH);
                Gfx.rect(x + w - 8, y + top + (view - sh) * (pickScroll / maxS), 3, sh, 1.5f, Gfx.a(Th.ov, 0.16f));
            }
        } finally { Gfx.round = r0; }
    }

    /** One widget type: icon, name, one line of what it shows; ON SCREEN when it is there already. */
    private static void tile(Module m, float x, float y, float w, float h, float vTop, float vH) {
        if (y + h < vTop || y > vTop + vH) return;
        boolean on = m.enabled(), lock = m.locked(), vis = Menu.in(x, Math.max(y, vTop), w, Math.min(y + h, vTop + vH) - Math.max(y, vTop));
        float t = Menu.an("hpk" + m.id, vis);
        Gfx.rect(x, y, w, h, 8, Gfx.mix(Th.s2, Th.s3, t * 0.9f));
        Gfx.ring(x, y, w, h, 8, 1, on ? Gfx.a(Menu.hudTint(), 0.5f + 0.3f * t) : Gfx.mix(Th.ln, Th.ln2, t));
        float a0 = Gfx.alpha;
        if (lock) Gfx.alpha = a0 * 0.45f;
        Gfx.rect(x + 10, y + h / 2 - 17, 34, 34, 7, Gfx.a(on ? Menu.hudTint() : Th.ov, on ? 0.18f : 0.06f));
        if (!ModIcons.drawItem(m.id, x + 19, y + h / 2 - 8, 16) && !ModIcons.draw(m.id, x + 18.5f, y + h / 2 - 8.5f, 17, Gfx.mix(Th.dim, Th.tx, Math.max(t, on ? 1 : 0))))
            Gfx.textC(Gfx.HEAD, 14, x + 27, y + h / 2, Th.dim, m.name.substring(0, 1), 0);
        String chip = lock ? "N/A" : on ? "ON SCREEN" : null;
        float chw = chip == null ? 0 : Gfx.width(Gfx.MONO, 8, chip, 1.2f) + 12;
        float tx = x + 54, room = w - 54 - 10;
        Gfx.text(Gfx.SEMI, 12, tx, y + 20, Th.tx, Menu.fit(m.name, Gfx.SEMI, 12, room - chw - 6, 0));
        Gfx.text(Gfx.UI, 10, tx, y + 38, Th.mut, Menu.fit(m.desc, Gfx.UI, 10, room, 0));
        if (chip != null) {
            Gfx.rect(x + w - 10 - chw, y + 12, chw, 16, 8, lock ? Gfx.a(Th.ov, 0.08f) : Gfx.a(Menu.hudTint(), 0.22f));
            Gfx.text(Gfx.MONO, 8, x + w - 10 - chw + 6, y + 20, lock ? Th.mut : Gfx.mix(Menu.hudTint(), 0xFFFFFFFF, 0.4f), chip, false, 1.2f);
        }
        Gfx.alpha = a0;
        if (vis) Menu.tip = lock && m.lockNote() != null ? m.lockNote() : on ? m.desc + "  (on your screen: click to select it)" : m.desc;
        if (vis && !lock && Menu.hit(x, y, w, h)) {
            if (on) { sel.clear(); sel.add(m.id); picker = false; }
            else add(m);
        }
    }

    // ---------------------------------------------------------------- the settings sheet

    private static float[] sheetRect() {
        float W = Menu.W, H = Menu.H, sw = Math.min(380, W * 0.4f), sh = Math.max(220, H - 20 - 124);
        return new float[] {sheetLeft ? 20 : W - 20 - sw, 20, sw, sh};
    }

    /** Widget m's settings, live (the box on the screen changes as they do); DONE, RESET and REMOVE under them. */
    private static void drawSheet(Module m, float[] r, float wheel) {
        float x = r[0], y = r[1], w = r[2], h = r[3], top = 64, foot = 54;
        boolean r0 = Gfx.round;
        Gfx.round = Th.round();
        try {
            Gfx.rect(x, y + 4, w, h, 12, Gfx.a(0xFF000000, 0.35f));
            Gfx.rect(x, y, w, h, 12, Gfx.a(Th.s1, 0.96f));
            Gfx.ring(x, y, w, h, 12, 1, Th.ln2);
            Gfx.rect(x + 18, y + 20, 7, 7, 4, Menu.hudTint());
            Gfx.text(Gfx.UI, 10, x + 32, y + 24, Gfx.mix(Menu.hudTint(), 0xFFFFFFFF, 0.3f), "WIDGET SETTINGS", false, 1.4f);
            Gfx.text(Gfx.SEMI, 15, x + 18, y + 45, Th.tx, Menu.fit(m.name, Gfx.SEMI, 15, w - 80, 0));
            float cx = x + w - 16 - 28, cyx = y + 32;
            float xt = Menu.an("hsclose", Menu.in(cx, cyx - 14, 28, 28));
            Gfx.rect(cx, cyx - 14, 28, 28, 4, Gfx.a(Th.ov, 0.07f * xt));
            Menu.drawX(cx + 14, cyx, 4.2f, Gfx.mix(Th.mut, Th.tx, xt));
            if (Menu.hit(cx, cyx - 14, 28, 28)) { ColorPicker.close(); sheet = null; return; }
            Gfx.rect(x + 1, y + top - 1, w - 2, 1, 0, Th.ln2);
            float rows = SettingsPage.flatH(m, w - 40) + 12, view = h - top - foot, maxS = Math.max(0, rows - view);
            sheetScroll = Math.max(0, Math.min(maxS, sheetScroll - wheel * 40));
            Gfx.clip(x, y + top, w, view);
            SettingsPage.flat(m, x + 20, y + top + 6 - sheetScroll, w - 40);
            Gfx.unclip();
            if (maxS > 0) {
                float th = Math.max(30, view * view / rows);
                Gfx.rect(x + w - 8, y + top + (view - th) * (sheetScroll / maxS), 3, th, 1.5f, Gfx.a(Th.ov, 0.16f));
            }
            float fy = y + h - foot + 12;
            Gfx.rect(x + 1, y + h - foot, w - 2, 1, 0, Th.ln2);
            boolean done = Menu.fbutton("hsdone", "DONE", x + 20, fy, 90, 30, true);
            boolean rs = Menu.fbutton("hsreset", "RESET", x + 118, fy, 80, 30, false);
            boolean rm = Menu.fbutton("hsremove", "REMOVE", x + w - 20 - 100, fy, 100, 30, false);
            if (Menu.in(x + 118, fy, 80, 30)) Menu.tip = "Its settings back to the defaults (it stays where it is)";
            if (Menu.in(x + w - 120, fy, 100, 30)) Menu.tip = "Take it off the screen (Delete). Its settings stay for next time";
            if (done) { ColorPicker.close(); Menu.commitEdit(); sheet = null; }
            else if (rs) { // settings only: on screen, place and scale stay
                ColorPicker.close();
                Menu.commitEdit();
                Object[] keep = new Object[KEYS.length];
                for (int i = 0; i < KEYS.length; i++) keep[i] = Config.raw(m.id, KEYS[i]);
                m.reset();
                for (int i = 0; i < KEYS.length; i++) Config.set(m.id, KEYS[i], keep[i]);
            } else if (rm) { ColorPicker.close(); remove(Collections.singleton(m.id)); }
        } finally { Gfx.round = r0; }
    }

    // ---------------------------------------------------------------- dev

    /** Dev: -Dnether.editDrag=<id>:<dx|c>:<dy> grabs that box and holds it moved by GUI pixels (c = its centre onto the
     *  screen's, 2 px off so the snap shows), for snapshots of the guides. */
    private static void devDrag(List<Module> els, int fbW) {
        if (dev == null || devHold) return;
        String[] d = dev.split(":");
        if (d.length < 3) { dev = null; return; }
        for (Module m : els) if (m.id.equals(d[0])) {
            sel.clear();
            sel.add(m.id);
            beginDrag(m.id, els, m.bx + 4, m.by + 4);
            devHold = true;
            devParts = d;
            dev = null;
            return;
        }
    }

    private static float devOff(String v, Module m, int fbW) {
        if (!v.equals("c")) return Float.parseFloat(v);
        float[] s0 = start.get(m.id);
        return s0 == null ? 0 : (fbW / 2f - (s0[0] + m.w * m.lk / 2)) / Gfx.gui + 2;
    }
}
