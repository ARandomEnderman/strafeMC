package dev.strafemc.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * The end of match camera (CS2's end_of_match prefab, which every map places once): placed facing where the team
 * should line up. When a match ends the camera takes its place (over it, at the floor above when it's buried: the
 * prefab's camera stands 32 units over its floor) and looks the way it faces; the team lines up about five blocks
 * ahead. The nearest one in the player's dimension is used.
 */
public class EndOfMatchBlock extends Block {
	public EndOfMatchBlock(Properties p) {
		super(p);
		registerDefaultState(stateDefinition.any().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
		b.add(HorizontalDirectionalBlock.FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		return defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, ctx.getHorizontalDirection()); // the way the placer looks
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
		super.onPlace(state, level, pos, old, moved);
		if (level instanceof ServerLevel sl) SpawnPoints.add(sl, pos);
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
		super.affectNeighborsAfterRemoval(state, level, pos, moved);
		SpawnPoints.remove(level, pos);
	}
}
