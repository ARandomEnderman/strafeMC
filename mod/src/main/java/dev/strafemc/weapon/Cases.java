package dev.strafemc.weapon;

import net.minecraft.world.item.ItemStack;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * CS2's weapon cases (strafemc/cases.tsv): their guns by rarity from CS2's own loot lists, and their rare special items
 * (knives, gloves) from pattern.wiki's special groups, since CS2's files leave those out. Opening one rolls CS2's
 * published odds: Mil-Spec 79.92%, Restricted 15.98%, Classified 3.2%, Covert 0.64%, a rare special item 0.26%.
 */
public final class Cases {
	/** One thing a case can give: a paint kit on a weapon / knife / gloves ("weapon_ak47", "sporty_gloves"). */
	public record Entry(String kit, String item) {
		public boolean knife() {
			return item.startsWith("weapon_knife") || item.equals("weapon_bayonet");
		}

		public boolean gloves() {
			return !item.startsWith("weapon_");
		}
	}

	/** image: CS2's inventory picture path (econ/weapon_cases/...). */
	public record Case(String id, String name, String image, Map<SkinWraps.Rarity, List<Entry>> guns, List<Entry> special, String key) {}

	/** A case's key (CS2 matches them by the tool restriction both carry): its English name and inventory picture. */
	public record Key(String id, String name, String image) {}

	private static final Map<String, Key> KEYS = loadKeys();

