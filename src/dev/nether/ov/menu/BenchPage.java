package dev.nether.ov.menu;

import java.util.List;
import java.util.Locale;

import dev.nether.ov.features.performance.Bench;
import dev.nether.ov.features.performance.BenchInfo;
import dev.nether.ov.framework.Config;
import dev.nether.ov.game.Game;
import dev.nether.ov.platform.Plat;
import dev.nether.ov.ui.Gfx;
import dev.nether.ov.ui.Th;
import dev.nether.ov.ui.Toast;

/**
 * Settings > Benchmark (2026-11-28): start a camera-spin test (one run, or five with parts of Nether switched off), read
 * the numbers, the frame-time graph and the findings, and copy a report to share. See {@link Bench} and {@link BenchInfo}.
 */
public final class BenchPage {
    private BenchPage() {}

    private static float lastH;
    private static String status;
    private static long statusAt;
    private static final int GOOD = 0xFF6BE58A, WARN = 0xFFFFB020, BAD = 0xFFFF5A4D;

    private static void header(String t, float x, float y, float w) {
        Gfx.text(Gfx.MONO, 9, x, y + 20, Th.mut, t, false, 2f);
        Gfx.rect(x, y + 33, w, 1, 0, Th.ln2);
    }

    private static boolean chip(String id, String label, float x, float y, float w, boolean sel) {
        boolean f = Menu.focus(x, y, w, 28), hv = Menu.in(x, y, w, 28);
        float t = Menu.an(id, sel);
        Gfx.rect(x, y, w, 28, 6, Gfx.mix(Gfx.a(Th.ov, hv ? 0.09f : 0.05f), Th.ac, t));
        Gfx.textC(Gfx.UI, 11, x + w / 2, y + 14, Gfx.mix(Gfx.mix(Th.dim, Th.tx, hv ? 1 : 0), 0xFFFFFFFF, t), label, 0);
        return Menu.hit(x, y, w, 28) | Menu.used(f) != 0;
    }

    private static String f0(double v) { return String.format(Locale.ROOT, "%.0f", v); }

    private static String dur(int s) { return s >= 60 ? (s / 60) + " min" + (s % 60 > 0 ? " " + s % 60 + " s" : "") : s + " s"; }

