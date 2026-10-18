package dev.strafemc.weapon;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * The upgrader (ours, like the upgrade sites CS players know): stake up to MAX_STAKE skins and pick one of five better
 * skins; the chance to get it is RETURN x stake worth / its worth (at most MAX_CHANCE), so on average you get back 90%
 * of what you put in. The five offers depend only on you and the exact items staked (same items, same offers), so
 * nothing rerolls them. Client and server work them out the same way; the server rolls.
 */
public final class Upgrader {
	/** Expected return per upgrade (under 1: the house keeps 10%). FLAGGED ours. */
	public static final double RETURN = 0.9;
	public static final double MAX_CHANCE = 0.75;
	public static final int MAX_STAKE = 10, OFFERS = 5;
	/** The offers aim at these multiples of the stake's worth. FLAGGED ours. */
	private static final double[] MULTS = {1.5, 2, 3, 5, 10};
	private static final int TRIES = 400;

	/** item: the skin you'd get (pattern and float set), its worth and your chance. */
	public record Offer(ItemStack item, double worth, double chance) {}

	private Upgrader() {}

	/**
	 * Where a staked skin is: an inventory slot (below UNIT), or a place in a Storage Unit carried in the inventory
	 * (UNIT + its inventory slot x 1000 + the place).
	 */
	public static final int UNIT = 100_000;

	public static int unitRef(int unitSlot, int place) {
		return UNIT + unitSlot * 1000 + place;
	}

	/** The skin at a ref (the live stack for the inventory, a copy for a storage unit), or empty. */
	public static ItemStack at(net.minecraft.world.entity.player.Player p, int ref) {
		var inv = p.getInventory();
		if (ref >= 0 && ref < UNIT) return ref < inv.getContainerSize() ? inv.getItem(ref) : ItemStack.EMPTY;
		int slot = (ref - UNIT) / 1000, place = (ref - UNIT) % 1000;
		if (ref < UNIT || slot >= inv.getContainerSize() || !(inv.getItem(slot).getItem() instanceof StorageUnitItem)) return ItemStack.EMPTY;
		List<ItemStack> in = inv.getItem(slot).getOrDefault(StorageUnitItem.CONTENTS, List.<ItemStack>of());
		return place < in.size() ? in.get(place) : ItemStack.EMPTY;
	}

	/** Takes this many of the items at each ref (inventory slots or storage unit places; all of a stack empties it). */
	static void take(net.minecraft.world.entity.player.Player p, java.util.Map<Integer, Integer> refs) {
		var inv = p.getInventory();
		java.util.Map<Integer, List<ItemStack>> units = new java.util.HashMap<>();
		for (var e : refs.entrySet()) {
			int ref = e.getKey();
			if (ref < UNIT) {
				inv.getItem(ref).shrink(e.getValue());
				continue;
			}
			int slot = (ref - UNIT) / 1000, place = (ref - UNIT) % 1000;
			List<ItemStack> all = units.computeIfAbsent(slot, s -> StorageUnitItem.contents(inv.getItem(s)));
			ItemStack left = all.get(place).copy();
			left.shrink(e.getValue());
			all.set(place, left.isEmpty() ? ItemStack.EMPTY : left);
		}
		for (var e : units.entrySet()) StorageUnitItem.store(inv.getItem(e.getKey()), e.getValue());
	}

	/** Takes the staked skins out (inventory slots emptied, storage unit places cleared). */
	static void take(net.minecraft.world.entity.player.Player p, List<Integer> refs) {
		var inv = p.getInventory();
		java.util.Map<Integer, List<ItemStack>> units = new java.util.HashMap<>();
		for (int ref : refs) {
			if (ref < UNIT) {
				inv.setItem(ref, ItemStack.EMPTY);
				continue;
			}
			int slot = (ref - UNIT) / 1000, place = (ref - UNIT) % 1000;
			List<ItemStack> all = units.computeIfAbsent(slot, s -> StorageUnitItem.contents(inv.getItem(s)));
			all.set(place, ItemStack.EMPTY);
		}
		for (var e : units.entrySet()) StorageUnitItem.store(inv.getItem(e.getKey()), e.getValue());
	}

	/** What the client sends to say which skin it picked: finish id and float bits. */
	public static String key(Offer o) {
		return SkinWrapItem.skin(o.item()) + "@" + Float.floatToIntBits(SkinWrapItem.wear(o.item()));
	}

	/** Stakes it (server): the skins at these refs on that offer, if it's the skin the client picked (expect); true if won. Null if not valid. */
	public static Boolean stake(net.minecraft.server.level.ServerPlayer p, int offer, List<Integer> refs, String expect) {
		if (refs.isEmpty() || refs.size() > MAX_STAKE || new java.util.HashSet<>(refs).size() != refs.size()) return null;
		List<ItemStack> stake = new ArrayList<>();
		for (int ref : refs) {
			ItemStack s = at(p, ref);
			if (!stakeable(s)) return null;
			stake.add(s.copy());
		}
		List<Offer> offers = offers(p.getUUID(), stake);
		if (offer < 0 || offer >= offers.size()) return null;
		Offer o = offers.get(offer);
		// the skin the player picked on their screen must be the server's offer too, or nothing happens
		String got = key(o);
		if (expect != null && !expect.equals(got)) {
			dev.strafemc.server.Toasts.warn(p, "The upgrader's offers changed. Nothing was staked; pick again.");
			dev.strafemc.StrafeMc.LOG.warn("[strafemc] upgrader: {} picked {} but the server's offer {} is {}", p.getScoreboardName(), expect, offer, got);
			return null;
		}
		take(p, refs); // the stake is gone either way
		boolean win = p.getRandom().nextDouble() < o.chance();
		if (win) {
			hold(p, o.item().copy());
			Advancements.award(p, "upgrade_win");
		} // handed over when the needle lands (or the upgrader closes), like a case
		dev.strafemc.StrafeMc.LOG.info("[strafemc] upgrader: {} staked ${} for {} (${}, {}%): {}", p.getScoreboardName(), String.format("%.2f", worth(stake)),
				o.item().getHoverName().getString(), String.format("%.2f", o.worth()), Math.round(o.chance() * 100), win ? "won" : "lost");
		return win;
	}

