package dev.strafemc.server;

import dev.strafemc.bridge.Cs2Bridge;
import dev.strafemc.config.StrafeConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.WebBlock;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CS2 fall damage measured by the server, not reported by the client (a modified client could otherwise skip it).
 * The server tracks the highest point of each airborne stretch and, on landing, turns the fall height into an impact
 * speed with Source gravity: v = sqrt(2 * sv_gravity * height), capped at sv_maxvelocity. Height, not speed between
 * ticks: client position updates arrive unevenly (two in one tick, or late), which made per-tick speeds jump around.
 * Anything that stops a fall resets the height: water, ladders, powder snow / cobwebs / berry bushes (they cap vertical
 * speed), flight, noclip. Slime bounces (unless sneaking) and teleports never count.
 */
public final class ServerFall {
	private static final class St {
		double prevY = Double.NaN, apexY;
		boolean air;
	}

	private static final Map<UUID, St> STATE = new HashMap<>();

	private ServerFall() {}

	public static void tick(MinecraftServer server) {
		double units = StrafeConfig.unitsPerBlock, g = cvar("sv_gravity", 800), maxVel = cvar("sv_maxvelocity", 3500);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			St s = STATE.computeIfAbsent(p.getUUID(), u -> new St());
			double y = p.getY();
			boolean teleport = !Double.isNaN(s.prevY) && Math.abs(y - s.prevY) * units > maxVel; // > 20 ticks' worth of max speed in one
			s.prevY = y;
			if (!StrafeSettings.sourceMovement() || Noclip.active(p) || p.isSpectator() || p.getAbilities().flying || p.isPassenger()
					|| p.isFallFlying() || p.isInWater() || p.onClimbable() || teleport || inSlowingBlock(p)) {
				s.air = false; // nothing to land from (or the fall was stopped / is a teleport)
				continue;
			}
			boolean grounded = !p.level().noCollision(p, p.getBoundingBox().move(0, -0.06, 0));
			if (!grounded) {
				if (!s.air) s.apexY = y;
				s.air = true;
				s.apexY = Math.max(s.apexY, y);
				continue;
			}
			if (!s.air) continue;
			s.air = false;
			double height = (s.apexY - y) * units;
			if (height <= 0) continue;
			BlockPos under = BlockPos.containing(p.getX(), p.getBoundingBox().minY - 0.06, p.getZ());
			if (p.level().getBlockState(under).getBlock() instanceof SlimeBlock && !p.isShiftKeyDown()) continue; // bounces like vanilla
			double impact = Math.min(maxVel, Math.sqrt(2 * g * height));
			CsFallDamage.apply(p, impact, cvar("sv_falldamage_scale", 1));
		}
	}

	/** Powder snow, cobweb, berry bush: Source movement caps vertical speed inside them, so a fall through them ends there. */
	private static boolean inSlowingBlock(ServerPlayer p) {
		var box = p.getBoundingBox();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX); x++)
			for (int y = (int) Math.floor(box.minY); y <= (int) Math.floor(box.maxY); y++)
				for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ); z++) {
					Block b = p.level().getBlockState(m.set(x, y, z)).getBlock();
					if (b instanceof PowderSnowBlock || b instanceof WebBlock || b instanceof SweetBerryBushBlock) return true;
				}
		return false;
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
