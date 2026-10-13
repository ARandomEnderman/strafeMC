package dev.strafemc.world;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Surf slope: one block, a 45 degree slope rising toward FACING (like a straight stair), with the surf ramp's look.
 * Next to another slope at a right angle it becomes a corner like stairs do: an inner corner (both slopes, the higher
 * one wins) or an outer corner (the pyramid tip, the lower one wins). Source movement collides with the exact smooth
 * surfaces (normal.y 0.707 >= sv_standable_normal 0.7: walkable, like stairs); vanilla collision/outline is a stepped
 * approximation.
 */
public class SurfSlopeBlock extends HorizontalDirectionalBlock {
	public static final MapCodec<SurfSlopeBlock> CODEC = simpleCodec(SurfSlopeBlock::new);
	public static final EnumProperty<StairsShape> SHAPE = BlockStateProperties.STAIRS_SHAPE;
	private static final Map<String, VoxelShape> SHAPES = new ConcurrentHashMap<>();
	private static final int GRID = 8; // vanilla shape: 8 x 8 columns of 2 px

	public SurfSlopeBlock(Properties p) {
		super(p);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(SHAPE, StairsShape.STRAIGHT));
	}

	@Override
	protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
		b.add(FACING, SHAPE);
	}

	/** The second direction of a corner (the slope it meets), or null for a straight slope. */
	public static Direction second(BlockState s) {
		Direction f = s.getValue(FACING);
		return switch (s.getValue(SHAPE)) {
			case OUTER_LEFT, INNER_LEFT -> f.getCounterClockWise();
			case OUTER_RIGHT, INNER_RIGHT -> f.getClockWise();
			default -> null;
		};
	}

	public static boolean outer(BlockState s) {
		StairsShape sh = s.getValue(SHAPE);
		return sh == StairsShape.OUTER_LEFT || sh == StairsShape.OUTER_RIGHT;
	}

	/** How far along d (0 at the low side, 1 at the high side) the point (x, z) in 0..1 block coordinates is. */
	public static double along(Direction d, double x, double z) {
		return switch (d) {
			case EAST -> x;
			case WEST -> 1 - x;
			case SOUTH -> z;
			default -> 1 - z; // NORTH
		};
	}

	/** Surface height (0..1) at (x, z) in 0..1 block coordinates. */
	public static double height(BlockState s, double x, double z) {
		double h = along(s.getValue(FACING), x, z);
		Direction d2 = second(s);
		if (d2 == null) return h;
		double h2 = along(d2, x, z);
		return outer(s) ? Math.min(h, h2) : Math.max(h, h2);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		BlockState s = defaultBlockState().setValue(FACING, ctx.getHorizontalDirection()); // rises away from you, like stairs
		return s.setValue(SHAPE, shape(s, ctx.getLevel(), ctx.getClickedPos()));
	}

	@Override
	protected BlockState updateShape(BlockState s, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction dir, BlockPos nPos, BlockState n,
									 RandomSource rnd) {
		return dir.getAxis().isHorizontal() ? s.setValue(SHAPE, shape(s, level, pos)) : super.updateShape(s, level, ticks, pos, dir, nPos, n, rnd);
	}

	/** Like vanilla stairs: a perpendicular slope on the high side makes an outer corner, on the low side an inner one. */
	private static StairsShape shape(BlockState s, BlockGetter level, BlockPos pos) {
		Direction f = s.getValue(FACING);
		BlockState high = level.getBlockState(pos.relative(f));
		if (high.getBlock() instanceof SurfSlopeBlock) {
			Direction d = high.getValue(FACING);
			if (d.getAxis() != f.getAxis() && notSame(s, level, pos, d.getOpposite()))
				return d == f.getCounterClockWise() ? StairsShape.OUTER_LEFT : StairsShape.OUTER_RIGHT;
		}
		BlockState low = level.getBlockState(pos.relative(f.getOpposite()));
		if (low.getBlock() instanceof SurfSlopeBlock) {
			Direction d = low.getValue(FACING);
			if (d.getAxis() != f.getAxis() && notSame(s, level, pos, d))
				return d == f.getCounterClockWise() ? StairsShape.INNER_LEFT : StairsShape.INNER_RIGHT;
		}
		return StairsShape.STRAIGHT;
	}

	private static boolean notSame(BlockState s, BlockGetter level, BlockPos pos, Direction side) {
		BlockState o = level.getBlockState(pos.relative(side));
		return !(o.getBlock() instanceof SurfSlopeBlock) || o.getValue(FACING) != s.getValue(FACING);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
		return SHAPES.computeIfAbsent(state.getValue(FACING) + "/" + state.getValue(SHAPE), k -> {
			VoxelShape v = Shapes.empty();
			for (int i = 0; i < GRID; i++)
				for (int j = 0; j < GRID; j++) {
					double x = (i + 0.5) / GRID, z = (j + 0.5) / GRID;
					double h = Math.max(1, Math.round(height(state, x, z) * 16));
					v = Shapes.or(v, Block.box(i * 16.0 / GRID, 0, j * 16.0 / GRID, (i + 1) * 16.0 / GRID, h, (j + 1) * 16.0 / GRID));
				}
			return v.optimize();
		});
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
		return getShape(state, level, pos, ctx);
	}
}
