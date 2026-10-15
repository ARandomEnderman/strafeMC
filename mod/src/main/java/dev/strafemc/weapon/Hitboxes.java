package dev.strafemc.weapon;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Body-part hitboxes on humanoids (players, zombies, skeletons, piglins, illagers): Minecraft's own player model boxes
 * (in model pixels: legs 4x12x4, torso 8x12x4 split into stomach below and chest above, arms 4x12x4, head 8x8x8 turned
 * by the head's yaw and pitch about the neck), turned by the body's yaw and scaled to the entity's box height. A bullet
 * hits the part it passes through first, so where it hits decides the hit group (not how high), and a shot through
 * the empty corners of the entity's box misses. FLAGGED: Minecraft's model, not CS2's hitboxes; CS2's groups.
 */
public final class Hitboxes {
	/** How a humanoid stands: body yaw, head yaw, head pitch (degrees, Minecraft's), crouching. */
	public record Pose(float bodyYaw, float headYaw, float pitch, boolean crouch) {
		public Pose lerp(Pose o, double t) {
			return new Pose(lerpDeg(bodyYaw, o.bodyYaw, t), lerpDeg(headYaw, o.headYaw, t), (float) (pitch + (o.pitch - pitch) * t), t < 0.5 ? crouch : o.crouch);
		}
	}

	/** A part hit: its group and where the ray entered it. */
	public record Hit(String group, Vec3 at) {}

	private record Part(String group, double x0, double y0, double z0, double x1, double y1, double z1) {}

	/** Model pixels, from the feet up, x across the body, z forward. */
	private static final Part[] BODY = {
			new Part("legs", -4, 0, -2, 0, 12, 2), new Part("legs", 0, 0, -2, 4, 12, 2),
			new Part("stomach", -4, 12, -2, 4, 18, 2), new Part("chest", -4, 18, -2, 4, 24, 2),
			new Part("arms", -8, 12, -2, -4, 24, 2), new Part("arms", 4, 12, -2, 8, 24, 2)};
	private static final Part HEAD = new Part("head", -4, 0, -4, 4, 8, 4); // about the neck (0, 24, 0)
	private static final double MODEL_HEIGHT = 32;

	private Hitboxes() {}

	public static boolean humanoid(LivingEntity e) {
		return e instanceof net.minecraft.world.entity.player.Player || e instanceof net.minecraft.world.entity.monster.skeleton.AbstractSkeleton
				|| e instanceof net.minecraft.world.entity.monster.zombie.Zombie || e instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin
				|| e instanceof net.minecraft.world.entity.monster.illager.AbstractIllager;
	}

	public static Pose pose(LivingEntity e) {
		return new Pose(e.yBodyRot, e.getYHeadRot(), e.getXRot(), e.isCrouching());
	}

	/**
	 * The first part the ray from -> to passes through on an entity standing in box, each part grown by slack blocks
	 * (a claimed hit's tolerance); null if it passes through none.
	 */
	public static Hit trace(AABB box, Pose pose, Vec3 from, Vec3 to, double slack) {
		double u = box.getYsize() / (pose.crouch ? MODEL_HEIGHT * 0.83 : MODEL_HEIGHT); // blocks per model pixel
		Vec3 feet = new Vec3((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2);
		Hit best = null;
		double bestT = Double.MAX_VALUE;
		for (Part p : BODY) {
			double t = hit(p, feet, pose.bodyYaw, 0, Vec3.ZERO, u, from, to, slack);
			if (t < bestT) {
				bestT = t;
				best = new Hit(p.group, from.add(to.subtract(from).scale(t)));
			}
		}
		double t = hit(HEAD, feet, pose.headYaw, pose.pitch, new Vec3(0, 24, 0), u, from, to, slack);
		if (t < bestT) best = new Hit("head", from.add(to.subtract(from).scale(t)));
		return best;
	}

	/**
	 * Where along from -> to (0..1) the ray enters the part (turned by yaw about the feet, pitched about pivot), or
	 * MAX_VALUE: the ray goes into the part's own space and meets an axis-aligned box there.
	 */
	private static double hit(Part p, Vec3 feet, float yawDeg, float pitchDeg, Vec3 pivot, double u, Vec3 from, Vec3 to, double slack) {
		double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
		double[] a = local(from, feet, yaw, pitch, pivot, u), b = local(to, feet, yaw, pitch, pivot, u);
		double s = slack / u, t0 = 0, t1 = 1;
		double[] lo = {p.x0 - s, p.y0 - s, p.z0 - s}, hi = {p.x1 + s, p.y1 + s, p.z1 + s};
		for (int i = 0; i < 3; i++) {
			double d = b[i] - a[i];
			if (Math.abs(d) < 1e-9) {
				if (a[i] < lo[i] || a[i] > hi[i]) return Double.MAX_VALUE;
				continue;
			}
			double e0 = (lo[i] - a[i]) / d, e1 = (hi[i] - a[i]) / d;
			if (e0 > e1) {
				double x = e0;
				e0 = e1;
				e1 = x;
			}
			t0 = Math.max(t0, e0);
			t1 = Math.min(t1, e1);
			if (t0 > t1) return Double.MAX_VALUE;
		}
		return t0;
	}

	/**
	 * A world point in a part's space (model pixels): relative to the feet, turned back by the yaw (a Minecraft entity
	 * faces (-sin yaw, cos yaw)), then relative to the pivot, turned back by the pitch (positive looks down).
	 */
	private static double[] local(Vec3 w, Vec3 feet, double yaw, double pitch, Vec3 pivot, double u) {
		double dx = (w.x - feet.x) / u, dy = (w.y - feet.y) / u, dz = (w.z - feet.z) / u;
		double c = Math.cos(yaw), s = Math.sin(yaw);
		// world = x (cos, 0, sin) + z (-sin, 0, cos)
		double x = dx * c + dz * s, z = -dx * s + dz * c;
		double y = dy - pivot.y;
		x -= pivot.x;
		z -= pivot.z;
		if (pitch != 0) { // looking down tips the head forward: undo it about the neck
			double cp = Math.cos(pitch), sp = Math.sin(pitch);
			double y2 = y * cp + z * sp, z2 = -y * sp + z * cp;
			y = y2;
			z = z2;
		}
		return new double[] {x, y, z};
	}

	private static float lerpDeg(float a, float b, double t) {
		float d = ((b - a) % 360 + 540) % 360 - 180;
		return (float) (a + d * t);
	}
}