	private static Map<String, Key> loadKeys() {
		Map<String, Key> out = new LinkedHashMap<>();
		var in = Cases.class.getResourceAsStream("/strafemc/keys.tsv");
		if (in == null) return out;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isBlank() || line.startsWith("#")) continue;
				String[] f = line.split("\t", -1);
				if (f.length >= 3) out.put(f[0], new Key(f[0], f[1], f[2]));
			}
		} catch (Exception e) {
			dev.strafemc.StrafeMc.LOG.error("[strafemc] case keys unreadable", e);
		}
		return Collections.unmodifiableMap(out);
	}

	public static Key key(String id) {
		return id == null ? null : KEYS.get(id);
	}

	public static List<Key> keys() {
		return new ArrayList<>(KEYS.values());
	}

	/** What a roll gave: the entry, its rarity (null: a rare special item) and the item handed out. */
	public record Drop(Entry entry, SkinWraps.Rarity rarity, ItemStack stack) {}

	private static final Map<String, Case> ALL = load();

	/** The odds per tier (CS2's published numbers; the rest of the 100% is the rare special item). */
	private static final double MIL_SPEC = 0.7992, RESTRICTED = 0.1598, CLASSIFIED = 0.032, COVERT = 0.0064;

	private Cases() {}

	private static Map<String, Case> load() {
		Map<String, Case> out = new LinkedHashMap<>();
		var in = Cases.class.getResourceAsStream("/strafemc/cases.tsv");
		if (in == null) return out;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isBlank() || line.startsWith("#")) continue;
				String[] f = line.split("\t", -1);
				if (f.length < 5) continue;
				Map<SkinWraps.Rarity, List<Entry>> guns = new EnumMap<>(SkinWraps.Rarity.class);
				for (String e : f[3].split(" ")) {
					int c = e.indexOf(':');
					Entry en = entry(c > 0 ? e.substring(c + 1) : e);
					SkinWraps.Rarity rar = c > 0 ? rarity(e.substring(0, c)) : null;
					if (en != null && rar != null) guns.computeIfAbsent(rar, k -> new ArrayList<>()).add(en);
				}
				List<Entry> special = new ArrayList<>();
				for (String e : f[4].split(" ")) {
					Entry en = entry(e);
					if (en != null) special.add(en);
				}
				out.put(f[0], new Case(f[0], f[1], f[2], guns, Collections.unmodifiableList(special), f.length > 5 ? f[5] : ""));
			}
		} catch (Exception e) {
			dev.strafemc.StrafeMc.LOG.error("[strafemc] cases unreadable", e);
		}
		return Collections.unmodifiableMap(out);
	}

	/** "[kit]item" -> Entry. */
	private static Entry entry(String s) {
		int close = s.indexOf(']');
		if (!s.startsWith("[") || close < 0) return null;
		return new Entry(s.substring(1, close), s.substring(close + 1));
	}

	private static SkinWraps.Rarity rarity(String id) {
		try {
			return SkinWraps.Rarity.valueOf(id.toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public static Case get(String id) {
		return id == null ? null : ALL.get(id);
	}

	public static List<Case> all() {
		return new ArrayList<>(ALL.values());
	}

	/**
	 * Opens a case: a tier by CS2's odds (a tier the case lacks falls to the next lower one it has), then one of its
	 * items at random, handed out as a wrap / knife wrap / gloves with a random pattern template (0-1000).
	 */
	public static Drop roll(Case c, Random rng) {
		double x = rng.nextDouble();
		SkinWraps.Rarity tier = x < MIL_SPEC ? SkinWraps.Rarity.RARE : x < MIL_SPEC + RESTRICTED ? SkinWraps.Rarity.MYTHICAL
				: x < MIL_SPEC + RESTRICTED + CLASSIFIED ? SkinWraps.Rarity.LEGENDARY : x < MIL_SPEC + RESTRICTED + CLASSIFIED + COVERT ? SkinWraps.Rarity.ANCIENT : null;
		int pattern = rng.nextInt(1001);
		if (tier == null && !c.special().isEmpty()) {
			// knives and gloves: one of the case's at random
			List<Entry> pool = new ArrayList<>(c.special());
			Collections.shuffle(pool, rng);
			for (Entry e : pool) {
				ItemStack s = stack(e, pattern, dev.strafemc.server.StrafeSettings.wearFor((SkinWraps.Rarity) null) ? Wear.roll(e.kit(), rng) : 0);
				if (!s.isEmpty()) return new Drop(e, null, s);
			}
		}
		if (tier == null) tier = SkinWraps.Rarity.ANCIENT;
		for (int t = tier.ordinal(); t >= 0; t--) {
			List<Entry> list = c.guns().get(SkinWraps.Rarity.values()[t]);
			if (list == null || list.isEmpty()) continue;
			List<Entry> pool = new ArrayList<>(list);
			Collections.shuffle(pool, rng);
			for (Entry e : pool) {
				ItemStack s = stack(e, pattern, dev.strafemc.server.StrafeSettings.wearFor(SkinWraps.Rarity.values()[t]) ? Wear.roll(e.kit(), rng) : 0);
				if (!s.isEmpty()) return new Drop(e, SkinWraps.Rarity.values()[t], s);
			}
		}
		return null;
	}

	/** The item for an entry: the gun's wrap, the knife wrap of that finish, or the gloves; empty if we have none. */
	public static ItemStack stack(Entry e, int pattern) {
		return stack(e, pattern, 0);
	}

	/** The same with a wear (wraps; gloves stay Factory New for now). */
	public static ItemStack stack(Entry e, int pattern, float wear) {
		if (e.gloves()) {
			var skin = GloveSkins.get(e.kit());
			return skin == null ? ItemStack.EMPTY : GloveItem.stack(WeaponItems.GLOVES.get(skin.kind()), e.kit());
		}
		if (e.knife()) {
			for (SkinWraps.Wrap w : SkinWraps.all())
				if (w.knife() && (e.kit().equals(w.kits().get(e.item())) || w.kits().containsValue(e.kit())))
					return SkinWrapItem.stack(WeaponItems.KNIFE_WRAP, w.id(), pattern, wear);
			return ItemStack.EMPTY; // a vanilla knife: no finish to wrap
		}
		SkinWraps.Wrap w = SkinWraps.ofKit(e.item(), e.kit());
		return w == null ? ItemStack.EMPTY : SkinWrapItem.stack(WeaponItems.GUN_WRAP, w.id(), pattern, wear);
	}
}
