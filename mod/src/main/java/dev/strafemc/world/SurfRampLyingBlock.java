package dev.strafemc.world;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * Lying surf ramp: the surf ramp tipped over, one block high and two deep, rising toward FACING (26.6 degrees). Two
 * blocks like a bed: PART foot is the low half (where it's placed), head the high half one block further. At this
 * angle (normal.y 0.894 >= sv_standable_normal 0.7) you walk on it in Source movement, which collides with the exact
 * smooth slope; vanilla collision/outline is a 16-step approximation.
 */
public class SurfRampLyingBlock extends HorizontalDirectionalBlock {
	public static final MapCodec<SurfRampLyingBlock> CODEC = simpleCodec(SurfRampLyingBlock::new);
	public static final EnumProperty<BedPart> PART = BlockStateProperties.BED_PART;
	private static final Map<Direction, VoxelShape> LOW = new EnumMap<>(Direction.class), HIGH = new EnumMap<>(Direction.class);

	static {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			VoxelShape lo = Shapes.empty(), hi = Shapes.empty();
			for (int i = 0; i < 16; i++) { // slice i covers u = i..i+1 px along the rise; height = u / 2 over both blocks
				lo = Shapes.or(lo, slice(d, i, (i + 2) / 2.0));
				hi = Shapes.or(hi, slice(d, i, 8 + (i + 2) / 2.0));
			}
			LOW.put(d, lo.optimize());
			HIGH.put(d, hi.optimize());
		}
	}

	/** One 1px-deep slice at u = i (along the rise direction), full width, given height in px. */
	private static VoxelShape slice(Direction d, int i, double h) {
		return switch (d) {
			case EAST -> Block.box(i, 0, 0, i + 1, h, 16);
			case WEST -> Block.box(15 - i, 0, 0, 16 - i, h, 16);
			case SOUTH -> Block.box(0, 0, i, 16, h, i + 1);
			default -> Block.box(0, 0, 15 - i, 16, h, 16 - i); // NORTH
		};
	}

	public SurfRampLyingBlock(Properties p) {
		super(p);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, BedPart.FOOT));
	}

	@Override
	protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
		b.add(FACING, PART);
	}

	/** Rises away from the player; the high half needs room one block further. */
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		Direction f = ctx.getHorizontalDirection();
		BlockPos head = ctx.getClickedPos().relative(f);
		Level level = ctx.getLevel();
		if (!level.getBlockState(head).canBeReplaced(ctx) || !level.getWorldBorder().isWithinBounds(head)) return null;
		return defaultBlockState().setValue(FACING, f).setValue(PART, BedPart.FOOT);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		if (!level.isClientSide()) level.setBlock(pos.relative(state.getValue(FACING)), state.setValue(PART, BedPart.HEAD), 3);
	}

	/** The other half is gone: this one goes too (the low half drops the item, see its loot table). */
	@Override
	protected BlockState updateShape(BlockState s, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction dir, BlockPos nPos, BlockState n,
									 RandomSource rnd) {
		Direction toOther = s.getValue(PART) == BedPart.FOOT ? s.getValue(FACING) : s.getValue(FACING).getOpposite();
		if (dir == toOther && !(n.is(this) && n.getValue(PART) != s.getValue(PART) && n.getValue(FACING) == s.getValue(FACING)))
			return Blocks.AIR.defaultBlockState();
		return super.updateShape(s, level, ticks, pos, dir, nPos, n, rnd);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
		return (state.getValue(PART) == BedPart.FOOT ? LOW : HIGH).get(state.getValue(FACING));
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
		return getShape(state, level, pos, ctx);
	}
}
