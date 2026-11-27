package dev.nether.ov.features.lyrics;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.LinearGradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.LineMetrics;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.nether.ov.ui.Gfx;

/**
 * Lyric words as baked sprites. A word in its full look (halo, outline, shadow, neon tube, chrome / sunset / hologram fills, sticker border, retro extrusion) is
 * drawn once with java.awt off the render thread into one texture: the effects on top, the plain word in white below.
 * The renderer then puts a word on screen with two to five quads, whatever the style, at any size and position
 * without rounding (no shimmer while it moves), instead of 20-35 glyph runs rasterised at every whole pixel size on the
 * render thread (the old path: stutters while words grew or shrank, and every pass cost fill rate).
 * Three raster sizes (LOD) keep scaled-down words clean; a word not baked yet draws plain once in the meantime.
 */
final class LyricSprites {
    private LyricSprites() {}

    /** raster sizes (font px) a word is baked at; the smallest at least 90% of the drawn size is used */
    private static final int[] LOD = {28, 64, 128};
    private static final int MAX = 160;
    private static final long BYTES = 48L << 20;
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    /** A baked word: texture key, size, and where its advance centre / capital centre sit (px of one half). */
    static final class Sprite {
        String key;
        int w, half, lod;
        float cx, cy;
        long bytes;
    }

    private static final LinkedHashMap<String, Sprite> ready = new LinkedHashMap<>(64, 0.75f, true);
    private static final Set<String> queued = new HashSet<>();
    private static final ConcurrentLinkedQueue<Object[]> done = new ConcurrentLinkedQueue<>();
    private static long bytes;
    private static int serial;
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nether-lyric-sprites");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    /** Look of a word, everything that changes its pixels. col = highlight colour, ga1 / ga2 = the two glow colours. */
    static final class Look {
        int face, col, ga1, ga2, col2;
        String style, fill = "Solid";
        float glow, outline;
        boolean graded() { return !fill.equals("Solid"); }
        String id(String text, int lod) {
            return text + '\u0001' + face + '|' + style + '|' + Math.round(glow * 20) + '|' + Math.round(outline * 20) + '|'
                + Integer.toHexString(col) + '|' + Integer.toHexString(ga1) + '|' + Integer.toHexString(ga2) + '|' + lod
                + (graded() ? "|" + fill + Integer.toHexString(col2) : "");
        }
    }

    /** Render thread: uploads what the baker finished (a few per frame) and trims the cache. */
    static void pump() {
        for (int i = 0; i < 6; i++) {
            Object[] d = done.poll();
            if (d == null) break;
            String id = (String) d[0];
            queued.remove(id);
            Sprite s = (Sprite) d[1];
            BufferedImage img = (BufferedImage) d[2];
            if (s == null || img == null) continue;
            s.key = "lyr" + (serial++);
            Gfx.texture(s.key, img, true);
            s.bytes = 4L * img.getWidth() * img.getHeight();
            Sprite old = ready.put(id, s);
            if (old != null) { Gfx.dropTexture(old.key); bytes -= old.bytes; }
            bytes += s.bytes;
        }
        for (Iterator<Map.Entry<String, Sprite>> it = ready.entrySet().iterator(); it.hasNext() && (ready.size() > MAX || bytes > BYTES); ) {
            Sprite s = it.next().getValue();
            it.remove();
            bytes -= s.bytes;
            Gfx.dropTexture(s.key);
        }
    }

    /** Frees every sprite (theme or font switched off for good; render thread). */
    static void clear() {
        for (Sprite s : ready.values()) Gfx.dropTexture(s.key);
        ready.clear();
        bytes = 0;
    }

    /** The sprite for text at about px font pixels, baking it when missing; a coarser or finer baked one meanwhile, null if none. */
    static Sprite get(String text, Look l, float px) {
        int want = LOD[LOD.length - 1];
        for (int v : LOD) if (v >= px * 0.9f) { want = v; break; }
        String id = l.id(text, want);
        Sprite s = ready.get(id);
        if (s != null && Gfx.hasTexture(s.key)) return s;
        if (s != null) { ready.remove(id); bytes -= s.bytes; } // freed by Gfx under pressure
        if (queued.add(id)) {
            final Look c = copy(l);
            final int lod = want;
            POOL.execute(() -> {
                Object[] r = {id, null, null};
                try { Sprite n = new Sprite(); r[2] = bake(text, c, lod, n); r[1] = n; }
                catch (Throwable t) { System.out.println("[nether-agent] lyric sprite: " + t); }
                done.add(r);
            });
        }
        for (int v : LOD) { // anything baked of this word meanwhile
            if (v == want) continue;
            Sprite o = ready.get(l.id(text, v));
            if (o != null && Gfx.hasTexture(o.key)) return o;
        }
        return null;
    }

