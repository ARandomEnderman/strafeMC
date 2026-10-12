package dev.strafemc.weapon;

import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * The one shot formula, shared by the shooting client (instant prediction) and the server (authority), so honest
 * shots agree exactly. CS model: a random offset inside the inaccuracy circle plus one inside the spread circle, around
 * eye angles + aim punch. The randomness comes from the server-issued seed and the shot number, never from the
 * client's own choice, so a client can't pick favourable spread. Angles in MC degrees; punch in Source convention
 * (pitch + = down, yaw + = left).
 */
public final class ShotMath {
	private ShotMath() {}

	public static Vec3 direction(float viewPitch, float viewYaw, double punchPitch, double punchYaw, double inaccuracy, double spread,
								 long seed, int shot) {
		Random r = new Random(seed * 0x9E3779B97F4A7C15L + shot * 0xBF58476D1CE4E5B9L);
		double t0 = r.nextDouble() * Math.PI * 2, r0 = r.nextDouble() * inaccuracy;
		double t1 = r.nextDouble() * Math.PI * 2, r1 = r.nextDouble() * spread;
		double ox = Math.cos(t0) * r0 + Math.cos(t1) * r1, oy = Math.sin(t0) * r0 + Math.sin(t1) * r1;
		Vec3 fwd = Vec3.directionFromRotation((float) (viewPitch + punchPitch), (float) (viewYaw - punchYaw));
		Vec3 right = fwd.cross(new Vec3(0, 1, 0)).normalize();
		Vec3 up = right.cross(fwd).normalize();
		return fwd.add(right.scale(ox)).add(up.scale(oy)).normalize();
	}

	/** MC yaw/pitch of a direction (for the hit trace). */
	public static float[] angles(Vec3 d) {
		return new float[] {(float) Math.toDegrees(Math.atan2(-d.x, d.z)), (float) Math.toDegrees(Math.asin(-d.y))};
	}
}
