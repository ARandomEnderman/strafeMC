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
 * CS2's charms (items_game keychain_definitions, strafemc/charms.tsv) from its charm collections: a picture
 * (panorama/images/<image>_png) and a model (its pedestal_display_model) that hangs off the gun's "keychain"
 * attachment. One per gun wrap; taken off, a charm comes back.
 */
public final class Charms {
	public record Charm(String id, String name, SkinWraps.Rarity rarity, String image, String model) {}

	private static final Map<String, Charm> ALL = new LinkedHashMap<>();

	static {
		var in = Charms.class.getResourceAsStream("/strafemc/charms.tsv");
		if (in != null) try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isBlank() || line.startsWith("#")) continue;
				String[] f = line.split("\t", -1);
				if (f.length < 5) continue;
				SkinWraps.Rarity rar = SkinWraps.Rarity.of(f[2]);
				ALL.put(f[0], new Charm(f[0], f[1], rar == null ? SkinWraps.Rarity.RARE : rar, f[3], f[4]));
			}
		} catch (Exception e) {
			dev.strafemc.StrafeMc.LOG.error("[strafemc] charms unreadable", e);
		}
	}

	private Charms() {}

	public static Charm get(String id) {
		return id == null ? null : ALL.get(id);
	}

	public static List<Charm> all() {
		return Collections.unmodifiableList(new ArrayList<>(ALL.values()));
	}
}