    private static Look copy(Look l) {
        Look c = new Look();
        c.face = l.face; c.col = l.col; c.ga1 = l.ga1; c.ga2 = l.ga2; c.style = l.style; c.glow = l.glow; c.outline = l.outline;
        c.fill = l.fill; c.col2 = l.col2;
        return c;
    }

    /** The effects half of sprite s with the word's centre at (cx, cy) framebuffer px, size P px; unrounded. */
    static void fx(Sprite s, float cx, float cy, float P, int tint) { quad(s, cx, cy, P, 0, tint); }

    /** The white word half of sprite s, tinted. */
    static void body(Sprite s, float cx, float cy, float P, int tint) { quad(s, cx, cy, P, 1, tint); }

    /** A horizontal strip (f0..f1 of the height) of the word half, shifted: the glitch tear. */
    static void bodySlice(Sprite s, float cx, float cy, float P, int tint, float f0, float f1) {
        if ((tint >>> 24) < 3) return;
        float k = P / s.lod, x0 = cx - s.cx * k, y0 = cy - s.cy * k, x1 = x0 + s.w * k;
        float ya = y0 + s.half * f0 * k, yb = y0 + s.half * f1 * k, va = 0.5f + 0.5f * f0, vb = 0.5f + 0.5f * f1;
        P8[0] = x0; P8[1] = ya; P8[2] = x1; P8[3] = ya; P8[4] = x1; P8[5] = yb; P8[6] = x0; P8[7] = yb;
        UV[0] = 0; UV[1] = va; UV[2] = 1; UV[3] = va; UV[4] = 1; UV[5] = vb; UV[6] = 0; UV[7] = vb;
        Gfx.imageQuad(s.key, P8, UV, tint);
    }

    private static final float[] P8 = new float[8], UV = new float[8];

    private static void quad(Sprite s, float cx, float cy, float P, int half, int tint) {
        if ((tint >>> 24) < 3) return;
        float k = P / s.lod, x0 = cx - s.cx * k, y0 = cy - s.cy * k, x1 = x0 + s.w * k, y1 = y0 + s.half * k;
        float h2 = s.half * 2f, v0 = (half * s.half + 0.5f) / h2, v1 = ((half + 1) * s.half - 0.5f) / h2;
        P8[0] = x0; P8[1] = y0; P8[2] = x1; P8[3] = y0; P8[4] = x1; P8[5] = y1; P8[6] = x0; P8[7] = y1;
        UV[0] = 0; UV[1] = v0; UV[2] = 1; UV[3] = v0; UV[4] = 1; UV[5] = v1; UV[6] = 0; UV[7] = v1;
        Gfx.imageQuad(s.key, P8, UV, tint);
    }

    // ---------------------------------------------------------------- baking (worker thread)

