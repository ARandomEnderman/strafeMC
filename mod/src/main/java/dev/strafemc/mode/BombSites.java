package dev.strafemc.mode;

import dev.strafemc.weapon.CsTeam;
import dev.strafemc.world.BombSiteBlock;
import dev.strafemc.world.SpawnBlock;
import dev.strafemc.world.SpawnPoints;
import net.minecraft.server.level.ServerPlayer;

/** Where the round modes' bomb sites and buy zones are: around the bomb site and spawn blocks. */
final class BombSites {
	/** How near a bomb site block the C4 can be planted: across and up/down. FLAGGED ours (CS2's sites are drawn per map). */
	static final double RADIUS = 5, HEIGHT = 3;

	private BombSites() {}

	static boolean near(ServerPlayer p) {
		for (SpawnPoints.Marker m : SpawnPoints.markers()) {
			if (!(m.state().getBlock() instanceof BombSiteBlock) || m.level() != p.level()) continue;
			double dx = p.getX() - (m.pos().getX() + 0.5), dz = p.getZ() - (m.pos().getZ() + 0.5), dy = p.getY() - (m.pos().getY() + 1);
			if (dx * dx + dz * dz <= RADIUS * RADIUS && Math.abs(dy) <= HEIGHT) return true;
		}
		return false;
	}

	/** Near one of the player's side's spawn blocks (or anyone's); anywhere when the world has none. */
	static boolean inBuyZone(ServerPlayer p, double radius) {
		SpawnBlock.Side own = CsTeam.isT(p) ? SpawnBlock.Side.T : SpawnBlock.Side.CT;
		boolean any = false;
		for (SpawnPoints.Marker m : SpawnPoints.markers()) {
			if (!(m.state().getBlock() instanceof SpawnBlock)) continue;
			any = true;
			SpawnBlock.Side s = m.state().getValue(SpawnBlock.SIDE);
			if (s != own && s != SpawnBlock.Side.ANY || m.level() != p.level()) continue;
			if (p.position().distanceToSqr(m.pos().getX() + 0.5, m.pos().getY() + 1, m.pos().getZ() + 0.5) <= radius * radius) return true;
		}
		return !any;
	}
}
