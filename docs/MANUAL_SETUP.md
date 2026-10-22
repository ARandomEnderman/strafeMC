# Manual setup

This is how to set up strafeMC by hand, for development builds (before the guided first-time setup exists) or if you
prefer to do it yourself. It takes about five minutes.

strafeMC never changes your CS2 install. It only **reads** files from it, and keeps everything it converts in its own
folder on your PC.

## 1. Install Counter-Strike 2

Install CS2 through Steam (it's free). You don't need to start it.

Find its folder: in Steam, right-click **Counter-Strike 2** → **Manage** → **Browse local files**. The folder that
opens is the one you need. It contains `game\csgo\pak01_dir.vpk`. A typical location is:

```
C:\Program Files (x86)\Steam\steamapps\common\Counter-Strike Global Offensive
```

## 2. Get the converter

strafeMC uses the open-source ValveResourceFormat converter to read CS2's files.

1. Open the [ValveResourceFormat releases](https://github.com/ValveResourceFormat/ValveResourceFormat/releases).
2. Download the **CLI** build for your system (on Windows: the `cli-windows-x64` zip).
3. Unzip it anywhere, for example `C:\Tools\vrf\`. Keep all the files together; `Source2Viewer-CLI.exe` needs the
   files next to it.

## 3. Install the mod

1. Make a Fabric profile for Minecraft **26.1.2** (Fabric Loader 0.19.5 or newer, Java 25 or newer).
2. Put the strafeMC jar and Fabric API (0.155.3 or newer) into the profile's `mods` folder.
3. Start Minecraft once, until the title screen, then close it.

This creates strafeMC's settings file:

```
<your profile>\strafemc\strafemc.properties
```

(`<your profile>` is the Minecraft profile folder, the one that contains `mods`. In the Modrinth App: open the profile, then the ⋯ menu → Open folder.)

## 4. Point strafeMC at CS2 and the converter

Open `strafemc.properties` in a text editor and set these two lines. In this file, every backslash is written twice and
the `:` after the drive letter gets a backslash in front:

```
cs2Path=C\:\\Program Files (x86)\\Steam\\steamapps\\common\\Counter-Strike Global Offensive
vrfPath=C\:\\Tools\\vrf\\Source2Viewer-CLI.exe
```

Save the file.

## 5. Play

Start Minecraft and open a world. strafeMC converts what it needs the first time it needs it (gun models, sounds,
effects) and keeps the results in `<your profile>\strafemc\cache`. The first time you draw a weapon, shoot or hear a
sound can take a moment; after that it's instant.

Give yourself the weapons from the **strafeMC Weapons** creative tab.

## On a Mac

Counter-Strike 2 has no Mac version, and the Mac Steam app can't install it. strafeMC reads the **Windows** CS2 instead,
installed with Windows Steam inside a Wine bottle. You never need to play CS2 there; strafeMC only reads its files.

1. Install [Sikarugir](https://github.com/Sikarugir-App/Sikarugir) (free). CrossOver or Whisky work too.
2. Make a wrapper (a bottle) and install **Windows Steam** in it (`SteamSetup.exe` from the Steam website).
3. Sign in to that Steam and install **Counter-Strike 2** (around 60 GB).
4. Open that Steam now and then so it keeps CS2 up to date. After a CS2 update, run the strafeMC setup again
   (Options > strafeMC > Open setup...).

strafeMC finds CS2 there by itself: it looks through Sikarugir, Kegworks and Wineskin wrapper apps (in
`~/Applications`, `/Applications` and their `Sikarugir` folders), CrossOver and Whisky bottles, and `~/.wine`,
including Steam libraries on other drives of the bottle. If it doesn't, pick the wrapper app or bottle on the setup
screen, or paste the CS2 folder. The Mac folder picker can't open apps, so a wrapper's folder has to be pasted:

```
~/Applications/Sikarugir/Steam.app/Contents/SharedSupport/prefix/drive_c/Program Files (x86)/Steam/steamapps/common/Counter-Strike Global Offensive
```

The converter has its own Apple Silicon (and Intel) Mac build, which the setup downloads by itself. For a manual
setup, download the `cli-macos-arm64` zip (`cli-macos-x64` on an Intel Mac). Hosting a CS2 server from inside
Minecraft is Windows only.

## Troubleshooting

- **No gun model, only the flat item:** `cs2Path` or `vrfPath` is wrong. Check that `cs2Path` contains
  `game\csgo\pak01_dir.vpk` and that `vrfPath` points to `Source2Viewer-CLI.exe`. The game log
  (`logs\latest.log` in your profile) names the path it tried.
- **Placeholder sounds and icons:** same cause; strafeMC falls back to placeholders when it can't find CS2 or the converter.
- **After a CS2 update something looks wrong:** delete `<your profile>\strafemc\cache`; strafeMC converts the files again.

## Removing it

Delete the jar from `mods` and the `strafemc` folder in your profile. Your CS2 install was never changed.
