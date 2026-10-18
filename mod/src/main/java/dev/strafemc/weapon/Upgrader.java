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

	/** What can be staked: anything with a worth. */
	public static boolean stakeable(ItemStack s) {
		return !s.isEmpty() && Worth.of(s) > 0;
	}

	public static double worth(List<ItemStack> stake) {
		double w = 0;
		for (ItemStack s : stake) w += Math.max(0, Worth.of(s)) * s.getCount();
		return w;
	}

	/** The five offers for this player and stake (empty if nothing staked has a worth). */
	public static List<Offer> offers(UUID player, List<ItemStack> stake) {
		double s = worth(stake);
		List<Offer> out = new ArrayList<>();
		if (s <= 0) return out;
		// the seed: the player and the staked items (order doesn't matter)
		List<Integer> hashes = new ArrayList<>();
		for (ItemStack st : stake) hashes.add(ItemStack.hashItemAndComponents(st) * 31 + st.getCount());
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
