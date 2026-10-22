package dev.strafemc.setup;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Cs2Finder on a fake Wine bottle (the Mac case): a Sikarugir-style wrapper app with Windows Steam, whose library list
 * points to C:\Games\Library where CS2 is. Picking the app, its prefix, its drive_c or the Steam folder must all find CS2.
 * gradlew vmCheck -PvmMain=dev.strafemc.setup.Cs2FinderBottleCheck
 */
public final class Cs2FinderBottleCheck {
	public static void main(String[] a) throws Exception {
		Path root = Files.createTempDirectory("strafemc-bottle");
		Path app = root.resolve("Steam.app"), prefix = app.resolve("Contents/SharedSupport/prefix"), drive = prefix.resolve("drive_c");
		Path steam = drive.resolve("Program Files (x86)/Steam"), cs2 = drive.resolve("Games/Library/steamapps/common/" + Cs2Finder.FOLDER);
		Files.createDirectories(steam.resolve("steamapps"));
		Files.writeString(steam.resolve("steamapps/libraryfolders.vdf"),
				"\"libraryfolders\"\n{\n\t\"0\"\n\t{\n\t\t\"path\"\t\t\"C:\\\\Program Files (x86)\\\\Steam\"\n\t}\n\t\"1\"\n\t{\n\t\t\"path\"\t\t\"C:\\\\Games\\\\Library\"\n\t}\n}\n");
		Files.createDirectories(cs2.resolve("game/csgo"));
		Files.writeString(cs2.resolve("game/csgo/pak01_dir.vpk"), "");
		int bad = 0;
		for (Path pick : new Path[] {app, prefix, drive, steam, cs2, cs2.resolve("game/csgo/pak01_dir.vpk")}) {
			Path got = Cs2Finder.normalize(pick.toString());
			boolean ok = cs2.equals(got);
			System.out.println((ok ? "OK   " : "FAIL ") + root.relativize(pick) + " -> " + (got == null ? null : root.relativize(got)));
			if (!ok) bad++;
		}
		Path none = Cs2Finder.normalize(root.toString());
		System.out.println((none == null ? "OK   " : "FAIL ") + "the folder above the app -> " + none);
		if (none != null) bad++;
		System.out.println("C:\\Games\\Library -> " + root.relativize(Cs2Finder.wineToHost(prefix, "C:\\Games\\Library")));
		try (var s = Files.walk(root)) {
			s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
		if (bad > 0) System.exit(1);
	}
}
