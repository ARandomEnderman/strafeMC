package dev.strafemc.weapon;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * A skin's wear (float, 0..1): each paint kit's range (strafemc/paint_wear.tsv, from CS2's items_game wear_remap_min /
 * wear_remap_max; kits not listed use CS2's default 0.06-0.8), CS2's exterior names, and the "finish" number the
 * client bakes a finish by (pattern seed and wear in one int, so every path that carries a pattern carries the wear).
 */
public final class Wear {
	private static final Map<String, double[]> RANGES = load();
	private static final double[] DEFAULT = {0.06, 0.8};

	/** Exterior bounds (CS2): Factory New below 0.07, Minimal Wear below 0.15, Field-Tested below 0.38, Well-Worn below 0.45. */
	private static final double[] BOUNDS = {0.07, 0.15, 0.38, 0.45, 1};
	private static final String[] NAMES = {"Factory New", "Minimal Wear", "Field-Tested", "Well-Worn", "Battle-Scarred"};
	/**
	 * How often each exterior band is rolled before the kit's range is applied. FLAGGED: CS2 doesn't publish this; these
	 * are the community's measured odds (3 / 24 / 33 / 24 / 16 %).
	 */
	private static final double[] ODDS = {0.03, 0.24, 0.33, 0.24, 0.16};

	/** Pattern seeds run 0-1000. */
	private static final int SEEDS = 1001;

	private Wear() {}

	private static Map<String, double[]> load() {
		Map<String, double[]> out = new HashMap<>();
		var in = Wear.class.getResourceAsStream("/strafemc/paint_wear.tsv");
		if (in == null) return out;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isBlank() || line.startsWith("#")) continue;
				String[] f = line.split("\t");
				if (f.length >= 3) out.put(f[0].toLowerCase(), new double[] {Double.parseDouble(f[1]), Double.parseDouble(f[2])});
			}
		} catch (Exception ignored) {
		}
		return out;
	}

	/** The kit's {min, max} float. */
	public static double[] range(String kit) {
		return kit == null ? DEFAULT : RANGES.getOrDefault(kit.toLowerCase(), DEFAULT);
	}

	/** A float for a new drop of this kit: an exterior band by the odds, a value in it, squeezed into the kit's range. */
	public static float roll(String kit, Random rng) {
		double x = rng.nextDouble(), lo = 0;
		int band = 0;
		while (band < ODDS.length - 1 && x >= ODDS[band]) x -= ODDS[band++];
		if (band > 0) lo = BOUNDS[band - 1];
		double t = lo + rng.nextDouble() * (BOUNDS[band] - lo);
		double[] r = range(kit);
		return (float) (r[0] + t * (r[1] - r[0]));
	}

	/** An exterior's float band {from, to} by its short or full name (FN, MW, FT, WW, BS), or null. */
	public static double[] band(String name) {
		String n = name.trim().toUpperCase(java.util.Locale.ROOT).replace("-", " ");
		String[] shortNames = {"FN", "MW", "FT", "WW", "BS"};
		for (int i = 0; i < NAMES.length; i++)
			if (n.equals(shortNames[i]) || n.equals(NAMES[i].toUpperCase(java.util.Locale.ROOT).replace("-", " ")))
				return new double[] {i == 0 ? 0 : BOUNDS[i - 1], BOUNDS[i]};
		return null;
	}

	public static String exterior(double wear) {
		for (int i = 0; i < BOUNDS.length; i++) if (wear < BOUNDS[i]) return NAMES[i];
		return NAMES[NAMES.length - 1];
	}

	/** Pattern seed and wear (baked in 0.01 steps) as one number. */
	public static int finish(int pattern, double wear) {
		return Math.max(0, pattern) % SEEDS + SEEDS * (int) Math.round(Math.max(0, Math.min(1, wear)) * 100);
	}

	public static int seed(int finish) {
		return Math.max(0, finish) % SEEDS;
	}

	public static double wear(int finish) {
		return Math.max(0, finish) / SEEDS / 100.0;
	}
}
