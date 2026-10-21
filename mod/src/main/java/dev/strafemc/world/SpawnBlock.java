package dev.strafemc.world;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A respawn point: players who die respawn on top of a random one (strafemc_spawnblocks), bots too. Right-clicking it
 * (by someone who may build there) sets whose it is: anyone's, the Terrorists' or the Counter-Terrorists'; round-based
 * game modes start each side on its own, the others use any.
 */
public class SpawnBlock extends Block {
	public enum Side implements StringRepresentable {
		ANY("any", "anyone"), T("t", "Terrorists"), CT("ct", "Counter-Terrorists");

		private final String id, label;

		Side(String id, String label) {
			this.id = id;
			this.label = label;
		}

		@Override
		public String getSerializedName() {
			return id;
		}
	}

	public static final EnumProperty<Side> SIDE = EnumProperty.create("side", Side.class);

	public SpawnBlock(Properties p) {
		super(p);
		registerDefaultState(stateDefinition.any().setValue(SIDE, Side.ANY));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
		b.add(SIDE);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!player.mayBuild()) return InteractionResult.PASS;
		if (!level.isClientSide()) {
			Side next = Side.values()[(state.getValue(SIDE).ordinal() + 1) % Side.values().length];
			level.setBlock(pos, state.setValue(SIDE, next), Block.UPDATE_ALL);
			player.sendOverlayMessage(Component.literal("Spawn point: " + next.label));
		}
		return InteractionResult.SUCCESS;
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
