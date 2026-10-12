package dev.strafemc.weapon;

/**
 * CS inaccuracy from stance and movement, recovering toward it with the weapon's recovery time. Shared by the
 * client (prediction) and the server (ServerShots checks the client's claim against its own value).
 */
public final class InaccuracyModel {
	/** CS: movement inaccuracy ramps from 34% to 95% of max speed (Source/CS:GO values; not exposed by CS2 data). */
	public static final double MOVE_INACC_MIN = 0.34, MOVE_INACC_MAX = 0.95;

	private InaccuracyModel() {}

	/** The inaccuracy the player is heading toward (before shot penalties). */
	public static double target(WeaponStats w, boolean ladder, boolean ground, boolean ducked, double horizontalSpeed) {
		double target;
		if (ladder) target = w.inaccuracyLadder();
		else if (!ground) target = w.inaccuracyStand() + w.inaccuracyJump();
		else if (ducked) target = w.inaccuracyCrouch();
		else target = w.inaccuracyStand();
		double max = w.maxSpeed();
		double frac = Math.max(0, Math.min(1, (horizontalSpeed - max * MOVE_INACC_MIN) / (max * (MOVE_INACC_MAX - MOVE_INACC_MIN))));
		return target + frac * w.inaccuracyMove();
	}

	/** Rises instantly to the target, recovers toward it exponentially (10x per recovery time). */
	public static double recover(double penalty, double target, double recoveryTime, double dt) {
		if (target >= penalty) return target;
		return target + (penalty - target) * Math.exp(-dt * Math.log(10) / Math.max(1e-3, recoveryTime));
	}
}
