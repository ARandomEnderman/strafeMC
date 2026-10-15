package dev.strafemc.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** A respawn point: players who die respawn on top of a random one (strafemc_spawnblocks), later bots too. */
public class SpawnBlock extends Block {
	public SpawnBlock(Properties p) {
		super(p);
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
