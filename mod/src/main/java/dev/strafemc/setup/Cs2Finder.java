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
 * CS2 has no Mac version: on a Mac it's the Windows CS2, installed with Windows Steam inside a Wine bottle (a Sikarugir /
 * Kegworks / Wineskin wrapper app, CrossOver, Whisky or a plain Wine prefix), so those bottles are searched too.
 */
public final class Cs2Finder {
	public static final String FOLDER = "Counter-Strike Global Offensive";
	public static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

	private Cs2Finder() {}

	/** A CS2 folder contains game/csgo/pak01_dir.vpk. */
	public static boolean valid(Path cs2) {
		return cs2 != null && Files.isRegularFile(cs2.resolve("game").resolve("csgo").resolve("pak01_dir.vpk"));
	}

	/**
	 * The CS2 folder for whatever the player picked: the folder itself, its game or csgo subfolder, the pak file, or the
	 * steamapps\\common folder above it. Null if none of those lead to CS2.
	 */
	public static Path normalize(String input) {
		Path p = normalize0(input);
		if (p == null) return null;
		if (Files.isRegularFile(p)) p = p.getParent(); // picked pak01_dir.vpk (or another file in there)
		for (Path c = p; c != null; c = c.getParent()) { // the folder itself, or one of its parents (game\\csgo, game)
			if (valid(c)) return c;
		}
		if (p != null && valid(p.resolve(FOLDER))) return p.resolve(FOLDER); // picked steamapps\\common
		if (p != null && valid(p.resolve("common").resolve(FOLDER))) return p.resolve("common").resolve(FOLDER); // picked steamapps
		if (p != null && valid(p.resolve("steamapps").resolve("common").resolve(FOLDER))) return p.resolve("steamapps").resolve("common").resolve(FOLDER);
		// picked the Steam folder with CS2 in another of its libraries
		if (p != null && Files.isRegularFile(p.resolve("steamapps").resolve("libraryfolders.vdf"))) {
			Path found = inLibraries(List.of(p));
			if (found != null) return found;
		}
		// picked a Wine bottle (a wrapper app, a bottle or prefix folder, its drive_c): Windows Steam inside it
		if (p != null) for (Path steam : bottleSteam(p)) {
			Path found = inLibraries(List.of(steam));
			if (found != null) return found;
		}
		return null;
	}

	/** What the player typed or picked, as a path (null if blank or not a path). */
	public static Path normalize0(String input) {
		if (input == null || input.isBlank()) return null;
		try {
			return Path.of(input.trim().replace("\"", ""));
		} catch (RuntimeException e) {
			return null;
		}
	}

	public static Path find(String configured) {
		if (configured != null && !configured.isBlank() && valid(Path.of(configured))) return Path.of(configured);
		return inLibraries(steamFolders());
	}

	/** True when the folder is the native Mac Steam's: CS2 can't be installed there (no Mac version). */
	public static boolean macSteam(Path p) {
		return MAC && p != null && p.toAbsolutePath().normalize().startsWith(Path.of(System.getProperty("user.home"), "Library", "Application Support", "Steam"));
	}

	private static Path inLibraries(List<Path> steamFolders) {
		for (Path lib : libraries(steamFolders)) {
			Path p = lib.resolve("steamapps").resolve("common").resolve(FOLDER);
			if (valid(p)) return p;
		}
		return null;
	}

