package dev.strafemc.world;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * Surf slope: one block, a 45 degree slope rising toward FACING (like a straight stair), with the surf ramp's look.
 * Source movement collides with the exact smooth slope (normal.y 0.707 >= sv_standable_normal 0.7: walkable, like
 * stairs); vanilla collision/outline is a 16-step approximation.
 */
public class SurfSlopeBlock extends HorizontalDirectionalBlock {
	public static final MapCodec<SurfSlopeBlock> CODEC = simpleCodec(SurfSlopeBlock::new);
	private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

	static {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			VoxelShape s = Shapes.empty();
			for (int i = 0; i < 16; i++) s = Shapes.or(s, slice(d, i, i + 1));
			SHAPES.put(d, s.optimize());
		}
	}

	/** One 1px-deep slice at u = i along the rise direction, full width, given height in px. */
	private static VoxelShape slice(Direction d, int i, int h) {
		return switch (d) {
			case EAST -> Block.box(i, 0, 0, i + 1, h, 16);
			case WEST -> Block.box(15 - i, 0, 0, 16 - i, h, 16);
			case SOUTH -> Block.box(0, 0, i, 16, h, i + 1);
			default -> Block.box(0, 0, 15 - i, 16, h, 16 - i); // NORTH
		};
	}

	public SurfSlopeBlock(Properties p) {
		super(p);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
		b.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection()); // rises away from you, like stairs
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
		return SHAPES.get(state.getValue(FACING));
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
		return getShape(state, level, pos, ctx);
	}
}
