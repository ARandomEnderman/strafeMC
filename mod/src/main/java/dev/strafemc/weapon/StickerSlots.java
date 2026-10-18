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
 * the slot's, as CS2's). Applying one uses the sticker up; scraping it off destroys it, as in CS2. The charm likewise:
 * "id", or "id@x,y,z" for the model-space point it hangs from; taking it off uses a Charm Detachment and gives it back.
 */
public final class StickerSlots {
	public static final int MAX = 5;
	public static DataComponentType<List<String>> STICKERS;
	/** The charm hanging on a gun wrap (Charms id). */
	public static DataComponentType<String> CHARM;
	private static final Map<String, Integer> SLOTS = load();

	private StickerSlots() {}

	public static void register() {
		STICKERS = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, WeaponItems.id("stickers"), DataComponentType.<List<String>>builder()
				.persistent(Codec.STRING.listOf()).networkSynchronized(ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list())).build());
		CHARM = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, WeaponItems.id("charm"), DataComponentType.<String>builder()
				.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).build());
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
		if (!(wrap.getItem() instanceof SkinWrapItem w) || w.knife()) return null;
		SkinWraps.Wrap s = SkinWraps.get(SkinWrapItem.skin(wrap));
		return s == null || s.kits().size() != 1 ? null : s.kits().keySet().iterator().next();
	}

	/** How many sticker slots this wrap's gun has (0: none, knives). */
	public static int count(ItemStack wrap) {
		String gun = gun(wrap);
		return gun == null ? 0 : Math.min(MAX, SLOTS.getOrDefault(gun, 4));
	}

	/** A sticker or charm entry's id (before its placement). */
	public static String idOf(String entry) {
		int at = entry == null ? -1 : entry.indexOf('@');
		return entry == null ? "" : at < 0 ? entry : entry.substring(0, at);
	}

	/** A sticker or charm entry's placement numbers, or null when it sits at its default place. */
	public static double[] placeOf(String entry) {
		int at = entry == null ? -1 : entry.indexOf('@');
		if (at < 0) return null;
		try {
			String[] f = entry.substring(at + 1).split(",");
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
		return ItemStack.EMPTY;
	}

	/** Puts the wrap back where it was (the loadout's copy is replaced as a whole, which syncs it). */
	private static void store(ServerPlayer p, String ref, ItemStack wrap) {
		if (ref.startsWith("L:")) Loadout.set(p, ref.substring(2), wrap);
	}

	/**
	 * Applies the sticker at inventory slot stickerSlot to slot `slot` of the wrap at ref (an empty slot), where it was
	 * placed (place: u, v, turn; null: the slot's place): the sticker is used up.
	 */
	public static void apply(ServerPlayer p, int stickerSlot, String ref, int slot, double[] place) {
		var inv = p.getInventory();
		if (stickerSlot < 0 || stickerSlot >= inv.getContainerSize()) return;
		ItemStack sticker = inv.getItem(stickerSlot);
		if (!(sticker.getItem() instanceof StickerItem) || Stickers.get(StickerItem.sticker(sticker)) == null) return;
		ItemStack live = target(p, ref);
		if (slot < 0 || slot >= count(live)) return;
		ItemStack wrap = ref.startsWith("L:") ? live.copy() : live;
		String[] s = get(wrap);
		if (!s[slot].isEmpty()) {
			dev.strafemc.server.Toasts.warn(p, "That slot already has a sticker. Scrape it off first.");
			return;
		}
		if (place != null && (place.length != 3 || place[0] < -0.2 || place[0] > 1.2 || place[1] < -1.5 || place[1] > 1.5)) return;
		s[slot] = entry(StickerItem.sticker(sticker), place);
		set(wrap, s);
		store(p, ref, wrap);
		sticker.shrink(1);
		dev.strafemc.server.Toasts.send(p, "Sticker applied.", dev.strafemc.server.Toasts.GOOD);
		Advancements.award(p, "sticker_on");
	}

	/** Scrapes the sticker off slot `slot` of the wrap at ref: it's gone, as in CS2. */
	public static void scrape(ServerPlayer p, String ref, int slot) {
		ItemStack live = target(p, ref);
		if (slot < 0 || slot >= MAX || count(live) == 0) return;
		ItemStack wrap = ref.startsWith("L:") ? live.copy() : live;
		String[] s = get(wrap);
		if (s[slot].isEmpty()) return;
		s[slot] = "";
		set(wrap, s);
		store(p, ref, wrap);
		dev.strafemc.server.Toasts.info(p, "Sticker scraped off.");
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
		ItemStack wrap = ref.startsWith("L:") ? live.copy() : live;
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
		ItemStack wrap = ref.startsWith("L:") ? live.copy() : live;
		wrap.remove(CHARM);
		store(p, ref, wrap);
		ItemStack back = CharmItem.stack(WeaponItems.CHARM, id);
		if (!p.getInventory().add(back)) p.drop(back, false);
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
