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
 * A bomb site (A or B, right-click to switch): in round-based game modes the C4 can only be planted near one
 * (BombSites.RADIUS), as CS2's bomb target areas.
 */
public class BombSiteBlock extends Block {
	public enum Site implements StringRepresentable {
		A("a"), B("b");

		private final String id;

		Site(String id) {
			this.id = id;
		}

		@Override
		public String getSerializedName() {
			return id;
		}
	}

	public static final EnumProperty<Site> SITE = EnumProperty.create("site", Site.class);

	public BombSiteBlock(Properties p) {
		super(p);
		registerDefaultState(stateDefinition.any().setValue(SITE, Site.A));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
		b.add(SITE);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!player.mayBuild()) return InteractionResult.PASS;
		if (!level.isClientSide()) {
			Site next = state.getValue(SITE) == Site.A ? Site.B : Site.A;
			level.setBlock(pos, state.setValue(SITE, next), Block.UPDATE_ALL);
			player.sendOverlayMessage(Component.literal("Bomb site " + next.name()));
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
