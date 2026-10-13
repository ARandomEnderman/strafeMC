<!-- Modrinth project description for strafeMC. Paste everything below this comment into the project's Description.
     Modrinth doesn't support GitHub's [!WARNING] boxes: plain quote blocks are used instead. -->

# strafeMC

**Counter-Strike 2 movement and weapons, in Minecraft.** Strafe, bunnyhop and surf with Source movement, and use CS2
guns and knives with their real models, animations, sounds and spray patterns, built from the files in **your own**
CS2 install.

> ⚠️ **Requires your own copy of Counter-Strike 2** (free on Steam), installed on the same PC. strafeMC contains no
> CS2 files: it converts what it needs from your installation, locally on your PC.

> 💾 **First-time setup: about 900 MB and about 3 minutes.** When you join a world, strafeMC offers to convert what it
> needs from your CS2 install: automatically (it tells you exactly what it does first), or by hand with the
> [setup guide](https://github.com/ARandomEnderman/strafeMC/blob/main/docs/MANUAL_SETUP.md). Every gun and knife is
> converted at once, so updates rarely need more.

## Features

### Movement
![Surfing along a surf ramp](https://cdn.modrinth.com/data/xu9Tirxr/images/dbab36874421bb809d5ec2c0076cbbed5eec2a02.jpeg)

- Air strafing, bunnyhopping and surfing that follow CS2's rules, including its sub-tick input
- CS2's 2026 jump (a weaker jump right after landing), with the pre-2026 jump as a quick setting
- Stairs climb as smooth ramps; ladders, water, ice, slime and soul sand all work with it
- Surf ramp, lying surf ramp and surf slope blocks (with corners) for building your own maps
- CS2 fall damage, plus an optional half-block step for slabs

### Weapons
![AK-47 spray with bullet holes on a stone wall](https://cdn.modrinth.com/data/xu9Tirxr/images/7819ba5ffcd86810277f5a1d1f227620136fef81.jpeg)

![Inspecting the Falchion knife](https://cdn.modrinth.com/data/xu9Tirxr/images/6519fe52df669b11c0eaddcff3aed21b0c32a84e.jpeg)

*Weapons are shown with models from the player's own CS2 install; strafeMC doesn't include them.*

- The AK-47, the Falchion knife and every grenade (HE, flashbang, smoke, molotov, incendiary, decoy), with the real first-person models, animations and sounds
- Spray patterns, recoil, inaccuracy, reloads (including silent reload), inspects and rare inspects (hold to keep them going)
- Damage by hitgroup, headshots, range falloff; knife slashes, stabs and backstabs; wallbangs through blocks with CS2's penetration rules per material
- CS2 tagging (hits slow you instead of knocking you back), kevlar and helmets with CS armor rules, hit sounds and the dink
- Grenades with CS throws (left, right and both buttons), physics, spin, a trajectory preview and CS2's lineup crosshair
- Volumetric smokes that fill rooms and corridors, in T and CT colours; HE grenades blow holes in them, and molotovs pop into flames that smokes put out
- Flashbangs with CS2's blind strength and fade (depends on distance and where you look), decoys that fake gunfire
- `sv_rethrow_last_grenade` for practising lineups
- The Medi-Shot
- Bullet holes, knife marks, muzzle flash, tracers and impact effects per block material
- Viewmodel bob and sway, viewmodel_fov, viewmodel_offset_x/y/z and switching hands (`switchhands`, H by default)

### Gloves and buy menu
- Every CS2 glove type in every finish, always Factory New, drawn on your first-person arms (right-click to wear).
  Finishes are rebuilt on your PC from your own CS2 files; they're cosmetic only and have nothing to do with your Steam inventory
- CS2's buy menu (B): gear, rifles and grenades in CS2's layout and loadout order, with CS2's grenade limits.
  Operators choose who can buy (nobody, creative players or everyone) and for how long after respawning
- CS2's killfeed, with headshot, wallbang, through-smoke, blind and in-air icons
- Pick T or CT in the console's quick menu: smoke colours and footsteps follow your side

### Console and multiplayer
- In-game developer console (default key: the one under Esc, left of 1) with autocomplete, binds, movement presets, speed and jump stats
- Per-world CS2 settings controlled by server operators (`/strafeop` to give players console rights)
- The server decides spread, recoil and fall damage
- Lag compensation: you hit what you saw, like in CS
- Optional: run CS2's own server alongside your worlds for its real settings, commands and bots (asked once)
- Friends without CS2 can still join and play, with placeholder icons and Minecraft sounds

## Requirements
- Minecraft **26.1.2**, Fabric Loader **0.19.5+**, Java **25+** (Fabric API is included)
- **Counter-Strike 2** on Steam, on the same PC
- For multiplayer: strafeMC on the server and on every client

## What strafeMC does on your PC

So you know exactly what to expect:
- **Reads your CS2 files** (read-only) from the folder you set, and never changes your CS2 install.
- **Uses the open-source ValveResourceFormat converter** (`Source2Viewer-CLI`) to convert CS2's models, textures,
  sounds and animations into your Minecraft profile's `strafemc` folder (about 900 MB). With automatic setup, strafeMC
  downloads it from its [official GitHub release](https://github.com/ValveResourceFormat/ValveResourceFormat/releases)
  only after you click "Set up automatically", and checks it against its published checksum before running it. You can
  also download it yourself and follow the setup guide.
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
