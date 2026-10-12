<!-- Modrinth project description for strafeMC. Paste everything below this comment into the project's Description.
     Modrinth doesn't support GitHub's [!WARNING] boxes: plain quote blocks are used instead. -->

# strafeMC

**Counter-Strike 2 movement and weapons, in Minecraft.** Strafe, bunnyhop and surf with Source movement, and use CS2
guns and knives with their real models, animations, sounds and spray patterns, built from the files in **your own**
CS2 install.

![strafeMC in Minecraft](https://cdn.modrinth.com/data/xu9Tirxr/images/6519fe52df669b11c0eaddcff3aed21b0c32a84e.jpeg)

*Shown with models from the player's own CS2 install; strafeMC doesn't include them.*

> ⚠️ **Requires your own copy of Counter-Strike 2** (free on Steam), installed on the same PC. strafeMC contains no
> CS2 files: it converts what it needs from your installation, locally on your PC.

> 🛠️ **Development build.** First-time setup is currently manual; follow the
> [setup guide](https://github.com/ARandomEnderman/strafeMC/blob/main/docs/MANUAL_SETUP.md). A guided setup inside the
> game is coming.

## Features

### Movement
- Air strafing, bunnyhopping and surfing that follow CS2's rules, including its sub-tick input
- Stairs climb as smooth ramps; ladders, water, ice, slime and soul sand all work with it
- Surf ramp and surf slope blocks for building your own maps
- CS2 fall damage

### Weapons
- The AK-47 and the Falchion knife, with the real first-person models, animations and sounds
- Spray patterns, recoil, inaccuracy, reloads (including silent reload), inspects and rare inspects
- Damage by hitgroup, headshots, range falloff; knife slashes, stabs and backstabs
- Bullet holes, knife marks, muzzle flash, tracers and impact effects per block material

### Console and multiplayer
- In-game developer console (default key: `) with autocomplete, binds, movement presets, speed and jump stats
- Per-world CS2 settings controlled by server operators (`/strafeop` to give players console rights)
- The server decides spread, recoil and fall damage
- Friends without CS2 can still join and play, with placeholder icons and Minecraft sounds

## Requirements
- Minecraft **26.1.2**, Fabric Loader **0.19.5+**, Java **25+** (Fabric API is included)
- **Counter-Strike 2** on Steam, on the same PC
- For multiplayer: strafeMC on the server and on every client

## What strafeMC does on your PC

So you know exactly what to expect:
- **Reads your CS2 files** (read-only) from the folder you set, and never changes your CS2 install.
- **Runs the open-source ValveResourceFormat converter** (`Source2Viewer-CLI`), which you download yourself from its
  [official releases](https://github.com/ValveResourceFormat/ValveResourceFormat/releases), to convert CS2's models,
  textures, sounds and animations into a cache in `%USERPROFILE%\strafemc`.
- **Uploads nothing.** Converted files stay on your PC. On a server, only gameplay numbers (like weapon damage and
  movement settings) are sent from the server to its players.
- **Never connects to Valve's servers**, matchmaking or anti-cheat. Hosts can optionally run a local CS2 dedicated
  server for live CS2 settings (an advanced extra, off unless you set it up); it runs offline, in LAN mode only.

## Disclaimer

**strafeMC is an unofficial fan project. It is not affiliated with, endorsed by, sponsored by or approved by Valve
Corporation.** Counter-Strike, Counter-Strike 2, CS2, Steam and Valve are trademarks of Valve Corporation, used here
only to describe what the mod is compatible with.

**No Valve content is included.** The mod, its downloads and its source code contain no files from Counter-Strike 2.
All CS2 assets are converted locally from your own legally obtained installation and are never redistributed.

strafeMC is free, and always will be. Not an official Minecraft product; not approved by or associated with Mojang or
Microsoft.

## Credits
strafeMC's particle system is a Java port of [ValveResourceFormat](https://github.com/ValveResourceFormat/ValveResourceFormat)'s
particle code (MIT license). Full third-party notices are in the
[repository](https://github.com/ARandomEnderman/strafeMC/blob/main/THIRD_PARTY_NOTICES.md).
