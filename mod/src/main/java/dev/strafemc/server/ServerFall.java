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
 * Bunny hops touch the ground for less than a server tick, so the server often never sees the player grounded: a fall
 * also ends when the player starts rising again after falling, if the lowest point was within one tick's travel of the
 * ground (a hidden landing + jump). Without that, a whole hop chain counted as one fall from its highest point.
 */
public final class ServerFall {
	private static final class St {
		double prevY = Double.NaN, lastY = Double.NaN, apexY, minY, prevDy;
		boolean air, minNearGround, minOnSlime;
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
				s.lastY = y;
				s.prevDy = 0;
				continue;
			}
			double dy = Double.isNaN(s.lastY) ? 0 : y - s.lastY;
			s.lastY = y;
			boolean grounded = !p.level().noCollision(p, p.getBoundingBox().move(0, -0.06, 0));
			if (!grounded) {
				if (!s.air) { // took off: a new fall starts here
					s.apexY = s.minY = y;
					s.minNearGround = false;
				}
				s.air = true;
				if (s.prevDy < 0 && dy > 1e-4 && s.minNearGround) { // was falling, now rising near the ground: landed and jumped between ticks
					land(p, s, s.minY, s.minOnSlime, units, g, maxVel);
					s.apexY = s.minY = y;
					s.minNearGround = false;
				}
				s.apexY = Math.max(s.apexY, y);
				if (y < s.minY || dy < 0 && y <= s.minY) {
					s.minY = y;
					// within one tick's fall of the ground (plus a margin): touching down before the next sample is possible
					double reach = Math.abs(dy) + 0.1;
					s.minNearGround = !p.level().noCollision(p, p.getBoundingBox().move(0, -reach, 0));
					s.minOnSlime = p.level().getBlockState(BlockPos.containing(p.getX(), p.getBoundingBox().minY - reach, p.getZ())).getBlock() instanceof SlimeBlock;
				}
				if (dy != 0) s.prevDy = dy;
				continue;
			}
			s.prevDy = 0;
			if (!s.air) continue;
			s.air = false;
			BlockPos under = BlockPos.containing(p.getX(), p.getBoundingBox().minY - 0.06, p.getZ());
			land(p, s, y, p.level().getBlockState(under).getBlock() instanceof SlimeBlock, units, g, maxVel);
		}
	}

	/** A landing at landY after falling from the stretch's highest point: CS2 fall damage from the impact speed. */
	private static void land(ServerPlayer p, St s, double landY, boolean slime, double units, double g, double maxVel) {
		double height = (s.apexY - landY) * units;
		if (height <= 0) return;
		if (slime && !p.isShiftKeyDown()) return; // bounces like vanilla
		double impact = Math.min(maxVel, Math.sqrt(2 * g * height));
		CsFallDamage.apply(p, impact, cvar("sv_falldamage_scale", 1));
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
