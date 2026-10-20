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
 * Every CS2 sticker (items_game sticker_kits, strafemc/stickers.tsv): community, tournament team and autograph
 * stickers, in capsules or not. Their picture is panorama/images/econ/stickers/<material>_png.
 */
public final class Stickers {
	public record Sticker(String id, String name, String material, SkinWraps.Rarity rarity) {}

	private static final Map<String, Sticker> ALL = new LinkedHashMap<>();

	static {
		var in = Stickers.class.getResourceAsStream("/strafemc/stickers.tsv");
		if (in != null) try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isBlank() || line.startsWith("#")) continue;
				String[] f = line.split("\t", -1);
				if (f.length < 4) continue;
				SkinWraps.Rarity rar = SkinWraps.Rarity.of(f[3]);
				ALL.put(f[0], new Sticker(f[0], f[1], f[2], rar == null ? SkinWraps.Rarity.RARE : rar));
			}
		} catch (Exception e) {
			dev.strafemc.StrafeMc.LOG.error("[strafemc] stickers unreadable", e);
		}
	}

	private Stickers() {}

	public static Sticker get(String id) {
		return id == null ? null : ALL.get(id);
	}

	public static List<Sticker> all() {
		return Collections.unmodifiableList(new ArrayList<>(ALL.values()));
	}

	/** CS2's sticker grade names (High Grade, Remarkable, Exotic, Extraordinary, Contraband). */
	public static String grade(SkinWraps.Rarity r) {
		return switch (r) {
			case COMMON, UNCOMMON -> "Base Grade";
			case RARE -> "High Grade";
			case MYTHICAL -> "Remarkable";
			case LEGENDARY -> "Exotic";
			case ANCIENT -> "Extraordinary";
			case IMMORTAL -> "Contraband";
		};
	}
}
