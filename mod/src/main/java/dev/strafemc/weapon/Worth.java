package dev.strafemc.weapon;

import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
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
 * <li>gloves: $375 times (gloves in their case / 24) over the square root of how many cases hold them, then their wear like wraps;</li>
 * <li>stickers and sealed graffiti: STICKER / GRAFFITI for a High Grade one, 5x per grade (a Base Grade graffiti 1/25),
 * times the square root of (items in its capsule tier / the average tier) over the square root of how many capsules hold
 * it: so a typical sticker capsule's shop price (its average opening x 1.2, as for cases) comes to about $1.20 and a
 * graffiti box's to about $1.00 (tools/research/capsules/calibrate.py). Unsealed graffiti have none.</li>
 * </ul>
 */
public final class Worth {
	private static final double[] GRADE = {0.03, 0.15, 0.75, 3.75, 18.75, 93.75, 1875};
	private static final double SPECIAL = 375, STATTRAK = 2.5, AVG_TIER = 4, BEST_FLOAT_BONUS = 0.25, AVG_SPECIALS = 24;
	/** A High Grade sticker's / graffiti's worth before scarcity (calibrated, see above). FLAGGED ours. */
	private static final double STICKER = 0.4854, GRAFFITI = 0.404;
	/** A High Grade charm's worth before scarcity: a collection comes to about $1.15 (CS2 sells one for three armory stars). FLAGGED ours. */
	private static final double CHARM = 0.27;
	/** "sticker|kit" / "spray|id" -> its capsule scarcity. */
	private static Map<String, Double> capsuleScarcity;

	private static synchronized double capsuleScarcity(String kind, String id) {
		if (capsuleScarcity == null) {
			Map<String, Double> out = new HashMap<>();
			for (String k : new String[] {"sticker", "spray", "charm"}) {
				List<Cases.Case> caps = Cases.capsules(k);
				Map<String, Integer> sources = new HashMap<>();
				double sum = 0;
				int tiers = 0;
				for (Cases.Case c : caps)
					for (var list : c.guns().values()) {
						sum += list.size();
						tiers++;
						for (Cases.Entry e : list) sources.merge(e.kit(), 1, Integer::sum);
					}
				double avg = tiers == 0 ? 1 : sum / tiers;
				for (Cases.Case c : caps)
					for (var list : c.guns().values())
						for (Cases.Entry e : list)
							out.merge(k + "|" + e.kit(), Math.sqrt(list.size() / avg) / Math.sqrt(sources.get(e.kit())), Math::max);
			}
			capsuleScarcity = out;
		}
		return capsuleScarcity.getOrDefault(kind + "|" + id, 1.0);
	}

	private static double ladder(double highGrade, SkinWraps.Rarity r) {
		return highGrade * Math.pow(5, r.ordinal() - SkinWraps.Rarity.RARE.ordinal());
	}
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
	/** knife finish -> its chance from a knife drop of a random case, and the average over all knife finishes. */
	private static Map<String, Double> knifeOdds;
	private static double knifeAvg = 1;

	/**
	 * A knife finish's scarcity: the average finish's chance / this one's, where its chance is the share it has of a
	 * case's knife drops (Cases.specialWeight: the Doppler gems rarer), averaged over the cases with knives. So a finish
	 * in few cases, or beside few others in them, or a gem, is worth more, in proportion. FLAGGED ours.
	 */
	private static synchronized double knifeScarcity(SkinWraps.Wrap w) {
		if (knifeOdds == null) {
			Map<String, Double> byKit = new HashMap<>();
			int cases = 0;
			for (Cases.Case c : Cases.all()) {
				double total = 0;
				for (Cases.Entry e : c.special()) if (e.knife()) total += Cases.specialWeight(e.kit());
				if (total <= 0) continue;
				cases++;
				for (Cases.Entry e : c.special()) if (e.knife()) byKit.merge(e.kit(), Cases.specialWeight(e.kit()) / total, Double::sum);
			}
			Map<String, Double> out = new HashMap<>();
			double sum = 0;
			for (SkinWraps.Wrap k : SkinWraps.all()) {
				if (!k.knife() || cases == 0) continue;
				double p = 0;
				for (String kit : new java.util.HashSet<>(k.kits().values())) p += byKit.getOrDefault(kit, 0.0);
				if (p > 0) {
					out.put(k.id(), p / cases);
					sum += p / cases;
				}
			}
			knifeOdds = out;
			knifeAvg = out.isEmpty() ? 1 : sum / out.size();
		}
		Double p = knifeOdds.get(w.id());
		return p == null ? 1.5 : knifeAvg / p; // in no case: as rare as they come
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
		} else if (s.getItem() instanceof GloveItem g) {
			base = SPECIAL * specialScarcity(g.kind.id, g.kit(s));
			wear = GloveItem.wear(s);
			hasWear = true;
		}
		else if (s.getItem() instanceof SprayItem) { // sealed graffiti: by grade and how rare in the graffiti boxes; unsealed ones have none
			Sprays.Spray sp = Sprays.get(SprayItem.spray(s));
			if (sp == null || !SprayItem.sealed(s)) return -1;
			base = ladder(GRAFFITI, sp.rarity()) * capsuleScarcity("spray", sp.id());
		} else if (s.getItem() instanceof CharmItem) {
			Charms.Charm c = Charms.get(CharmItem.charm(s));
			if (c == null) return -1;
			base = ladder(CHARM, c.rarity()) * capsuleScarcity("charm", c.id());
		} else if (s.getItem() instanceof StickerItem) {
			Stickers.Sticker st = Stickers.get(StickerItem.sticker(s));
			if (st == null) return -1;
			base = ladder(STICKER, st.rarity()) * capsuleScarcity("sticker", st.id());
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

	/**
	 * What opening a sticker capsule / graffiti box gives on average: CS2's case odds by tier (High Grade 79.92%, Remarkable
	 * 15.98%, Exotic 3.2%, Extraordinary the rest), a tier it lacks falling to the next lower one it has (else the next higher), as Cases.roll does.
	 */
	public static double capsuleOpening(Cases.Case c) {
		double[] odds = {0.7992, 0.1598, 0.032, 0.0064 + 0.0026};
		double ev = 0;
		for (int t = 0; t < odds.length; t++) {
			java.util.List<Integer> order = new java.util.ArrayList<>(); // as Cases.roll: lower first, then higher
			for (int k = SkinWraps.Rarity.RARE.ordinal() + t; k >= 0; k--) order.add(k);
			for (int k = SkinWraps.Rarity.RARE.ordinal() + t + 1; k < SkinWraps.Rarity.values().length; k++) order.add(k);
			for (int k : order) {
				var list = c.guns().get(SkinWraps.Rarity.values()[k]);
				if (list == null || list.isEmpty()) continue;
				double sum = 0;
				for (Cases.Entry e : list) sum += Math.max(0, of(Cases.stack(e, 0)));
				ev += odds[t] * sum / list.size();
				break;
			}
		}
		return ev;
	}

	/** "$1,234.56". */
	public static String format(double v) {
		return "$" + String.format(java.util.Locale.ROOT, "%,.2f", v);
	}
}