	/** Steam library folders: each Steam folder itself plus every "path" in its steamapps/libraryfolders.vdf. */
	private static List<Path> libraries(List<Path> steamFolders) {
		Set<Path> out = new LinkedHashSet<>();
		for (Path steam : steamFolders) {
			out.add(steam);
			Path vdf = steam.resolve("steamapps").resolve("libraryfolders.vdf");
			try {
				if (!Files.isRegularFile(vdf)) continue;
				Path prefix = winePrefix(steam);
				Matcher m = Pattern.compile("\"path\"\\s+\"([^\"]+)\"").matcher(Files.readString(vdf, StandardCharsets.UTF_8));
				while (m.find()) {
					String lib = m.group(1).replace("\\\\", "\\");
					Path mapped = prefix != null ? wineToHost(prefix, lib) : Path.of(lib);
					if (mapped != null) out.add(mapped);
				}
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
		} else if (MAC) {
			// Windows Steam in Wine bottles; the native Mac Steam last (its CS folder can't hold CS2, only old CS:GO files)
			for (Path bottle : macBottles(home)) out.addAll(bottleSteam(bottle));
			out.add(Path.of(home, "Library", "Application Support", "Steam"));
		} else {
			out.add(Path.of(home, ".steam", "steam"));
			out.add(Path.of(home, ".local", "share", "Steam"));
		}
		return out;
	}

	/**
	 * Wine bottles on a Mac: Sikarugir / Kegworks / Wineskin / Porting Kit wrapper apps (in the Applications folders and
	 * their own subfolders), CrossOver and Whisky bottles, and the default ~/.wine prefix.
	 */
	private static List<Path> macBottles(String home) {
		List<Path> out = new ArrayList<>();
		for (Path apps : List.of(Path.of(home, "Applications"), Path.of("/Applications"))) {
			out.addAll(children(apps, true));
			for (String sub : List.of("Sikarugir", "Kegworks", "Wineskin", "Porting Kit")) out.addAll(children(apps.resolve(sub), true));
		}
		out.addAll(children(Path.of(home, "Library", "Application Support", "CrossOver", "Bottles"), false));
		out.addAll(children(Path.of(home, "Library", "Containers", "com.isaacmarovitz.Whisky", "Bottles"), false));
		out.add(Path.of(home, ".wine"));
		return out;
	}

	/** The folders in dir (only *.app ones when apps), or none if it isn't there. */
	private static List<Path> children(Path dir, boolean apps) {
		if (!Files.isDirectory(dir)) return List.of();
		try (var s = Files.list(dir)) {
			return s.filter(Files::isDirectory).filter(p -> !apps || p.getFileName().toString().endsWith(".app")).toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	/**
	 * Windows Steam folders inside a Wine bottle, whatever part of it was given: a wrapper app (its bottle is
	 * Contents/SharedSupport/prefix, or Contents/Resources in older Wineskin ones), a bottle / prefix folder, or its drive_c.
	 */
	private static List<Path> bottleSteam(Path bottle) {
		List<Path> out = new ArrayList<>();
		for (Path prefix : List.of(bottle, bottle.resolve("Contents").resolve("SharedSupport").resolve("prefix"), bottle.resolve("Contents").resolve("Resources"))) {
			Path drive = prefix.getFileName() != null && prefix.getFileName().toString().equals("drive_c") ? prefix : prefix.resolve("drive_c");
			for (String pf : List.of("Program Files (x86)", "Program Files")) {
				Path steam = drive.resolve(pf).resolve("Steam");
				if (Files.isDirectory(steam)) out.add(steam);
			}
		}
		return out;
	}

	/** The Wine prefix a path is in (the folder holding its drive_c), or null if it isn't in one. */
	private static Path winePrefix(Path p) {
		for (Path c = p.toAbsolutePath(); c != null; c = c.getParent())
			if (c.getFileName() != null && c.getFileName().toString().equals("drive_c")) return c.getParent();
		return null;
	}

	/**
	 * A Windows path from inside a Wine prefix (D:\\SteamLibrary) on this computer: through the prefix's drive links
	 * (dosdevices/d:), C: also as drive_c. Null if that drive isn't mapped.
	 */
	static Path wineToHost(Path prefix, String winPath) {
		Matcher m = Pattern.compile("^([A-Za-z]):[\\\\/]?(.*)$").matcher(winPath);
		if (!m.matches()) return null;
		String letter = m.group(1).toLowerCase(Locale.ROOT);
		try {
			Path drive = prefix.resolve("dosdevices").resolve(letter + ":");
			if (!Files.exists(drive)) drive = letter.equals("c") ? prefix.resolve("drive_c") : null;
			if (drive == null) return null;
			for (String part : m.group(2).split("[\\\\/]+")) if (!part.isEmpty()) drive = drive.resolve(part);
			return drive;
		} catch (RuntimeException e) { // "d:" isn't a file name on Windows (only Macs and Linux have Wine prefixes)
			return null;
		}
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
