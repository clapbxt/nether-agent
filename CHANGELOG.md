# Changelog

What changed in each client release, newest first. Add a line under **Unreleased** in the same pull request as the
change: what a player notices, not how it was done. At release, move the lines under the new version.

## Unreleased
- Mods and Settings are one screen: Settings is the last chip after the module categories and HUD, and switching between them only swaps what is under the chips (no more pop-in like a new window). In the lobby, the MODS tab stays lit while Settings is open.
- Title panoramas are softly blurred (Main Menu > Blur), and the game's own menus outside a world (options, server list, connecting...) now show Nether's panorama instead of Minecraft's ("Panorama in every menu").
- Lobby: you stand on the stage in your own skin with your cape and cosmetics again, with or without a party.
- Game mode selector: bigger, typing works in Add / Edit Server on 26.x, and server icons load (the status ping now uses the game's own protocol).
- Module settings: typing no longer fills the settings search; click the search box (or Ctrl+F) first. Esc in the HUD editor opened from the pause screen now goes straight back to the game instead of through the menu. The pause screen's square icon buttons no longer draw on top of a server button's tooltip.
- HUD editor: the toolbar is just the buttons (Add widget, Undo, Redo, Reset all, Done), no help text. Widget settings are simpler: scale, font, text colour or rainbow, labels, background and its opacity (label colour, text shadow, accent bar and outline are gone). The opacity slider no longer cuts off its name.
- Pause screen (1.16+): the button is now "Nether". The big buttons are stacked as one column (Back to Game, Nether, Mods and any server's custom buttons, then Save and Quit / Disconnect last), so a server's custom button can no longer cover Disconnect. Every small vanilla button (Advancements, Statistics, Open to LAN / World Options, Give Feedback, Report Bugs, Player Reporting, Friends, Server Links) is now a square with an icon in the bottom left; Screenshots, Edit HUD, Invite and Party sit up the right side from the leave button.
- Menu: Mods and Settings are now one window with MODS | SETTINGS tabs. Settings has General, Title screen, Controls and Profiles pages (the title screen options are listed directly), and a new "Opens on" choice (Hub, Mods or Settings) for what the menu key shows first. The right-shift hub is three buttons: Mods, Wardrobe, Friends.
- Title screen: splash texts now come from Nether's server and change without a client update (Settings > Title screen > Splashes: Nether / Minecraft / Mine / Mix; offline falls back to Minecraft's). The Quick join card is gone from the home screen.
- Pause screen: the separate Nether settings button and the "Nether button" setting are gone (the NETHER button opens mods and settings together). Send Feedback and Report Bugs moved to the bottom row beside Advancements, Statistics and Open to LAN.
- Custom Sky: its locked tooltip now just says "Incompatible version", and the sky is documented as working on 26.1 and newer wherever the game's sky renderer is hooked.
- Main menu panoramas are drawn like vanilla's: six cube faces in perspective at an 85 degree field of view, so they are sharp with no stepped lines, and the brightness steps where cube faces meet are smoothed out.
- Music: Media Player and Lyrics are now one Music module (in the Visual tab) for lyrics in the world, including Screen (words floating across your screen), plus the player source and media keys. The on-screen players are HUD widgets you can use together: Media Player (cover, title, artist, progress bar) and the new Karaoke widget (the song with its synced lyrics under it, with its own layout settings). Your old on-screen lyrics panel becomes the Karaoke widget where it was; the Lyrics Bar and "Lyrics in player" are gone.
- HUD widgets: HUD modules are no longer switched on and off in the mod list. They live in their own HUD tab (its own colour), where clicking one opens its settings whether it is on your screen or not. In the HUD editor, ADD WIDGET lists every widget (FPS, ping, clock, Media Player, Karaoke...) to put on your screen; double-click or right-click > Settings edits a widget in a sheet beside it, live; right-click > Remove or Delete takes it off (its settings stay); moving, scaling, undo and redo work as before. The PvP tab is gone (its widgets are a PvP group in the HUD tab).
- Pause screen: an Edit HUD button in the game's style, above Screenshots.
- Pause screen: Nether, Invite and Party are no longer chips in the corner. A wide NETHER button (opens your mods) sits between Back to Game and Mods, with square game-style buttons down the sides: Options, Nether settings and Wardrobe on the left; Party, Invite, Screenshots on the right (a slot is kept for the HUD editor). Advancements, Statistics and Open to LAN became small squares in the bottom left. Opening any Nether page from the pause screen now hides the pause screen behind it (you stay paused) and brings it back when you close. The Screenshots button's tooltip no longer shows its key.
- Lyrics: the theme presets are replaced by a colour maker: pick the text and two glow colours, optionally blend the text into a second colour (vertical, horizontal, diagonal or spiral fill), with one live preview of the result. Your old theme's colours carry over. World layout is now a plain choice row without preview cards.
- Game mode selector, like Fortnite's: a mode tile above PLAY (solo) or the party card (party leader) opens a selector with SURVIVAL (your worlds) and MULTIPLAYER (your servers) cards and a grid of worlds and servers to choose from, with each world's and server's own icon, on every Minecraft version. Solo, PLAY starts your pick; in a party, it is the party's pick. It replaces the old mode dropdown and the trip through the game's own lists.
- Game mode selector: add, edit and remove servers (name and address form), REFRESH the list (re-pings, reloads icons), CREATE NEW WORLD (opens the game's own create screen) and delete a world (asks first), all without leaving the selector.
- The title screen never uses a GUI scale below 2 (labels overlapped at scale 1), the party lineup is centred and sized to the window and GUI scale, and the settings menu no longer has "Customise this screen".
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
