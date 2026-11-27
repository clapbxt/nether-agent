package dev.nether.ov.features.lyrics;

import dev.nether.ov.features.media.Media;
import dev.nether.ov.framework.Modules;
import dev.nether.ov.framework.Module;
import dev.nether.ov.framework.Setting;

/**
 * Karaoke HUD widget: the song playing (cover, title, artist, controls) with its synced lyrics under it, lit word by
 * word, in place of a timeline. The lyrics, their colours and font come from the Music module ({@link Lyrics}), which
 * draws this panel with this widget's layout and box settings. Works with Music's in-world lyrics on or off.
 */
public final class Karaoke extends Module {
    public Karaoke() {
        super("karaoke", "Karaoke", "HUD", "The song playing with its synced lyrics under it, lit word by word as they are sung. Lyric colours and font follow the Music module.", true, false, 0.7, 0.45);
        add(Setting.header("Layout"));
        add(Setting.choice("panelStyle", "Style", "Karaoke", "Karaoke", "Focus", "Minimal").hint("Karaoke shows the lines around the current one. Focus and Minimal keep attention on the sung line."));
        add(Setting.choice("lines", "Lines at once", "3", "1", "3", "5").when(() -> str("panelStyle").equals("Karaoke")));
        add(Setting.choice("align", "Alignment", "Centre", "Left", "Centre"));
        add(Setting.slider("width", "Width", 160, 420, 2, 260));
        add(Setting.bool("songInfo", "Song info", true).when(() -> !str("panelStyle").equals("Minimal")));
        add(Setting.bool("albumArt", "Album art", true).when(() -> !str("panelStyle").equals("Minimal") && bool("songInfo")));
        add(Setting.choice("playerControls", "Player controls", "On hover", "Off", "On hover", "Always").hint("Play/pause and skip while the cursor is free (chat, inventory, this menu).").when(() -> !str("panelStyle").equals("Minimal")));
        add(Setting.header("Behaviour"));
        add(Setting.slider("opacity", "Opacity (%)", 20, 100, 5, 100));
        add(Setting.choice("paused", "When paused", "Keep", "Keep", "Dim", "Hide"));
        drop("mcFont"); // the lyrics keep their own type
        drop("color");  // and their own colours (Music > Look)
        drop("chroma");
    }

    @Override public boolean available() { return Media.supported(); }
    @Override public String lockNote() { return !available() ? "NOT AVAILABLE ON THIS OS" : super.lockNote(); }

    @Override public void tick() { if (live()) Media.keep(); }
    @Override public boolean visible() { return Modules.LYRICS.panelVisible(this); }
    @Override public void measure() { Modules.LYRICS.panelMeasure(this); }
    @Override public void draw(float x, float y) { Modules.LYRICS.panelDraw(this, x, y); }
}