    static void draw(float px, float py, float wheel) {
        BenchInfo.ensure();
        Menu.navCols = 1;
        float lx = px + 28, lw = Menu.PW - 56, top = py + 124, viewH = py + Menu.PH - 24 - top, xr = lx + lw;
        float maxS = Math.max(0, lastH - viewH);
        Menu.scrollT = Math.max(0, Math.min(maxS, Menu.scrollT - wheel * 46));
        Menu.scroll = Config.calm() ? Menu.scrollT : Menu.scroll + (Menu.scrollT - Menu.scroll) * Math.min(1, Gfx.dt * 16);
        Menu.beginClip(lx - 8, top, lw + 16, viewH);
        float y0 = top - Menu.scroll, y = y0;

        // ------------------------------------------------------------ run
        header("RUN A TEST", lx, y, lw);
        y += 34;
        Gfx.text(Gfx.UI, 13, lx, y + 18, Th.tx, "Test");
        Gfx.text(Gfx.UI, 10, lx, y + 34, Th.mut, Menu.fit(Bench.compare ? "Five runs in the same spot: Nether as set, HUD hidden, performance fixes off, Nether off, and the first one again." : "One run with everything the way you have it set up.", Gfx.UI, 10, lw - 330, 0));
        float cx = xr - 304;
        if (chip("bq", "One run", cx, y + 11, 100, !Bench.compare)) Bench.compare = false;
        if (chip("bc", "What does Nether cost?", cx + 108, y + 11, 196, Bench.compare)) Bench.compare = true;
        Gfx.rect(lx, y + 49, lw, 1, 0, Th.ln);
        y += 50;
        Gfx.text(Gfx.UI, 13, lx, y + 18, Th.tx, "Length of each run");
        Gfx.text(Gfx.UI, 10, lx, y + 34, Th.mut, "Longer runs are steadier. 20 seconds is enough for most PCs.");
        cx = xr - 4 * 72 + 8;
        for (int i = 0; i < Bench.SECS.length; i++)
            if (chip("bs" + i, Bench.SECS[i] + " s", cx + i * 72, y + 11, 64, Bench.secs == Bench.SECS[i])) Bench.secs = Bench.SECS[i];
        Gfx.rect(lx, y + 49, lw, 1, 0, Th.ln);
        y += 50;
        boolean inWorld = Game.hasPlayer(), busy = Bench.running();
        String why = busy ? "A test is running." : !inWorld ? "Join a world first. The test runs in your own world, where you stand." : null;
        Gfx.text(Gfx.UI, 13, lx, y + 18, Th.tx, why != null ? "Not yet" : "Ready");
        String hint = why != null ? why : "Stand still. The camera turns by itself; don't touch the keyboard or mouse. About " + dur(Bench.totalSecs()) + ". Esc stops it.";
        Gfx.text(Gfx.UI, 10, lx, y + 34, Th.mut, Menu.fit(hint, Gfx.UI, 10, lw - 200, 0));
        float bx = xr - 180, by = y + 8;
        if (why == null) {
            if (Menu.fbutton("bstart", "START BENCHMARK", bx, by, 180, 34, true)) Bench.start(Bench.compare, Bench.secs);
        } else {
            Gfx.rect(bx, by, 180, 34, 6, Gfx.a(Th.ov, 0.04f));
            Gfx.textC(Gfx.SEMI, 11, bx + 90, by + 17, Th.mut, "START BENCHMARK", 1.2f);
        }
        y += 56;

        // ------------------------------------------------------------ results
        if (Bench.note != null) {
            Gfx.rect(lx, y + 4, 3, 34, 1.5f, Bench.noteBad ? WARN : GOOD);
            Gfx.text(Gfx.UI, 12, lx + 14, y + 21, Th.tx, Menu.fit(Bench.note, Gfx.UI, 12, lw - 20, 0));
            y += 48;
        }
        List<Bench.Result> res = Bench.results;
        if (!res.isEmpty()) {
            Bench.Result r = res.get(Math.max(0, Math.min(Bench.sel, res.size() - 1)));
            header("RESULTS", lx, y, lw);
            boolean ok = status != null && System.currentTimeMillis() - statusAt < 5000;
            if (Menu.fbutton("bcopy", "COPY REPORT", xr - 130, y + 2, 130, 28, true)) {
                boolean done = Plat.cur.setClipboard(BenchInfo.report());
                status = done ? "Report copied. Paste it into a chat." : "Could not reach the clipboard.";
                statusAt = System.currentTimeMillis();
                if (done) Toast.show("Benchmark report copied");
            }
            if (ok) Gfx.text(Gfx.UI, 10, xr - 140 - Gfx.width(Gfx.UI, 10, status), y + 16, Th.dim, status);
            else if (BenchInfo.saved != null) Gfx.text(Gfx.UI, 10, xr - 140 - Gfx.width(Gfx.UI, 10, Menu.fit(BenchInfo.saved, Gfx.UI, 10, lw - 330, 0)), y + 16, Th.mut, Menu.fit(BenchInfo.saved, Gfx.UI, 10, lw - 330, 0));
            y += 44;

            // numbers of the picked run
            String[] lab = {"AVERAGE", "1% LOW", "0.1% LOW", "SLOWEST FRAME", "FASTEST FRAME"};
            float[] val = {r.avg, r.low1, r.low01, r.min, r.max};
            float gap = 10, cw = (lw - gap * 4) / 5;
            for (int i = 0; i < 5; i++) {
                float x = lx + i * (cw + gap);
                Gfx.rect(x, y, cw, 78, 8, Th.s2);
                Gfx.ring(x, y, cw, 78, 8, 1, Th.ln);
                Gfx.text(Gfx.MONO, 9, x + 14, y + 18, Th.mut, lab[i], false, 1.6f);
                Gfx.text(Gfx.SEMI, 28, x + 14, y + 47, i == 0 ? Th.tx : Th.tx, f0(val[i]));
                float vw = Gfx.width(Gfx.SEMI, 28, f0(val[i]));
                Gfx.text(Gfx.UI, 11, x + 14 + vw + 6, y + 52, Th.mut, "FPS");
                Gfx.text(Gfx.UI, 10, x + 14, y + 67, Th.mut, String.format(Locale.ROOT, "%.2f ms", 1000.0 / Math.max(val[i], 0.001f)));
            }
            y += 90;

            // frame-time graph
            float gh = 96;
            Gfx.rect(lx, y, lw, gh + 52, 8, Th.s2);
            Gfx.ring(lx, y, lw, gh + 52, 8, 1, Th.ln);
            Gfx.text(Gfx.MONO, 9, lx + 14, y + 20, Th.mut, "FRAME TIMES", false, 2f);
            String sub = r.name + "  -  " + r.frames + " frames in " + Math.round(r.secs) + " s  -  " + r.stutters + (r.stutters == 1 ? " stutter" : " stutters");
            Gfx.text(Gfx.UI, 10, xr - 14 - Gfx.width(Gfx.UI, 10, sub), y + 20, Th.dim, sub);
            float gx = lx + 14, gw = lw - 28, gy = y + 36;
            float ymax = Math.max(r.p99 * 1.8f, r.p50 * 3f);
            if (r.cols != null && ymax > 0) {
                int n = r.cols.length;
                float colW = gw / n;
                for (int i = 0; i < n; i++) {
                    float v = r.cols[i];
                    if (v <= 0) continue;
                    float h = Math.max(1.5f, Math.min(1f, v / ymax) * gh);
                    Gfx.rect(gx + i * colW, gy + gh - h, Math.max(1f, colW - 0.8f), h, 0.8f, v > r.p99 ? Th.ac : Gfx.a(Th.ac, 0.42f));
                }
                float ym = gy + gh - Math.min(1f, r.p50 / ymax) * gh, y99 = gy + gh - Math.min(1f, r.p99 / ymax) * gh;
                Gfx.rect(gx, ym, gw, 1, 0, Gfx.a(Th.tx, 0.30f));
                Gfx.rect(gx, y99, gw, 1, 0, Gfx.a(Th.tx, 0.18f));
                String m1 = String.format(Locale.ROOT, "median %.2f ms", r.p50), m2 = String.format(Locale.ROOT, "99th percentile %.2f ms", r.p99);
                float w1 = Gfx.width(Gfx.UI, 9, m1), w2 = Gfx.width(Gfx.UI, 9, m2);
                Gfx.rect(gx + gw - w1 - 8, ym - 14, w1 + 8, 13, 3, Gfx.a(Th.s2, 0.9f));
                Gfx.text(Gfx.UI, 9, gx + gw - w1 - 4, ym - 7, Th.dim, m1);
                if (y99 < ym - 16) {
                    Gfx.rect(gx + gw - w2 - 8, y99 - 14, w2 + 8, 13, 3, Gfx.a(Th.s2, 0.9f));
                    Gfx.text(Gfx.UI, 9, gx + gw - w2 - 4, y99 - 7, Th.dim, m2);
                }
                Gfx.text(Gfx.UI, 9, gx, gy - 4, Th.mut, String.format(Locale.ROOT, "%.1f ms", ymax));
            }
            Gfx.text(Gfx.UI, 9, gx, gy + gh + 14, Th.mut, "start");
            Gfx.text(Gfx.UI, 9, gx + gw - Gfx.width(Gfx.UI, 9, "end of run (worst frame in each slice; taller = slower)"), gy + gh + 14, Th.mut, "end of run (worst frame in each slice; taller = slower)");
            y += gh + 64;

            // comparison
            if (res.size() > 1) {
                float rh = 40, w0 = lw - 28;
                Gfx.text(Gfx.MONO, 9, lx, y + 12, Th.mut, "RUNS  -  CLICK ONE TO SEE ITS GRAPH", false, 2f);
                y += 24;
                float best = 1;
                for (Bench.Result q : res) best = Math.max(best, q.avg);
                float cA = xr - 340, cB = xr - 250, cC = xr - 160, cD = xr - 70;
                Gfx.text(Gfx.MONO, 9, cA, y + 8, Th.mut, "AVG", false, 1.4f);
                Gfx.text(Gfx.MONO, 9, cB, y + 8, Th.mut, "1% LOW", false, 1.4f);
                Gfx.text(Gfx.MONO, 9, cC, y + 8, Th.mut, "0.1% LOW", false, 1.4f);
                Gfx.text(Gfx.MONO, 9, cD, y + 8, Th.mut, "VS FIRST", false, 1.4f);
                y += 20;
                for (int i = 0; i < res.size(); i++, y += rh) {
                    Bench.Result q = res.get(i);
                    boolean sel = i == Bench.sel, hv = Menu.in(lx, y, lw, rh);
                    boolean f = Menu.focus(lx, y, lw, rh);
                    float t = Menu.an("brow" + i, sel);
                    Gfx.rect(lx, y + 2, lw, rh - 4, 6, Gfx.a(Th.ov, 0.03f * t + (hv ? 0.04f : 0)));
                    Gfx.rect(lx, y + rh - 1, lw, 1, 0, Th.ln);
                    Gfx.text(Gfx.SEMI, 13, lx + 12, y + 14, sel ? Th.tx : Th.dim, q.name);
                    float nw = Gfx.width(Gfx.SEMI, 13, q.name), bw = cA - lx - 36;
                    Gfx.text(Gfx.UI, 10, lx + 24 + nw, y + 15, Th.mut, Menu.fit(q.why, Gfx.UI, 10, cA - lx - 40 - nw, 0));
                    Gfx.rect(lx + 12, y + 27, bw, 4, 2, Gfx.a(Th.ov, 0.08f));
                    Gfx.rect(lx + 12, y + 27, Math.max(2, bw * q.avg / best), 4, 2, sel ? Th.ac : Gfx.a(Th.ac, 0.55f));
                    Gfx.text(Gfx.SEMI, 13, cA, y + 20, Th.tx, f0(q.avg));
                    Gfx.text(Gfx.UI, 13, cB, y + 20, Th.tx, f0(q.low1));
                    Gfx.text(Gfx.UI, 13, cC, y + 20, Th.tx, f0(q.low01));
                    if (i > 0) {
                        double d = (q.avg - res.get(0).avg) / Math.max(res.get(0).avg, 1) * 100;
                        Gfx.text(Gfx.UI, 13, cD, y + 20, Math.abs(d) < 2 ? Th.dim : d > 0 ? GOOD : WARN, String.format(Locale.ROOT, "%+.1f%%", d));
                    } else Gfx.text(Gfx.UI, 13, cD, y + 20, Th.mut, "-");
                    if (Menu.hit(lx, y, lw, rh) | Menu.used(f) != 0) Bench.sel = i;
                }
                y += 10;
            }
        }

        // ------------------------------------------------------------ findings
        List<Object[]> fs = BenchInfo.findings();
        if (!fs.isEmpty()) {
            header("WHAT IT MEANS", lx, y, lw);
            y += 40;
            for (Object[] f : fs) {
                int lv = (Integer) f[0];
                List<String> lines = Menu.wrap((String) f[1], Gfx.UI, 12, lw - 34);
                int col = lv == 0 ? GOOD : lv == 1 ? Th.mut : lv == 2 ? WARN : BAD;
                Gfx.rect(lx + 2, y + 6, 8, 8, 4, col);
                for (int i = 0; i < lines.size(); i++) Gfx.text(Gfx.UI, 12, lx + 24, y + 12 + i * 17, lv >= 2 ? Th.tx : Th.dim, lines.get(i));
                y += Math.max(1, lines.size()) * 17 + 9;
            }
            y += 6;
        }

        // ------------------------------------------------------------ this PC
        header("THIS PC AND GAME", lx, y, lw);
        if (Menu.fbutton("brefresh", BenchInfo.busy() ? "READING..." : "REFRESH", xr - 100, y + 2, 100, 28, false) && !BenchInfo.busy()) BenchInfo.collect();
        y += 42;
        String sec = "";
        for (String[] r : BenchInfo.rows()) {
            if (!r[0].equals(sec)) {
                sec = r[0];
                y += 6;
                Gfx.text(Gfx.SEMI, 12, lx, y + 8, Th.dim, sec);
                y += 22;
            }
            List<String> lines = Menu.wrap(r[2], Gfx.UI, 12, lw - 214);
            int n = Math.min(lines.size(), 7);
            Gfx.text(Gfx.UI, 11, lx + 8, y + 8, Th.mut, Menu.fit(r[1], Gfx.UI, 11, 190, 0));
            for (int i = 0; i < n; i++) Gfx.text(Gfx.UI, 12, lx + 206, y + 8 + i * 16, Th.tx, i == n - 1 && lines.size() > n ? lines.get(i) + " ..." : lines.get(i));
            y += Math.max(1, n) * 16 + 7;
        }
        y += 12;
        Gfx.text(Gfx.UI, 10, lx, y + 4, Th.mut, "Reports and frame times are saved in the client folder under benchmarks. Your user name is left out of copied reports.");
        y += 30;

        Menu.endClip();
        lastH = y - y0;
        Menu.scrollBar(px, top, Menu.PW - 4, viewH, lastH, maxS);
    }

