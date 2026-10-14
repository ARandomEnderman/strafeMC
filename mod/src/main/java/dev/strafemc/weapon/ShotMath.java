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

	/**
	 * Pellet n (n >= 1) of a shotgun shot: the shot's inaccuracy offset (the same draws as direction) plus its own spread
	 * offset. Pellet 0 is direction() itself. FLAGGED: CS2's fixed shotgun patterns (weapon_accuracy_shotgun_spread_patterns)
	 * aren't modelled: pellets spread randomly inside the spread circle like CS:GO.
	 */
	public static Vec3 pellet(float viewPitch, float viewYaw, double punchPitch, double punchYaw, double inaccuracy, double spread, long seed, int shot, int n) {
		Random r = new Random(seed * 0x9E3779B97F4A7C15L + shot * 0xBF58476D1CE4E5B9L);
		double t0 = r.nextDouble() * Math.PI * 2, r0 = r.nextDouble() * inaccuracy;
		Random p = new Random(seed * 0x9E3779B97F4A7C15L + shot * 0xBF58476D1CE4E5B9L + n * 0x94D049BB133111EBL);
		double t1 = p.nextDouble() * Math.PI * 2, r1 = p.nextDouble() * spread;
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
