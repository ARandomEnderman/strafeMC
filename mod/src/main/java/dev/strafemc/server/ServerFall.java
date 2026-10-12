package dev.strafemc.server;

import dev.strafemc.bridge.Cs2Bridge;
import dev.strafemc.config.StrafeConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.SlimeBlock;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CS2 fall damage measured by the server, not reported by the client (a modified client could otherwise skip it).
 * For players on Source movement, the server tracks their vertical speed from their positions every tick. Landing
 * after a fall applies CsFallDamage with the impact speed: the last airborne tick's speed plus one tick of gravity,
 * since 20 Hz positions lag the true touchdown speed. Slime bounces, water, ladders, noclip, flight and teleports
 * (jumps faster than sv_maxvelocity) never count.
 */
public final class ServerFall {
	private static final class St {
		double prevY = Double.NaN, airSpeed;
		boolean air;
	}

	private static final Map<UUID, St> STATE = new HashMap<>();

	private ServerFall() {}

	public static void tick(MinecraftServer server) {
		double units = StrafeConfig.unitsPerBlock, g = cvar("sv_gravity", 800), maxVel = cvar("sv_maxvelocity", 3500);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			St s = STATE.computeIfAbsent(p.getUUID(), u -> new St());
			double y = p.getY(), dy = Double.isNaN(s.prevY) ? 0 : y - s.prevY;
			s.prevY = y;
			double speed = -dy * 20 * units; // u/s, + = falling
			if (!StrafeSettings.sourceMovement() || Noclip.active(p) || p.isSpectator() || p.getAbilities().flying || p.isPassenger()
					|| p.isFallFlying() || p.isInWater() || p.onClimbable() || Math.abs(speed) > maxVel * 1.5) {
				s.air = false; // not a Source fall (or a teleport): nothing to land from
				continue;
			}
			boolean grounded = !p.level().noCollision(p, p.getBoundingBox().move(0, -0.06, 0));
			if (!grounded) {
				s.air = true;
				s.airSpeed = speed;
				continue;
			}
			if (!s.air) continue;
			s.air = false;
			if (s.airSpeed <= 0) continue; // was still rising
			BlockPos under = BlockPos.containing(p.getX(), p.getBoundingBox().minY - 0.06, p.getZ());
			if (p.level().getBlockState(under).getBlock() instanceof SlimeBlock && !p.isShiftKeyDown()) continue; // bounces like vanilla
			double impact = s.airSpeed + g / 20; // one more tick of gravity to touchdown
			CsFallDamage.apply(p, impact, cvar("sv_falldamage_scale", 1));
		}
	}

	public static void forget(ServerPlayer p) {
		STATE.remove(p.getUUID());
	}

	private static double cvar(String n, double d) {
		String v = Cs2Bridge.INSTANCE.cvar(n);
		try {
			return v == null ? d : Double.parseDouble(v);
		} catch (NumberFormatException e) {
			return d;
		}
	}
}
