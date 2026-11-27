# Changelog

What changed in each client release, newest first. Add a line under **Unreleased** in the same pull request as the
change: what a player notices, not how it was done. At release, move the lines under the new version.

## Unreleased
- The lyrics key (O) now opens the normal lyric settings, like the pause menu, instead of the 3D panel, which is gone.
- Fixed: a custom lyrics key that is a letter or digit (like 7) no longer types itself into the Mods search box when it opens the lyric settings.
- Lyric text styles are new and distinct: Neon (glass tube), Chrome, Sunset, Sticker, Hologram, Retro, Echo and Glitch (torn slices). Glow, Clean, Chroma and Outline are replaced; saved looks switch to the nearest one.
- Sky lyrics are flat sheets fixed in the sky instead of turning to face you.
- Fixed: lyrics could stay seconds off when a song was started part way through or you jumped within it; words are re-timed whenever the song position corrects.
- Faster frames on 1.21.5 and newer (OpenGL): Performance merges the game's per-buffer GPU fences into one per frame
  and drops needless framebuffer switches after each clear. Measured on 1.21.11 with Sodium, Lithium,
  ImmediatelyFast, BadOptimizations and EntityCulling: about 27% more FPS and 13% better 1% lows than before; plain
  1.21.11 and 26.1.2 gain too. Both are Performance settings (One GPU fence per frame, Fewer framebuffer switches).
- Nether's HUD costs less each frame: when it is reused between rebuilds it is drawn from a buffer kept on the GPU
  instead of being uploaded again.
- Four new title panoramas from real cube captures: Sakura (sharper, replaces the old one), Lookout, Meadow and Dusk.
- World lyrics are drawn as ready-made word images: far smoother frame rate, no shimmer while words move, and every
  text style keeps its look at any size.
- Sky lyrics now sit in the sky itself on 1.21.2 - 26.1: hills, trees and clouds pass in front of them, and they stay
  put in the sky as you walk. Air, Row and Lines words fade when a wall or tree is between you and them.
- Fixed: your cape now shows on your character in the party lobby.
- World lyrics and waypoints glide smoothly while you walk on 1.8.9 and 1.12.2 instead of stepping.
- Fire, Shield and Ride View Editor pages open the live editor over your own game when you are in a world.
- The title panorama turns again (Animate background is back on) and looks sharper.

## 1.0.0
- First release from this repo. The client used to ship inside the launcher; the launcher now installs and
  updates it in every instance on its own.
- Fixed: Minecraft 1.13 could crash when drawing the overlay.
