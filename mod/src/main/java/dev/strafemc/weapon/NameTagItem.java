package dev.strafemc.weapon;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/** CS2's Name Tag: use it to give a gun or knife wrap its own name (the screen lists your wraps). */
public class NameTagItem extends Item {
	/** Opens the naming screen with the tag's inventory slot (set on the client). */
	public static java.util.function.IntConsumer clientOpen;

	public NameTagItem(Properties p) {
		super(p);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level.isClientSide() && clientOpen != null) clientOpen.accept(hand == InteractionHand.MAIN_HAND ? player.getInventory().getSelectedSlot() : 40); // 40: the off hand
		return InteractionResult.SUCCESS;
	}
}
