package dev.strafemc.client.weapon;

import dev.strafemc.config.StrafeConfig;
import dev.strafemc.weapon.GrenadeEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Grenade practice (grenadecam): while it's on, a charged grenade shows the path it would fly in red (GrenadeClient),
 * and a grenade you throw takes the camera along until it goes off or comes to rest, then the view comes back. Only
 * where it gives nothing away: singleplayer, or a server with sv_cheats 1 (CS2's practice cvars are cheats).
 */
public final class GrenadeCam {
	private GrenadeCam() {}

	/** Back to you after the grenade has lain still this long (ticks), or at the latest after MAX_TICKS. */
	private static final int STILL_TICKS = 20, MAX_TICKS = 20 * 10;

	private static GrenadeEntity riding;
	private static Vec3 last;
	private static int still, ridden;
	/** The newest grenade id already ridden (or skipped), so each throw is picked up once. */
	private static int lastId = -1;

	public static boolean allowed() {
		Minecraft mc = Minecraft.getInstance();
		return mc.hasSingleplayerServer() || dev.strafemc.client.console.ConsoleCommands.cheats();
	}

	public static boolean active() {
		return StrafeConfig.grenadeCam && allowed();
	}

	/** The camera is riding a grenade (no hands drawn then). */
	public static boolean following() {
		return riding != null;
	}

	public static void register() {
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(GrenadeCam::tick);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> {
			riding = null;
			lastId = -1;
		});
	}

	private static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			riding = null;
			return;
		}
		if (riding == null) {
			if (!active() || mc.screen != null) return;
			for (Entity e : mc.level.entitiesForRendering())
				if (e instanceof GrenadeEntity g && g.getId() > lastId && g.getOwner() == mc.player && g.tickCount < 5) {
					lastId = g.getId();
					riding = g;
					last = g.position();
					still = ridden = 0;
					mc.setCameraEntity(g);
					break;
				}
			return;
		}
		Vec3 now = riding.position(), move = now.subtract(last);
		last = now;
		still = move.lengthSqr() < 1e-6 ? still + 1 : 0;
		if (move.lengthSqr() > 1e-6) { // look along the flight
			float yaw = (float) Math.toDegrees(Math.atan2(-move.x, move.z)), pitch = (float) -Math.toDegrees(Math.atan2(move.y, move.horizontalDistance()));
			riding.setYRot(yaw);
			riding.setXRot(pitch);
			riding.yRotO = yaw;
			riding.xRotO = pitch;
		}
		if (riding.isRemoved() || still >= STILL_TICKS || ++ridden >= MAX_TICKS || !active()) {
			riding = null;
			mc.setCameraEntity(mc.player);
		}
	}
}
