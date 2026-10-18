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
			return !item.startsWith("weapon_") && !sticker() && !spray();
		}

		/** From a sticker capsule: the kit is the sticker (Stickers). */
		public boolean sticker() {
			return item.equals("sticker");
		}

		/** From a graffiti box: the kit is the graffiti (Sprays). */
		public boolean spray() {
			return item.equals("spray");
		}
	}

	/**
	 * image: CS2's inventory picture path (econ/weapon_cases/...); kind: "case" (a weapon case), "sticker" (a sticker
	 * capsule) or "spray" (a graffiti box). Capsules and boxes need no key (key ""), as most of CS2's don't.
	 */
	public record Case(String id, String name, String image, Map<SkinWraps.Rarity, List<Entry>> guns, List<Entry> special, String key, String kind) {
		/** A sticker capsule or graffiti box rather than a weapon case. */
		public boolean capsule() {
			return !kind.equals("case");
		}

		public boolean needsKey() {
			return !key.isEmpty();
		}
	}

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

	private static final Map<String, Case> ALL = load("/strafemc/cases.tsv", false), CAPSULES = load("/strafemc/capsules.tsv", true);

	/** The odds per tier (CS2's published numbers; the rest of the 100% is the rare special item). */
	private static final double MIL_SPEC = 0.7992, RESTRICTED = 0.1598, CLASSIFIED = 0.032, COVERT = 0.0064;

	private Cases() {}

	/** cases.tsv rows end in the rare special items and the key; capsules.tsv rows in the kind ("sticker" / "spray"). */
	private static Map<String, Case> load(String path, boolean capsules) {
		Map<String, Case> out = new LinkedHashMap<>();
		var in = Cases.class.getResourceAsStream(path);
		if (in == null) return out;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isBlank() || line.startsWith("#")) continue;
				String[] f = line.split("\t", -1);
				if (f.length < (capsules ? 4 : 5)) continue;
				Map<SkinWraps.Rarity, List<Entry>> guns = new EnumMap<>(SkinWraps.Rarity.class);
				for (String e : f[3].split(" ")) {
					int c = e.indexOf(':');
					Entry en = entry(c > 0 ? e.substring(c + 1) : e);
					SkinWraps.Rarity rar = c > 0 ? rarity(e.substring(0, c)) : null;
					if (en != null && rar != null) guns.computeIfAbsent(rar, k -> new ArrayList<>()).add(en);
				}
				if (capsules) {
					out.put(f[0], new Case(f[0], f[1], f[2], guns, List.of(), "", f.length > 4 && f[4].equals("spray") ? "spray" : "sticker"));
					continue;
				}
				List<Entry> special = new ArrayList<>();
				for (String e : f[4].split(" ")) {
					Entry en = entry(e);
					if (en != null) special.add(en);
				}
				out.put(f[0], new Case(f[0], f[1], f[2], guns, Collections.unmodifiableList(special), f.length > 5 ? f[5] : "", "case"));
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

	/** A weapon case, sticker capsule or graffiti box. */
	public static Case get(String id) {
		if (id == null) return null;
		Case c = ALL.get(id);
		return c != null ? c : CAPSULES.get(id);
	}

	/** The weapon cases (not the capsules). */
	public static List<Case> all() {
		return new ArrayList<>(ALL.values());
	}

	/** The sticker capsules and graffiti boxes. */
	public static List<Case> capsules() {
		return new ArrayList<>(CAPSULES.values());
	}

	/** Just the sticker capsules ("sticker") or just the graffiti boxes ("spray"). */
	public static List<Case> capsules(String kind) {
		List<Case> out = new ArrayList<>();
		for (Case c : CAPSULES.values()) if (c.kind().equals(kind)) out.add(c);
		return out;
	}

	/**
	 * Opens a case: a tier by CS2's odds (a tier the case lacks falls to the next lower one it has), then one of its
	 * items at random, handed out as a wrap / knife wrap / gloves with a random pattern template (0-1000); wraps are StatTrak 10% of the time.
	 */
	public static Drop roll(Case c, Random rng) {
		double x = rng.nextDouble();
		SkinWraps.Rarity tier = x < MIL_SPEC ? SkinWraps.Rarity.RARE : x < MIL_SPEC + RESTRICTED ? SkinWraps.Rarity.MYTHICAL
				: x < MIL_SPEC + RESTRICTED + CLASSIFIED ? SkinWraps.Rarity.LEGENDARY : x < MIL_SPEC + RESTRICTED + CLASSIFIED + COVERT ? SkinWraps.Rarity.ANCIENT : null;
		int pattern = rng.nextInt(1001);
		if (tier == null && !c.special().isEmpty()) {
			// knives and gloves: one of the case's at random, the Doppler gems rarer (specialWeight)
			List<Entry> pool = new ArrayList<>(c.special()), order = new ArrayList<>();
			while (!pool.isEmpty()) {
				double total = 0;
				for (Entry e : pool) total += specialWeight(e.kit());
				double r = rng.nextDouble() * total;
				int i = 0;
				for (; i < pool.size() - 1; i++) if ((r -= specialWeight(pool.get(i).kit())) < 0) break;
				order.add(pool.remove(i));
			}
			for (Entry e : order) {
				ItemStack s = stack(e, pattern, dev.strafemc.server.StrafeSettings.wearFor((SkinWraps.Rarity) null) ? Wear.roll(e.kit(), rng) : 0);
				if (!s.isEmpty()) return new Drop(e, null, statTrak(e, s, rng));
			}
		}
		if (tier == null) tier = SkinWraps.Rarity.ANCIENT;
		for (int t = tier.ordinal(); t >= 0; t--) {
			List<Entry> list = c.guns().get(SkinWraps.Rarity.values()[t]);
			if (list == null || list.isEmpty()) continue;
			List<Entry> pool = new ArrayList<>(list);
			Collections.shuffle(pool, rng);
			for (Entry e : pool) {
				if (c.capsule()) { // a sticker, or a sealed graffiti (the standard ones in a random tint)
					ItemStack s = stack(e, 0);
					if (e.spray() && Sprays.get(e.kit()) != null && Sprays.get(e.kit()).tintable() && !Sprays.tints().isEmpty())
						s.set(WeaponItems.TINT, Sprays.tints().get(rng.nextInt(Sprays.tints().size())).id());
					if (!s.isEmpty()) return new Drop(e, SkinWraps.Rarity.values()[t], s);
					continue;
				}
				ItemStack s = stack(e, pattern, dev.strafemc.server.StrafeSettings.wearFor(SkinWraps.Rarity.values()[t]) ? Wear.roll(e.kit(), rng) : 0);
				if (!s.isEmpty()) return new Drop(e, SkinWraps.Rarity.values()[t], statTrak(e, s, rng));
			}
		}
		return null;
	}

	/**
	 * How likely a case's special item is against its others. CS2 lists each Doppler phase as its own knife finish, and its
	 * items_game paint_kits_rarity grades them: every Doppler / Gamma Doppler phase Mythical, Ruby, Sapphire and Emerald
	 * Legendary (one grade up), Black Pearl Ancient (two up). Each grade up is 5x rarer, CS2's own step between case tiers
	 * (79.92% / 15.98% / 3.2% / 0.64%): a gem 1/5 of a phase, Black Pearl 1/25. Other finishes weigh the same.
	 */
	public static double specialWeight(String kit) {
		return switch (kit) {
			case "am_ruby_marbleized", "am_sapphire_marbleized", "am_sapphire_marbleized_b", "am_emerald_marbleized" -> 0.2;
			case "am_blackpearl_marbleized", "am_blackpearl_marbleized_b" -> 0.04; // the _b ones: the same finish on newer knives
			default -> 1;
		};
	}

	/** CS2's StatTrak roll on a drop: wraps (guns and knives) only, never gloves. */
	private static ItemStack statTrak(Entry e, ItemStack s, Random rng) {
		if (!e.gloves() && rng.nextDouble() < StatTrak.CASE_ODDS) s.set(WeaponItems.STATTRAK, 0);
		return s;
	}

	/** The item for an entry: the gun's wrap, the knife wrap of that finish, or the gloves; empty if we have none. */
	public static ItemStack stack(Entry e, int pattern) {
		return stack(e, pattern, 0);
	}

	/** The same with a wear. */
	public static ItemStack stack(Entry e, int pattern, float wear) {
		if (e.sticker()) return Stickers.get(e.kit()) == null ? ItemStack.EMPTY : StickerItem.stack(WeaponItems.STICKER, e.kit());
		if (e.spray()) {
			Sprays.Spray sp = Sprays.get(e.kit());
			return sp == null ? ItemStack.EMPTY : SprayItem.stack(WeaponItems.SPRAY, sp.id(), sp.tintable() ? 2 : 0);
		}
		if (e.gloves()) {
			var skin = GloveSkins.get(e.kit());
			return skin == null ? ItemStack.EMPTY : GloveItem.stack(WeaponItems.GLOVES.get(skin.kind()), e.kit(), wear);
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
