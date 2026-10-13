package dev.strafemc.client.viewmodel;

import dev.strafemc.config.StrafeConfig;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Viewmodel bob and sway, as a camera-space transform in front of the viewmodel.
 * Bob: a walk cycle whose size follows the horizontal speed (vertical, lateral, a slight roll / pitch / yaw, and the
 * weapon lowered a little while moving). Sway: when turning, the weapon lags behind the view and catches up, and it
 * shifts with the view pitch. FLAGGED: CS2 does this in game code, not data; these are CS:GO's viewmodel bob and
 * weapon lag formulas and defaults (cl_bobcycle 0.98, cl_bobup 0.5, cl_bob_lower_amt 21, max lag 1.5, lag speed 5),
 * with the bob amount picked to look right. To be matched against CS2 later.
 */
final class ViewmodelMotion {
	private static final double BOB_CYCLE = 0.98, BOB_UP = 0.5, LOWER_AMT = 21, MAX_SPEED = 250;
	/** Units of bob per unit/s of speed, on the ground / in the air. */
	private static final double BOB_GROUND = 0.005, BOB_AIR = 0.001, LATERAL = 1.6;
	private static final double LAG_MAX = 1.5, LAG_SPEED = 5, LAG_SCALE = 5;

	private double bobTime;
	private long lastNs;
	private Vec3 lastFacing;

	/** The offset for this frame (camera space: x right, y up, z backward; blocks). */
	Matrix4f offset(LocalPlayer p) {
		long now = System.nanoTime();
		double dt = lastNs == 0 ? 0 : Math.min(0.1, (now - lastNs) / 1e9);
		lastNs = now;
		double upb = StrafeConfig.unitsPerBlock;

		// ---- bob ----
		Vec3 v = dev.strafemc.client.movement.MovementController.INSTANCE.velocityUnits(p);
		double speed = Math.min(MAX_SPEED, Math.hypot(v.x, v.z));
		double frac = speed / MAX_SPEED;
		bobTime += dt * frac;
		double mult = p.onGround() ? BOB_GROUND : BOB_AIR;
		double vert = clamp(speed * mult * (0.3 + 0.7 * Math.sin(phase(bobTime, BOB_CYCLE))), -7, 4);
		double lat = clamp(speed * mult * LATERAL * (0.3 + 0.7 * Math.sin(phase(bobTime, BOB_CYCLE * 2))), -7, 4);
		double lower = frac * LOWER_AMT * 0.05;

		// ---- sway: the facing the weapon follows lags behind the view ----
		Vec3 f = Vec3.directionFromRotation(p.getXRot(), p.getYRot());
		Vec3 right = new Vec3(-f.z, 0, f.x).normalize(), up = right.cross(f).normalize();
		if (right.lengthSqr() < 1e-6) right = new Vec3(1, 0, 0);
		if (lastFacing == null) lastFacing = f;
		Vec3 diff = f.subtract(lastFacing);
		if (dt > 0) {
			double s = LAG_SPEED, d = diff.length();
			if (d > LAG_MAX) s *= d / LAG_MAX;
			lastFacing = lastFacing.add(diff.scale(Math.min(1, s * dt))).normalize();
		}
		Vec3 lag = diff.scale(-LAG_SCALE); // units, world space
		double pitch = p.getXRot(); // Minecraft and Source pitch: positive looks down

		// camera-space translation (units): sway + pitch shift + bob
		double x = lag.dot(right) - pitch * 0.03 + lat * 0.8;
		double y = lag.dot(up) - pitch * 0.02 + vert * 0.1 - lower;
		double z = -(lag.dot(f) - pitch * 0.035 + vert * 0.4);
		return new Matrix4f()
				.translate((float) (x / upb), (float) (y / upb), (float) (z / upb))
				.rotateZ((float) Math.toRadians(vert * 0.5))
				.rotateX((float) Math.toRadians(vert * 0.4))
				.rotateY((float) Math.toRadians(-lat * 0.3));
	}

	/** Source's bob phase: the rising part of the cycle (bobup) is stretched to half a sine. */
	private static double phase(double time, double cycle) {
		double c = (time - Math.floor(time / cycle) * cycle) / cycle;
		return c < BOB_UP ? Math.PI * c / BOB_UP : Math.PI + Math.PI * (c - BOB_UP) / (1 - BOB_UP);
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
