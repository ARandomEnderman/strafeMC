package dev.strafemc.mode;

import dev.strafemc.weapon.CsTeam;
import dev.strafemc.world.BombSiteBlock;
import dev.strafemc.world.SpawnBlock;
import dev.strafemc.world.SpawnPoints;
import net.minecraft.server.level.ServerPlayer;

/** Where the round modes' bomb sites and buy zones are: around the bomb site and spawn blocks. */
final class BombSites {
	/**
	 * A bomb site block's plant area: a box on top of it, strafemc_bombsite blocks wide and deep (5) around it and HEIGHT
	 * tall from its top, so the block can sit hidden under the floor. FLAGGED ours: CS2 maps draw their sites.
	 */
	static final double HEIGHT = 6;

	/** Whether an entity's feet are in a bomb site block's box. */
	static boolean inBox(net.minecraft.world.entity.Entity e, net.minecraft.core.BlockPos block) {
		double half = dev.strafemc.server.StrafeSettings.bombSite() / 2.0, dy = e.getY() - (block.getY() + 1);
		return Math.abs(e.getX() - (block.getX() + 0.5)) <= half && Math.abs(e.getZ() - (block.getZ() + 0.5)) <= half && dy >= -0.01 && dy < HEIGHT;
	}

	private BombSites() {}

	static boolean near(net.minecraft.world.entity.Entity p) {
		if (Zones.any(Zones.Type::site)) return Zones.in(p, Zones.Type::site) != null; // drawn sites, when the world has them
		for (SpawnPoints.Marker m : SpawnPoints.markers()) {
			if (m.state().getBlock() instanceof BombSiteBlock && m.level() == p.level() && inBox(p, m.pos())) return true;
		}
		return false;
	}

	/** The nearest bomb site block's top to a point (null: none in that level). */
	static net.minecraft.world.phys.Vec3 nearest(net.minecraft.world.entity.Entity e) {
		net.minecraft.world.phys.Vec3 best = null;
		String dim = e.level().dimension().identifier().toString();
		for (Zones.Zone z : Zones.all())
			if (z.type().site() && z.dim().equals(dim) && (best == null || z.floorCentre().distanceToSqr(e.position()) < best.distanceToSqr(e.position())))
				best = z.floorCentre();
		if (best != null) return best;
		for (SpawnPoints.Marker m : SpawnPoints.markers()) {
			if (!(m.state().getBlock() instanceof BombSiteBlock) || m.level() != e.level()) continue;
			var top = SpawnPoints.standOn(m.level(), m.pos()); // a buried block: the floor over it
			if (best == null || top.distanceToSqr(e.position()) < best.distanceToSqr(e.position())) best = top;
		}
		return best;
	}

	/** Which bomb site a point is at ("A" or "B"), or "" (drawn sites, else the nearest site block in range). */
	static String siteLetter(net.minecraft.world.entity.Entity e) {
		Zones.Zone z = Zones.in(e, Zones.Type::site);
		if (z != null) return z.type() == Zones.Type.SITE_A ? "A" : "B";
		for (SpawnPoints.Marker m : SpawnPoints.markers()) {
			if (m.state().getBlock() instanceof BombSiteBlock && m.level() == e.level() && inBox(e, m.pos()))
				return m.state().getValue(BombSiteBlock.SITE) == BombSiteBlock.Site.A ? "A" : "B";
		}
		return "";
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
