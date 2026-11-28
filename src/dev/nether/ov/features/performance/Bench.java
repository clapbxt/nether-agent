package dev.nether.ov.features.performance;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.nether.agent.Hook;
import dev.nether.ov.framework.Input;
import dev.nether.ov.game.Game;
import dev.nether.ov.menu.Menu;
import dev.nether.ov.platform.Plat;
import dev.nether.ov.ui.Toast;

/**
 * Benchmark (2026-11-28, vault: research/Benchmark methodology): a repeatable camera spin in the player's own world, with
 * frame times taken at every buffer swap. The same scene is measured several times, each with a different part of Nether
 * switched off, so a player (or we, from a shared report) can tell what Nether costs on that PC and what is the PC's own
 * doing (GPU, settings, JVM). Method: 3 s countdown, then per run 3 s warm-up (discarded; same path) and N s measured;
 * camera = fixed yaw / pitch curve from a fixed start (time based, 36 degrees a second); nothing of ours is drawn while a
 * run is measured; a run is thrown away when a screen opens, the window loses focus or the world is left.
 * Run order puts "Nether as set" first and last: the gap between them is the run-to-run noise every other gap is read against.
 */
public final class Bench {
    private Bench() {}

    public static final int IDLE = 0, COUNT = 1, WARM = 2, RUN = 3;
    /** run 0..4 of the full comparison; the quick test is run 0 only */
    public static final String[] NAMES = {"Nether as set", "HUD hidden", "Performance fixes off", "Nether off", "Nether as set, again"};
    public static final String[] WHY = {
        "Everything the way you have it set up.",
        "Modules still tick, but nothing of the HUD is drawn.",
        "Nether's frame-loop fixes (fences, GPU checks, culling, frame cap, HUD reuse) off.",
        "Overlay skipped and performance fixes off: the game with Nether's agent only.",
        "The first run once more: the gap to it is the noise of this PC."};
    public static final int[] SECS = {10, 20, 30, 60};
    private static final float COUNT_S = 3, WARM_S = 3, WARM_FIRST_S = 8; // the first run also warms up the JIT and the chunk meshes

    public static volatile int state;
    public static boolean compare = true;
    public static int secs = 20;
    /** why the last test stopped (null = nothing to say) and whether that is a problem */
    public static String note;
    public static boolean noteBad;
    public static final List<Result> results = new ArrayList<>();
    /** the run shown in the menu */
    public static int sel;
    /** what the current run switches off, read by Performance / Modules / Overlay */
    public static boolean pausePerf, hideHud;
    private static boolean strip;

    private static int[] plan = {0};
    private static int idx;
    private static long phaseNs, scnNs, lastNs;
    private static float yaw0;
    private static double[] pos0;
    private static boolean moved, drove, camOk;
    private static float[] buf = new float[1 << 16];
    private static int n;
    private static long ovNs, ovMax, ovN, gcN0, gcMs0;
    public static String wasAt = "", testLabel = "";
    /** framebuffer size and game state of the test, for the report */
    public static int fw, fh;

    /** One measured run. */
    public static final class Result {
        public String name, why;
        public int scenario, frames, stutters;
        public double secs;
        /** FPS: average, 1 % low and 0.1 % low (mean of the slowest 1 % / 0.1 % of frames), slowest frame, fastest frame */
        public float avg, low1, low01, min, max;
        /** frame time in ms: median and 99th percentile; coefficient of variation of the frame times */
        public float p50, p99, cv;
        /** worst frame time (ms) per graph column, oldest first */
        public float[] cols;
        /** every frame time (ms), kept for the first run only (the CSV) */
        public float[] ms;
        public double ovUs, ovMaxUs;
        public long gcN, gcMs;
        public boolean moved;
    }

    public static boolean running() { return state != IDLE; }
    public static boolean strip() { return strip; }

    public static int runs() { return compare ? 5 : 1; }
    public static int totalSecs() { return Math.round(COUNT_S + WARM_FIRST_S - WARM_S + runs() * (WARM_S + secs)); }

