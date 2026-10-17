package dev.strafemc.client.movement;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * The speed advancements, from your own u/s (the Source movement's horizontal speed, as the speed meter shows it): in the
 * air at 350 / 600 u/s, or surfing (in the air on a surf block at 300 u/s). Each is told to the server once per world.
 */
final class SpeedGoals {
	private static final double BHOP = 350, SPEED_DEMON = 600, SURF = 300;
	private static final java.util.Set<String> SENT = new java.util.HashSet<>();
	private static Object world;

	private SpeedGoals() {}

	static void check(SourceMovement sim) {
		var mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || sim.onGround || sim.onLadder || sim.noclip) return;
		if (mc.level != world) {
			world = mc.level;
			SENT.clear();
		}
		double speed = sim.horizontalSpeed();
		if (speed >= BHOP) send("bhop");
		if (speed >= SPEED_DEMON) send("speed_demon");
		if (speed >= SURF && !SENT.contains("surf") && onSurf(mc)) send("surf");
	}

	private static void send(String goal) {
		if (!SENT.add(goal) || !ClientPlayNetworking.canSend(dev.strafemc.net.StrafePayloads.Action.TYPE)) return;
		ClientPlayNetworking.send(new dev.strafemc.net.StrafePayloads.Action("speed_goal", goal));
	}

	/** A surf ramp or slope under (or just below) the player. */
	private static boolean onSurf(Minecraft mc) {
		var box = mc.player.getBoundingBox();
		for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX); x++)
			for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ); z++)
				for (int y = (int) Math.floor(box.minY) - 1; y <= (int) Math.floor(box.minY); y++) {
					var b = mc.level.getBlockState(new net.minecraft.core.BlockPos(x, y, z)).getBlock();
					if (b == dev.strafemc.weapon.WeaponItems.SURF_RAMP || b == dev.strafemc.weapon.WeaponItems.SURF_SLOPE
							|| b == dev.strafemc.weapon.WeaponItems.SURF_RAMP_LYING) return true;
				}
		return false;
	}
}
