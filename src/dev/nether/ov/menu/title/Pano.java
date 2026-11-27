package dev.nether.ov.menu.title;

import java.awt.image.BufferedImage;

import dev.nether.ov.ui.Gfx;

/**
 * Nether title panoramas, stored as six vanilla-style cube faces under {@code pano/<name>/panorama_N.png}. Cube
 * folders are drawn the way vanilla's CubeMap does (six faces in perspective, 85 degree field of view, so the faces
 * stay close to 1:1 on screen and sharp); each face is a grid of small quads with exactly projected corners, which
 * works on 1.8.9 through 26.x and in editor previews. Panoramas that are still strips use the strip projection.
 */
public final class Pano {
    private Pano() {}

    /** panorama folders under pano/, in the order the setting lists them */
    static final String[] NAMES = {"Sakura", "Lookout", "Meadow", "Dusk", "Valley", "Peaks", "Village", "Mesa"};
    static final String[] CHOICES = new String[NAMES.length + 1];
    static { CHOICES[0] = "Random"; System.arraycopy(NAMES, 0, CHOICES, 1, NAMES.length); }

    /** half the strip's height in h units (tangent of elevation) */
    private static final float HMAX = 0.75f;
    /** tangent of half of vanilla's 85 degree panorama field of view */
    private static final float VANILLA_TV = 0.9163f;
    /** degrees per second of the slow turn (one turn in ~2.7 minutes, close to vanilla's) */
    private static final float SPEED = 2.2f;

    private static final int RANDOM = new java.util.Random().nextInt(NAMES.length);
    private static volatile BufferedImage ready;
    private static volatile BufferedImage[] readyFaces;
    private static volatile String readyName, failed;
    private static volatile boolean busy;
    private static volatile int readyBlur;
    private static String loaded;
    /** blur radius (percent of a face's edge, x10) the loaded textures were made with */
    private static int loadedBlur = -1;
    private static float yaw = -1, fade;
    private static long turnedAt;

    private static void drop(String n) {
        Gfx.dropTexture("pano:" + n);
        for (int f = 0; f < 6; f++) Gfx.dropTexture("pano:" + n + ":" + f);
    }

    /** The panorama the setting asks for (Random = one per game start). */
    static String wanted(String choice) {
        for (String n : NAMES) if (n.equals(choice)) return n;
        return NAMES[RANDOM];
    }

