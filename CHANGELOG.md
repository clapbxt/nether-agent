# Changelog

What changed in each client release, newest first. Add a line under **Unreleased** in the same pull request as the
change: what a player notices, not how it was done. At release, move the lines under the new version.

## Unreleased
- New SELECT MODE screen for picking where to play: your worlds and servers as cards with their icons, game mode,
  version, last played, server status, ping and players, plus search. Type a server address in the search box to
  join a server that isn't in your list.
- Parties work like Fortnite and Valorant now: the leader presses SELECT MODE (or clicks the pick on the party card)
  and picks a world or server, everyone sees it and readies up, and you all load in together. This replaces picking
  on Minecraft's own world and server screens, and works the same on 1.8.9 and 1.12.2.
- Solo, the MODE card above PLAY opens the same screen, so PLAY can start a specific world or server directly.
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
- The 3D lyrics panel opens a plain white bubble round you, with the panel on its wall, like stepping into a settings
  room. It moves with you instead of drifting and works like a normal screen: no walking, dropping, using items or
  hotbar while it is open, and Esc leaves. A slider under the panel (also in settings) sets how far away it stands.
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
