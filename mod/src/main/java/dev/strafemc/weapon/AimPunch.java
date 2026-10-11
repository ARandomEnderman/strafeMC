package dev.strafemc.weapon;

/**
 * CS2-style aim punch (CCSPlayer_AimPunchServices):
 * each shot kicks the punch velocity by the spray table entry (pitch -= cos(angle) * magnitude, yaw -= sin(angle) * magnitude);
 * the punch angle is integrated in fixed 1/128 s steps: exponential decay e^-8t, linear decay 18 deg/s, then the
 * trapezoid of the analytically decaying velocity v0 * e^-4.5(t - t0) (velocities below 1/32 count as 0), each
 * component clamped to +-89 deg. Bullets fly along eye angles + punch; the camera shows punch * 0.45.
 * Angles in Source convention: [0] pitch (+ = down), [1] yaw (+ = left).
 */
public final class AimPunch {
	public static final double STEP = 1.0 / 128, VIEW_TRACKING = 0.45, VIEW_PUNCH_EXTRA = 0.055;
	/**
	 * Kick scale on the aim punch (CS:GO's weapon_recoil_scale default 2; the cvar is gone in CS2, and x1 played visibly
	 * too small - verify with a live CS2 spray measurement).
	 */
	public static final double RECOIL_SCALE = 2;
	private static final double EXP_DECAY = 8, LIN_DECAY = 18, VEL_DECAY = 4.5, VEL_EPS = 1.0 / 32, CLAMP = 89;

	/** Punch angle at the time of the last {@link #advance} call (output). */
	public final double[] angle = new double[2];
	/** Integrator state on the 1/128 s grid anchored at the last kick. */
	private final double[] grid = new double[2];
	/** Punch velocity as set at {@link #velTime}. */
	private final double[] vel0 = new double[2];
	private double gridTime, velTime;

	public void reset(double now) {
		angle[0] = angle[1] = grid[0] = grid[1] = vel0[0] = vel0[1] = 0;
		gridTime = velTime = now;
	}

	private void velAt(double t, double[] out) {
		double k = Math.exp(-VEL_DECAY * Math.max(0, t - velTime));
		out[0] = vel0[0] * k;
		out[1] = vel0[1] * k;
		if (Math.hypot(out[0], out[1]) < VEL_EPS) out[0] = out[1] = 0;
	}

	/** Punch at 'now': whole 1/128 s steps on the grid, the partial step interpolated (the grid itself is kept). */
	public void advance(double now) {
		int steps = 0;
		while (gridTime + STEP <= now && steps++ < 1024) {
			step(gridTime, grid);
			gridTime += STEP;
		}
		if (steps >= 1024) gridTime = now; // long idle: everything has decayed anyway
		double frac = (now - gridTime) / STEP;
		angle[0] = grid[0];
		angle[1] = grid[1];
		if (frac > 1e-9) {
			double[] next = {grid[0], grid[1]};
			step(gridTime, next);
			angle[0] += (next[0] - angle[0]) * frac;
			angle[1] += (next[1] - angle[1]) * frac;
		}
	}

	private void step(double t, double[] p) {
		double e = Math.exp(-EXP_DECAY * STEP);
		p[0] *= e;
		p[1] *= e;
		double len = Math.hypot(p[0], p[1]), lin = LIN_DECAY * STEP;
		if (len > lin) {
			double s = 1 - lin / len;
			p[0] *= s;
			p[1] *= s;
		} else p[0] = p[1] = 0;
		double[] v0 = new double[2], v1 = new double[2];
		velAt(t, v0);
		velAt(t + STEP, v1);
		for (int i = 0; i < 2; i++) p[i] = Math.max(-CLAMP, Math.min(CLAMP, p[i] + (v0[i] + v1[i]) * STEP * 0.5));
	}

	/** One shot's kick (AddRecoil); returns the view punch kick [pitch, yaw] (x0.055) for the camera. */
	public double[] kick(double now, float angleDeg, float magnitude) {
		advance(now);
		double[] v = new double[2];
		velAt(now, v);
		double r = Math.toRadians(angleDeg);
		vel0[0] = v[0] - Math.cos(r) * magnitude * RECOIL_SCALE;
		vel0[1] = v[1] - Math.sin(r) * magnitude * RECOIL_SCALE;
		velTime = now;
		grid[0] = angle[0]; // the stored punch and its step grid restart at the shot
		grid[1] = angle[1];
		gridTime = now;
		return new double[] {-Math.cos(r) * magnitude * VIEW_PUNCH_EXTRA, -Math.sin(r) * magnitude * VIEW_PUNCH_EXTRA};
	}
}