	/** A won skin waiting for the needle to land (or the screen to close); handed out anyway after HOLD_MS. */
	private record Pending(ItemStack item, long dueMs) {}

	private static final java.util.Map<UUID, Pending> PENDING = new java.util.concurrent.ConcurrentHashMap<>();
	private static final long HOLD_MS = 15_000;

	private static void hold(net.minecraft.server.level.ServerPlayer p, ItemStack item) {
		claim(p); // an earlier one not claimed yet goes out first
		PENDING.put(p.getUUID(), new Pending(item, System.currentTimeMillis() + HOLD_MS));
	}

	/** The needle landed, or the upgrader closed (or the player left): the won skin is theirs. */
	public static void claim(net.minecraft.server.level.ServerPlayer p) {
		Pending d = PENDING.remove(p.getUUID());
		if (d != null && !p.getInventory().add(d.item().copy())) p.drop(d.item().copy(), false);
	}

	public static void tick(net.minecraft.server.MinecraftServer server) {
		if (PENDING.isEmpty() || server.getTickCount() % 20 != 0) return;
		long now = System.currentTimeMillis();
		for (var e : PENDING.entrySet()) {
			if (e.getValue().dueMs() > now) continue;
			var p = server.getPlayerList().getPlayer(e.getKey());
			if (p != null) claim(p);
		}
	}

	/** What can be staked: anything with a worth. */
	public static boolean stakeable(ItemStack s) {
		return !s.isEmpty() && Worth.of(s) > 0;
	}

	public static double worth(List<ItemStack> stake) {
		double w = 0;
		for (ItemStack s : stake) w += Math.max(0, Worth.of(s)) * s.getCount();
		return w;
	}

	/**
	 * A skin's fingerprint that is the same on every computer: its item id, finish, pattern, float, StatTrak count, tint
	 * and count. (Minecraft's own item hash includes component types' identity hashes, which differ between the server
	 * and a remote player's game: their offers came out different and they got another item than they picked.)
	 */
	static int stableHash(ItemStack s) {
		var cmd = s.get(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA);
		return java.util.Objects.hash(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString(),
				cmd == null ? java.util.List.of() : cmd.strings(), s.get(WeaponItems.PATTERN), s.get(WeaponItems.WEAR), s.get(WeaponItems.STATTRAK),
				s.get(WeaponItems.TINT), s.getCount());
	}

	/** The five offers for this player and stake (empty if nothing staked has a worth). */
	public static List<Offer> offers(UUID player, List<ItemStack> stake) {
		double s = worth(stake);
		List<Offer> out = new ArrayList<>();
		if (s <= 0) return out;
		// the seed: the player and the staked items (order doesn't matter)
		List<Integer> hashes = new ArrayList<>();
		for (ItemStack st : stake) hashes.add(stableHash(st));
		hashes.sort(Integer::compare);
		long seed = player.getMostSignificantBits() ^ player.getLeastSignificantBits() * 0x9E3779B97F4A7C15L;
		for (int h : hashes) seed = seed * 1_000_003L + h;
		Random rng = new Random(seed);
		List<SkinWraps.Wrap> pool = new ArrayList<>(SkinWraps.all());
		pool.removeIf(w -> !w.knife() && w.kits().size() != 1);
		java.util.Set<String> used = new java.util.HashSet<>();
		for (double m : MULTS) {
			double target = s * m;
			ItemStack best = ItemStack.EMPTY;
			double bestWorth = 0, bestErr = Double.MAX_VALUE;
			for (int t = 0; t < TRIES; t++) {
				SkinWraps.Wrap w = pool.get(rng.nextInt(pool.size()));
				String kit = w.kits().values().iterator().next();
				double[] r = Wear.range(kit);
				float wear = (float) (r[0] + rng.nextDouble() * (r[1] - r[0]));
				ItemStack c = SkinWrapItem.stack(w.knife() ? WeaponItems.KNIFE_WRAP : WeaponItems.GUN_WRAP, w.id(), rng.nextInt(1001), wear);
				double v = Worth.of(c);
				if (v <= s || used.contains(w.id())) continue;
				double err = Math.abs(Math.log(v / target));
				if (err < bestErr) {
					bestErr = err;
					best = c;
					bestWorth = v;
				}
			}
			if (best.isEmpty()) continue;
			used.add(SkinWrapItem.skin(best));
			out.add(new Offer(best, bestWorth, Math.min(MAX_CHANCE, RETURN * s / bestWorth)));
		}
		return out;
	}
}
