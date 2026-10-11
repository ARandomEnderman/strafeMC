package dev.strafemc.weapon;

import dev.strafemc.data.WeaponData;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CS2 recoil data of one weapon:
 * the 64-entry spray table per mode, the recovery time used for inaccuracy recovery
 * and the recoil index decay. All inputs come from weapons.vdata.
 */
public final class Recoil {
	public static final int TABLE = 64;
	/** Full-auto table smoothing and first-shot magnitude ramp. */
	private static final float SMOOTHING = 0.55f, RAMP_BASE = 0.75f, RAMP_STEP = 0.25f;
	private static final Map<String, Recoil> CACHE = new ConcurrentHashMap<>();

	/** [mode][shot] spray angle (degrees, 0 = straight up) and magnitude (punch velocity, degrees/s). */
	public final float[][] angle = new float[2][TABLE], magnitude = new float[2][TABLE];
	public final double recoveryStand, recoveryStandFinal, recoveryCrouch, recoveryCrouchFinal;
	public final int transitionStart, transitionEnd;

	public static Recoil of(String cls) {
		Recoil r = CACHE.get(cls);
		if (r == null) {
			Map<String, Object> w = WeaponData.get(cls);
			if (w == null) return null;
			r = new Recoil(w);
			CACHE.put(cls, r);
		}
		return r;
	}

	private Recoil(Map<String, Object> w) {
		boolean fullAuto = Boolean.TRUE.equals(w.get("m_bIsFullAuto"));
		int seed = (int) num(w, "m_nRecoilSeed", 0, 0);
		for (int mode = 0; mode < 2; mode++) {
			float a0 = (float) num(w, "m_flRecoilAngle", mode, 0), av = (float) num(w, "m_flRecoilAngleVariance", mode, 0),
					m0 = (float) num(w, "m_flRecoilMagnitude", mode, 0), mv = (float) num(w, "m_flRecoilMagnitudeVariance", mode, 0);
			UniformRandomStream rnd = new UniformRandomStream(seed);
			float ang = 0, mag = 0;
			for (int j = 0; j < TABLE; j++) {
				float newAngle = rnd.randomFloat(-av, av) + a0;
				float newMag = rnd.randomFloat(-mv, mv) + m0;
				if (fullAuto && j > 0) {
					ang += (newAngle - ang) * SMOOTHING;
					mag += (newMag - mag) * SMOOTHING;
				} else {
					ang = newAngle;
					mag = newMag;
				}
				if (fullAuto && j < 4) mag *= j * RAMP_STEP * RAMP_STEP + RAMP_BASE;
				angle[mode][j] = ang;
				magnitude[mode][j] = mag;
			}
		}
		recoveryStand = num(w, "m_flRecoveryTimeStand", 0, 0);
		recoveryStandFinal = num(w, "m_flRecoveryTimeStandFinal", 0, -1);
		recoveryCrouch = num(w, "m_flRecoveryTimeCrouch", 0, 0);
		recoveryCrouchFinal = num(w, "m_flRecoveryTimeCrouchFinal", 0, -1);
		transitionStart = (int) num(w, "m_nRecoveryTransitionStartBullet", 0, 0);
		transitionEnd = (int) num(w, "m_nRecoveryTransitionEndBullet", 0, 0);
	}

	/** GetRecoveryTime: airborne = crouch time x4; else stand/crouch time remapped by the recoil index toward its "Final" value. */
	public double recoveryTime(boolean onGround, boolean ducked, float recoilIndex) {
		if (!onGround) return recoveryCrouch * 4;
		double base = ducked ? recoveryCrouch : recoveryStand, fin = ducked ? recoveryCrouchFinal : recoveryStandFinal;
		if (fin == -1) return base;
		return remapClamped((int) recoilIndex, transitionStart, transitionEnd, base, fin);
	}

	/** Recoil index decay once the weapon can fire again: index *= 10^(-2 dt), dropped below 0.1. */
	public static float decayIndex(float index, double dt) {
		if (index <= 0) return 0;
		float v = (float) (index * Math.exp(-2 * Math.log(10) * dt));
		return v < 0.1f ? 0 : v;
	}

	private static double remapClamped(double v, double a, double b, double c, double d) {
		if (a == b) return v >= b ? d : c;
		double t = Math.max(0, Math.min(1, (v - a) / (b - a)));
		return c + (d - c) * t;
	}

	private static double num(Map<String, Object> w, String k, int mode, double def) {
		Object v = w.get(k);
		if (v instanceof List<?> l && l.size() > mode && l.get(mode) instanceof Number n) return n.doubleValue();
		if (v instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Number n) return n.doubleValue();
		if (v instanceof Number n) return n.doubleValue();
		return def;
	}

	/** Source's CUniformRandomStream: Park-Miller minimal standard generator with a 32-entry shuffle table. */
	static final class UniformRandomStream {
		private static final int IA = 16807, IM = 2147483647, IQ = 127773, IR = 2836, NTAB = 32, NDIV = 1 + (IM - 1) / NTAB;
		private static final float AM = Float.intBitsToFloat(0x30000000), RNMX = Float.intBitsToFloat(0x3f7ffffe);
		private int idum, iy;
		private final int[] iv = new int[NTAB];

		UniformRandomStream(int seed) {
			idum = -Math.abs(seed);
			iy = 0;
		}

		private int next() {
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

		float randomFloat(float lo, float hi) {
			float fl = Math.min(RNMX, AM * (float) next());
			return fl * (hi - lo) + lo;
		}
	}
}
