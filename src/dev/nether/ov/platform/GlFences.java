package dev.nether.ov.platform;

import org.lwjgl.opengl.GL32;

import dev.nether.agent.Hook;

/** The real GL sync calls behind Hook's shared fences (Performance "One GPU fence per frame"); LWJGL3, render thread. */
public final class GlFences implements Hook.Fences {
    @Override public long create() { return GL32.glFenceSync(0x9117, 0); }
    @Override public int await(long sync, int flags, long timeout) { return GL32.glClientWaitSync(sync, flags, timeout); }
    @Override public void delete(long sync) { GL32.glDeleteSync(sync); }
}
