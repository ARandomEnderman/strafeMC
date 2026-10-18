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
 * materials: four on most, five on a few), and the wrap keeps which sticker is in which ("" for none). Applying one
 * uses the sticker up; scraping it off destroys it, as in CS2. The viewmodel paints them onto the finish
 * (WeaponSkinCache).
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

	/** The stickers in each slot ("" for an empty one), always MAX long. */
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

	/** Applies the sticker at inventory slot stickerSlot to slot `slot` of the wrap at ref (an empty slot): the sticker is used up. */
	public static void apply(ServerPlayer p, int stickerSlot, String ref, int slot) {
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
		s[slot] = StickerItem.sticker(sticker);
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

	/** Hangs the charm at inventory slot charmSlot on the wrap at ref (one with none): the charm goes onto it. */
	public static void applyCharm(ServerPlayer p, int charmSlot, String ref) {
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
		wrap.set(CHARM, CharmItem.charm(charm));
		store(p, ref, wrap);
		charm.shrink(1);
		dev.strafemc.server.Toasts.send(p, "Charm attached.", dev.strafemc.server.Toasts.GOOD);
	}

	/** Takes the charm off the wrap at ref: it comes back, as in CS2. */
	public static void removeCharm(ServerPlayer p, String ref) {
		ItemStack live = target(p, ref);
		String id = charm(live);
		if (id.isEmpty()) return;
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
			Stickers.Sticker st = Stickers.get(id);
			if (st != null) out.add(st.name());
		}
		return out;
	}
}
