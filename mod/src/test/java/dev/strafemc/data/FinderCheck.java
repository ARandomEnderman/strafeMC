package dev.strafemc.data;

/** Checks Cs2Finder.normalize on the different things a player might pick: gradlew vmCheck -PvmMain=...FinderCheck -PvmArgs=<cs2 folder> */
public final class FinderCheck {
	public static void main(String[] a) {
		String root = a[0], sep = java.io.File.separator;
		String[] picks = {root, root + sep + "game", root + sep + "game" + sep + "csgo", root + sep + "game" + sep + "csgo" + sep + "pak01_dir.vpk",
				"\"" + root + "\"", root.substring(0, root.lastIndexOf(sep)), "C:" + sep + "Windows", ""};
		for (String p : picks) System.out.println((dev.strafemc.setup.Cs2Finder.normalize(p) != null ? "found    " : "rejected ") + p);
	}
}
