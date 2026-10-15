package dev.strafemc.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.Vec3;

/**
 * Bullet impacts on the blocks Source movement treats as smooth slopes (surf ramps 1 deep x 2 high, lying ramps 2 deep x
 * 1 high, surf slopes and bottom stairs at 45 degrees, with their inner and outer corners), so decals and impact effects
 * follow the slope instead of Minecraft's stepped hitbox. Same geometry as McWorld's solids: a ramp rises toward its
 * direction, normal (-fx*rise, run, -fz*rise); an inner corner is both ramps (the higher wins), an outer corner the part
 * both share (the lower wins).
 */
public final class SlopeHit {
	private SlopeHit() {}

	/** One ramp plane: footprint from (ox, oz), run deep along f (1 across), rise high, from by. */
	private record Ramp(double ox, double by, double oz, double run, double rise, Direction f) {
		double height(double x, double z) {
			double along = f.getStepX() > 0 ? x - ox : f.getStepX() < 0 ? ox + run - x : f.getStepZ() > 0 ? z - oz : oz + run - z;
			return by + rise * Math.max(0, Math.min(run, along)) / run;
		}

		Vec3 normal() {
			return new Vec3(-f.getStepX() * rise, run, -f.getStepZ() * rise).normalize();
		}
	}

	/** {point, normal} where the ray meets the block's slope, or null for other blocks / misses. */
	public static Vec3[] hit(Level level, BlockPos pos, Vec3 eye, Vec3 dir) {
		BlockState st = level.getBlockState(pos);
		int bx = pos.getX(), by = pos.getY(), bz = pos.getZ();
		Ramp[] ramps;
		boolean outer = false;
		if (st.getBlock() instanceof SurfRampBlock) {
			if (st.getValue(SurfRampBlock.HALF) == DoubleBlockHalf.UPPER) by -= 1;
			ramps = new Ramp[] {new Ramp(bx, by, bz, 1, 2, st.getValue(SurfRampBlock.FACING))};
		} else if (st.getBlock() instanceof SurfRampLyingBlock) {
			Direction f = st.getValue(SurfRampLyingBlock.FACING);
			boolean foot = st.getValue(SurfRampLyingBlock.PART) == BedPart.FOOT;
			int fx = foot ? bx : bx - f.getStepX(), fz = foot ? bz : bz - f.getStepZ(); // the low half
			ramps = new Ramp[] {new Ramp(Math.min(fx, fx + f.getStepX()), by, Math.min(fz, fz + f.getStepZ()), 2, 1, f)};
		} else if (st.getBlock() instanceof SurfSlopeBlock) {
			Direction d2 = SurfSlopeBlock.second(st);
			ramps = corner(bx, by, bz, st.getValue(SurfSlopeBlock.FACING), d2);
			outer = d2 != null && SurfSlopeBlock.outer(st);
		} else if (st.getBlock() instanceof StairBlock && st.getValue(StairBlock.HALF) == Half.BOTTOM) {
			Direction f = st.getValue(StairBlock.FACING); // stairs rise toward FACING
			StairsShape shape = st.getValue(StairBlock.SHAPE);
			Direction d2 = switch (shape) {
				case OUTER_LEFT, INNER_LEFT -> f.getCounterClockWise();
				case OUTER_RIGHT, INNER_RIGHT -> f.getClockWise();
				default -> null;
			};
			ramps = corner(bx, by, bz, f, d2);
			outer = shape == StairsShape.OUTER_LEFT || shape == StairsShape.OUTER_RIGHT;
		} else return null;
		// the footprint and height the ramps cover
		double x0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE, top = by;
		for (Ramp r : ramps) {
			boolean alongX = r.f.getStepX() != 0;
			x0 = Math.min(x0, r.ox);
			z0 = Math.min(z0, r.oz);
			x1 = Math.max(x1, r.ox + (alongX ? r.run : 1));
			z1 = Math.max(z1, r.oz + (alongX ? 1 : r.run));
			top = Math.max(top, r.by + r.rise);
		}
		double e = 0.02;
		Vec3 best = null, bestN = null;
		double bestT = Double.MAX_VALUE;
		for (Ramp r : ramps) {
			Vec3 n = r.normal();
			double denom = dir.dot(n);
			if (denom >= -1e-6) continue; // from behind or grazing
			Vec3 p0 = new Vec3(r.f.getStepX() < 0 ? r.ox + r.run : r.ox, r.by, r.f.getStepZ() < 0 ? r.oz + r.run : r.oz); // on the low edge
			double t = p0.subtract(eye).dot(n) / denom;
			if (t < 0 || t >= bestT) continue;
			Vec3 p = eye.add(dir.scale(t));
			if (p.x < x0 - e || p.x > x1 + e || p.z < z0 - e || p.z > z1 + e || p.y < by - e || p.y > top + e) continue;
			// on the real surface: the higher ramp at this spot (inner corner) or the lower one (outer corner)
			double surface = outer ? Double.MAX_VALUE : -Double.MAX_VALUE;
			for (Ramp o : ramps) surface = outer ? Math.min(surface, o.height(p.x, p.z)) : Math.max(surface, o.height(p.x, p.z));
			if (Math.abs(p.y - surface) > e) continue;
			best = p;
			bestN = n;
			bestT = t;
		}
		return best == null ? null : new Vec3[] {best, bestN};
	}

	/** A 45 degree slope in one block, plus the second slope of a corner. */
	private static Ramp[] corner(int bx, int by, int bz, Direction f, Direction d2) {
		Ramp a = new Ramp(bx, by, bz, 1, 1, f);
		return d2 == null ? new Ramp[] {a} : new Ramp[] {a, new Ramp(bx, by, bz, 1, 1, d2)};
	}
}
