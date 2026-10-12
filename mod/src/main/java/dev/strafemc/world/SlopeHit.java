package dev.strafemc.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.Vec3;

/**
 * Bullet impacts on the blocks Source movement treats as smooth slopes (surf ramps: 1 deep x 2 high; straight bottom
 * stairs: 45 degrees), so decals and impact effects follow the slope instead of Minecraft's stepped hitbox.
 * Same geometry as HullTrace.Solid.ramp: rises toward FACING, normal (-fx*rise, run, -fz*rise).
 */
public final class SlopeHit {
	private SlopeHit() {}

	/** {point, normal} where the ray meets the block's slope, or null for other blocks / misses. */
	public static Vec3[] hit(Level level, BlockPos pos, Vec3 eye, Vec3 dir) {
		BlockState st = level.getBlockState(pos);
		int bx = pos.getX(), by = pos.getY(), bz = pos.getZ(), rise;
		Direction f;
		if (st.getBlock() instanceof SurfRampBlock) {
			if (st.getValue(SurfRampBlock.HALF) == DoubleBlockHalf.UPPER) by -= 1;
			rise = 2;
			f = st.getValue(SurfRampBlock.FACING);
		} else if (st.getBlock() instanceof SurfSlopeBlock) {
			rise = 1;
			f = st.getValue(SurfSlopeBlock.FACING);
		} else if (st.getBlock() instanceof StairBlock && st.getValue(StairBlock.HALF) == Half.BOTTOM
				&& st.getValue(StairBlock.SHAPE) == StairsShape.STRAIGHT) {
			rise = 1;
			f = st.getValue(StairBlock.FACING);
		} else return null;
		int fx = f.getStepX(), fz = f.getStepZ();
		Vec3 n = new Vec3(-fx * rise, 1, -fz * rise).normalize();
		// a point on the low edge of the slope
		Vec3 p0 = new Vec3(fx > 0 ? bx : fx < 0 ? bx + 1 : bx, by, fz > 0 ? bz : fz < 0 ? bz + 1 : bz);
		double denom = dir.dot(n);
		if (denom >= -1e-6) return null; // from behind or grazing
		double t = p0.subtract(eye).dot(n) / denom;
		if (t < 0) return null;
		Vec3 p = eye.add(dir.scale(t));
		double e = 0.02;
		if (p.x < bx - e || p.x > bx + 1 + e || p.z < bz - e || p.z > bz + 1 + e || p.y < by - e || p.y > by + rise + e) return null;
		return new Vec3[] {p, n};
	}
}
