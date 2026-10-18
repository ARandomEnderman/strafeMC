package dev.strafemc.weapon;

import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * What a skin is worth, in dollars: one fixed number per item from how hard it is to get in strafeMC (no market data), so
 * an upgrader or a trade-up can compare items. FLAGGED ours throughout (CS2 has no such number; Steam's market does):
 * <ul>
 * <li>grade: 5x per grade, as CS2's case odds step (Mil-Spec 79.92% vs Restricted 15.98%): Consumer $0.03, Industrial
 * $0.15, Mil-Spec $0.75, Restricted $3.75, Classified $18.75, Covert $93.75, Contraband $1,875; knives and gloves $375;</li>
 * <li>scarcity: times (items in its case tier / 4) and divided by the square root of how many cases hold it;</li>
 * <li>wear: by the exterior's odds (Factory New 3% vs Field-Tested 33%): the square root of the ratio, up for better
 * exteriors and down for worse (FN x3.3, MW x1.17, FT x1, WW x0.85, BS x0.70), and up to 25% more toward the best float
 * of its exterior;</li>
 * <li>StatTrak x2.5 (its 10% odds would say x10).</li>
 * </ul>
 */
public final class Worth {
	private static final double[] GRADE = {0.03, 0.15, 0.75, 3.75, 18.75, 93.75, 1875};
	private static final double SPECIAL = 375, STATTRAK = 2.5, AVG_TIER = 4, BEST_FLOAT_BONUS = 0.25;
	/** weapon|kit -> scarcity factor, from the cases. */
	private static Map<String, Double> scarcity;

	private Worth() {}

	private static synchronized Map<String, Double> scarcity() {
		if (scarcity != null) return scarcity;
		Map<String, Integer> sources = new HashMap<>();
		Map<String, Integer> tierSize = new HashMap<>(); // the biggest tier it's in (rarest single pick)
		for (Cases.Case c : Cases.all())
			for (var list : c.guns().values())
				for (Cases.Entry e : list) {
					String k = e.item() + "|" + e.kit();
					sources.merge(k, 1, Integer::sum);
					tierSize.merge(k, list.size(), Math::max);
				}
		Map<String, Double> out = new HashMap<>();
		for (var e : sources.entrySet()) out.put(e.getKey(), tierSize.get(e.getKey()) / AVG_TIER / Math.sqrt(e.getValue()));
		return scarcity = out;
	}

	/** The item's worth in dollars, or -1 for things without one (anything but wraps and gloves). */
	public static double of(ItemStack s) {
		double base;
		float wear = 0;
		boolean hasWear = false;
		String kit = null;
		if (s.getItem() instanceof SkinWrapItem wi) {
			SkinWraps.Wrap w = SkinWraps.get(SkinWrapItem.skin(s));
			if (w == null) return -1;
			if (wi.knife()) base = SPECIAL;
			else {
				SkinWraps.Rarity r = w.rarity();
				base = GRADE[r == null ? SkinWraps.Rarity.RARE.ordinal() : Math.min(GRADE.length - 1, r.ordinal())];
				if (w.kits().size() == 1) {
					var e = w.kits().entrySet().iterator().next();
					base *= scarcity().getOrDefault(e.getKey() + "|" + e.getValue(), 1.0);
					kit = e.getValue();
				}
			}
			wear = SkinWrapItem.wear(s);
			hasWear = true;
		} else if (s.getItem() instanceof GloveItem) base = SPECIAL; // Factory New for now
		else return -1;
		if (hasWear) base *= wearFactor(wear);
		if (StatTrak.kills(s) >= 0) base *= STATTRAK;
		return Math.round(base * 100) / 100.0;
	}

	/** The exterior's odds against Field-Tested's (square-rooted), and the bonus toward the best float within it. */
	static double wearFactor(double wear) {
		int band = Wear.bandOf(wear), ft = 2;
		double odds = Wear.bandOdds(band), ref = Wear.bandOdds(ft);
		double f = band < ft ? Math.sqrt(ref / odds) : band > ft ? Math.sqrt(odds / ref) : 1;
		double[] b = Wear.bandBounds(band);
		double pos = Math.max(0, Math.min(1, (wear - b[0]) / (b[1] - b[0])));
		return f * (1 + BEST_FLOAT_BONUS * (1 - pos));
	}

	/** "$1,234.56". */
	public static String format(double v) {
		return "$" + String.format(java.util.Locale.ROOT, "%,.2f", v);
	}
}
