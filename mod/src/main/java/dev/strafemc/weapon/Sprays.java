package dev.strafemc.weapon;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CS2's graffiti (items_game sticker_kits spray_*, strafemc/sprays.tsv): its picture is materials/decals/sprays/<material>,
 * and the standard ones (spray_std_*) are one colour, sprayed in one of CS2's graffiti_tints (strafemc/spray_tints.tsv).
 */
public final class Sprays {
	public record Spray(String id, String name, String material, SkinWraps.Rarity rarity, boolean tintable) {}

	public record Tint(int id, String name, int color) {
		/** "blood_red" -> "Blood Red". */
		public String label() {
			StringBuilder b = new StringBuilder();
			for (String w : name.split("_")) if (!w.isEmpty()) b.append(b.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
			return b.toString();
		}
	}

	private static final Map<String, Spray> ALL = new LinkedHashMap<>();
	private static final Map<Integer, Tint> TINTS = new LinkedHashMap<>();

	static {
		read("/strafemc/sprays.tsv", f -> {
			if (f.length < 5) return;
			SkinWraps.Rarity r;
			try {
				r = SkinWraps.Rarity.valueOf(f[3].toUpperCase(java.util.Locale.ROOT));
			} catch (IllegalArgumentException e) {
				r = SkinWraps.Rarity.COMMON;
			}
			ALL.put(f[0], new Spray(f[0], f[1], f[2], r, f[4].equals("1")));
		});
		read("/strafemc/spray_tints.tsv", f -> {
			if (f.length >= 3) TINTS.put(Integer.parseInt(f[0]), new Tint(Integer.parseInt(f[0]), f[1], Integer.parseInt(f[2].substring(1), 16)));
		});
	}

	private static void read(String path, java.util.function.Consumer<String[]> row) {
		var in = Sprays.class.getResourceAsStream(path);
		if (in == null) return;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) if (!line.isBlank() && !line.startsWith("#")) row.accept(line.split("\t", -1));
		} catch (Exception e) {
			dev.strafemc.StrafeMc.LOG.error("[strafemc] " + path + " unreadable", e);
		}
	}

	private Sprays() {}

	public static Spray get(String id) {
		return id == null ? null : ALL.get(id);
	}

	public static List<Spray> all() {
		return Collections.unmodifiableList(new ArrayList<>(ALL.values()));
	}

	public static Tint tint(int id) {
		return TINTS.get(id);
	}

	public static List<Tint> tints() {
		return new ArrayList<>(TINTS.values());
	}
}
