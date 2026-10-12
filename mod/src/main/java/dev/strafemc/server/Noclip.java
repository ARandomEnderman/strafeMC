package dev.strafemc.server;

import net.minecraft.world.entity.player.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** Players currently noclipping. Server side is authoritative; the client registers its own check for the local player. */
public final class Noclip {
	public static final Set<UUID> SERVER = ConcurrentHashMap.newKeySet();
	/** Set by the client so the shared Player.tick hook also knows about the local player. */
	public static volatile Predicate<Player> clientCheck = p -> false;

	private Noclip() {}

	/** Players driven by Source movement (announced by their client). */
	public static final Set<UUID> SOURCE = ConcurrentHashMap.newKeySet();
	public static volatile Predicate<Player> clientSource = p -> false;

	/**
	 * Source-movement players may stand inside the corner of a stair block (the ramp cuts through it); give them
	 * noPhysics while they touch stairs so neither the client push-out nor the server move check fights the ramp.
	 */
	public static boolean onStairs(Player p) {
		boolean source = p.level().isClientSide() ? clientSource.test(p) : StrafeSettings.sourceMovement();
		if (!source) return false;
		var box = p.getBoundingBox().inflate(0.45, 0.1, 0.45);
		var m = new net.minecraft.core.BlockPos.MutableBlockPos();
		for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX); x++)
			for (int y = (int) Math.floor(box.minY); y <= (int) Math.floor(box.maxY); y++)
				for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ); z++)
					if (p.level().getBlockState(m.set(x, y, z)).getBlock() instanceof net.minecraft.world.level.block.StairBlock
							|| p.level().getBlockState(m).getBlock() instanceof dev.strafemc.world.SurfRampBlock
							|| p.level().getBlockState(m).getBlock() instanceof dev.strafemc.world.SurfSlopeBlock) return true;
		return false;
	}

	/** Is this player driven by Source movement (server: announced by its client; client: the local controller)? */
	public static boolean usesSource(Player p) {
		return p.level().isClientSide() ? clientSource.test(p) : StrafeSettings.sourceMovement(); // the server's setting, not the client's word
	}

	public static boolean active(Player p) {
		return p.level().isClientSide() ? clientCheck.test(p) : SERVER.contains(p.getUUID());
	}
}
