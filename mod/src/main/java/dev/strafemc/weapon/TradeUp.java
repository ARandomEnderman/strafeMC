package dev.strafemc.weapon;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * CS2's Trade Up Contract (CSGO_Recipe_TradeUp_Desc): "In exchange for 10 items of identical quality, you will receive a
 * single item of a quality one higher, from a collection of one of the items you provided." All StatTrak or none (a
 * StatTrak contract gives StatTrak). Each input is one tenth of the odds for its collection's next-quality skins. Our
 * collections are the cases (the map-collection skins from care packages have no collection here, so they can't go in).
 * The float: each input's place in its own range, averaged, put into the result's range (CS2's 2025 rule, FLAGGED: not
 * in CS2's files, from its patch notes as the community documents them).
 */
public final class TradeUp {
	public static final int INPUTS = 10;

	/** One possible result and its odds. */
	public record Outcome(Cases.Entry entry, SkinWraps.Rarity rarity, double chance) {}

	private TradeUp() {}

	/** The case and grade a gun wrap comes from (its first case), or null. */
	private static Object[] source(ItemStack s) {
		if (!(s.getItem() instanceof SkinWrapItem w) || w.knife()) return null;
		SkinWraps.Wrap wrap = SkinWraps.get(SkinWrapItem.skin(s));
		if (wrap == null || wrap.kits().size() != 1) return null;
		var k = wrap.kits().entrySet().iterator().next();
		for (Cases.Case c : Cases.all())
			for (var e : c.guns().entrySet())
				for (Cases.Entry en : e.getValue())
					if (en.item().equals(k.getKey()) && en.kit().equals(k.getValue())) return new Object[] {c, e.getKey()};
		return null;
	}

	/** Whether a skin can go into a contract: a case skin below Covert (there's a next grade in its case). */
	public static boolean eligible(ItemStack s) {
		Object[] src = source(s);
		if (src == null) return false;
		SkinWraps.Rarity r = (SkinWraps.Rarity) src[1];
		if (r.ordinal() >= SkinWraps.Rarity.ANCIENT.ordinal()) return false;
		var next = ((Cases.Case) src[0]).guns().get(SkinWraps.Rarity.values()[r.ordinal() + 1]);
		return next != null && !next.isEmpty();
	}

	/** Why these inputs can't be traded up, or null if they can. */
	public static String problem(List<ItemStack> in) {
		if (in.size() != INPUTS) return in.size() + " / " + INPUTS + " selected";
		SkinWraps.Rarity grade = null;
		Boolean st = null;
		for (ItemStack s : in) {
			if (!eligible(s)) return "Not every item can be traded up";
			SkinWraps.Rarity r = (SkinWraps.Rarity) source(s)[1];
			if (grade != null && r != grade) return "All items must be the same quality";
			grade = r;
			boolean t = StatTrak.kills(s) >= 0;
			if (st != null && t != st) return "StatTrak and normal items can't be mixed";
			st = t;
		}
		return null;
	}

	/** The possible results with their odds (each input a tenth, split evenly over its case's next-grade skins). */
	public static List<Outcome> outcomes(List<ItemStack> in) {
		List<Outcome> out = new ArrayList<>();
		java.util.Map<String, Integer> at = new java.util.HashMap<>();
		for (ItemStack s : in) {
			Object[] src = source(s);
			if (src == null) continue;
			SkinWraps.Rarity next = SkinWraps.Rarity.values()[((SkinWraps.Rarity) src[1]).ordinal() + 1];
			List<Cases.Entry> pool = ((Cases.Case) src[0]).guns().get(next);
			if (pool == null || pool.isEmpty()) continue;
			for (Cases.Entry e : pool) {
				String key = e.item() + "|" + e.kit();
				double c = 1.0 / in.size() / pool.size();
				Integer i = at.get(key);
				if (i == null) {
					at.put(key, out.size());
					out.add(new Outcome(e, next, c));
				} else out.set(i, new Outcome(e, next, out.get(i).chance() + c));
			}
		}
		out.sort((a, b) -> Double.compare(b.chance(), a.chance()));
		return out;
	}

	/** Each input's place in its own float range, averaged (0 = all at their best). */
	public static double place(List<ItemStack> in) {
		double sum = 0;
		for (ItemStack s : in) {
			SkinWraps.Wrap w = SkinWraps.get(SkinWrapItem.skin(s));
			double[] r = Wear.range(w == null ? null : w.kits().values().iterator().next());
			double f = SkinWrapItem.wear(s);
			sum += r[1] > r[0] ? Math.max(0, Math.min(1, (f - r[0]) / (r[1] - r[0]))) : 0;
		}
		return in.isEmpty() ? 0 : sum / in.size();
	}

	/** The result's float for this kit from the inputs' average place. */
	public static float wearFor(String kit, double place) {
		double[] r = Wear.range(kit);
		return (float) (r[0] + place * (r[1] - r[0]));
	}

	/** Signs the contract (server): takes the ten (Upgrader refs) and gives the result; returns it, or null if not valid. */
	public static ItemStack sign(ServerPlayer p, List<Integer> refs) {
		if (new java.util.HashSet<>(refs).size() != refs.size()) return null;
		List<ItemStack> in = new ArrayList<>();
		for (int ref : refs) in.add(Upgrader.at(p, ref).copy());
		if (problem(in) != null) return null;
		List<Outcome> outs = outcomes(in);
		if (outs.isEmpty()) return null;
		Random rng = new Random();
		double x = rng.nextDouble();
		Outcome pick = outs.get(outs.size() - 1);
		for (Outcome o : outs) {
			if (x < o.chance()) {
				pick = o;
				break;
			}
			x -= o.chance();
		}
		ItemStack result = Cases.stack(pick.entry(), rng.nextInt(1001), wearFor(pick.entry().kit(), place(in)));
		if (StatTrak.kills(in.get(0)) >= 0) result.set(WeaponItems.STATTRAK, 0);
		Upgrader.take(p, refs);
		if (!p.getInventory().add(result.copy())) p.drop(result.copy(), false);
		dev.strafemc.StrafeMc.LOG.info("[strafemc] trade up: {} got {}", p.getScoreboardName(), result.getHoverName().getString());
		return result;
	}
}
