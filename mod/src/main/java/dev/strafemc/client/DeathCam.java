package dev.strafemc.client;

import dev.strafemc.net.StrafePayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * CS2's death camera (freezecam): when you're killed, your view turns to your killer over spec_freeze_traveltime and
 * holds on them for spec_freeze_time before the death screen shows. FLAGGED: CS:GO's defaults (0.4 s, 3.0 s); CS2's
 * aren't in our cached cvars. A headshot death turns faster (half the travel time). FLAGGED ours.
 */
public final class DeathCam {
	private static final double TRAVEL = 0.4, HOLD = 3.0;
	private static int killer = -1;
	private static boolean headshot;
	private static long diedAt;
	private static Screen pending;
	private static float fromYaw, fromPitch;
	private static boolean released;

	private DeathCam() {}

	/** A killfeed line: remember who killed us (it arrives around our death). */
	public static void notice(StrafePayloads.KillNotice m) {
		var p = Minecraft.getInstance().player;
		if (p == null || m.victimId() != p.getId() || m.attackerId() == p.getId()) return;
		killer = m.attackerId();
		headshot = (m.flags() & dev.strafemc.weapon.KillFeed.HEADSHOT) != 0;
	}

	/** The death screen asked to open: hold it while the camera looks at the killer. True if it was held. */
	public static boolean hold(Screen deathScreen) {
		var mc = Minecraft.getInstance();
		if (released || killer < 0 || mc.level == null || mc.player == null || !(mc.level.getEntity(killer) instanceof Entity)) return false;
		if (pending == null) {
			pending = deathScreen;
			diedAt = System.nanoTime();
			fromYaw = mc.player.getYRot();
			fromPitch = mc.player.getXRot();
		}
		return true;
	}

	/** Every frame while dead: turn toward the killer; then let the death screen through. */
	public static void frame(Minecraft mc) {
		if (pending == null) {
			if (mc.player != null && mc.player.isAlive()) { // alive again: ready for the next death
				killer = -1;
				released = false;
			}
			return;
		}
		double t = (System.nanoTime() - diedAt) / 1e9;
		Entity k = mc.level == null ? null : mc.level.getEntity(killer);
		if (k != null && mc.player != null) {
			Vec3 eye = mc.player.getEyePosition(), to = k.getEyePosition().subtract(eye);
			float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z)), pitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
			double travel = headshot ? TRAVEL / 2 : TRAVEL, f = Math.min(1, t / travel);
			f = 1 - Math.pow(1 - f, 3);
			float dy = net.minecraft.util.Mth.wrapDegrees(yaw - fromYaw);
			mc.player.setYRot(fromYaw + (float) (dy * f));
			mc.player.setXRot(fromPitch + (float) ((pitch - fromPitch) * f));
			mc.player.yRotO = mc.player.getYRot();
			mc.player.xRotO = mc.player.getXRot();
		}
		if (t >= HOLD || k == null) {
			Screen s = pending;
			pending = null;
			released = true;
			mc.execute(() -> mc.setScreen(s)); // not from inside the frame being drawn
		}
	}
}