    private static BufferedImage bake(String text, Look l, int lod, Sprite out) {
        Font base = Gfx.awtFont(l.face);
        if (base.canDisplayUpTo(text) != -1) base = new Font(Font.DIALOG, Font.PLAIN, 12); // CJK, Cyrillic, ... the system face
        Font f = base.deriveFont((float) lod);
        GlyphVector gv = f.createGlyphVector(FRC, text);
        Shape shape = gv.getOutline();
        LineMetrics lm = f.getLineMetrics(text, FRC);
        float adv = (float) f.getStringBounds(text, FRC).getWidth(), asc = lm.getAscent(), desc = lm.getDescent();
        float k = lod / 16f;
        String st = l.style;
        boolean neon = st.equals("Neon"), retro = st.equals("Retro"), chrome = st.equals("Chrome"), sunset = st.equals("Sunset"),
            sticker = st.equals("Sticker"), holo = st.equals("Hologram"), glitch = st.equals("Glitch");
        float glow = glitch ? l.glow * 0.4f : l.glow;
        int pad = (int) Math.ceil(lod * (0.22f + 0.28f * glow) + (retro ? lod * 0.2f : 0) + (sticker ? lod * 0.14f : 0) + (chrome || holo ? lod * 0.06f : 0)) + 2;
        int w = (int) Math.ceil(adv) + 2 * pad, half = (int) Math.ceil(asc + desc) + 2 * pad;
        float bx = pad, by = pad + asc; // baseline origin in each half
        BufferedImage img = new BufferedImage(w, half * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        hints(g);
        Rectangle2D gb = shape.getBounds2D();
        float top = (float) gb.getMinY(), bot = (float) gb.getMaxY();
        // ---- effects (top half)
        float r = lod * (0.07f + 0.08f * glow), a = 0.5f + 0.4f * glow;
        if (neon) { // a glass tube: wide halo in the second glow colour, a tighter one in the first, the tube itself hollow, a hot core in the body half
            blurred(g, shape, w, half, bx, by, r * 2.1f, l.ga2, 0.55f + 0.4f * glow);
            blurred(g, shape, w, half, bx, by, r * 1.05f, l.ga1, a);
            fill(g, shape, bx, by, l.ga1, 0.14f);
            stroke(g, shape, bx, by, Math.max(1.4f, lod * 0.085f), Gfx.mix(l.ga1, 0xFFFFFFFF, 0.18f), 1);
        } else if (chrome) {
            blurred(g, shape, w, half, bx + lod * 0.02f, by + lod * 0.05f, lod * 0.06f, 0xFF04060C, 0.6f);
            stroke(g, shape, bx, by, Math.max(2f, lod * 0.1f), 0xFF0B0F1A, 0.95f);
            int sky = Gfx.mix(0xFFFFFFFF, l.ga2, 0.3f), mid = Gfx.mix(l.ga2, 0xFF0B1630, 0.55f), gnd = Gfx.mix(l.ga1, 0xFF21140A, 0.45f), low = Gfx.mix(l.col, 0xFFFFFFFF, 0.55f);
            fillPaint(g, shape, bx, by, new LinearGradientPaint(0, top, 0, bot, new float[] {0f, 0.46f, 0.5f, 0.53f, 1f},
                new Color[] {new Color(sky, true), new Color(mid, true), new Color(0xFFFFFFFF, true), new Color(gnd, true), new Color(low, true)}));
            stroke(g, shape, bx, by, Math.max(0.9f, lod * 0.018f), 0xFFFFFFFF, 0.55f);
        } else if (sunset) {
            blurred(g, shape, w, half, bx, by, r * 1.4f, l.ga2, 0.45f + 0.3f * glow);
            BufferedImage t = new BufferedImage(w, half, BufferedImage.TYPE_INT_ARGB);
            Graphics2D tg = t.createGraphics();
            hints(tg);
            fillPaint(tg, shape, bx, by, new LinearGradientPaint(0, top, 0, bot, new float[] {0f, 0.55f, 1f},
                new Color[] {new Color(Gfx.mix(l.col, 0xFFFFFFFF, 0.12f), true), new Color(Gfx.mix(l.col, l.ga1, 0.7f), true), new Color(l.ga2, true)}));
            tg.setComposite(AlphaComposite.Clear); // retro-sun bars cut through the lower half, thicker towards the bottom
            float h = bot - top, y0 = by + top + h * 0.5f;
            for (int j = 0; j < 5; j++) {
                float bh = lod * (0.012f + 0.02f * j), yy = y0 + h * (0.5f * j / 5f) + h * 0.04f;
                tg.fill(new Rectangle2D.Float(0, yy, w, bh));
            }
            tg.dispose();
            g.setComposite(AlphaComposite.SrcOver);
            g.drawImage(t, 0, 0, null);
            stroke(g, shape, bx, by, 2.2f * k, 0xFF070A14, 0.22f);
        } else if (sticker) {
            fill(g, shape, bx + lod * 0.05f, by + lod * 0.075f, 0xFF05070C, 0.55f);
            stroke(g, shape, bx, by, lod * 0.27f, 0xFF0B0D14, 1);
            boolean lightText = lum(l.col) > 0.72f;
            stroke(g, shape, bx, by, lod * 0.19f, lightText ? 0xFF1B2233 : 0xFFFFFFFF, 1);
        } else if (holo) {
            BufferedImage t = new BufferedImage(w, half, BufferedImage.TYPE_INT_ARGB);
            Graphics2D tg = t.createGraphics();
            hints(tg);
            int hb = Gfx.mix(l.col, l.ga2, 0.55f);
            fill(tg, shape, bx + lod * 0.035f, by, l.ga1, 0.3f); // a ghost of the projection, offset
            fill(tg, shape, bx, by, hb, 0.6f);
            stroke(tg, shape, bx, by, Math.max(1f, lod * 0.022f), Gfx.mix(hb, 0xFFFFFFFF, 0.6f), 0.95f);
            tg.setComposite(AlphaComposite.Clear); // scanlines
            float period = Math.max(2.4f, lod * 0.052f);
            for (float yy = 0; yy < half; yy += period) tg.fill(new Rectangle2D.Float(0, yy, w, period * 0.42f));
            tg.dispose();
            blurred(g, shape, w, half, bx, by, r * 1.3f, l.ga2, 0.5f + 0.3f * glow);
            g.setComposite(AlphaComposite.SrcOver);
            g.drawImage(t, 0, 0, null);
        } else { // Retro, Echo, Glitch and the plain look (Air in PvP): a halo in the highlight colour
            if (glow > 0.02f) blurred(g, shape, w, half, bx, by, r, l.col, a);
            if (l.outline > 0.02f) {
                stroke(g, shape, bx, by, 4.4f * k, 0xFF070A14, 0.16f * l.outline);
                stroke(g, shape, bx, by, 2.2f * k, 0xFF070A14, 0.34f * l.outline);
            }
            if (retro) {
                float o = Math.max(0.6f, lod * 0.028f);
                for (int j = 6; j >= 1; j--) fill(g, shape, bx + o * j, by + o * j, Gfx.mix(l.ga2, 0xFF000000, 0.15f + 0.06f * j), 1);
                stroke(g, shape, bx, by, 1.6f * k, l.ga1, 0.5f);
            }
        }
        // ---- the word itself, white (bottom half; Chrome, Sunset and Hologram live whole in the top half)
        boolean graded = l.graded() && !(chrome || sunset || holo); // the colour fill: baked in, drawn untinted
        if (neon) { // just the hot core of the tube
            if (graded) {
                fillPaint(g, shape, bx, by + half, gradient(l, gb, 0.55f, 0.3f));
                strokePaint(g, shape, bx, by + half, Math.max(0.9f, lod * 0.032f), gradient(l, gb, 0.55f, 1));
            } else {
                fill(g, shape, bx, by + half, 0xFFFFFFFF, 0.22f);
                stroke(g, shape, bx, by + half, Math.max(0.9f, lod * 0.032f), 0xFFFFFFFF, 1);
            }
        } else if (!(chrome || sunset || holo)) {
            if (graded) fillPaint(g, shape, bx, by + half, gradient(l, gb, 0, 1));
            else fill(g, shape, bx, by + half, 0xFFFFFFFF, 1);
        }
        g.dispose();
        out.w = w; out.half = half; out.lod = lod;
        out.cx = bx + adv / 2;
        out.cy = by - lod * 0.36f; // capitals centred on cy, as Gfx.textFree draws
        return img;
    }

    private static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private static float lum(int argb) { return (0.299f * (argb >> 16 & 255) + 0.587f * (argb >> 8 & 255) + 0.114f * (argb & 255)) / 255f; }

    private static void fillPaint(Graphics2D g, Shape s, float x, float y, java.awt.Paint p) {
        AffineTransform t = g.getTransform();
        g.translate(x, y);
        g.setPaint(p);
        g.fill(s);
        g.setTransform(t);
    }

    private static void strokePaint(Graphics2D g, Shape s, float x, float y, float width, java.awt.Paint p) {
        AffineTransform t = g.getTransform();
        g.translate(x, y);
        g.setPaint(p);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(s);
        g.setTransform(t);
    }

    /** The colour fill of a word (look l: Vertical / Horizontal / Diagonal gradient, or Spiral) over glyph bounds gb, lightened toward white and at alpha a. */
    private static java.awt.Paint gradient(Look l, Rectangle2D gb, float lighten, float a) {
        int al = Math.round(255 * a) << 24;
        int c1 = Gfx.mix(l.col | 0xFF000000, 0xFFFFFFFF, lighten) & 0xFFFFFF | al, c2 = Gfx.mix(l.col2 | 0xFF000000, 0xFFFFFFFF, lighten) & 0xFFFFFF | al;
        float x0 = (float) gb.getMinX(), x1 = (float) Math.max(gb.getMaxX(), gb.getMinX() + 1), y0 = (float) gb.getMinY(), y1 = (float) Math.max(gb.getMaxY(), gb.getMinY() + 1);
        float[] at = {0f, 1f};
        Color[] cs = {new Color(c1, true), new Color(c2, true)};
        switch (l.fill) {
            case "Horizontal": return new LinearGradientPaint(x0, 0, x1, 0, at, cs);
            case "Diagonal": return new LinearGradientPaint(x0, y0, x1, y1, at, cs);
            case "Spiral": { // colour turns round the word's centre and drifts with the distance from it
                int iw = Math.max(2, Math.min(512, (int) Math.ceil(x1 - x0) + 1)), ih = Math.max(2, Math.min(512, (int) Math.ceil(y1 - y0) + 1));
                BufferedImage t = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
                double rmax = 0.5 * Math.hypot(iw, ih);
                for (int y = 0; y < ih; y++) for (int x = 0; x < iw; x++) {
                    double dx = x - iw / 2.0, dy = y - ih / 2.0;
                    double u = Math.atan2(dy, dx) / (2 * Math.PI) + 1.5 * Math.hypot(dx, dy) / rmax;
                    u -= Math.floor(u);
                    t.setRGB(x, y, Gfx.mix(c1, c2, (float) Math.abs(2 * u - 1)));
                }
                return new java.awt.TexturePaint(t, new Rectangle2D.Float(x0, y0, iw, ih));
            }
            default: return new LinearGradientPaint(0, y0, 0, y1, at, cs);
        }
    }

    private static void fill(Graphics2D g, Shape s, float x, float y, int col, float a) {
        AffineTransform t = g.getTransform();
        g.translate(x, y);
        g.setColor(colour(col, a));
        g.fill(s);
        g.setTransform(t);
    }

    private static void stroke(Graphics2D g, Shape s, float x, float y, float width, int col, float a) {
        AffineTransform t = g.getTransform();
        g.translate(x, y);
        g.setColor(colour(col, a));
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(s);
        g.setTransform(t);
    }

    private static Color colour(int argb, float a) {
        int al = Math.max(0, Math.min(255, Math.round(((argb >>> 24) / 255f) * a * 255)));
        return new Color(argb >> 16 & 255, argb >> 8 & 255, argb & 255, al);
    }

    /** The shape filled, blurred by radius r (three box passes ~ Gaussian) and composited in colour col at alpha a. */
    private static void blurred(Graphics2D g, Shape s, int w, int h, float x, float y, float r, int col, float a) {
        BufferedImage m = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = m.createGraphics();
        mg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        mg.translate(x, y);
        mg.setColor(Color.WHITE);
        mg.fill(s);
        mg.dispose();
        int[] px = m.getRGB(0, 0, w, h, null, 0, w);
        float[] al = new float[px.length], tmp = new float[px.length];
        for (int i = 0; i < px.length; i++) al[i] = (px[i] >>> 24) / 255f;
        int rad = Math.max(1, Math.round(r / 1.7f));
        for (int pass = 0; pass < 3; pass++) { box(al, tmp, w, h, rad, true); box(tmp, al, w, h, rad, false); }
        int rgb = col & 0xFFFFFF;
        float ca = ((col >>> 24) / 255f) * a * 1.6f; // a blurred halo is thinner than the shape: lifted a little
        for (int i = 0; i < px.length; i++) px[i] = Math.min(255, Math.round(Math.min(1, al[i] * ca) * 255)) << 24 | rgb;
        m.setRGB(0, 0, w, h, px, 0, w);
        g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(m, 0, 0, null);
    }

    /** One running-sum box blur pass, horizontal or vertical, edges clamped to zero. */
    private static void box(float[] src, float[] dst, int w, int h, int r, boolean horiz) {
        int n = horiz ? w : h, lines = horiz ? h : w;
        float inv = 1f / (2 * r + 1);
        for (int l = 0; l < lines; l++) {
            int base = horiz ? l * w : l, step = horiz ? 1 : w;
            float sum = 0;
            for (int i = 0; i <= r && i < n; i++) sum += src[base + i * step];
            for (int i = 0; i < n; i++) {
                dst[base + i * step] = sum * inv;
                int add = i + r + 1, sub = i - r;
                if (add < n) sum += src[base + add * step];
                if (sub >= 0) sum -= src[base + sub * step];
            }
        }
    }
}
