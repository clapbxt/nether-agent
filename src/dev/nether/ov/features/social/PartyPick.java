package dev.nether.ov.features.social;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import dev.nether.ov.framework.Input;
import dev.nether.ov.game.Game;
import dev.nether.ov.game.Leave;
import dev.nether.ov.menu.title.MainMenu;
import dev.nether.ov.ui.Toast;

/**
 * Opening a picked world (2026-11-21; the pick itself is {@link dev.nether.ov.menu.title.PlaySelect} since 2026-11-27).
 * When the party starts on a world, the leader's game opens it the way a player would (the game's world list, its
 * entry selected, the select button pressed; launchIntegratedServer on 1.8.9 / 1.12.2), then hosts it for the party
 * (WorldShare). Solo, PLAY opens a picked world the same way, without hosting.
 *
 * Found without member names, so it works on 1.14+ with any mappings: the select button by its translation key, the
 * entry by a String naming its folder in saves/.
 */
public final class PartyPick {
    private PartyPick() {}

    public static final int SERVER = 1, WORLD = 2;
    /** a world being opened, and whether its select button was pressed yet */
    private static String opening;
    private static long openingAt;
    private static boolean queued;
    private static long hostUntil;

    private static Class<?> cWorlds;
    private static boolean worldList(Object s) {
        if (s == null || Game.legacy()) return false;
        if (cWorlds == null) cWorlds = Game.cls0("net.minecraft.class_526");
        return cWorlds != null && cWorlds.isInstance(s);
    }

    /** Non-static, non-null object fields of o (not Strings, boxes or the game itself). */
    private static List<Object> objects(Object o) {
        List<Object> out = new ArrayList<>();
        Object mc = Game.mc();
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v == null || v == mc || v instanceof Number || v instanceof Boolean || v instanceof Map) continue;
                    out.add(v);
                } catch (Throwable ignored) { }
            }
        return out;
    }

    private static Path saves() { return Paths.get("saves"); }

    private static boolean isSave(String n) {
        if (n == null || n.isEmpty() || n.length() > 200 || n.contains("/") || n.contains("\\") || n.equals(".") || n.equals("..")) return false;
        try { return Files.isRegularFile(saves().resolve(n).resolve("level.dat")); } catch (Throwable t) { return false; }
    }

    /** A save folder named by one of the String fields of o or of the objects it holds, else null. */
    private static String folderIn(Object e, String want) {
        List<Object> look = new ArrayList<>();
        look.add(e);
        look.addAll(objects(e));
        for (Object o : look) {
            if (o instanceof Collection) continue;
            for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                for (Field f : k.getDeclaredFields()) {
                    if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        String v = (String) f.get(o);
                        if (want != null ? want.equals(v) : isSave(v)) return v;
                    } catch (Throwable ignored) { }
                }
        }
        return null;
    }

    // ---------------------------------------------------------------- opening the picked world (party start)

    /** The party starts on our world: open it, then host it for the party. */
    static void host(String folder) { open(folder, true); }

    /** Solo PLAY on a picked world: open it (from the title screen). */
    public static void openWorld(String folder) { open(folder, false); }

    private static void open(String folder, boolean party) {
        if (!isSave(folder)) { Toast.show("Couldn't find the world " + folder); return; }
        hostUntil = party ? System.currentTimeMillis() + 90_000 : 0;
        if (Game.player0() != null) return; // already in a world (it is the one: we started it)
        if (Game.legacy()) {
            final Object mc = Game.mc();
            final Runnable load = () -> Game.call(mc, Game.l("a", "func_71371_a"), folder, folder, null); // launchIntegratedServer
            new Thread(() -> Game.call(mc, Game.l("a", "func_152344_a"), load), "nether-party-world").start();
            return;
        }
        opening = folder;
        openingAt = System.currentTimeMillis();
        queued = false;
        Input.setMenu(false);
        MainMenu.openRaw(MainMenu.SINGLE, Game.screen());
    }

    /** Every frame: finish opening (world list -> select the entry -> press its select button), then host it. */
    static void tick() {
        long now = System.currentTimeMillis();
        if (opening != null) {
            Object s = Game.screen();
            if (now - openingAt > 20_000) { Toast.show("Couldn't open the world " + opening); opening = null; hostUntil = 0; }
            else if (!queued && worldList(s)) openStep(s);
        }
        if (hostUntil > 0) {
            if (now > hostUntil) hostUntil = 0;
            else if (WorldShare.server() != null && Game.player0() != null) { hostUntil = 0; WorldShare.forParty(); }
        }
    }

    private static void openStep(Object s) {
        Object list = null, entry = null;
        for (Object l : objects(s)) {
            if (l instanceof Collection) continue;
            for (Class<?> k = l.getClass(); k != null && k != Object.class && entry == null; k = k.getSuperclass())
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || !Collection.class.isAssignableFrom(f.getType())) continue;
                    try {
                        f.setAccessible(true);
                        Object c = f.get(l);
                        if (c instanceof Collection) for (Object e : (Collection<?>) c) if (e != null && folderIn(e, opening) != null) { entry = e; break; }
                    } catch (Throwable ignored) { }
                    if (entry != null) break;
                }
            if (entry != null) { list = l; break; }
        }
        if (entry == null) return; // the list loads in the background: try again next frame
        Object sel = Leave.button(Leave.widgets(s), "selectWorld.select");
        if (sel == null) return;
        queued = true;
        final Object fl = list, fe = entry, fs = sel, mc = Game.mc();
        final String name = opening;
        Runnable r = () -> {
            try {
                // the list's selected entry (every field of the entry's type), then the game's own Play Selected World
                for (Class<?> k = fl.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                    for (Field f : k.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers()) || f.getType() == Object.class || Collection.class.isAssignableFrom(f.getType()) || !f.getType().isInstance(fe)) continue;
                        f.setAccessible(true);
                        f.set(fl, fe);
                    }
                if (Leave.click(fs)) System.out.println("[nether-agent] opening world " + name);
            } catch (Throwable t) {
                System.out.println("[nether-agent] party: open world " + name + ": " + t);
            } finally { opening = null; }
        };
        new Thread(() -> Game.call(mc, "execute", r), "nether-party-world").start();
    }
}
