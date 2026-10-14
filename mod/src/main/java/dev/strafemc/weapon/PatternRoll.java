package dev.strafemc.weapon;

/**
 * A skin's pattern template (paint seed) -> its pattern placement, like CS2: the composite's random-roll mutator seeds
 * Valve's uniform random stream (CUniformRandomStream, the Numerical Recipes ran1 generator from the public Source SDK)
 * with the seed and rolls, in order, the pattern offset x, offset y and rotation, each between the kit's bounds
 * (the composite's econ_instance loose variables). Same seed, same placement as CS2 (and CS:GO).
 */
public final class PatternRoll {
	/** Bounds of the rolled variables: offset x / y and rotation (degrees), each {min, max}. */
	public record Bounds(double[] offsetX, double[] offsetY, double[] rotation) {}

	/** One rolled placement. */
	public record Placement(double offsetX, double offsetY, double rotation) {}

	private PatternRoll() {}

	/** Rolls offset x, offset y, then rotation: always three draws (a variable the kit doesn't list rolls between 0 and 0). */
	public static Placement roll(int seed, Bounds b) {
		Stream r = new Stream(seed);
		double x = b.offsetX != null ? r.randomFloat(b.offsetX[0], b.offsetX[1]) : r.randomFloat(0, 0);
		double y = b.offsetY != null ? r.randomFloat(b.offsetY[0], b.offsetY[1]) : r.randomFloat(0, 0);
		double rot = b.rotation != null ? r.randomFloat(b.rotation[0], b.rotation[1]) : r.randomFloat(0, 0);
		return new Placement(x, y, rot);
	}

	/** CUniformRandomStream: ran1 with a 32-entry shuffle table. */
	static final class Stream {
		private static final int IA = 16807, IM = 2147483647, IQ = 127773, IR = 2836, NTAB = 32, NDIV = 1 + (IM - 1) / NTAB;
		private static final double AM = 1.0 / IM, EPS = 1.2e-7, RNMX = 1.0 - EPS;
		private int idum, iy;
		private final int[] iv = new int[NTAB];

		Stream(int seed) {
			idum = seed < 0 ? seed : -seed;
			iy = 0;
		}

		int next() {
			int k;
			if (idum <= 0 || iy == 0) {
				idum = -idum < 1 ? 1 : -idum;
				for (int j = NTAB + 7; j >= 0; j--) {
					k = idum / IQ;
					idum = IA * (idum - k * IQ) - IR * k;
					if (idum < 0) idum += IM;
					if (j < NTAB) iv[j] = idum;
				}
				iy = iv[0];
			}
			k = idum / IQ;
			idum = IA * (idum - k * IQ) - IR * k;
			if (idum < 0) idum += IM;
			int j = iy / NDIV;
			if (j >= NTAB || j < 0) j = (j % NTAB) & 0x7fffffff;
			iy = iv[j];
			iv[j] = idum;
			return iy;
		}

		/** RandomFloat(low, high), in float precision like Valve's. */
		double randomFloat(double low, double high) {
			float fl = (float) (AM * next());
			if (fl > RNMX) fl = (float) RNMX;
			return fl * (float) (high - low) + (float) low;
		}
	}
}
