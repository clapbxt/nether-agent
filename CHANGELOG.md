# Changelog

What changed in each client release, newest first. Add a line under **Unreleased** in the same pull request as the
change: what a player notices, not how it was done. At release, move the lines under the new version.

## Unreleased
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
- The 3D lyrics panel opens a white bubble round you, with the panel on its wall, like stepping into a settings room.
  It moves with you instead of drifting, clicks never hit the hidden world, Esc leaves, and a new 3D panel distance
  setting (default a little further than before) sets how far away it stands.
- Fixed: your cape now shows on your character in the party lobby.
- The 3D lyrics panel is a solid slab with visible edges from the side and a plain back, and it no longer vanishes
  while chat is open.
- World lyrics, the 3D panel and waypoints glide smoothly while you walk on 1.8.9 and 1.12.2 instead of stepping.
- Fire, Shield and Ride View Editor pages open the live editor over your own game when you are in a world.
- The title panorama turns again (Animate background is back on) and looks sharper.

## 1.0.0
- First release from this repo. The client used to ship inside the launcher; the launcher now installs and
  updates it in every instance on its own.
- Fixed: Minecraft 1.13 could crash when drawing the overlay.
