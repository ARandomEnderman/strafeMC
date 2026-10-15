package dev.strafemc.weapon;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Shoots rays at a standing player's body-part hitboxes and prints the parts hit: gradlew vmCheck -PvmMain=dev.strafemc.weapon.HitboxCheck */
public final class HitboxCheck {
	public static void main(String[] a) {
		AABB box = new AABB(-0.3, 0, -0.3, 0.3, 1.8, 0.3);
		// facing the shooter: yaw 180 faces -z, the shooter stands at z = -5
		for (float pitch : new float[] {0, 60}) {
			Hitboxes.Pose pose = new Hitboxes.Pose(180, 180, pitch, false);
			System.out.println("pitch " + pitch);
			for (double y = 0.1; y < 1.9; y += 0.1)
				for (double x : new double[] {0, 0.12, 0.29, 0.4}) {
					Vec3 from = new Vec3(x, y, -5), to = new Vec3(x, y, 5);
					Hitboxes.Hit h = Hitboxes.trace(box, pose, from, to, 0);
					System.out.printf("  y %.1f x %.2f -> %s%n", y, x, h == null ? "miss" : h.group() + String.format(" at z %.2f", h.at().z));
				}
		}
		// from the side (body turned 90 degrees): the torso is 4 px deep
		Hitboxes.Pose side = new Hitboxes.Pose(90, 90, 0, false);
		for (double z : new double[] {0, 0.1, 0.2})
			System.out.println("side z " + z + " chest -> " + Hitboxes.trace(box, side, new Vec3(-5, 1.2, z), new Vec3(5, 1.2, z), 0));
	}
}
