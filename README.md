# strafeMC

A Fabric mod for Minecraft 26.1.2 that brings Counter-Strike 2 movement and weapons into Minecraft: Source-style
strafing, bunnyhopping and surfing, plus CS2 guns and knives with their real models, animations, sounds, spray patterns
and impact effects.

> [!IMPORTANT]
> **Requires your own copy of Counter-Strike 2.** strafeMC does not work without CS2 installed through Steam on the
> same PC. The mod contains no CS2 files; it reads them from your installation.

> [!IMPORTANT]
> **Not affiliated with Valve.** strafeMC is an unofficial fan project. It is not affiliated with, endorsed by,
> sponsored by or approved by Valve Corporation.

## Status

Early development, not released yet. The first-time setup is built in: on the title screen, strafeMC offers to
convert what it needs from your CS2 install.

## Features

**Movement**
- Air strafing, bunnyhopping and surfing that follow CS2's rules, including its sub-tick input.
- Stairs climb as smooth ramps; ladders, water, ice, slime and soul sand all work with it.
- Surf ramp and surf slope blocks (with corners) for building your own maps.
- CS2 fall damage, and an optional half-block step height for slabs.

**Weapons**
- The AK-47, the Falchion knife and every grenade (HE, flashbang, smoke, molotov, incendiary, decoy), with the real
  first-person models, animations and sounds.
- Spray patterns, recoil, inaccuracy, deploy times, reloads (including silent reload) and inspects (rare inspects
  included; hold the key to keep them going).
- Damage by hitgroup, headshots and range falloff; knife slashes, stabs and backstabs; wallbangs through thin blocks.
- CS2 tagging: getting hit slows you down instead of knocking you back (Minecraft knockback is a server option).
- Kevlar and helmets with CS armor rules, hit sounds and the helmet dink.
- Grenades: CS throws (left, right, both), grenade physics and spin, a trajectory preview, flashes, smokes that put
  out fires, fire that cooks the mobs it kills, and decoys that fake your last gun.
- Bullet holes, knife marks, muzzle flash, tracers and impact effects that change with the block you hit.
- Viewmodel bob and sway, and CS2's viewmodel_fov and viewmodel_offset_x/y/z settings.

**Console and multiplayer**
- An in-game developer console (default key: <kbd>`</kbd>) with autocomplete, binds, movement presets and speed / jump stats.
- Per-world CS2 settings, controlled by server operators (Minecraft operators, plus anyone added with `/strafeop`).
- The server decides spread, recoil and fall damage, so everyone plays by the same rules.
- Lag compensation: shots and knife attacks are checked against what the shooter saw, like in CS.
- Source movement for everyone, or let each player choose (`strafemc_movement_choice`).
- Players without CS2 can still join a server and play, with placeholder icons, marks and Minecraft sounds.

## Requirements

| | |
|---|---|
| Minecraft | 26.1.2 |
| Loader | Fabric Loader 0.19.5 or newer (Fabric API is included in the mod) |
| Java | 25 or newer |
| Also needs | Counter-Strike 2, installed through Steam on the same PC |
| Tested on | Windows |

## Installation

1. Install Counter-Strike 2 through Steam (it's free).
2. Put the strafeMC jar into the `mods` folder of a Fabric profile for Minecraft 26.1.2.
3. Do the first-time setup below, so strafeMC can convert the CS2 files it needs.

## First-time setup

strafeMC converts models, sounds and data from your CS2 install into a `strafemc` folder in your Minecraft profile.
This happens once, locally; nothing is uploaded and your CS2 install is never changed.

> [!NOTE]
> **Disk space:** the setup converts every gun and knife at once (so updates rarely need more), about **900 MB**, and
> takes **about 3 minutes**. Weapon textures are stored at reduced size to keep it small.

When you start Minecraft without a finished setup, strafeMC offers on the title screen:
- **Set up automatically:** strafeMC finds CS2 (or you paste its folder), downloads the open-source converter
  (ValveResourceFormat 20.0) from its official GitHub release, checks it against its published checksum, and converts
  everything. It shows exactly what it will do before it starts, and runs in the background while you play.
- **Do it myself:** follow [docs/MANUAL_SETUP.md](docs/MANUAL_SETUP.md). (A standalone setup tool is planned.)
- **Later** / **Don't ask again:** play with placeholders.

strafeMC checks on every start whether anything is missing. If a mod update needs more files from CS2, it offers to
convert only those (a few MB). Its progress shows on every menu and in game, and Options > strafeMC > Open setup...
brings it back. Restart Minecraft when it's done.

## Manual setup

If you'd rather not let the mod download the converter, the full step-by-step guide is in
[docs/MANUAL_SETUP.md](docs/MANUAL_SETUP.md). In short:

1. Download the ValveResourceFormat command-line converter (`Source2Viewer-CLI`) from its
   [official releases](https://github.com/ValveResourceFormat/ValveResourceFormat/releases).
2. Start Minecraft with strafeMC once, then close it. This creates `<your profile>\strafemc\strafemc.properties`.
3. In that file, set `cs2Path` to your CS2 folder and `vrfPath` to the converter.
4. Start Minecraft again. strafeMC converts files the first time it needs them, so the first shots and sounds can lag
   for a moment.

## CS2 server (optional)

strafeMC works without a CS2 server: it uses CS2's default settings. With one, the console gets CS2's real settings
and commands (cvars, bots). On your first singleplayer world strafeMC asks once whether to run CS2's own dedicated
server from your CS2 folder alongside your worlds (Options > strafeMC changes it). It runs in insecure mode, LAN only,
changes nothing in your CS2 folder and stops when Minecraft closes. Note: while it runs, people on your local network
could reach its console (Options > strafeMC > About hosting).

## Multiplayer

- Install strafeMC on the server and on every player's client.
- The server sends its gameplay values (weapon numbers, CS2 settings) to every player when they join.
- Minecraft operators can make other players strafeMC operators with `/strafeop <player>` (and remove them with
  `/strafedeop <player>`). strafeMC operators can change CS2 settings in the console without full Minecraft operator
  rights.

## Building from source

```
cd mod
./gradlew build
```

The jar ends up in `mod/build/libs`.

## Credits

strafeMC's particle system is a Java port of [ValveResourceFormat](https://github.com/ValveResourceFormat/ValveResourceFormat)'s
particle code (MIT license), and the mod uses ValveResourceFormat's converter to read files from your own CS2 install.
See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for all third-party licenses.

## Trademarks

Counter-Strike, Counter-Strike 2, CS2, Steam and Valve are trademarks of Valve Corporation, used here only to describe
what the mod is compatible with. strafeMC is free, and always will be. Not an official Minecraft product; not approved
by or associated with Mojang or Microsoft.

## No Valve content is included

This repository and the mod contain **no** files from Counter-Strike 2. strafeMC reads models, sounds, animations and
game data from **your own** CS2 installation and converts them locally on your PC. Nothing from CS2 is uploaded,
bundled or redistributed.

## License

All rights reserved. See the license field in `mod/src/main/resources/fabric.mod.json`.
