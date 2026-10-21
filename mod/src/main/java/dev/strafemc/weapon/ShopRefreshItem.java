package dev.strafemc.weapon;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/** The Shop Refresh (ours, not CS2's): used, the player's shop rotates at once to new offers. */
public class ShopRefreshItem extends Item {
	public ShopRefreshItem(Properties p) {
		super(p);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (player instanceof ServerPlayer p) {
			Shop.refresh(p);
			if (!p.getAbilities().instabuild) p.getItemInHand(hand).shrink(1);
		}
		return InteractionResult.SUCCESS;
	}
}
