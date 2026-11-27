# Changelog

What changed in each client release, newest first. Add a line under **Unreleased** in the same pull request as the
change: what a player notices, not how it was done. At release, move the lines under the new version.

## Unreleased

## 1.0.1
Under 4,000 characters

- **Navigation:** Closing Nether screens (Wardrobe, Friends, etc.) opened from pause returns directly to the game without flashing the Right Shift menu. Opening Nether pages keeps the game paused and restores the pause screen on close. Esc in the HUD editor also returns directly to the game.
- **Mods & Settings:** Combined into one window with MODS | SETTINGS tabs. Settings is the final chip after module categories and HUD. Switching tabs only replaces the content below. Settings includes General, Title Screen, Controls and Profiles, plus an "Opens on" option (Hub, Mods, Settings). Lobby MODS tab stays lit while Settings is open.
- **Main Menu:** Four sharper cube-captured panoramas: Sakura, Lookout, Meadow and Dusk. Background animation restored. New soft blur option. Nether's panorama replaces Minecraft's in menus outside worlds. Panorama rendering uses vanilla-style perspective (85° FOV) with smoother brightness between faces. Splash texts load dynamically from Nether's server, with Minecraft fallback offline. Removed Quick Join.
- **Lobby:** Player stands on the stage in their own skin, wearing their cape and cosmetics, with or without a party. Party lineup scales and centres correctly.
- **Pause Screen:** Main button renamed Nether. Big buttons form one column: Back to Game, Nether, Mods, server buttons, then Save and Quit/Disconnect. Custom server buttons cannot overlap Disconnect. Vanilla utility buttons become square icon buttons. Screenshots, Edit HUD, Invite and Party sit along the right side. Nether, Wardrobe, Options and party controls have dedicated side buttons. Feedback and bug reports move to the bottom row.
- **Game Mode Selector:** Fortnite-style selector with SURVIVAL (worlds) and MULTIPLAYER (servers) cards, world/server grids and individual icons. Works across Minecraft versions. PLAY starts the selected mode, or selects the party's mode when party leader. Supports adding, editing, removing and refreshing servers, creating worlds and confirming world deletion without leaving the selector. Larger layout, working text input on 26.x and server icons via the game's native ping protocol.
- **HUD:** Dedicated HUD tab replaces toggling HUD modules in the mod list. ADD WIDGET lists all widgets (FPS, ping, clock, Media Player, Karaoke, etc.). Double/right-click opens live settings in a side sheet; right-click/Delete removes widgets without deleting settings. Moving, scaling, undo and redo remain. Removed PvP tab; its widgets are now grouped in HUD. Added pause-screen Edit HUD button.
- **HUD Editor:** Simplified toolbar (Add Widget, Undo, Redo, Reset All, Done). Widget settings now cover scale, font, text colour/rainbow, labels, background and opacity. Removed label colour, text shadow, accent bar and outline. Fixed opacity slider label clipping.
- **Music:** Media Player and Lyrics combined into one Music module under Visual. Supports in-world lyrics, Screen mode, media sources and media keys. Media Player and new Karaoke widgets can appear together. Existing lyrics panel becomes Karaoke in its previous position. Removed Lyrics Bar and "Lyrics in player". O now opens normal lyric settings; removed the 3D panel.
- **Lyrics:** Replaced theme presets with a colour maker supporting text colour, two glow colours and optional vertical, horizontal, diagonal or spiral gradients, with live preview. Existing colours carry over. New styles: Neon, Chrome, Sunset, Sticker, Hologram, Retro, Echo and Glitch. Old styles map to the nearest replacement. Simplified world layout selection. Sky lyrics are flat, fixed in the sky, with terrain occlusion and smooth fading. Words are rendered as images for smoother performance and consistent styling. Timing automatically corrects when song position changes. Fixed custom letter/digit lyric keys typing into Mods search.
- **Performance:** On 1.21.5+ OpenGL, added One GPU Fence Per Frame and Fewer Framebuffer Switches. Tested on 1.21.11 with Sodium, Lithium, Immediate

## 1.0.0
- First release from this repo. The client used to ship inside the launcher; the launcher now installs and
  updates it in every instance on its own.
- Fixed: Minecraft 1.13 could crash when drawing the overlay.
