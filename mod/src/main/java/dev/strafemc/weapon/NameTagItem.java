package dev.strafemc.weapon;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * CS2's tools used on your wraps through a screen: the Name Tag (give a gun or knife wrap its own name) and the StatTrak
 * Swap Tool (swap two StatTrak wraps' counts).
 */
public class NameTagItem extends Item {
	/** Opens the naming screen with the tag's inventory slot (set on the client). */
	public static java.util.function.IntConsumer clientOpen;
	/** Opens the StatTrak swap screen with the tool's inventory slot (set on the client). */
	public static java.util.function.IntConsumer clientOpenSwap;
	private final boolean swap;

	public NameTagItem(Properties p) {
		this(p, false);
	}

	public NameTagItem(Properties p, boolean swap) {
		super(p);
		this.swap = swap;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		var open = swap ? clientOpenSwap : clientOpen;
		if (level.isClientSide() && open != null) open.accept(hand == InteractionHand.MAIN_HAND ? player.getInventory().getSelectedSlot() : 40); // 40: the off hand
		return InteractionResult.SUCCESS;
	}
}
