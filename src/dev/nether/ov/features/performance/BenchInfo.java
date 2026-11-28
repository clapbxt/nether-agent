package dev.nether.ov.features.performance;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import dev.nether.agent.Hook;
import dev.nether.ov.framework.Config;
import dev.nether.ov.framework.Json;
import dev.nether.ov.framework.Module;
import dev.nether.ov.framework.Modules;
import dev.nether.ov.framework.Setting;
import dev.nether.ov.game.Game;
import dev.nether.ov.platform.GlBackend;

/**
 * What the benchmark page knows about this PC (2026-11-28): the graphics adapter the game really draws on (OpenGL strings)
 * next to the adapters Windows lists, CPU / RAM / power plan, Java and its flags, Minecraft version, loader, mods and
 * shaders, the video settings from options.txt, and which Nether features are on; plus the findings drawn from them and
 * from the benchmark runs, and the text report a player can copy. Everything is read once (and again on Refresh); the
 * Windows part runs PowerShell on a background thread.
 */
public final class BenchInfo {
    private BenchInfo() {}

    /** {section, label, value} for the page and the report */
    private static volatile List<String[]> rows = new ArrayList<>();
    /** {level, text}: 0 fine, 1 note, 2 worth a look, 3 likely the problem */
    private static volatile List<Object[]> finds = new ArrayList<>();
    private static boolean collected;
    private static volatile boolean slowDone;

    private static String glVendor, glRenderer, glVersion, loader = "Vanilla", shader, cpuName = "", planGuid = "";
    private static final List<String> gpus = new ArrayList<>(), mods = new ArrayList<>(), prefs = new ArrayList<>();
    private static final Map<String, String> opt = new LinkedHashMap<>();
    private static int threads, hz;
    private static long ramMb, freeMb;
    private static String plan = "";
    private static boolean slowFailed;
    /** where the last report went, shown under the buttons */
    public static volatile String saved;

    public static List<String[]> rows() { return rows; }
    public static List<Object[]> findings() { return finds; }
    public static boolean ready() { return collected; }
    public static boolean busy() { return collected && !slowDone; }

    public static void ensure() { if (!collected) collect(); }

    public static void begin() { collect(); }

    /** Render thread (OpenGL strings are read here). */
    public static void collect() {
        collected = true;
        slowDone = false;
        slowFailed = false;
        String[] gl = null;
        try { gl = GlBackend.cur == null ? null : GlBackend.cur.glInfo(); } catch (Throwable t) { }
        glVendor = gl == null ? null : gl[0];
        glRenderer = gl == null ? null : gl[1];
        glVersion = gl == null ? null : gl[2];
        loader = loader();
        readOptions();
        readMods();
        ramMb = 0; freeMb = 0;
        try {
            Object os = ManagementFactory.getOperatingSystemMXBean();
            Class<?> c = Class.forName("com.sun.management.OperatingSystemMXBean");
            if (c.isInstance(os)) {
                ramMb = ((Number) c.getMethod("getTotalPhysicalMemorySize").invoke(os)).longValue() >> 20;
                freeMb = ((Number) c.getMethod("getFreePhysicalMemorySize").invoke(os)).longValue() >> 20;
            }
        } catch (Throwable t) { }
        threads = Runtime.getRuntime().availableProcessors();
        cpuName = ""; plan = ""; planGuid = ""; hz = 0;
        gpus.clear();
        prefs.clear();
        rebuild();
        Thread th = new Thread(BenchInfo::system, "nether-bench-info");
        th.setDaemon(true);
        th.start();
    }

    // ---------------------------------------------------------------- readers

