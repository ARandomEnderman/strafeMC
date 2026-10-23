package dev.strafemc.mode;

import dev.strafemc.weapon.CsTeam;
import dev.strafemc.world.SpawnBlock;
import dev.strafemc.world.SpawnPoints;
import net.minecraft.server.level.ServerPlayer;

/** Where the round modes' bomb sites (the zones drawn with the Zone Tool) and buy zones are. */
final class BombSites {
	private BombSites() {}

	static boolean near(net.minecraft.world.entity.Entity p) {
		return Zones.in(p, Zones.Type::site) != null;
	}

	/** The middle of the nearest drawn bomb site's floor (null: none in that level). */
	static net.minecraft.world.phys.Vec3 nearest(net.minecraft.world.entity.Entity e) {
		net.minecraft.world.phys.Vec3 best = null;
		String dim = e.level().dimension().identifier().toString();
		for (Zones.Zone z : Zones.all())
			if (z.type().site() && z.dim().equals(dim) && (best == null || z.floorCentre().distanceToSqr(e.position()) < best.distanceToSqr(e.position())))
				best = z.floorCentre();
		return best;
	}

	/** Which bomb site a point is at ("A" or "B"), or "". */
	static String siteLetter(net.minecraft.world.entity.Entity e) {
		Zones.Zone z = Zones.in(e, Zones.Type::site);
		return z == null ? "" : z.type() == Zones.Type.SITE_A ? "A" : "B";
	}

	/** Near one of the player's side's spawn blocks (or anyone's); anywhere when the world has none. */
	static boolean inBuyZone(ServerPlayer p, double radius) {
		Zones.Type ownZone = CsTeam.isT(p) ? Zones.Type.BUY_T : Zones.Type.BUY_CT;
		if (Zones.any(t -> t == ownZone)) return Zones.in(p, t -> t == ownZone) != null; // drawn buy zones, when the side has them
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
