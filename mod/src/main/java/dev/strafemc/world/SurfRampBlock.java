package dev.strafemc.world;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * Surf ramp: two blocks high, one block deep, rising toward FACING (like stairs) - a 63.4 degree slope.
 * Source movement collides with the exact smooth slope (normal.y 0.447 < sv_standable_normal 0.7, so you surf it);
 * vanilla collision/outline is a 16-step approximation of the same slope.
 */
public class SurfRampBlock extends DoublePlantBlock {
	public static final MapCodec<SurfRampBlock> CODEC = simpleCodec(SurfRampBlock::new);
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
	private static final Map<Direction, VoxelShape> LOWER = new EnumMap<>(Direction.class), UPPER = new EnumMap<>(Direction.class);

	static {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			VoxelShape lo = Shapes.empty(), up = Shapes.empty();
			for (int i = 0; i < 16; i++) { // slice i covers u = i..i+1 px along the rise; surface height = 2u
				int h = 2 * (i + 1); // total height of this slice in px (0..32 over both halves)
				if (h > 0) lo = Shapes.or(lo, slice(d, i, Math.min(16, h)));
				if (h > 16) up = Shapes.or(up, slice(d, i, h - 16));
			}
			LOWER.put(d, lo.optimize());
			UPPER.put(d, up.optimize());
		}
	}

	/** One 1px-deep slice at u = i (along the rise direction), full width, given height in px. */
	private static VoxelShape slice(Direction d, int i, int h) {
		return switch (d) {
			case EAST -> Block.box(i, 0, 0, i + 1, h, 16);
			case WEST -> Block.box(15 - i, 0, 0, 16 - i, h, 16);
			case SOUTH -> Block.box(0, 0, i, 16, h, i + 1);
			default -> Block.box(0, 0, 15 - i, 16, h, 16 - i); // NORTH
		};
	}

	public SurfRampBlock(Properties p) {
		super(p);
		registerDefaultState(stateDefinition.any().setValue(HALF, DoubleBlockHalf.LOWER).setValue(FACING, Direction.NORTH));
	}

	@Override
	public MapCodec<? extends DoublePlantBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
		super.createBlockStateDefinition(b);
		b.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		BlockState s = super.getStateForPlacement(ctx);
		return s == null ? null : s.setValue(FACING, ctx.getHorizontalDirection());
	}

	/** DoublePlantBlock places the top half from the default state (facing north); keep the placed facing instead. */
	@Override
	public void setPlacedBy(net.minecraft.world.level.Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, net.minecraft.world.item.ItemStack stack) {
		level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), 3);
	}

	@Override
	protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
			BlockState below = level.getBlockState(pos.below());
			return below.is(this) && below.getValue(HALF) == DoubleBlockHalf.LOWER;
		}
		return true; // stands anywhere (not a plant)
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
		return (state.getValue(HALF) == DoubleBlockHalf.LOWER ? LOWER : UPPER).get(state.getValue(FACING));
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
		return getShape(state, level, pos, ctx);
	}
}
