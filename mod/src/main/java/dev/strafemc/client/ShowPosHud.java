package dev.strafemc.client;

import dev.strafemc.client.movement.MovementController;
import dev.strafemc.config.StrafeConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * cl_showpos 1: CS-style readout in the top-left corner (name / pos / ang / vel) in Hammer units and CS axes
 * (x = MC x, y = -MC z, z = MC y; yaw 0 = +x). vel is the horizontal speed, like the CS speedometer.
 */
public final class ShowPosHud {
	private ShowPosHud() {}

	public static void register() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("strafemc", "showpos"), (g, delta) -> {
			Minecraft mc = Minecraft.getInstance();
			var p = mc.player;
			if (p == null || mc.options.hideGui || !StrafeConfig.showSpeed) return;
			double u = StrafeConfig.unitsPerBlock;
			double yaw = Math.toRadians(p.getYRot());
			double csYaw = Math.toDegrees(Math.atan2(-Math.cos(yaw), -Math.sin(yaw)));
			MovementController m = MovementController.INSTANCE;
			double vel = m.active() ? m.horizontalSpeed() : Math.hypot(p.getDeltaMovement().x, p.getDeltaMovement().z) * 20 * u;
			String[] lines = {
					"name: " + p.getName().getString(),
					String.format("pos:  %.2f %.2f %.2f", p.getX() * u, -p.getZ() * u, p.getY() * u),
					String.format("ang:  %.2f %.2f %.2f", p.getXRot(), csYaw, 0.0),
					String.format("vel:  %.2f", vel)};
			int y = 4;
			for (String l : lines) {
				g.text(mc.font, l, 4, y, 0xFFFFFFFF, true);
				y += 10;
			}
		});
	}
}