    /** Over the world while a test counts down and warms up (nothing is drawn while a run is measured). */
    public static void hud(int fbW, int fbH) {
        int st = Bench.state;
        if (st != Bench.COUNT && st != Bench.WARM) return;
        float s0 = Gfx.s, a0 = Gfx.alpha;
        Gfx.s = Gfx.base;
        Gfx.alpha = 1;
        float w = 330, h = 74, x = fbW / Gfx.s / 2 - w / 2, y = 40;
        Toast.glass(x, y, w, h);
        String t = st == Bench.COUNT ? "Benchmark starts in " + Bench.phaseLeft() : "Warming up  -  run " + (Bench.index() + 1) + " of " + Bench.planned() + (Bench.planned() > 1 ? "  -  " + Bench.current() : "");
        Gfx.text(Gfx.SEMI, 13, x + 18, y + 20, Gfx.ICE, Menu.fit(t, Gfx.SEMI, 13, w - 36, 0));
        Gfx.text(Gfx.UI, 11, x + 18, y + 40, Gfx.DIM, "Stand still and don't touch anything. Esc stops it.");
        Gfx.rect(x + 18, y + h - 16, w - 36, 4, 2, Gfx.a(Gfx.ICE, 0.12f));
        Gfx.rect(x + 18, y + h - 16, Math.max(4, (w - 36) * Bench.progress()), 4, 2, Th.ac);
        Gfx.s = s0;
        Gfx.alpha = a0;
    }
}