    private static String loader() {
        ClassLoader[] cl = {Game.loader(), Thread.currentThread().getContextClassLoader(), ClassLoader.getSystemClassLoader()};
        String[][] probe = {{"org.quiltmc.loader.api.QuiltLoader", "Quilt"}, {"net.fabricmc.loader.api.FabricLoader", "Fabric"},
            {"net.neoforged.fml.loading.FMLLoader", "NeoForge"}, {"net.minecraftforge.fml.loading.FMLLoader", "Forge"}, {"net.minecraftforge.fml.common.Loader", "Forge"}};
        for (String[] p : probe)
            for (ClassLoader c : cl) {
                if (c == null) continue;
                try { Class.forName(p[0], false, c); return p[1]; } catch (Throwable t) { }
            }
        return "Vanilla";
    }

    private static Path gameDir() { return Paths.get("").toAbsolutePath(); }

    private static void readOptions() {
        opt.clear();
        try {
            for (String l : Files.readAllLines(gameDir().resolve("options.txt"), StandardCharsets.UTF_8)) {
                int i = l.indexOf(':');
                if (i > 0) opt.put(l.substring(0, i), l.substring(i + 1).trim());
            }
        } catch (Throwable t) { }
    }

    private static void readMods() {
        mods.clear();
        shader = null;
        File[] fs = gameDir().resolve("mods").toFile().listFiles();
        if (fs != null) for (File f : fs) if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) mods.add(f.getName().replaceAll("(?i)\\.jar$", ""));
        Collections.sort(mods, String.CASE_INSENSITIVE_ORDER);
        // Iris / OptiFine shader pack in use
        try {
            Path ir = gameDir().resolve("config").resolve("iris.properties");
            if (Files.exists(ir)) {
                String pack = null; boolean on = true;
                for (String l : Files.readAllLines(ir, StandardCharsets.UTF_8)) {
                    if (l.startsWith("shaderPack=")) pack = l.substring(11).trim();
                    if (l.startsWith("enableShaders=")) on = !l.endsWith("false");
                }
                if (on && pack != null && !pack.isEmpty() && !pack.equals("(internal)")) shader = pack + " (Iris)";
            }
            Path of = gameDir().resolve("optionsshaders.txt");
            if (shader == null && Files.exists(of))
                for (String l : Files.readAllLines(of, StandardCharsets.UTF_8))
                    if (l.startsWith("shaderPack=")) { String p = l.substring(11).trim(); if (!p.isEmpty() && !p.equals("OFF") && !p.equals("(internal)")) shader = p + " (OptiFine)"; }
        } catch (Throwable t) { }
    }

    private static final String PS = "$ProgressPreference='SilentlyContinue';$ErrorActionPreference='SilentlyContinue';"
        + "$c=Get-CimInstance Win32_Processor|Select-Object -First 1;"
        + "$g=@(Get-CimInstance Win32_VideoController|ForEach-Object{[pscustomobject]@{n=$_.Name;d=$_.DriverVersion;hz=$_.CurrentRefreshRate}});"
        + "$p=(powercfg /getactivescheme|Out-String).Trim();"
        + "$k=Get-ItemProperty 'HKCU:\\Software\\Microsoft\\DirectX\\UserGpuPreferences';$j=@();"
        + "if($k){$j=@($k.PSObject.Properties|Where-Object{$_.Name -like '*java*'}|ForEach-Object{[pscustomobject]@{n=$_.Name;v=[string]$_.Value}})};"
        + "[pscustomobject]@{cpu=$c.Name;threads=$c.NumberOfLogicalProcessors;gpu=$g;plan=$p;pref=$j}|ConvertTo-Json -Compress -Depth 4";

    /** Background: the parts only the OS knows (Windows: PowerShell; elsewhere the CPU name only). */
    @SuppressWarnings("unchecked")
    private static void system() {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                String enc = java.util.Base64.getEncoder().encodeToString(PS.getBytes(StandardCharsets.UTF_16LE));
                Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", enc).redirectErrorStream(true).start();
                final StringBuilder out = new StringBuilder();
                Thread rd = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                        String l;
                        while ((l = r.readLine()) != null) out.append(l);
                    } catch (Throwable t) { }
                });
                rd.setDaemon(true);
                rd.start();
                if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroyForcibly();
                rd.join(2000);
                String s = out.toString();
                int i = s.indexOf('{'), je = s.lastIndexOf('}');
                Map<String, Object> m = (Map<String, Object>) Json.parse(i < 0 || je < i ? "{}" : s.substring(i, je + 1));
                cpuName = str(m.get("cpu")).replaceAll("\\s+", " ");
                Object th = m.get("threads");
                if (th instanceof Number) threads = ((Number) th).intValue();
                Object g = m.get("gpu");
                List<Object> gl = g instanceof List ? (List<Object>) g : g instanceof Map ? Collections.singletonList(g) : new ArrayList<>();
                for (Object o : gl) {
                    Map<String, Object> e = (Map<String, Object>) o;
                    String n = str(e.get("n"));
                    if (n.isEmpty()) continue;
                    gpus.add(n + (str(e.get("d")).isEmpty() ? "" : "  (driver " + str(e.get("d")) + ")"));
                    Object h = e.get("hz");
                    if (h instanceof Number && ((Number) h).intValue() > hz) hz = ((Number) h).intValue();
                }
                plan = str(m.get("plan"));
                java.util.regex.Matcher gm = java.util.regex.Pattern.compile("([0-9a-fA-F]{8}-[0-9a-fA-F-]{27})").matcher(plan);
                if (gm.find()) planGuid = gm.group(1).toLowerCase(Locale.ROOT);
                Object pf = m.get("pref");
                List<Object> pl = pf instanceof List ? (List<Object>) pf : pf instanceof Map ? Collections.singletonList(pf) : new ArrayList<>();
                for (Object o : pl) {
                    Map<String, Object> e = (Map<String, Object>) o;
                    String pn = str(e.get("n")).replace('/', '\\').toLowerCase(Locale.ROOT), jh = System.getProperty("java.home", "").replace('/', '\\').toLowerCase(Locale.ROOT);
                    if (!jh.isEmpty() && pn.startsWith(jh)) prefs.add(str(e.get("n")).replaceAll("^.*\\\\", "") + " = " + str(e.get("v")));
                }
            } else if (os.contains("linux")) {
                for (String l : Files.readAllLines(Paths.get("/proc/cpuinfo"), StandardCharsets.UTF_8)) if (l.startsWith("model name")) { cpuName = l.substring(l.indexOf(':') + 1).trim(); break; }
            }
        } catch (Throwable t) {
            slowFailed = true;
        }
        slowDone = true;
        rebuild();
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    // ---------------------------------------------------------------- the rows

    private static String redact(String s) {
        String u = System.getProperty("user.name");
        return u == null || u.length() < 2 ? s : s.replace(u, "<user>");
    }

    private static String opt(String k) { return opt.get(k); }
    private static int optInt(String k, int def) { try { return Integer.parseInt(opt.get(k)); } catch (Throwable t) { return def; } }

    private static List<String> jvmArgs() {
        List<String> l = new ArrayList<>();
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            String x = a.toLowerCase(Locale.ROOT);
            if (!a.startsWith("-") || x.contains("token") || x.contains("session") || x.contains("uuid") || x.contains("password") || x.startsWith("-dnether.")) continue;
            if (x.startsWith("-d") && (a.contains("/") || a.contains("\\"))) continue; // paths: not performance flags
            if (x.startsWith("-javaagent:")) { l.add("-javaagent:" + a.replaceAll("^.*[/\\\\]", "")); continue; }
            l.add(redact(a));
        }
        return l;
    }

    private static String gcNames() {
        StringBuilder b = new StringBuilder();
        for (java.lang.management.GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) b.append(b.length() > 0 ? ", " : "").append(g.getName());
        return b.toString();
    }

    private static String mb(long m) { return m >= 1024 ? String.format(Locale.ROOT, "%.1f GB", m / 1024.0) : m + " MB"; }

    private static void rebuild() {
        List<String[]> r = new ArrayList<>();
        String nv = System.getProperty("nether.version");
        r.add(new String[] {"Game", "Minecraft", (Game.version() == null ? "unknown" : Game.version()) + "  -  " + loader});
        r.add(new String[] {"Game", "Nether client", nv == null ? "development build" : nv});
        r.add(new String[] {"Game", "Mods", mods.isEmpty() ? "none" : mods.size() + ":  " + String.join(", ", mods)});
        r.add(new String[] {"Game", "Shaders", shader == null ? "off" : shader});
        String rp = opt("resourcePacks");
        if (rp != null) r.add(new String[] {"Game", "Resource packs", rp.equals("[]") ? "none" : rp.replaceAll("[\\[\\]\"]", "").replace("file/", "").replace(",", ", ")});
        // video settings
        String[][] keys = {{"renderDistance", "Render distance"}, {"simulationDistance", "Simulation distance"}, {"maxFps", "Max framerate"}, {"enableVsync", "VSync"},
            {"graphicsMode", "Graphics (0 fast, 1 fancy, 2 fabulous)"}, {"guiScale", "GUI scale"}, {"particles", "Particles (0 all, 2 minimal)"},
            {"entityDistanceScaling", "Entity distance"}, {"biomeBlendRadius", "Biome blend"}, {"mipmapLevels", "Mipmap levels"}, {"ao", "Smooth lighting"}, {"entityShadows", "Entity shadows"},
            {"fullscreen", "Fullscreen"}};
        r.add(new String[] {"Video", "Resolution", Bench.fw > 0 ? Bench.fw + " x " + Bench.fh : "unknown"});
        for (String[] k : keys) if (opt(k[0]) != null) r.add(new String[] {"Video", k[1], opt(k[0]).equals("260") && k[0].equals("maxFps") ? "unlimited" : opt(k[0])});
        // graphics
        r.add(new String[] {"Graphics", "Drawing with", glRenderer == null ? "unknown (no OpenGL information; the game may be using Vulkan)" : glRenderer});
        if (glVendor != null) r.add(new String[] {"Graphics", "Vendor", glVendor});
        if (glVersion != null) r.add(new String[] {"Graphics", "OpenGL", glVersion});
        if (slowDone) {
            for (String g : gpus) r.add(new String[] {"Graphics", "Adapter", g});
            if (hz > 0) r.add(new String[] {"Graphics", "Monitor refresh", hz + " Hz"});
            if (!gpus.isEmpty() || System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
                r.add(new String[] {"Graphics", "Windows graphics choice for Java", prefs.isEmpty() ? "none set for this Java (Windows decides)" : redact(String.join("; ", prefs))});
        }
        // pc
        r.add(new String[] {"This PC", "Processor", slowDone ? (cpuName.isEmpty() ? "unknown" : cpuName) + "  (" + threads + " threads)" : "reading..."});
        r.add(new String[] {"This PC", "Memory", ramMb > 0 ? mb(ramMb) + " (" + mb(freeMb) + " free)" : "unknown"});
        r.add(new String[] {"This PC", "System", System.getProperty("os.name", "?") + " " + System.getProperty("os.version", "") + " " + System.getProperty("os.arch", "")});
        if (slowDone && !plan.isEmpty()) r.add(new String[] {"This PC", "Power plan", redact(plan.matches(".*\\(.*\\)\\s*$") ? plan.replaceAll("^.*\\((.*)\\)\\s*$", "$1") : plan)});
        // java
        r.add(new String[] {"Java", "Runtime", System.getProperty("java.version", "?") + "  -  " + System.getProperty("java.vm.name", "?") + " (" + System.getProperty("java.vendor", "?") + ")"});
        r.add(new String[] {"Java", "Memory for the game", mb(Runtime.getRuntime().maxMemory() >> 20) + " max,  " + mb((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20) + " in use"});
        r.add(new String[] {"Java", "Garbage collector", gcNames()});
        List<String> ja = jvmArgs();
        r.add(new String[] {"Java", "JVM flags", ja.isEmpty() ? "none" : String.join(" ", ja)});
        // nether
        StringBuilder on = new StringBuilder();
        int cnt = 0;
        for (Module m : Modules.all) if (!m.hidden() && m.enabled() && !m.id.equals("performance")) { cnt++; on.append(on.length() > 0 ? ", " : "").append(m.name); }
        r.add(new String[] {"Nether", "Modules on", cnt == 0 ? "none" : cnt + ":  " + on});
        StringBuilder pf = new StringBuilder();
        for (Setting s : Performance.M.settings)
            if (s.def instanceof Boolean && s.stored() && !s.key.equals("chroma") && !s.key.equals("mcFont") && !s.key.equals("labels") && !s.key.equals("bg"))
                pf.append(pf.length() > 0 ? ", " : "").append(s.label.toLowerCase(Locale.ROOT)).append(Performance.M.bool(s.key) ? "" : " (off)");
        r.add(new String[] {"Nether", "Performance fixes", (Performance.M.wantedOn() ? "on:  " : "off:  ") + pf});
        r.add(new String[] {"Nether", "Game hooks", "GL state " + yn(Hook.glShadowed) + ", camera " + yn(Hook.camHooked) + ", entity culling " + yn(Hook.cullHooked)});
        rows = r;
        finds = findings(Bench.results);
    }

    private static String yn(boolean b) { return b ? "yes" : "no"; }

    // ---------------------------------------------------------------- findings

    private static final String[][] STANDARD = {{"a1841308-3541-4fab-bc81-f71556f20b4a", "Power saver"}};

    static boolean software(String r) {
        String x = r.toLowerCase(Locale.ROOT);
        return x.contains("llvmpipe") || x.contains("software") || x.contains("microsoft basic") || x.contains("gdi generic") || x.contains("softpipe") || x.contains("swiftshader");
    }

    /** Graphics that share the CPU's memory: Intel UHD / HD / Iris, AMD "Radeon Graphics" / Vega of an APU. */
    static boolean integrated(String r) {
        String x = r.toLowerCase(Locale.ROOT).replace("(tm)", "").replace("(r)", "").replaceAll("\\s+", " ");
        if (x.contains("intel")) return !x.contains(" arc"); // Arc A / B cards are discrete
        if (!x.contains("radeon") || x.contains(" rx ") || x.contains("radeon pro") || x.contains("firepro")) return false;
        return x.matches(".*radeon( graphics|.*\\d{3,4}m\\b.*| vega \\d+.*| r\\d graphics.*).*");
    }

    private static void add(List<Object[]> l, int level, String text) { l.add(new Object[] {level, text}); }

    /** Findings from the PC (always) and from the benchmark results (when there are some). */
    static List<Object[]> findings(List<Bench.Result> shown) {
        List<Bench.Result> res = new ArrayList<>(shown);
        List<Object[]> l = new ArrayList<>();
        // graphics adapter
        if (glRenderer == null) add(l, 1, "OpenGL information could not be read here, so the graphics card in use is unknown (newer versions may draw with Vulkan).");
        else if (software(glRenderer)) add(l, 3, "The game is drawing with software rendering (" + glRenderer + ") instead of a graphics card. Update the graphics driver.");
        else if (integrated(glRenderer)) {
            String other = null;
            for (String g : gpus) if (!integrated(g) && !software(g) && !g.toLowerCase(Locale.ROOT).contains("virtual") && !g.toLowerCase(Locale.ROOT).contains("remote")) other = g;
            if (other != null)
                add(l, 3, "Minecraft is drawing on integrated graphics (" + glRenderer + ") while this PC also has " + other.replaceAll("\\s+\\(driver.*", "")
                    + ". Windows: Settings > System > Display > Graphics > add the Java used by the game (javaw.exe) > Options > High performance. A monitor plugged into the motherboard instead of the graphics card has the same effect.");
            else add(l, 2, "The game is drawing on integrated graphics (" + glRenderer + "). Expect much lower FPS than with a dedicated graphics card.");
        } else add(l, 0, "Drawing on " + glRenderer + ".");
        String jv = null;
        for (String p : prefs) if (p.toLowerCase(Locale.ROOT).contains("javaw") || p.toLowerCase(Locale.ROOT).contains("java.exe")) jv = p;
        if (jv != null && jv.contains("GpuPreference=1")) add(l, 2, "Windows is told to run this Java on the power-saving graphics adapter (Settings > System > Display > Graphics).");
        // settings
        if ("true".equals(opt("enableVsync"))) add(l, 2, "VSync is on: the frame rate is held to your monitor's refresh rate" + (hz > 0 ? " (" + hz + " Hz)" : "") + ". Turn it off to see what the PC can do.");
        int mf = optInt("maxFps", 260);
        if (mf < 260 && opt("maxFps") != null) add(l, 2, "Max framerate is set to " + mf + ": the game never draws more than that. Set it to Unlimited to measure.");
        int rd = optInt("renderDistance", 0);
        if (rd >= 24) add(l, 2, "Render distance is " + rd + " chunks. The game draws and updates far more terrain than at 12 - 16; it is the usual reason for low FPS on strong PCs.");
        if (optInt("graphicsMode", 1) == 2) add(l, 2, "Graphics is on Fabulous!, which adds extra full-screen passes. Fancy or Fast is much lighter.");
        if (shader != null) add(l, 2, "A shader pack is on (" + shader + "). Shaders usually cost more FPS than everything else combined.");
        if (Bench.fw >= 3840) add(l, 1, "Resolution is " + Bench.fw + " x " + Bench.fh + ": four times the pixels of 1080p, so the graphics card works much harder.");
        // memory and Java
        long heap = Runtime.getRuntime().maxMemory() >> 20;
        if (heap < 2000) add(l, 2, "The game has only " + mb(heap) + " of memory. Frequent garbage collection causes stutter; 3 - 6 GB is plenty.");
        else if (ramMb > 0 && heap > ramMb * 0.6) add(l, 2, "The game may use " + mb(heap) + ", more than 60% of this PC's " + mb(ramMb) + ". Windows then swaps to disk.");
        else if (heap > 12000) add(l, 1, "The game may use " + mb(heap) + ". A very large heap can make garbage collection pauses long; 3 - 8 GB is enough for most setups.");
        String gc = gcNames().toLowerCase(Locale.ROOT);
        if (gc.contains("zgc")) add(l, 1, "The ZGC garbage collector is in use. A client test (brucethemoose, GitHub) found it costs frame rate; G1 is the usual choice.");
        if (slowDone && !planGuid.isEmpty())
            for (String[] sp : STANDARD) if (planGuid.equals(sp[0])) add(l, 2, "Windows power plan is " + sp[1] + ", which lowers CPU speed. Use Balanced or High performance.");
        boolean fabricish = loader.equals("Fabric") || loader.equals("Quilt");
        boolean hasSodium = false;
        for (String m : mods) if (m.toLowerCase(Locale.ROOT).startsWith("sodium") || m.toLowerCase(Locale.ROOT).contains("embeddium")) hasSodium = true;
        if (fabricish && !hasSodium && Game.version() != null && !Game.version().startsWith("1.8") && !Game.version().startsWith("1.12")) add(l, 1, "No Sodium (or Embeddium) is installed. It replaces the game's terrain renderer and is the biggest single FPS gain on Fabric.");
        // results
        if (!res.isEmpty()) addResults(l, res);
        if (slowFailed) add(l, 1, "Parts of the system information could not be read.");
        return l;
    }

    private static final int[] RATES = {30, 60, 75, 90, 100, 120, 144, 165, 180, 240, 280, 360};

    private static void addResults(List<Object[]> l, List<Bench.Result> res) {
        Bench.Result a = res.get(0);
        if (a.frames < 10) { add(l, 2, "Too few frames were recorded (" + a.frames + "). Run the test again."); return; }
        for (int r : RATES) {
            if (Math.abs(a.avg - r) / r < 0.03 && a.low1 > a.avg * 0.9 && a.cv < 0.15) {
                add(l, 3, "The frame rate sits flat at " + Math.round(a.avg) + " FPS: it looks capped, not limited by speed. Max framerate, VSync, a driver cap or the monitor's refresh rate"
                    + (hz == r ? " (" + hz + " Hz) is the likely cause." : " could be the cause. Check those first."));
                break;
            }
        }
        if (a.low1 < a.avg * 0.5 && a.low1 < 100) add(l, 2, "The 1% low (" + Math.round(a.low1) + ") is under half the average (" + Math.round(a.avg) + "): the game stutters. Common causes: garbage collection, chunk loading, background programs.");
        if (a.avg < 60) add(l, 2, "Average is under 60 FPS. The game is struggling on this PC at these settings.");
        if (a.gcMs > a.secs * 10) add(l, 2, "Garbage collection paused the game for " + a.gcMs + " ms in " + Math.round(a.secs) + " s (" + a.gcN + " collections). More or less game memory, or other Java flags, may help.");
        for (Bench.Result r : res) if (r.moved) { add(l, 1, "You moved during a run, so it is not exactly comparable."); break; }
        if (!Hook.camHooked) add(l, 1, "The camera path is not available on this version, so the camera stood still.");
        if (a.ovUs > 0) add(l, a.ovUs / 10.0 / (1000.0 / a.avg) > 3 ? 1 : 0, String.format(Locale.ROOT, "Nether's own code takes %.0f µs of an average %.2f ms frame (%.1f%%); worst frame %.0f µs.", a.ovUs, 1000.0 / a.avg, a.ovUs / 10.0 / (1000.0 / a.avg), a.ovMaxUs));
        if (res.size() >= 5) {
            Bench.Result off = res.get(3), again = res.get(4), hud = res.get(1), perf = res.get(2);
            double noise = Math.abs(a.avg - again.avg) / Math.max(a.avg, 1) * 100;
            double mean = (a.avg + again.avg) / 2;
            double cost = (off.avg - mean) / Math.max(off.avg, 1) * 100;
            double costMs = 1000.0 / mean - 1000.0 / off.avg;
            if (noise > 8) add(l, 2, String.format(Locale.ROOT, "Two identical runs differ by %.1f%%. Close other programs and run again before trusting the comparison.", noise));
            if (Math.abs(cost) <= Math.max(1.5, noise * 1.5))
                add(l, 0, String.format(Locale.ROOT, "Nether makes no measurable difference on this PC: with it %d FPS, with it off %d FPS (%.1f%%, within the %.1f%% noise between identical runs).", Math.round(mean), Math.round(off.avg), cost, noise));
            else if (cost > 0)
                add(l, cost > 10 ? 3 : 2, String.format(Locale.ROOT, "Nether costs %.1f%% here (%d FPS with it, %d off; %.2f ms per frame). HUD drawing accounts for %.1f%%, the performance fixes for %.1f%%.",
                    cost, Math.round(mean), Math.round(off.avg), costMs, (hud.avg - a.avg) / Math.max(hud.avg, 1) * 100, (perf.avg - a.avg) / Math.max(perf.avg, 1) * 100));
            else add(l, 0, String.format(Locale.ROOT, "Nether makes this PC %.1f%% faster (%d FPS with it, %d with it off).", -cost, Math.round(mean), Math.round(off.avg)));
            if (cost <= 1.5 && a.avg < 200) add(l, 1, "Nether is not what limits the frame rate here: with it fully off the game runs at " + Math.round(off.avg) + " FPS. The graphics card, the settings or the game itself are.");
        }
    }

    // ---------------------------------------------------------------- finish + report

    /** Render thread, after the last run: rebuilds the findings and writes the report and the frame times next to the config. */
    static void finish(List<Bench.Result> res) {
        rebuild();
        final String text = report();
        final Bench.Result first = res.isEmpty() ? null : res.get(0);
        final String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date());
        Thread t = new Thread(() -> {
            try {
                Path d = Config.dir().resolve("benchmarks");
                Files.createDirectories(d);
                Files.write(d.resolve("benchmark-" + stamp + ".txt"), text.getBytes(StandardCharsets.UTF_8));
                if (first != null && first.ms != null) {
                    StringBuilder b = new StringBuilder("Frame,MsBetweenPresents\n");
                    for (int i = 0; i < first.ms.length; i++) b.append(i + 1).append(',').append(String.format(Locale.ROOT, "%.4f", first.ms[i])).append('\n');
                    Files.write(d.resolve("benchmark-" + stamp + "-frametimes.csv"), b.toString().getBytes(StandardCharsets.UTF_8));
                }
                saved = "Saved to " + redact(d.toString());
            } catch (Throwable e) { saved = "Could not save the report: " + e.getMessage(); }
        }, "nether-bench-save");
        t.setDaemon(true);
        t.start();
    }

    private static String f0(double v) { return String.format(Locale.ROOT, "%.0f", v); }

    /** The report as plain text, to paste into a chat. */
    public static String report() {
        StringBuilder b = new StringBuilder();
        String nv = System.getProperty("nether.version");
        b.append("NETHER BENCHMARK REPORT\n");
        b.append("Nether ").append(nv == null ? "dev" : nv).append("  |  Minecraft ").append(Game.version()).append(" (").append(loader).append(")  |  ")
            .append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date())).append("\n\n");
        List<Bench.Result> res = Bench.results;
        if (!res.isEmpty()) {
            b.append("RESULTS  (camera spin, ").append(Bench.secs).append(" s per run, ").append(Bench.fw).append(" x ").append(Bench.fh).append(", first run is the reference)\n");
            b.append(String.format(Locale.ROOT, "%-24s %8s %8s %9s %7s %7s %9s %8s%n", "Run", "Avg FPS", "1% low", "0.1% low", "Min", "Max", "Stutters", "vs first"));
            Bench.Result a = res.get(0);
            for (Bench.Result r : res)
                b.append(String.format(Locale.ROOT, "%-24s %8s %8s %9s %7s %7s %9d %8s%n", r.name, f0(r.avg), f0(r.low1), f0(r.low01), f0(r.min), f0(r.max), r.stutters,
                    r == a ? "-" : String.format(Locale.ROOT, "%+.1f%%", (r.avg - a.avg) / Math.max(a.avg, 1) * 100)));
            b.append(String.format(Locale.ROOT, "%nFrame time of the first run: median %.2f ms, 99th percentile %.2f ms.  Nether's overlay: %.0f us per frame (worst %.0f us).  Garbage collection: %d runs, %d ms.%n",
                a.p50, a.p99, a.ovUs, a.ovMaxUs, a.gcN, a.gcMs));
            b.append("1% / 0.1% low = FPS of the average of the slowest 1% / 0.1% of frames.\n\n");
        }
        b.append("FINDINGS\n");
        String[] lv = {"ok  ", "note", "look", "LIKELY"};
        for (Object[] f : finds) b.append(" [").append(lv[(Integer) f[0]]).append("] ").append(f[1]).append('\n');
        String sec = "";
        for (String[] r : rows) {
            if (!r[0].equals(sec)) { sec = r[0]; b.append('\n').append(sec.toUpperCase(Locale.ROOT)).append('\n'); }
            b.append(String.format(Locale.ROOT, " %-34s %s%n", r[1], r[2]));
        }
        return redact(b.toString());
    }
}
