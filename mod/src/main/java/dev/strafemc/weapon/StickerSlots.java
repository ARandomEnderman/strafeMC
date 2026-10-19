package dev.strafemc.weapon;

import com.mojang.serialization.Codec;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stickers on gun wraps, as in CS2: each gun has its sticker slots (strafemc/sticker_slots.tsv, from CS2's weapon
 * materials: four on most, five on a few), and the wrap keeps which sticker is in which ("" for none) and where it was
 * put: "id" sits at its slot's place, "id@u,v,turn" where it was placed (sticker space, the turn in radians; the size is
 * the slot's, as CS2's), and "#wear" once scraped. Applying one uses the sticker up; each scrape wears it SCRAPE more,
 * up to fully scraped, and the scrape after that takes it off (it's gone), as in CS2. The charm likewise:
 * "id", or "id@x,y,z" for the model-space point it hangs from; taking it off uses a Charm Detachment and gives it back.
 */
public final class StickerSlots {
	public static final int MAX = 5;
	/** How much one scrape wears a sticker. FLAGGED ours (Valve's servers decide CS2's). */
	public static final double SCRAPE = 0.1;
	public static DataComponentType<List<String>> STICKERS;
	/** The charm hanging on a gun wrap (Charms id). */
	public static DataComponentType<String> CHARM;
	/** A wrap's Name Tag (the name it shows instead of its own). */
	public static DataComponentType<String> NAME_TAG;
	/** CS2's longest name tag. */
	public static final int NAME_TAG_MAX = 20;
	private static final Map<String, Integer> SLOTS = load();

	private StickerSlots() {}

	public static void register() {
		STICKERS = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, WeaponItems.id("stickers"), DataComponentType.<List<String>>builder()
				.persistent(Codec.STRING.listOf()).networkSynchronized(ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list())).build());
		CHARM = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, WeaponItems.id("charm"), DataComponentType.<String>builder()
				.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).build());
		NAME_TAG = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, WeaponItems.id("name_tag"), DataComponentType.<String>builder()
				.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).build());
	}

	/** A wrap's name tag, or null. */
	public static String nameTag(ItemStack wrap) {
		return wrap.get(NAME_TAG);
	}

	/** A name as a name tag takes it: no formatting codes or control characters, trimmed, at most NAME_TAG_MAX long; null if empty. */
	public static String cleanName(String name) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c == '\u00a7') {
				i++; // a formatting code and its letter
				continue;
			}
			if (!Character.isISOControl(c)) b.append(c);
		}
		String s = b.toString().trim().replaceAll("\\s+", " ");
		if (s.length() > NAME_TAG_MAX) s = s.substring(0, NAME_TAG_MAX).trim();
		return s.isEmpty() ? null : s;
	}

	/** Names the wrap (gun or knife) at ref with the Name Tag in tagSlot (used up). */
	public static void applyNameTag(ServerPlayer p, int tagSlot, String ref, String name) {
		var inv = p.getInventory();
		if (tagSlot < 0 || tagSlot >= inv.getContainerSize() || !inv.getItem(tagSlot).is(WeaponItems.NAME_TAG)) return;
		String clean = cleanName(name);
		ItemStack live = target(p, ref);
		if (clean == null || !(live.getItem() instanceof SkinWrapItem || live.getItem() instanceof C4Item)) return; // CS2's C4 is nameable too
		ItemStack wrap = copied(ref) ? live.copy() : live;
		wrap.set(NAME_TAG, clean);
		store(p, ref, wrap);
		inv.getItem(tagSlot).shrink(1);
		dev.strafemc.server.Toasts.send(p, "Named ''" + clean + "''.", dev.strafemc.server.Toasts.GOOD);
	}

	/**
	 * What a StatTrak Swap Tool may swap between: the weapon a wrap counts on (its gun's class, or "knife"), as CS2 swaps
	 * only between StatTrak items of the same type; null if it isn't a StatTrak wrap.
	 */
	public static String statTrakType(ItemStack wrap) {
		if (StatTrak.kills(wrap) < 0 || !(wrap.getItem() instanceof SkinWrapItem)) return null;
		SkinWraps.Wrap w = SkinWraps.get(SkinWrapItem.skin(wrap));
		if (w == null) return null;
		if (w.knife()) return "knife";
		return w.kits().isEmpty() ? null : w.kits().keySet().iterator().next();
	}

	/** Swaps the Confirmed Kills of the StatTrak wraps at refs a and b (same type) with the tool in toolSlot (used up). */
	public static void swapStatTrak(ServerPlayer p, int toolSlot, String a, String b) {
		var inv = p.getInventory();
		if (a.equals(b) || toolSlot < 0 || toolSlot >= inv.getContainerSize() || !inv.getItem(toolSlot).is(WeaponItems.STATTRAK_SWAP)) return;
		ItemStack la = target(p, a), lb = target(p, b);
		String ta = statTrakType(la), tb = statTrakType(lb);
		if (ta == null || !ta.equals(tb)) {
			dev.strafemc.server.Toasts.warn(p, "Both need to be StatTrak™ wraps of the same weapon.");
			return;
		}
		int ka = StatTrak.kills(la), kb = StatTrak.kills(lb);
		ItemStack wa = copied(a) ? la.copy() : la, wb = copied(b) ? lb.copy() : lb;
		wa.set(WeaponItems.STATTRAK, kb);
		wb.set(WeaponItems.STATTRAK, ka);
		store(p, a, wa);
		store(p, b, wb);
		inv.getItem(toolSlot).shrink(1);
		dev.strafemc.server.Toasts.send(p, "StatTrak™ counts swapped (" + ka + " ⇄ " + kb + ").", dev.strafemc.server.Toasts.GOOD);
	}

	/** Takes the name tag off the wrap at ref (free, as in CS2; the tag is gone). */
	public static void removeNameTag(ServerPlayer p, String ref) {
		ItemStack live = target(p, ref);
		if (nameTag(live) == null) return;
		ItemStack wrap = copied(ref) ? live.copy() : live;
		wrap.remove(NAME_TAG);
		store(p, ref, wrap);
		dev.strafemc.server.Toasts.send(p, "Name tag removed.", dev.strafemc.server.Toasts.GOOD);
	}

	private static Map<String, Integer> load() {
		Map<String, Integer> out = new HashMap<>();
		var in = StickerSlots.class.getResourceAsStream("/strafemc/sticker_slots.tsv");
		if (in == null) return out;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isBlank() || line.startsWith("#")) continue;
				String[] f = line.split("\t");
				if (f.length >= 2) out.put(f[0], Integer.parseInt(f[1].trim()));
			}
		} catch (Exception e) {
			dev.strafemc.StrafeMc.LOG.error("[strafemc] sticker slots unreadable", e);
		}
		return out;
	}

	/** The gun a wrap is for (one gun per wrap), or null for knives and older multi-gun wraps. */
	public static String gun(ItemStack wrap) {
		if (wrap.getItem() instanceof C4Item) return C4Item.CLS;
		if (!(wrap.getItem() instanceof SkinWrapItem w) || w.knife()) return null;
		SkinWraps.Wrap s = SkinWraps.get(SkinWrapItem.skin(wrap));
		return s == null || s.kits().size() != 1 ? null : s.kits().keySet().iterator().next();
	}

	/** How many sticker slots this wrap's gun has (0: none, knives). */
	public static int count(ItemStack wrap) {
		String gun = gun(wrap);
		return gun == null ? 0 : gun.equals(C4Item.CLS) ? C4_SLOTS.length : Math.min(MAX, SLOTS.getOrDefault(gun, 4));
	}

	/**
	 * The C4's starting sticker places: u, v in its sticker space (a top-down unfolding of the bricks) and turn. CS2's C4
	 * supports stickers (its prefab: weapon_supports_stickers, weapon_supports_keychains) but its material's five slots
	 * have no place or size; in CS2 they go on its front (the keypad side; its mask, g_tStickerWepInputs, also allows the
	 * sides), so ours go where both allow. These starting places are where a sticker has most of itself on the front
	 * inside the mask (the C4Slots harness picked them).
	 */
	public static final double[][] C4_SLOTS = {{0.463, 0.189, 0}, {0.666, 0.213, 0}, {0.541, 0.307, 0}, {0.463, 0.588, 0}, {0.666, 0.580, 0}};
	/**
	 * The C4's sticker size (a sticker covers 1 / scale of its sticker space): a sticker as big on the bomb as on CS2's
	 * guns. Measured from the meshes (metres per sticker-space unit / slot scale): Deagle 7.1 cm, Glock 4.2, AK-47 7.0,
	 * AWP 7.5, MP9 6.5, P250 4.8, median 6.7 cm; the C4 has 0.471 m per unit, so 0.471 / 0.067 = 7.0.
	 */
	public static final double C4_SCALE = 7.0;

	/** A sticker or charm entry's id (before its placement and wear). */
	public static String idOf(String entry) {
		if (entry == null) return "";
		int end = entry.length(), at = entry.indexOf('@'), hash = entry.indexOf('#');
		if (at >= 0) end = Math.min(end, at);
		if (hash >= 0) end = Math.min(end, hash);
		return entry.substring(0, end);
	}

	/** A sticker's wear from scraping (0: as new). */
	public static double wearOf(String entry) {
		int hash = entry == null ? -1 : entry.indexOf('#');
		if (hash < 0) return 0;
		try {
			return Math.max(0, Math.min(1, Double.parseDouble(entry.substring(hash + 1))));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** A sticker or charm entry's placement numbers, or null when it sits at its default place. */
	public static double[] placeOf(String entry) {
		int at = entry == null ? -1 : entry.indexOf('@');
		if (at < 0) return null;
		int hash = entry.indexOf('#', at);
		try {
			String[] f = entry.substring(at + 1, hash < 0 ? entry.length() : hash).split(",");
			double[] out = new double[f.length];
			for (int i = 0; i < f.length; i++) out[i] = Double.parseDouble(f[i]);
			return out;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static String entry(String id, double[] place) {
		if (place == null) return id;
		StringBuilder b = new StringBuilder(id).append('@');
		for (int i = 0; i < place.length; i++) b.append(i > 0 ? "," : "").append(String.format(java.util.Locale.ROOT, "%.4f", place[i]));
		return b.toString();
	}

	/** The stickers in each slot ("" for an empty one; with their placement), always MAX long. */
	public static String[] get(ItemStack wrap) {
		String[] out = new String[MAX];
		java.util.Arrays.fill(out, "");
		List<String> l = wrap.get(STICKERS);
		if (l != null) for (int i = 0; i < Math.min(MAX, l.size()); i++) out[i] = l.get(i) == null ? "" : l.get(i);
		return out;
	}

	public static boolean any(ItemStack wrap) {
		for (String s : get(wrap)) if (!s.isEmpty()) return true;
		return false;
	}

	static void set(ItemStack wrap, String[] stickers) {
		boolean any = false;
		for (String s : stickers) any |= !s.isEmpty();
		if (!any) wrap.remove(STICKERS);
		else wrap.set(STICKERS, List.of(stickers));
	}

	/**
	 * Where a wrap is, for the apply screen: "L:<loadout slot>" (a gun slot of the loadout) or "I:<inventory slot>". The
	 * live stack, or empty.
	 */
	private static ItemStack target(ServerPlayer p, String ref) {
		if (ref.startsWith("L:")) return Loadout.get(p, ref.substring(2));
		if (ref.startsWith("I:")) {
			try {
				int i = Integer.parseInt(ref.substring(2));
				return i >= 0 && i < p.getInventory().getContainerSize() ? p.getInventory().getItem(i) : ItemStack.EMPTY;
			} catch (NumberFormatException e) {
				return ItemStack.EMPTY;
			}
		}
		if (ref.startsWith("U:")) { // in a carried Storage Unit (an Upgrader ref)
			try {
				int r = Integer.parseInt(ref.substring(2));
				return r >= Upgrader.UNIT ? Upgrader.at(p, r) : ItemStack.EMPTY;
			} catch (NumberFormatException e) {
				return ItemStack.EMPTY;
			}
		}
		return ItemStack.EMPTY;
	}

	/** Whether the wrap at ref is changed on a copy and put back (the loadout's, a Storage Unit's) rather than in place. */
	private static boolean copied(String ref) {
		return ref.startsWith("L:") || ref.startsWith("U:");
	}

	/** Puts the wrap back where it was (the loadout's or Storage Unit's copy replaced as a whole, which syncs it). */
	private static void store(ServerPlayer p, String ref, ItemStack wrap) {
		if (ref.startsWith("L:")) Loadout.set(p, ref.substring(2), wrap);
		if (ref.startsWith("U:")) {
			int r = Integer.parseInt(ref.substring(2)), slot = (r - Upgrader.UNIT) / 1000, place = (r - Upgrader.UNIT) % 1000;
			ItemStack unit = p.getInventory().getItem(slot);
			List<ItemStack> all = StorageUnitItem.contents(unit);
			if (place < all.size()) {
				all.set(place, wrap);
				StorageUnitItem.store(unit, all);
			}
		}
	}

	/**
	 * Applies the sticker at inventory slot stickerSlot to slot `slot` of the wrap at ref (an empty slot), where it was
	 * placed (place: u, v, turn; null: the slot's place): the sticker is used up.
	 */
	public static void apply(ServerPlayer p, int stickerSlot, String ref, int slot, double[] place, double wear) {
		var inv = p.getInventory();
		if (stickerSlot < 0 || stickerSlot >= inv.getContainerSize()) return;
		ItemStack sticker = inv.getItem(stickerSlot);
		if (!(sticker.getItem() instanceof StickerItem) || Stickers.get(StickerItem.sticker(sticker)) == null) return;
		ItemStack live = target(p, ref);
		if (slot < 0 || slot >= count(live)) return;
		ItemStack wrap = copied(ref) ? live.copy() : live;
		String[] s = get(wrap);
		if (!s[slot].isEmpty()) {
			dev.strafemc.server.Toasts.warn(p, "That slot already has a sticker. Scrape it off first.");
			return;
		}
		if (place != null && (place.length != 3 || place[0] < -0.2 || place[0] > 1.2 || place[1] < -1.5 || place[1] > 1.5)) return;
		s[slot] = entry(StickerItem.sticker(sticker), place);
		wear = Math.max(0, Math.min(1, Math.round(wear / SCRAPE) * SCRAPE)); // applied already scraped, in scrape steps (up to fully)
		if (wear > 0) s[slot] += "#" + String.format(java.util.Locale.ROOT, "%.2f", wear);
		set(wrap, s);
		store(p, ref, wrap);
		sticker.shrink(1);
		dev.strafemc.server.Toasts.send(p, "Sticker applied.", dev.strafemc.server.Toasts.GOOD);
		Advancements.award(p, "sticker_on");
	}

	/** The sticker after one more scrape: more worn, or "" when this scrape takes it off. */
	public static String scraped(String entry) {
		if (wearOf(entry) >= 1 - 1e-6) return ""; // fully scraped already: this one takes it off
		double w = Math.min(1, wearOf(entry) + SCRAPE);
		String base = entry.indexOf('#') < 0 ? entry : entry.substring(0, entry.indexOf('#'));
		return base + "#" + String.format(java.util.Locale.ROOT, "%.2f", w);
	}

	/** Scrapes the sticker in slot `slot` of the wrap at ref once: a bit more worn, the last scrape takes it off (gone), as in CS2. */
	public static void scrape(ServerPlayer p, String ref, int slot) {
		ItemStack live = target(p, ref);
		if (slot < 0 || slot >= MAX || count(live) == 0) return;
		ItemStack wrap = copied(ref) ? live.copy() : live;
		String[] s = get(wrap);
		if (s[slot].isEmpty()) return;
		s[slot] = scraped(s[slot]);
		set(wrap, s);
		store(p, ref, wrap);
		dev.strafemc.server.Toasts.info(p, s[slot].isEmpty() ? "Sticker scraped off." : "Sticker scraped (" + Math.round(wearOf(s[slot]) * 100) + "% worn).");
	}

	/** The charm on a wrap, or "" for none. */
	public static String charm(ItemStack wrap) {
		String c = wrap.get(CHARM);
		return c == null ? "" : c;
	}

	/** Hangs the charm at inventory slot charmSlot on the wrap at ref (one with none) from that point (null: the keychain point). */
	public static void applyCharm(ServerPlayer p, int charmSlot, String ref, double[] point) {
		var inv = p.getInventory();
		if (charmSlot < 0 || charmSlot >= inv.getContainerSize()) return;
		ItemStack charm = inv.getItem(charmSlot);
		if (!(charm.getItem() instanceof CharmItem) || Charms.get(CharmItem.charm(charm)) == null) return;
		ItemStack live = target(p, ref);
		if (count(live) == 0) return; // guns only, as in CS2
		ItemStack wrap = copied(ref) ? live.copy() : live;
		if (!charm(wrap).isEmpty()) {
			dev.strafemc.server.Toasts.warn(p, "That wrap already has a charm. Take it off first.");
			return;
		}
		if (point != null && (point.length != 3 || Math.abs(point[0]) > 2 || Math.abs(point[1]) > 2 || Math.abs(point[2]) > 2)) return;
		wrap.set(CHARM, entry(CharmItem.charm(charm), point));
		store(p, ref, wrap);
		charm.shrink(1);
		dev.strafemc.server.Toasts.send(p, "Charm attached.", dev.strafemc.server.Toasts.GOOD);
	}

	/** Takes the charm off the wrap at ref with a Charm Detachment (used up): the charm comes back, as in CS2. */
	public static void removeCharm(ServerPlayer p, String ref) {
		ItemStack live = target(p, ref);
		String id = idOf(charm(live));
		if (id.isEmpty()) return;
		var inv = p.getInventory();
		int tool = -1;
		for (int i = 0; i < inv.getContainerSize() && tool < 0; i++) if (inv.getItem(i).is(WeaponItems.CHARM_DETACHMENT)) tool = i;
		if (tool < 0 && !p.getAbilities().instabuild) {
			dev.strafemc.server.Toasts.warn(p, "You need a Charm Detachment (the shop sells them).");
			return;
		}
		if (tool >= 0 && !p.getAbilities().instabuild) inv.getItem(tool).shrink(1);
		ItemStack wrap = copied(ref) ? live.copy() : live;
		wrap.remove(CHARM);
		store(p, ref, wrap);
		ItemStack back = CharmItem.stack(WeaponItems.CHARM, id);
		if (!p.getInventory().add(back)) p.drop(back, false);
	}

	/** Tooltip lines for an item's stickers and charm (the C4's; wraps write their own). */
	public static void tooltip(ItemStack s, java.util.function.Consumer<net.minecraft.network.chat.Component> out) {
		List<String> st = names(s);
		if (!st.isEmpty()) out.accept(net.minecraft.network.chat.Component.literal("§7Stickers: " + String.join(", ", st)));
		Charms.Charm c = Charms.get(idOf(charm(s)));
		if (c != null) out.accept(net.minecraft.network.chat.Component.literal("§7Charm: " + c.name()));
	}

	/** Lines for a wrap's tooltip: its stickers' names. */
	public static List<String> names(ItemStack wrap) {
		List<String> out = new ArrayList<>();
		for (String id : get(wrap)) {
			Stickers.Sticker st = Stickers.get(idOf(id));
			if (st != null) out.add(st.name());
		}
		return out;
	}
}