    /** Uploads the decoded cube map once ready, or starts decoding and resampling it off-thread. */
    private static boolean ready(String name) {
        BufferedImage img = ready;
        BufferedImage[] fc = readyFaces;
        if (img != null || fc != null) {
            String n = readyName;
            boolean same = n.equals(loaded);
            if (loaded != null && !same) drop(loaded);
            if (fc != null) for (int f = 0; f < 6; f++) Gfx.texture("pano:" + n + ":" + f, fc[f], true);
            else Gfx.texture("pano:" + n, img, true);
            loaded = n;
            loadedBlur = readyBlur;
            ready = null;
            readyFaces = null;
            if (!same) fade = 0; // a new blur strength of the same panorama swaps in place
        }
        final int blur = blurWanted();
        boolean have = name.equals(loaded) && (Gfx.hasTexture("pano:" + name) || Gfx.hasTexture("pano:" + name + ":0"));
        if (have && blur == loadedBlur) return true;
        if (!busy && !(name.equals(failed) && !have)) {
            busy = true;
            Thread t = new Thread(() -> {
                try {
                    // shipped strips (pano/<name>.jpg) until cube-face folders replace them
                    byte[] jpg = face(name, 0) == null ? dev.nether.agent.Hook.file.apply("pano/" + name + ".jpg") : null;
                    if (jpg != null) {
                        BufferedImage i = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpg));
                        if (i == null) throw new IllegalArgumentException("unreadable strip");
                        readyBlur = blur;
                        readyName = name;
                        ready = blur > 0 ? blurStrip(i, blur) : sharpen(i);
                        return;
                    }
                    BufferedImage[] faces = new BufferedImage[6];
                    int edge = -1;
                    for (int f = 0; f < faces.length; f++) {
                        byte[] b = face(name, f);
                        faces[f] = b == null ? null : javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(b));
                        if (faces[f] == null || faces[f].getWidth() != faces[f].getHeight()
                                || (edge >= 0 && faces[f].getWidth() != edge)) throw new IllegalArgumentException("missing or mismatched panorama face " + f);
                        edge = faces[f].getWidth();
                    }
                    matchSeams(faces, edge);
                    if (blur > 0) faces = blurCube(faces, edge, blur);
                    readyBlur = blur;
                    readyName = name;
                    readyFaces = faces;
                } catch (Throwable e) {
                    failed = name;
                    System.out.println("[nether-agent] panorama " + name + ": " + e);
                } finally { busy = false; }
            }, "nether-pano");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }
        return have; // a new blur strength is being made: the old one shows meanwhile
    }

    /** The Blur setting as a radius in tenths of a percent of the face edge (0 = sharp). */
    private static int blurWanted() {
        return (int) Math.round(Math.max(0, Math.min(1, MainMenu.MODULE.num("blur"))) * 12);
    }

    /**
     * Soft focus for the cube faces (the captures are very detailed, and turning slowly they shimmered and pulled the eye
     * from the menu): three box blurs (close to a Gaussian) per face. Each face is padded with its neighbours' pixels
     * first, looked up through the cube, so the blur runs across the seams and no edge shows.
     */
    static BufferedImage[] blurCube(BufferedImage[] f, int edge, int blur) {
        int r = Math.max(1, Math.round(edge * blur / 1000f)), pad = r * 3 + 1, pw = edge + 2 * pad;
        int[][] src = new int[6][];
        for (int i = 0; i < 6; i++) src[i] = f[i].getRGB(0, 0, edge, edge, null, 0, edge);
        BufferedImage[] out = new BufferedImage[6];
        float[] d = new float[3], uv = new float[2];
        int[] p = new int[pw * pw], t = new int[pw * pw];
        for (int face = 0; face < 6; face++) {
            for (int y = 0; y < pw; y++) for (int x = 0; x < pw; x++) {
                int ix = x - pad, iy = y - pad;
                if (ix >= 0 && iy >= 0 && ix < edge && iy < edge) { p[y * pw + x] = src[face][iy * edge + ix]; continue; }
                dir(face, (ix + 0.5f) / edge, (iy + 0.5f) / edge, d);
                int nf = locate(d[0], d[1], d[2], uv);
                int nx = Math.max(0, Math.min(edge - 1, (int) (uv[0] * edge))), ny = Math.max(0, Math.min(edge - 1, (int) (uv[1] * edge)));
                p[y * pw + x] = src[nf][ny * edge + nx];
            }
            for (int pass = 0; pass < 3; pass++) { boxH(p, t, pw, pw, r); boxV(t, p, pw, pw, r); }
            BufferedImage o = new BufferedImage(edge, edge, BufferedImage.TYPE_INT_RGB);
            o.setRGB(0, 0, edge, edge, p, pad * pw + pad, pw);
            out[face] = o;
        }
        return out;
    }

    /** The same soft focus for a strip (wraps round left and right, clamps top and bottom). */
    static BufferedImage blurStrip(BufferedImage src, int blur) {
        int w = src.getWidth(), h = src.getHeight(), r = Math.max(1, Math.round(h * blur / 1000f));
        int[] p = src.getRGB(0, 0, w, h, null, 0, w), t = new int[p.length];
        for (int pass = 0; pass < 3; pass++) { boxH(p, t, w, h, r); boxV(t, p, w, h, r); }
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        o.setRGB(0, 0, w, h, p, 0, w);
        return o;
    }

    /** Horizontal box blur of radius r, src into dst (edges clamp). */
    private static void boxH(int[] src, int[] dst, int w, int h, int r) {
        float n = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w, sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) { int c = src[row + Math.max(0, Math.min(w - 1, k))]; sr += c >> 16 & 255; sg += c >> 8 & 255; sb += c & 255; }
            for (int x = 0; x < w; x++) {
                dst[row + x] = 0xFF000000 | Math.round(sr / n) << 16 | Math.round(sg / n) << 8 | Math.round(sb / n);
                int a = src[row + Math.max(0, x - r)], b = src[row + Math.min(w - 1, x + r + 1)];
                sr += (b >> 16 & 255) - (a >> 16 & 255); sg += (b >> 8 & 255) - (a >> 8 & 255); sb += (b & 255) - (a & 255);
            }
        }
    }

    /** Vertical box blur of radius r, src into dst (edges clamp). */
    private static void boxV(int[] src, int[] dst, int w, int h, int r) {
        float n = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) { int c = src[Math.max(0, Math.min(h - 1, k)) * w + x]; sr += c >> 16 & 255; sg += c >> 8 & 255; sb += c & 255; }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = 0xFF000000 | Math.round(sr / n) << 16 | Math.round(sg / n) << 8 | Math.round(sb / n);
                int a = src[Math.max(0, y - r) * w + x], b = src[Math.min(h - 1, y + r + 1) * w + x];
                sr += (b >> 16 & 255) - (a >> 16 & 255); sg += (b >> 8 & 255) - (a >> 8 & 255); sb += (b & 255) - (a & 255);
            }
        }
    }

    /** Cube face f of a panorama folder: shipped as JPEG (panorama_N.jpg, a tenth of the PNG's size), PNG also read. */
    private static byte[] face(String name, int f) {
        byte[] b = dev.nether.agent.Hook.file.apply("pano/" + name + "/panorama_" + f + ".jpg");
        return b != null ? b : dev.nether.agent.Hook.file.apply("pano/" + name + "/panorama_" + f + ".png");
    }

    /**
     * The shipped strips are 3840 x 720 (960 px per quarter turn), so a 1080p title shows them about 2x enlarged and the
     * GPU's bilinear stretch made them look soft. Off-thread: a bicubic 1.5x enlargement, then an unsharp mask (3x3
     * Gaussian, wrapping round the seam), so the remaining stretch on screen starts from a crisp picture.
     */
    static BufferedImage sharpen(BufferedImage src) {
        int w = src.getWidth() * 3 / 2, h = src.getHeight() * 3 / 2;
        BufferedImage up = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = up.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        int[] p = up.getRGB(0, 0, w, h, null, 0, w), b = new int[p.length];
        for (int y = 0; y < h; y++) { // horizontal 1-2-1 (the strip wraps round at its ends)
            int r = y * w;
            for (int x = 0; x < w; x++) {
                int l = p[r + (x == 0 ? w - 1 : x - 1)], c = p[r + x], q = p[r + (x == w - 1 ? 0 : x + 1)];
                b[r + x] = (((l >> 16 & 255) + 2 * (c >> 16 & 255) + (q >> 16 & 255)) / 4) << 16
                    | (((l >> 8 & 255) + 2 * (c >> 8 & 255) + (q >> 8 & 255)) / 4) << 8 | ((l & 255) + 2 * (c & 255) + (q & 255)) / 4;
            }
        }
        float amt = 0.65f;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) { // vertical 1-2-1 of that, then original + amt * (original - blur)
            int ru = Math.max(0, y - 1) * w, r = y * w, rd = Math.min(h - 1, y + 1) * w;
            for (int x = 0; x < w; x++) {
                int o = p[r + x], v = 0;
                for (int sh = 16; sh >= 0; sh -= 8) {
                    int bl = ((b[ru + x] >> sh & 255) + 2 * (b[r + x] >> sh & 255) + (b[rd + x] >> sh & 255)) / 4, oc = o >> sh & 255;
                    v |= Math.max(0, Math.min(255, Math.round(oc + amt * (oc - bl)))) << sh;
                }
                row[x] = v;
            }
            up.setRGB(0, y, w, 1, row, 0, w);
        }
        return up;
    }

    /**
     * Whole-screen title background. False until the picture is loaded (the caller keeps vanilla's panorama under it).
     */
    static boolean title(float W, float H, String choice, boolean anim) {
        String name = wanted(choice);
        if (!ready(name)) return false;
        if (yaw < 0) yaw = new java.util.Random().nextFloat() * 360; // each start opens on a different side
        long now = System.nanoTime();
        float dt = turnedAt == 0 ? 0 : Math.min(0.1f, (now - turnedAt) / 1e9f); // by the clock: the game's menus and our title can both draw it in one frame
        turnedAt = now;
        if (anim) yaw = (yaw + SPEED * dt) % 360;
        fade = Math.min(1, fade + dt * 1.6f);
        view(name, 0, 0, W, H, yaw, Gfx.hasTexture("pano:" + name + ":0") ? VANILLA_TV : 0.6f, 0, fade);
        return true;
    }

    // ---------------------------------------------------------------- the game's own menus (Hook.pano)

    private static int fbTex = -1, fbId;

    /** Our framebuffer with the game's main colour texture attached (made again when the game swaps it on resize). */
    private static int fbo(int tex) {
        if (tex == fbTex && fbId != 0) return fbId;
        int prev = org.lwjgl.opengl.GL11.glGetInteger(0x8CA6); // DRAW_FRAMEBUFFER_BINDING
        if (fbId == 0) fbId = org.lwjgl.opengl.GL30.glGenFramebuffers();
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, fbId);
        org.lwjgl.opengl.GL30.glFramebufferTexture2D(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, org.lwjgl.opengl.GL11.GL_TEXTURE_2D, tex, 0);
        boolean ok = org.lwjgl.opengl.GL30.glCheckFramebufferStatus(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER) == org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE;
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, prev);
        fbTex = tex;
        if (!ok) { System.out.println("[nether-agent] menu panorama: target not usable"); return 0; }
        return fbId;
    }

    /**
     * Instead of the game's panorama (its CubeMap.render, hooked): ours, into the game's main render target, under the
     * menu the game draws next. Its own menu blur and darkening (1.20.5+) then apply on top, as they do to its panorama.
     * False (the game draws its own) while ours loads, when the setting is off or no target can be found.
     */
    static boolean menu() {
        if (!MainMenu.MODULE.available() || !MainMenu.MODULE.bool("panoEverywhere") || dev.nether.ov.game.Game.legacy()) return false;
        if (dev.nether.ov.platform.GlBackend.cur == null) return false;
        String name = wanted(MainMenu.MODULE.str("pano"));
        Object rt = dev.nether.ov.game.Game.call(dev.nether.ov.game.Game.mc(), "method_1522");
        if (rt == null) return false;
        Object ct = dev.nether.ov.game.Game.call(rt, "method_30277");
        Object id = ct == null || ct instanceof Integer ? null : dev.nether.ov.game.Game.call(ct, "method_68427"); // 1.21.5+: GlTexture id
        Object wO = dev.nether.ov.game.Game.fld(rt, "field_1482"), hO = dev.nether.ov.game.Game.fld(rt, "field_1481");
        if (!(wO instanceof Integer) || !(hO instanceof Integer)) return false;
        int W = (Integer) wO, H = (Integer) hO;
        // 1.21.5+: nothing is bound between passes, so we draw through our own framebuffer on its colour texture;
        // before that the game's target is still bound while it draws the menu
        int fb = id instanceof Integer ? fbo((Integer) id) : org.lwjgl.opengl.GL11.glGetInteger(0x8CA6);
        if (fb == 0 || W <= 0 || H <= 0) return false;
        int w0 = Gfx.fbW, h0 = Gfx.fbH;
        float s0 = Gfx.s, a0 = Gfx.alpha;
        boolean drawn = false;
        dev.nether.ov.platform.GlBackend.targetFb = fb;
        Gfx.begin(W, H);
        try {
            Gfx.s = 1;
            Gfx.alpha = 1;
            if (!ready(name)) return false; // uploads inside our GL state (Gfx.texture saves the game's first)
            boolean anim = MainMenu.MODULE.bool("spin") && !dev.nether.ov.framework.Config.calm();
            fade = 1; // the game's panorama is not under it here: no fade in over an empty frame
            drawn = title(W, H, MainMenu.MODULE.str("pano"), anim);
        } finally {
            Gfx.end();
            dev.nether.ov.platform.GlBackend.targetFb = 0;
            Gfx.fbW = w0;
            Gfx.fbH = h0;
            Gfx.s = s0;
            Gfx.alpha = a0;
        }
        return drawn;
    }

    /** A still view of the given (or the random) panorama inside a box, for editor previews; false while loading. */
    public static boolean still(String choice, float x, float y, float w, float h, float yawDeg, float tv, float r) {
        String name = wanted(choice);
        if (!ready(name)) return false;
        int[] prev = Gfx.clipIn(x, y, w, h);
        view(name, x, y, w, h, yawDeg, tv, 0, 1);
        Gfx.reclip(prev);
        return true;
    }

    /**
     * Perspective view of a strip into the box: tv = tangent of half the vertical field of view (kept under HMAX), lift
     * shifts the horizon (h units), a = alpha. Use one slice per framebuffer pixel so the projection has no visible
     * vertical steps. (Vanilla renders a cube map; these assets are equirectangular strips, so they need this projection.)
     */
    private static void view(String name, float x, float y, float w, float h, float yawDeg, float tv, float lift, float a) {
        if (Gfx.hasTexture("pano:" + name + ":0")) { cube(name, x, y, w, h, yawDeg, tv, a); return; }
        String key = "pano:" + name;
        tv = Math.min(tv, HMAX - Math.abs(lift) - 0.01f);
        float f = (h / 2) / tv, cx = x + w / 2, s = Gfx.s;
        int tint = (Math.round(a * 255) << 24) | 0xFFFFFF;
        int n = Math.max(1, Math.round(w * s));
        float base = yawDeg / 360f;
        for (int i = 0, p0 = Math.round(x * s); i < n; i++) {
            int p1 = Math.round((x + w * (i + 1) / n) * s);
            if (p1 <= p0) continue;
            float x0 = p0 / s, x1 = p1 / s, xm = (x0 + x1) / 2 - cx;
            float a0 = (float) (Math.atan((x0 - cx) / f) / (2 * Math.PI)), a1 = (float) (Math.atan((x1 - cx) / f) / (2 * Math.PI));
            float u0 = base + a0 - (float) Math.floor(base + a0), u1 = u0 + a1 - a0;
            float span = (h / 2) / (float) Math.sqrt(xm * xm + f * f); // h units from the middle to the top of this column
            float v0 = 0.5f - (lift + span) / (2 * HMAX), v1 = 0.5f - (lift - span) / (2 * HMAX);
            if (u1 <= 1) Gfx.image(key, x0, y, x1 - x0, h, u0, v0, u1, v1, tint, 0);
            else { // crosses the seam: two pieces
                float xs = x0 + (x1 - x0) * (1 - u0) / (u1 - u0);
                Gfx.image(key, x0, y, xs - x0, h, u0, v0, 1, v1, tint, 0);
                Gfx.image(key, xs, y, x1 - xs, h, 0, v0, u1 - 1, v1, tint, 0);
            }
            p0 = p1;
        }
    }

    /** Face and uv (0..1) a direction hits, in vanilla's face layout; the inverse of {@link #dir}. */
    private static int locate(float dx, float dy, float dz, float[] uv) {
        float ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (az >= ax && az >= ay) {
            uv[1] = (1 - dy / az) * 0.5f;
            if (dz >= 0) { uv[0] = (dx / az + 1) * 0.5f; return 0; }
            uv[0] = (1 - dx / az) * 0.5f; return 2;
        }
        if (ax >= ay) {
            uv[1] = (1 - dy / ax) * 0.5f;
            if (dx >= 0) { uv[0] = (1 - dz / ax) * 0.5f; return 1; }
            uv[0] = (1 + dz / ax) * 0.5f; return 3;
        }
        uv[0] = (dx / ay + 1) * 0.5f;
        if (dy >= 0) { uv[1] = (dz / ay + 1) * 0.5f; return 4; }
        uv[1] = (1 - dz / ay) * 0.5f; return 5;
    }

    /**
     * The captures were rendered face by face with a shader pack, so exposure and bloom differ per face (the face looking at
     * the sun is washed out) and the cube edges show as steps in brightness. For every edge, compares each face's border
     * colour with the neighbour's across it and eases the face towards the middle of the two, fading out over the outer 40%
     * of the face. The picture inside is untouched; only the steps at the seams disappear.
     */
    static void matchSeams(BufferedImage[] f, int edge) {
        final int B = 64, blk = Math.max(1, edge / B);
        final float D = 0.4f;
        int[][] px = new int[6][];
        for (int i = 0; i < 6; i++) px[i] = f[i].getRGB(0, 0, edge, edge, null, 0, edge);
        float[][][] lr = new float[6][4][B * 3]; // log ratio neighbour / own, per side, block and channel
        float[] d = new float[3], uv = new float[2];
        for (int face = 0; face < 6; face++) for (int side = 0; side < 4; side++) {
            for (int b = 0; b < B; b++) {
                double[] own = new double[3], nb = new double[3];
                for (int k = 0; k < blk; k++) {
                    float t = (b * blk + k + 0.5f) / edge; // along the side
                    float u = side < 2 ? t : side == 2 ? 0 : 1, v = side >= 2 ? t : side == 0 ? 0 : 1;
                    float step = 1.5f / edge, ou = side == 2 ? -step : side == 3 ? step : 0, ov = side == 0 ? -step : side == 1 ? step : 0;
                    dir(face, u + ou, v + ov, d); // just outside this face: the neighbour's border
                    int nf = locate(d[0], d[1], d[2], uv);
                    int nx = Math.max(0, Math.min(edge - 1, (int) (uv[0] * edge))), ny = Math.max(0, Math.min(edge - 1, (int) (uv[1] * edge)));
                    int ox = Math.max(0, Math.min(edge - 1, (int) (u * edge) + (side == 2 ? 1 : side == 3 ? -1 : 0)));
                    int oy = Math.max(0, Math.min(edge - 1, (int) (v * edge) + (side == 0 ? 1 : side == 1 ? -1 : 0)));
                    int o = px[face][oy * edge + ox], n = px[nf][ny * edge + nx];
                    for (int c = 0; c < 3; c++) { own[c] += o >> (16 - 8 * c) & 255; nb[c] += n >> (16 - 8 * c) & 255; }
                }
                for (int c = 0; c < 3; c++) lr[face][side][b * 3 + c] = (float) Math.log(Math.max(0.4, Math.min(2.5, (nb[c] + 8 * blk) / (own[c] + 8 * blk))));
            }
            for (int pass = 0; pass < 3; pass++) { // smooth along the edge
                float[] a = lr[face][side], o = a.clone();
                for (int b = 0; b < B; b++) for (int c = 0; c < 3; c++)
                    a[b * 3 + c] = (o[Math.max(0, b - 1) * 3 + c] + 2 * o[b * 3 + c] + o[Math.min(B - 1, b + 1) * 3 + c]) / 4;
            }
        }
        float[] sum = new float[3];
        for (int face = 0; face < 6; face++) {
            for (int y = 0; y < edge; y++) for (int x = 0; x < edge; x++) {
                float u = (x + 0.5f) / edge, v = (y + 0.5f) / edge;
                sum[0] = sum[1] = sum[2] = 0;
                for (int side = 0; side < 4; side++) {
                    float dist = side == 0 ? v : side == 1 ? 1 - v : side == 2 ? u : 1 - u;
                    if (dist >= D) continue;
                    float w = (1 - dist / D) * (1 - dist / D) * 0.5f, pos = (side < 2 ? u : v) * B - 0.5f;
                    int i0 = Math.max(0, Math.min(B - 1, (int) Math.floor(pos))), i1 = Math.min(B - 1, i0 + 1);
                    float fr = Math.max(0, Math.min(1, pos - i0));
                    for (int c = 0; c < 3; c++) sum[c] += w * (lr[face][side][i0 * 3 + c] * (1 - fr) + lr[face][side][i1 * 3 + c] * fr);
                }
                if (sum[0] == 0 && sum[1] == 0 && sum[2] == 0) continue;
                int p = px[face][y * edge + x], out = 0xFF000000;
                for (int c = 0; c < 3; c++) {
                    float g = (float) Math.exp(Math.max(-0.7f, Math.min(0.7f, sum[c])));
                    out |= Math.min(255, Math.round((p >> (16 - 8 * c) & 255) * g)) << (16 - 8 * c);
                }
                px[face][y * edge + x] = out;
            }
            f[face].setRGB(0, 0, edge, edge, px[face], 0, edge);
        }
    }

    /** Cube-face direction for face f at (u, v) in 0..1: the layout of vanilla's faces (+Z, +X, -Z, -X, +Y, -Y). */
    private static void dir(int f, float u, float v, float[] d) {
        float a = 2 * u - 1, b = 1 - 2 * v;
        switch (f) {
            case 0: d[0] = a; d[1] = b; d[2] = 1; break;
            case 1: d[0] = 1; d[1] = b; d[2] = -a; break;
            case 2: d[0] = -a; d[1] = b; d[2] = -1; break;
            case 3: d[0] = -1; d[1] = b; d[2] = a; break;
            case 4: d[0] = a; d[1] = 1; d[2] = -b; break;
            default: d[0] = a; d[1] = -1; d[2] = b;
        }
    }

    /** Vanilla-style cube map view: each face is a grid of quads whose corners are projected exactly (perspective-correct). */
    private static void cube(String name, float x, float y, float w, float h, float yawDeg, float tv, float a) {
        final int N = 16;
        float f = (h / 2) / tv, cx = x + w / 2, cy = y + h / 2, s = Gfx.s;
        double yw = Math.toRadians(yawDeg);
        float fx = (float) Math.sin(yw), fz = (float) Math.cos(yw);
        int tint = (Math.round(a * 255) << 24) | 0xFFFFFF;
        float[] d = new float[3], px = new float[(N + 1) * (N + 1)], py = new float[px.length];
        boolean[] ok = new boolean[px.length];
        for (int face = 0; face < 6; face++) {
            String key = "pano:" + name + ":" + face;
            int[] sz = Gfx.textureSize(key);
            if (sz == null) continue;
            float e = 0.5f / sz[0]; // sample inside the face: no seams between faces
            for (int j = 0, i = 0; j <= N; j++) for (int k = 0; k <= N; k++, i++) {
                dir(face, k / (float) N, j / (float) N, d);
                float z = d[0] * fx + d[2] * fz;
                ok[i] = z > 0.02f;
                if (ok[i]) { px[i] = (cx + f * (d[0] * fz - d[2] * fx) / z) * s; py[i] = (cy - f * d[1] / z) * s; }
            }
            for (int j = 0; j < N; j++) for (int k = 0; k < N; k++) {
                int i0 = j * (N + 1) + k, i1 = i0 + 1, i2 = i1 + N + 1, i3 = i2 - 1;
                if (!(ok[i0] && ok[i1] && ok[i2] && ok[i3])) continue;
                float minX = Math.min(Math.min(px[i0], px[i1]), Math.min(px[i2], px[i3])), maxX = Math.max(Math.max(px[i0], px[i1]), Math.max(px[i2], px[i3]));
                float minY = Math.min(Math.min(py[i0], py[i1]), Math.min(py[i2], py[i3])), maxY = Math.max(Math.max(py[i0], py[i1]), Math.max(py[i2], py[i3]));
                if (maxX < x * s || minX > (x + w) * s || maxY < y * s || minY > (y + h) * s) continue;
                float u0 = e + (1 - 2 * e) * k / N, u1 = e + (1 - 2 * e) * (k + 1) / N, v0 = e + (1 - 2 * e) * j / N, v1 = e + (1 - 2 * e) * (j + 1) / N;
                Gfx.imageQuad(key, new float[] {px[i0], py[i0], px[i1], py[i1], px[i2], py[i2], px[i3], py[i3]}, new float[] {u0, v0, u1, v0, u1, v1, u0, v1}, tint);
            }
        }
    }
}
