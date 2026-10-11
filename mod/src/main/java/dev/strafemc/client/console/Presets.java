package dev.strafemc.client.console;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.strafemc.StrafeMc;
import dev.strafemc.bridge.Cs2Bridge;
import dev.strafemc.config.StrafeConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Named movement presets: the current values of every movement cvar, saved as JSON in the runtime dir (presets/). */
public final class Presets {
	private static final Path DIR = StrafeConfig.RUNTIME_DIR.resolve("presets");
	private static final Gson GSON = new Gson();

	private Presets() {}

	private static Path file(String name) {
		return DIR.resolve(name.replaceAll("[^A-Za-z0-9_-]", "_") + ".json");
	}

	public static boolean save(String name) {
		try {
			Map<String, String> m = new LinkedHashMap<>();
			for (String c : ConsoleCommands.MOVEMENT_CVARS) {
				String v = Cs2Bridge.INSTANCE.cvar(c);
				if (v != null) m.put(c, v);
			}
			if (m.isEmpty()) return false;
			Files.createDirectories(DIR);
			Files.writeString(file(name), GSON.toJson(m));
			return true;
		} catch (Exception e) {
			StrafeMc.LOG.warn("[strafemc] preset save failed: {}", e.toString());
			return false;
		}
	}

	public static boolean load(String name) {
		try {
			Path f = file(name);
			if (!Files.exists(f)) return false;
			Map<String, String> m = GSON.fromJson(Files.readString(f), new TypeToken<Map<String, String>>() {}.getType());
			StringBuilder cmd = new StringBuilder();
			m.forEach((k, v) -> cmd.append(k).append(' ').append(v).append(';'));
			ConsoleCommands.transport.send(cmd.toString());
			return true;
		} catch (Exception e) {
			StrafeMc.LOG.warn("[strafemc] preset load failed: {}", e.toString());
			return false;
		}
	}

	public static boolean delete(String name) {
		try {
			return Files.deleteIfExists(file(name));
		} catch (Exception e) {
			return false;
		}
	}

	public static List<String> names() {
		List<String> out = new ArrayList<>();
		try (var s = Files.list(DIR)) {
			s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).map(n -> n.substring(0, n.length() - 5)).sorted().forEach(out::add);
		} catch (Exception ignored) {
		}
		return out;
	}
}
