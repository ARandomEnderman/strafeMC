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
 * <li>StatTrak x2.5 (its 10% odds would say x10);</li>
 * <li>gloves: $375 times (gloves in their case / 24) over the square root of how many cases hold them; sealed graffiti
 * half their grade's price, unsealed ones none.</li>
 * </ul>
 */
public final class Worth {
	private static final double[] GRADE = {0.03, 0.15, 0.75, 3.75, 18.75, 93.75, 1875};
	private static final double SPECIAL = 375, STATTRAK = 2.5, AVG_TIER = 4, BEST_FLOAT_BONUS = 0.25, GRAFFITI_SHARE = 0.5, AVG_SPECIALS = 24;
	/** item|kit -> scarcity of a case's rare special item (gloves): (specials in its case / AVG_SPECIALS) / sqrt(cases holding it). */
	private static Map<String, Double> specials;

	private static synchronized double specialScarcity(String item, String kit) {
		if (specials == null) {
			Map<String, Integer> sources = new HashMap<>(), size = new HashMap<>();
			for (Cases.Case c : Cases.all())
				for (Cases.Entry e : c.special()) {
					String k = e.item() + "|" + e.kit();
					sources.merge(k, 1, Integer::sum);
					size.merge(k, c.special().size(), Math::max);
				}
			Map<String, Double> out = new HashMap<>();
			for (var e : sources.entrySet()) out.put(e.getKey(), size.get(e.getKey()) / AVG_SPECIALS / Math.sqrt(e.getValue()));
			specials = out;
		}
		return specials.getOrDefault(item + "|" + kit, 1.0);
	}
	/** knife finish -> how many case knife entries have it, and the average over all knife finishes. */
	private static Map<String, Integer> knifeEntries;
	private static double knifeAvg = 1;

	/**
	 * A knife finish's scarcity: the square root of (the average finish's entries / this one's), counting each knife in
	 * each case that comes in it; finishes on fewer knives / cases are rarer. FLAGGED ours.
	 */
	private static synchronized double knifeScarcity(SkinWraps.Wrap w) {
		if (knifeEntries == null) {
			Map<String, Integer> byKit = new HashMap<>();
			for (Cases.Case c : Cases.all()) for (Cases.Entry e : c.special()) if (e.knife()) byKit.merge(e.kit(), 1, Integer::sum);
			Map<String, Integer> out = new HashMap<>();
			long sum = 0;
			for (SkinWraps.Wrap k : SkinWraps.all()) {
				if (!k.knife()) continue;
				int n = 0;
				for (String kit : new java.util.HashSet<>(k.kits().values())) n += byKit.getOrDefault(kit, 0);
				if (n > 0) {
					out.put(k.id(), n);
					sum += n;
				}
			}
			knifeEntries = out;
			knifeAvg = out.isEmpty() ? 1 : (double) sum / out.size();
		}
		Integer n = knifeEntries.get(w.id());
		return n == null ? 1.5 : Math.sqrt(knifeAvg / n); // in no case: as rare as they come
	}

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
			if (wi.knife()) base = SPECIAL * knifeScarcity(w);
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
		} else if (s.getItem() instanceof GloveItem g) base = SPECIAL * specialScarcity(g.kind.id, g.kit(s)); // Factory New for now
		else if (s.getItem() instanceof SprayItem) { // sealed graffiti: half its grade's price; unsealed ones have none
			Sprays.Spray sp = Sprays.get(SprayItem.spray(s));
			if (sp == null || !SprayItem.sealed(s)) return -1;
			base = GRADE[Math.min(GRADE.length - 1, sp.rarity().ordinal())] * GRAFFITI_SHARE;
		} else return -1;
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