    /** Scenario being measured (or about to be), 0-based; -1 when idle. */
    public static int index() { return state == IDLE ? -1 : idx; }
    public static int planned() { return plan.length; }
    public static String current() { return state == IDLE || state == COUNT ? "" : NAMES[plan[idx]]; }
    /** seconds left of the whole test */
    public static int left() {
        if (state == IDLE) return 0;
        double t = (System.nanoTime() - phaseNs) / 1e9;
        double w = idx == 0 ? WARM_FIRST_S : WARM_S, done = state == COUNT ? t : COUNT_S + (idx == 0 ? 0 : WARM_FIRST_S - WARM_S + idx * (WARM_S + secs)) + (state == WARM ? t : w + t);
        return Math.max(0, (int) Math.ceil(totalSecs() - done));
    }
    /** whole seconds left of the countdown / warm-up */
    public static int phaseLeft() { return Math.max(1, (int) Math.ceil((state == COUNT ? COUNT_S : idx == 0 ? WARM_FIRST_S : WARM_S) - (System.nanoTime() - phaseNs) / 1e9)); }
    /** 0..1 */
    public static float progress() {
        if (state == IDLE) return 0;
        return Math.max(0, Math.min(1, 1f - left() / (float) totalSecs()));
    }

    /** Starts a test from the menu (render thread, a world is loaded). */
    public static void start(boolean cmp, int s) {
        if (state != IDLE || !Game.hasPlayer()) return;
        compare = cmp;
        secs = s;
        plan = cmp ? new int[] {0, 1, 2, 3, 0} : new int[] {0};
        idx = 0;
        results.clear();
        sel = 0;
        note = null;
        noteBad = false;
        double[] p = Game.player();
        pos0 = p == null ? null : p.clone();
        yaw0 = p == null ? 0 : (float) p[3];
        moved = false;
        drove = false;
        camOk = Hook.camHooked;
        BenchInfo.begin();
        phaseNs = System.nanoTime();
        lastNs = 0;
        state = COUNT;
        Input.setMenu(false);
    }

    /** User or a problem ends the test; results so far are dropped. */
    public static void cancel(String why, boolean bad) {
        if (state == IDLE) return;
        reset();
        results.clear();
        note = why;
        noteBad = bad;
        if (Game.hasPlayer()) { Toast.show(why); Menu.openBenchmark(); }
    }

    private static void reset() {
        state = IDLE;
        strip = pausePerf = hideHud = false;
        if (drove) { Hook.look = false; Hook.lookOf = null; drove = false; }
    }

    private static void startRun(long now) {
        int sc = plan[idx];
        pausePerf = sc == 2 || sc == 3;
        hideHud = sc == 1;
        strip = sc == 3;
        scnNs = phaseNs = now;
        state = WARM;
        n = 0;
    }

    /**
     * First thing of every overlay frame (render thread). Returns true while the run wants the overlay skipped (the
     * caller then does no more than {@link Performance#tick}).
     */
    public static boolean frame(long now) {
        if (state == IDLE) return false;
        long last = lastNs;
        lastNs = now;
        String bad = null;
        if (!Game.hasPlayer()) bad = "You left the world";
        else if (!Plat.cur.focused()) bad = "The game window lost focus";
        else if (Input.menuOpen) bad = "The menu was opened";
        else if (state != COUNT && Game.screenOpen()) bad = "A game screen opened";
        if (bad != null) { cancel("Benchmark stopped: " + bad.toLowerCase(java.util.Locale.ROOT) + ". Nothing was saved.", true); return false; }
        double t = (now - phaseNs) / 1e9;
        if (state == COUNT) {
            if (Game.screenOpen()) Game.setScreen(null); // started from the pause screen
            if (t >= COUNT_S) {
                if (Game.screenOpen()) { cancel("Benchmark stopped: close the game screen first. Nothing was saved.", true); return false; }
                startRun(now);
            }
            return false;
        }
        drive((now - scnNs) / 1e9f);
        if (state == WARM) {
            if (t >= (idx == 0 ? WARM_FIRST_S : WARM_S)) {
                state = RUN;
                phaseNs = now;
                n = 0;
                ovNs = ovMax = ovN = 0;
                long[] g = gc();
                gcN0 = g[0]; gcMs0 = g[1];
            }
            return strip;
        }
        if (last != 0) {
            if (n == buf.length) buf = Arrays.copyOf(buf, n * 2);
            buf[n++] = (now - last) / 1e6f;
        }
        double[] p = Game.player();
        if (p != null && pos0 != null && Math.abs(p[0] - pos0[0]) + Math.abs(p[2] - pos0[2]) > 2) moved = true;
        if (t >= secs) {
            long[] g = gc();
            Result r = stats(NAMES[plan[idx]], WHY[plan[idx]], plan[idx], buf, n, t);
            r.ovUs = ovN == 0 ? 0 : ovNs / 1e3 / ovN;
            r.ovMaxUs = ovMax / 1e3;
            r.gcN = g[0] - gcN0;
            r.gcMs = g[1] - gcMs0;
            r.moved = moved;
            if (results.isEmpty()) r.ms = Arrays.copyOf(buf, n);
            results.add(r);
            idx++;
            if (idx >= plan.length) complete(); else startRun(now);
        }
        return strip;
    }

