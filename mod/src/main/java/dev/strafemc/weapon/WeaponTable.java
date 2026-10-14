package dev.strafemc.weapon;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every CS2 gun and knife (strafemc/weapons.tsv in the jar, made from the user's CS2 files by a generator): its model,
 * inspect graph, CS2 loadout slot and teams (items_game flexible_loadout_slot / _default, used_by_classes), and its
 * first-person clips by name (from CS2's viewmodel animation graphs: draw, idle, shoot1, shoot_last, reload,
 * reload_empty, silencer_attach/detach, the knives' light_/heavy_ swings, inspects lookatNN...).
 */
public final class WeaponTable {
	public record Entry(String cls, boolean knife, String model, String inspectGraph, String slot, Set<String> teams, boolean loadoutDefault,
			Map<String, String> clips) {
		/** "weapon_ak47" -> "ak47": the name CS2's icons and the buy command use. */
		public String shortName() {
			return cls.substring("weapon_".length());
		}

		public boolean forT() {
			return teams.contains("terrorists");
		}

		public boolean forCt() {
			return teams.contains("counter-terrorists");
		}
	}

	private static final Map<String, Entry> ALL = load();

	/** CS2's knife names (for item names). */
	public static final Map<String, String> KNIFE_NAMES = Map.ofEntries(Map.entry("weapon_knife", "Knife (CT)"), Map.entry("weapon_knife_t", "Knife (T)"),
			Map.entry("weapon_bayonet", "Bayonet"), Map.entry("weapon_knife_survival_bowie", "Bowie Knife"), Map.entry("weapon_knife_butterfly", "Butterfly Knife"),
			Map.entry("weapon_knife_canis", "Survival Knife"), Map.entry("weapon_knife_cord", "Paracord Knife"), Map.entry("weapon_knife_css", "Classic Knife"),
			Map.entry("weapon_knife_falchion", "Falchion Knife"), Map.entry("weapon_knife_flip", "Flip Knife"), Map.entry("weapon_knife_gut", "Gut Knife"),
			Map.entry("weapon_knife_karambit", "Karambit"), Map.entry("weapon_knife_kukri", "Kukri Knife"), Map.entry("weapon_knife_m9_bayonet", "M9 Bayonet"),
			Map.entry("weapon_knife_gypsy_jackknife", "Navaja Knife"), Map.entry("weapon_knife_outdoor", "Nomad Knife"), Map.entry("weapon_knife_push", "Shadow Daggers"),
			Map.entry("weapon_knife_skeleton", "Skeleton Knife"), Map.entry("weapon_knife_stiletto", "Stiletto Knife"), Map.entry("weapon_knife_tactical", "Huntsman Knife"),
			Map.entry("weapon_knife_widowmaker", "Talon Knife"), Map.entry("weapon_knife_ursus", "Ursus Knife"));

	private WeaponTable() {}

	private static Map<String, Entry> load() {
		Map<String, Entry> out = new LinkedHashMap<>();
		var in = WeaponTable.class.getResourceAsStream("/strafemc/weapons.tsv");
		if (in == null) return out;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isBlank() || line.startsWith("#")) continue;
				String[] f = line.split("\t");
				if (f.length < 8) continue;
				Map<String, String> clips = new LinkedHashMap<>();
				for (String pair : f[7].split(" ")) {
					int eq = pair.indexOf('=');
					if (eq > 0) clips.put(pair.substring(0, eq), pair.substring(eq + 1));
				}
				out.put(f[0], new Entry(f[0], f[1].equals("knife"), f[2], f[3].equals("-") ? null : f[3], f[4].equals("-") ? null : f[4],
						Set.of(f[5].split(",")), f[6].equals("1"), Collections.unmodifiableMap(clips)));
			}
		} catch (Exception e) {
			dev.strafemc.StrafeMc.LOG.error("[strafemc] weapon table unreadable", e);
		}
		return Collections.unmodifiableMap(out);
	}

	public static Entry get(String cls) {
		return cls == null ? null : ALL.get(cls);
	}

	public static List<Entry> guns() {
		List<Entry> out = new ArrayList<>();
		for (Entry e : ALL.values()) if (!e.knife) out.add(e);
		return out;
	}

	public static List<Entry> knives() {
		List<Entry> out = new ArrayList<>();
		for (Entry e : ALL.values()) if (e.knife) out.add(e);
		return out;
	}

	/** The weapon's display name. */
	public static String name(String cls) {
		String n = KNIFE_NAMES.get(cls);
		return n != null ? n : SkinWraps.WEAPON_NAMES.getOrDefault(cls, cls);
	}

	/**
	 * What CS2's buy menu shows in a loadout slot ("secondary0".."rifle4", "smg0".."smg4") for a side: the weapon CS2
	 * marks as that slot's default for the side, else the first one the side can use; null if none.
	 */
	public static Entry inSlot(String slot, boolean t) {
		Entry first = null;
		for (Entry e : ALL.values()) {
			if (e.knife || !slot.equals(e.slot) || !(t ? e.forT() : e.forCt())) continue;
			if (e.loadoutDefault) return e;
			if (first == null) first = e;
		}
		return first;
	}
}
