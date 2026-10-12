package dev.strafemc.client.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Minecraft blocks as Source collision, in Hammer units. Every block keeps its exact collision boxes, except
 * straight bottom-half stairs, which become 45-degree ramp prisms (like the clip ramps over stairs in CS maps).
 */
public final class McWorld implements SourceMovement.World {
	private final Entity player;
	private final Level level;
	private final double s;
	private final CollisionContext ctx;

	public McWorld(Entity player, double unitsPerBlock) {
		this.player = player;
		this.level = player.level();
		this.s = unitsPerBlock;
		this.ctx = CollisionContext.of(player);
	}

	private static final int MAX_RAMP_ROW = 64;

	/** A lower surf-ramp half at (x, y, z) facing f (part of the same row). */
	private boolean sameRamp(BlockPos.MutableBlockPos tmp, int x, int y, int z, Direction f) {
		BlockState st = level.getBlockState(new BlockPos(x, y, z));
		return st.getBlock() instanceof dev.strafemc.world.SurfRampBlock
				&& st.getValue(dev.strafemc.world.SurfRampBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
				&& st.getValue(dev.strafemc.world.SurfRampBlock.FACING) == f;
	}

	/** Solids touching the given box (Hammer units). */
	private List<HullTrace.Solid> solids(double x0, double y0, double z0, double x1, double y1, double z1) {
		List<HullTrace.Solid> out = new ArrayList<>();
		int bx0 = floor(x0 / s), by0 = floor(y0 / s) - 1, bz0 = floor(z0 / s); // -1: fences/walls are 1.5 tall
		int bx1 = floor(x1 / s), by1 = floor(y1 / s), bz1 = floor(z1 / s);
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		java.util.Set<Long> ramps = new java.util.HashSet<>();
		for (int bx = bx0; bx <= bx1; bx++)
			for (int by = by0; by <= by1; by++)
				for (int bz = bz0; bz <= bz1; bz++) {
					m.set(bx, by, bz);
					BlockState st = level.getBlockState(m);
					if (st.isAir()) continue;
					if (st.getBlock() instanceof LadderBlock) continue; // CS ladders aren't solid; the wall behind still is
					if (st.getBlock() instanceof dev.strafemc.world.SurfRampBlock) {
						int baseY = st.getValue(dev.strafemc.world.SurfRampBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER ? by : by - 1;
						if (!ramps.contains(BlockPos.asLong(bx, baseY, bz))) {
							// Merge the whole row of side-by-side ramps (same facing, same height) into one prism: separate prisms
							// leave seams whose side faces the hull catches while surfing across them (Source "ramp bugs").
							Direction f = st.getValue(dev.strafemc.world.SurfRampBlock.FACING);
							int ax = f.getStepX() != 0 ? 0 : 1, az = 1 - ax; // across the ramp
							int lo = 0, hi = 0;
							while (lo > -MAX_RAMP_ROW && sameRamp(m, bx + (lo - 1) * ax, baseY, bz + (lo - 1) * az, f)) lo--;
							while (hi < MAX_RAMP_ROW && sameRamp(m, bx + (hi + 1) * ax, baseY, bz + (hi + 1) * az, f)) hi++;
							for (int k = lo; k <= hi; k++) ramps.add(BlockPos.asLong(bx + k * ax, baseY, bz + k * az));
							out.add(HullTrace.Solid.ramp((bx + lo * ax) * s, baseY * s, (bz + lo * az) * s, (hi - lo + 1) * s, s, 2 * s,
									f.getStepX(), f.getStepZ()));
						}
						continue;
					}
					if (st.getBlock() instanceof dev.strafemc.world.SurfSlopeBlock) {
						Direction f = st.getValue(dev.strafemc.world.SurfSlopeBlock.FACING);
						out.add(HullTrace.Solid.ramp(bx * s, by * s, bz * s, s, f.getStepX(), f.getStepZ()));
						continue;
					}
					if (st.getBlock() instanceof StairBlock && st.getValue(StairBlock.HALF) == Half.BOTTOM
							&& st.getValue(StairBlock.SHAPE) == StairsShape.STRAIGHT) {
						Direction f = st.getValue(StairBlock.FACING); // the tall side: stairs rise toward FACING
						out.add(HullTrace.Solid.ramp(bx * s, by * s, bz * s, s, f.getStepX(), f.getStepZ()));
						continue;
					}
					VoxelShape vs = st.getCollisionShape(level, m, ctx);
					if (vs.isEmpty()) continue;
					for (AABB b : vs.toAabbs())
						out.add(HullTrace.Solid.box((bx + b.minX) * s, (by + b.minY) * s, (bz + b.minZ) * s,
								(bx + b.maxX) * s, (by + b.maxY) * s, (bz + b.maxZ) * s));
				}
		AABB area = new AABB(x0 / s, y0 / s, z0 / s, x1 / s, y1 / s, z1 / s);
		for (VoxelShape vs : level.getEntityCollisions(player, area))
			for (AABB b : vs.toAabbs())
				out.add(HullTrace.Solid.box(b.minX * s, b.minY * s, b.minZ * s, b.maxX * s, b.maxY * s, b.maxZ * s));
		return out;
	}

	@Override
	public HullTrace.Result trace(double x, double y, double z, double hw, double h, double dx, double dy, double dz) {
		double pad = 1;
		List<HullTrace.Solid> list = solids(
				Math.min(x, x + dx) - hw - pad, Math.min(y, y + dy) - pad, Math.min(z, z + dz) - hw - pad,
				Math.max(x, x + dx) + hw + pad, Math.max(y, y + dy) + h + pad, Math.max(z, z + dz) + hw + pad);
		return HullTrace.trace(x, y, z, hw, h, dx, dy, dz, list);
	}

	@Override
	public boolean free(double x, double y, double z, double hw, double h) {
		return !HullTrace.overlaps(x, y, z, hw, h, solids(x - hw, y, z - hw, x + hw, y + h, z + hw));
	}

	@Override
	public double[] ladder(double x, double y, double z, double hw, double h, boolean volume) {
		double r = 2; // Source checks a couple of units around the hull for ladder contents
		double below = 0;
		AABB hull = new AABB((x - hw - r) / s, (y - below) / s, (z - hw - r) / s, (x + hw + r) / s, (y + h) / s, (z + hw + r) / s);
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int bx = floor(hull.minX); bx <= floor(hull.maxX); bx++)
			for (int by = floor(hull.minY); by <= floor(hull.maxY); by++)
				for (int bz = floor(hull.minZ); bz <= floor(hull.maxZ); bz++) {
					m.set(bx, by, bz);
					BlockState st = level.getBlockState(m);
					if (!(st.getBlock() instanceof LadderBlock)) continue;
					Direction f = st.getValue(LadderBlock.FACING); // ladder faces away from its wall
					double[] n = {f.getStepX(), 0, f.getStepZ()};
					if (volume) {
						if (new AABB(bx, by, bz, bx + 1, by + 1, bz + 1).intersects(hull)) return n;
					} else {
						for (AABB pl : st.getShape(level, m).toAabbs()) if (pl.move(bx, by, bz).intersects(hull)) return n;
					}
				}
		return null;
	}

	@Override
	public int waterLevel(double x, double y, double z, double h) {
		double[] probes = {y + 2, y + h * 0.5, y + h - 8}; // feet, waist, eyes (units)
		int lvl = 0;
		for (double py : probes) {
			BlockPos bp = BlockPos.containing(x / s, py / s, z / s);
			var fluid = level.getFluidState(bp);
			if (fluid.isEmpty() || !fluid.is(net.minecraft.tags.FluidTags.WATER)) break;
			if (py / s > bp.getY() + fluid.getHeight(level, bp)) break;
			lvl++;
		}
		return lvl;
	}

	@Override
	public double[] surface(double x, double y, double z, double hw) {
		BlockPos below = BlockPos.containing(x / s, (y - 1) / s, z / s);
		BlockState st = level.getBlockState(below);
		if (st.isAir()) return SourceMovement.NORMAL_SURFACE;
		var b = st.getBlock();
		// MC block friction 0.6 = normal ground. Source has no ice; mapping MC "slipperiness" to Source surface friction
		// (1 - f) / (1 - 0.6) is our own choice: ice 0.98 -> 0.05, blue ice -> 0.03, slime 0.8 -> 0.5.
		double sf = Math.max(0.0, Math.min(1.0, (1.0 - b.getFriction()) / 0.4));
		return new double[] {sf, b.getSpeedFactor(), b.getJumpFactor(), b instanceof net.minecraft.world.level.block.SlimeBlock ? 1 : 0};
	}

	@Override
	public double[] stuck(double x, double y, double z, double hw, double h) {
		AABB box = new AABB((x - hw) / s, y / s, (z - hw) / s, (x + hw) / s, (y + h) / s, (z + hw) / s);
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int bx = floor(box.minX); bx <= floor(box.maxX); bx++)
			for (int by = floor(box.minY); by <= floor(box.maxY); by++)
				for (int bz = floor(box.minZ); bz <= floor(box.maxZ); bz++) {
					var b = level.getBlockState(m.set(bx, by, bz)).getBlock();
					// MC's makeStuckInBlock multipliers for these blocks
					if (b instanceof net.minecraft.world.level.block.WebBlock) return new double[] {0.25, 0.05};
					if (b instanceof net.minecraft.world.level.block.PowderSnowBlock) return new double[] {0.9, 1.5};
					if (b instanceof net.minecraft.world.level.block.SweetBerryBushBlock) return new double[] {0.8, 0.75};
				}
		return null;
	}

	private static int floor(double v) {
		return (int) Math.floor(v);
	}
}