    /** Overlay time of one frame (only counted while a run is measured). */
    public static void ov(long ns) {
        if (state != RUN || strip) return;
        ovNs += ns;
        ovN++;
        if (ns > ovMax) ovMax = ns;
    }

    private static void drive(float s) {
        if (!camOk) return;
        Object me = Game.player0();
        if (me == null) return;
        Hook.lookOf = me;
        Hook.lookYaw = yaw0 + 36f * s;
        Hook.lookPitch = 8f + 24f * (float) Math.sin(s * 2 * Math.PI / 9);
        Hook.look = true;
        drove = true;
    }

    private static void complete() {
        reset();
        sel = 0;
        BenchInfo.finish(results);
        Toast.show("Benchmark done");
        Menu.openBenchmark();
    }

    private static long[] gc() {
        long c = 0, t = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            long x = b.getCollectionCount();
            if (x > 0) c += x;
            x = b.getCollectionTime();
            if (x > 0) t += x;
        }
        return new long[] {c, t};
    }

    /** Frame-time statistics of a[0..n) in ms. */
    static Result stats(String name, String why, int scenario, float[] a, int n, double secs) {
        Result r = new Result();
        r.name = name; r.why = why; r.scenario = scenario; r.frames = n; r.secs = secs;
        if (n < 2) return r;
        float[] s = Arrays.copyOf(a, n);
        Arrays.sort(s);
        double sum = 0, sq = 0;
        for (int i = 0; i < n; i++) sum += s[i];
        double mean = sum / n;
        for (int i = 0; i < n; i++) sq += (s[i] - mean) * (s[i] - mean);
        r.avg = (float) (1000.0 * n / sum);
        r.low1 = low(s, n, 0.01);
        r.low01 = low(s, n, 0.001);
        r.min = 1000f / Math.max(s[n - 1], 0.01f);
        r.max = 1000f / Math.max(s[0], 0.01f);
        r.p50 = s[n / 2];
        r.p99 = s[Math.min(n - 1, (int) (n * 0.99))];
        r.cv = (float) (Math.sqrt(sq / n) / mean);
        float lim = Math.max(2 * r.p50, r.p50 + 5);
        for (int i = n - 1; i >= 0 && s[i] > lim; i--) r.stutters++;
        int cols = 240;
        r.cols = new float[cols];
        double t = 0;
        for (int i = 0; i < n; i++) {
            int c = Math.min(cols - 1, (int) (t / sum * cols));
            if (a[i] > r.cols[c]) r.cols[c] = a[i];
            t += a[i];
        }
        return r;
    }

    /** FPS of the slowest `frac` of the frames: 1000 / mean of the longest frame times (sorted ascending). */
    private static float low(float[] s, int n, double frac) {
        int k = Math.max(1, (int) Math.ceil(n * frac));
        double sum = 0;
        for (int i = n - k; i < n; i++) sum += s[i];
        return (float) (1000.0 * k / sum);
    }
}
