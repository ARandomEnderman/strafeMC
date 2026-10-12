package dev.strafemc.setup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the player's Counter-Strike 2 folder. A folder that's already configured and valid always wins, and then nothing
 * of Steam's is read at all. Otherwise: Steam's library list (libraryfolders.vdf, read-only) and the usual install spots.
 */
public final class Cs2Finder {
	public static final String FOLDER = "Counter-Strike Global Offensive";

	private Cs2Finder() {}

	/** A CS2 folder contains game/csgo/pak01_dir.vpk. */
	public static boolean valid(Path cs2) {
		return cs2 != null && Files.isRegularFile(cs2.resolve("game").resolve("csgo").resolve("pak01_dir.vpk"));
	}

	public static Path find(String configured) {
		if (configured != null && !configured.isBlank() && valid(Path.of(configured))) return Path.of(configured);
		for (Path lib : libraries()) {
			Path p = lib.resolve("steamapps").resolve("common").resolve(FOLDER);
			if (valid(p)) return p;
		}
		return null;
	}

	/** Steam library folders: the Steam folder itself plus every "path" in steamapps/libraryfolders.vdf. */
	private static List<Path> libraries() {
		Set<Path> out = new LinkedHashSet<>();
		for (Path steam : steamFolders()) {
			out.add(steam);
			Path vdf = steam.resolve("steamapps").resolve("libraryfolders.vdf");
			try {
				if (!Files.isRegularFile(vdf)) continue;
				Matcher m = Pattern.compile("\"path\"\\s+\"([^\"]+)\"").matcher(Files.readString(vdf, StandardCharsets.UTF_8));
				while (m.find()) out.add(Path.of(m.group(1).replace("\\\\", "\\")));
			} catch (IOException | RuntimeException ignored) {
			}
		}
		return new ArrayList<>(out);
	}

	private static List<Path> steamFolders() {
		List<Path> out = new ArrayList<>();
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String home = System.getProperty("user.home");
		if (os.contains("win")) {
			String reg = registrySteamPath();
			if (reg != null) out.add(Path.of(reg));
			out.add(Path.of("C:\\Program Files (x86)\\Steam"));
			out.add(Path.of("C:\\Program Files\\Steam"));
		} else if (os.contains("mac")) {
			out.add(Path.of(home, "Library", "Application Support", "Steam"));
		} else {
			out.add(Path.of(home, ".steam", "steam"));
			out.add(Path.of(home, ".local", "share", "Steam"));
		}
		return out;
	}

	/** HKCU\Software\Valve\Steam SteamPath, read with the stock reg.exe (read-only query). */
	private static String registrySteamPath() {
		try {
			Process p = new ProcessBuilder("reg", "query", "HKCU\\Software\\Valve\\Steam", "/v", "SteamPath").redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			p.waitFor();
			Matcher m = Pattern.compile("SteamPath\\s+REG_SZ\\s+(.+)").matcher(out);
			return m.find() ? m.group(1).trim().replace('/', '\\') : null;
		} catch (Exception e) {
			return null;
		}
	}
}
