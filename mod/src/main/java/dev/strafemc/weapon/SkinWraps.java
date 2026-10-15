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
 * Every CS2 weapon finish as a skin wrap (strafemc/skin_wraps.tsv in the jar, made from the list of CS2's skin pictures):
 * one wrap per skin name ("gun:asiimov", "knife:fade"), with the paint kit CS2 uses for that skin on each weapon. A gun
 * wrap fits the guns that have the skin in CS2; a knife wrap fits every knife (it uses the knife's own kit if CS2 has one,
 * else another knife's: new combinations are allowed).
 */
public final class SkinWraps {
	/**
	 * pattern: the finish has a pattern (not a solid colour or plain anodizing), so a pattern seed changes how it looks.
	 * rarities: CS2's rarity of the skin on each weapon (its collection's loot list; knives ancient).
	 */
	public record Wrap(String id, String name, boolean knife, Map<String, String> kits, boolean pattern, Map<String, Rarity> rarities, boolean hidden) {
		/** The wrap's rarity: the highest it has on any weapon, or null if CS2 doesn't list one. */
		public Rarity rarity() {
			Rarity best = null;
			for (Rarity r : rarities.values()) if (best == null || r.ordinal() > best.ordinal()) best = r;
			return best;
		}
	}

	/** CS2's item rarities (items_game "rarities") with their name colours ("colors" desc_*). */
	public enum Rarity {
		COMMON("Consumer Grade", 0xB0C3D9), UNCOMMON("Industrial Grade", 0x5E98D9), RARE("Mil-Spec", 0x4B69FF), MYTHICAL("Restricted", 0x8847FF),
		LEGENDARY("Classified", 0xD32CE6), ANCIENT("Covert", 0xEB4B4B), IMMORTAL("Contraband", 0xE4AE39);

		public final String weaponName;
		public final int color;

		Rarity(String weaponName, int color) {
			this.weaponName = weaponName;
			this.color = color;
		}

		static Rarity of(String id) {
			try {
				return valueOf(id.toUpperCase(java.util.Locale.ROOT));
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
	}


	/** CS2's weapon names (for tooltips). */
	public static final Map<String, String> WEAPON_NAMES = Map.ofEntries(Map.entry("weapon_ak47", "AK-47"), Map.entry("weapon_aug", "AUG"),
			Map.entry("weapon_awp", "AWP"), Map.entry("weapon_bizon", "PP-Bizon"), Map.entry("weapon_cz75a", "CZ75-Auto"),
			Map.entry("weapon_deagle", "Desert Eagle"), Map.entry("weapon_elite", "Dual Berettas"), Map.entry("weapon_famas", "FAMAS"),
			Map.entry("weapon_fiveseven", "Five-SeveN"), Map.entry("weapon_g3sg1", "G3SG1"), Map.entry("weapon_galilar", "Galil AR"),
			Map.entry("weapon_glock", "Glock-18"), Map.entry("weapon_hkp2000", "P2000"), Map.entry("weapon_m249", "M249"),
			Map.entry("weapon_m4a1", "M4A4"), Map.entry("weapon_m4a1_silencer", "M4A1-S"), Map.entry("weapon_mac10", "MAC-10"),
			Map.entry("weapon_mag7", "MAG-7"), Map.entry("weapon_mp5sd", "MP5-SD"), Map.entry("weapon_mp7", "MP7"), Map.entry("weapon_mp9", "MP9"),
			Map.entry("weapon_negev", "Negev"), Map.entry("weapon_nova", "Nova"), Map.entry("weapon_p250", "P250"), Map.entry("weapon_p90", "P90"),
			Map.entry("weapon_revolver", "R8 Revolver"), Map.entry("weapon_sawedoff", "Sawed-Off"), Map.entry("weapon_scar20", "SCAR-20"),
			Map.entry("weapon_sg556", "SG 553"), Map.entry("weapon_ssg08", "SSG 08"), Map.entry("weapon_tec9", "Tec-9"),
			Map.entry("weapon_ump45", "UMP-45"), Map.entry("weapon_usp_silencer", "USP-S"), Map.entry("weapon_xm1014", "XM1014"),
			Map.entry("weapon_taser", "Zeus x27"));

	/** After WEAPON_NAMES: the split gun wraps are named after their gun. */
	private static final Map<String, Wrap> ALL = load();

	private SkinWraps() {}

	private static Map<String, Wrap> load() {
		Map<String, Wrap> out = new LinkedHashMap<>();
		var in = SkinWraps.class.getResourceAsStream("/strafemc/skin_wraps.tsv");
		if (in == null) return out;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isBlank() || line.startsWith("#")) continue;
				String[] f = line.split("\t");
				if (f.length < 3) continue;
				Map<String, String> kits = new LinkedHashMap<>();
				for (String pair : f[2].split(" ")) {
					int eq = pair.indexOf('=');
					if (eq > 0) kits.put(pair.substring(0, eq), pair.substring(eq + 1));
				}
				Map<String, Rarity> rarities = new LinkedHashMap<>();
				if (f.length > 4)
					for (String pair : f[4].split(" ")) {
						int eq = pair.indexOf('=');
						Rarity rarity = eq > 0 ? Rarity.of(pair.substring(eq + 1)) : null;
						if (rarity != null) rarities.put(pair.substring(0, eq), rarity);
					}
				boolean knife = f[0].startsWith("knife:"), pattern = f.length > 3 && f[3].equals("pattern");
				// a gun wrap is one gun's skin ("gun:asiimov.ak47", "AK-47 | Asiimov"), as CS2 items are: the line's
				// several-gun wrap stays only so wraps made before the split still work (hidden from tabs and cases)
				out.put(f[0], new Wrap(f[0], f[1], knife, Collections.unmodifiableMap(kits), pattern, Collections.unmodifiableMap(rarities), !knife));
				if (!knife)
					for (var e : kits.entrySet()) {
						String id = f[0] + "." + e.getKey().replace("weapon_", "");
						Map<String, Rarity> one = new LinkedHashMap<>();
						if (rarities.containsKey(e.getKey())) one.put(e.getKey(), rarities.get(e.getKey()));
						out.put(id, new Wrap(id, WEAPON_NAMES.getOrDefault(e.getKey(), e.getKey()) + " | " + f[1], false, Map.of(e.getKey(), e.getValue()), pattern,
								Collections.unmodifiableMap(one), false));
					}
			}
		} catch (Exception e) {
			dev.strafemc.StrafeMc.LOG.error("[strafemc] skin wraps unreadable", e);
		}
		return Collections.unmodifiableMap(out);
	}

	public static Wrap get(String id) {
		return id == null ? null : ALL.get(id);
	}

	/** Every wrap made today (gun wraps one per gun); the several-gun ones from before the split only by id. */
	public static List<Wrap> all() {
		List<Wrap> out = new ArrayList<>();
		for (Wrap w : ALL.values()) if (!w.hidden) out.add(w);
		return out;
	}

	/** The gun wrap of one gun's skin: a paint kit on a weapon (cases give these), or null. */
	public static Wrap ofKit(String weapon, String kit) {
		for (Wrap w : ALL.values()) if (!w.hidden && !w.knife && kit.equalsIgnoreCase(w.kits.get(weapon))) return w;
		return null;
	}

	/** A gun wrap that CS2 has on this gun. */
	public static boolean fits(String id, String weapon) {
		Wrap w = get(id);
		return w != null && !w.knife && w.kits.containsKey(weapon);
	}

	/** The paint kit this wrap uses on a weapon (knife wraps: the knife's own kit, else any knife's); null if none. */
	public static String kitFor(String id, String weapon) {
		Wrap w = get(id);
		if (w == null) return null;
		String k = w.kits.get(weapon);
		if (k != null || !w.knife || w.kits.isEmpty()) return k;
		return w.kits.values().iterator().next();
	}

	/** One kit to make the wrap's picture from (the first weapon's). */
	public static String iconKit(Wrap w) {
		return w.kits.isEmpty() ? null : w.kits.values().iterator().next();
	}
}
