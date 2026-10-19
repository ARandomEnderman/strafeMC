package dev.strafemc.weapon;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * Tools used through a screen: CS2's Name Tag (give a gun or knife wrap its own name) and StatTrak Swap Tool (swap two
 * StatTrak wraps' counts), and the bulk opener (open up to BulkOpener.MAX cases at once).
 */
public class NameTagItem extends Item {
	/** Opens the naming screen with the tag's inventory slot (set on the client). */
	public static java.util.function.IntConsumer clientOpen;
	/** Opens the StatTrak swap screen with the tool's inventory slot (set on the client). */
	public static java.util.function.IntConsumer clientOpenSwap;
	/** Opens the bulk opener's screen with the tool's inventory slot (set on the client). */
	public static java.util.function.IntConsumer clientOpenBulk;
	/** Which tool: 0 the Name Tag, 1 the StatTrak Swap Tool, 2 the bulk opener. */
	private final int tool;

	public NameTagItem(Properties p) {
		this(p, 0);
	}

	public NameTagItem(Properties p, boolean swap) {
		this(p, swap ? 1 : 0);
	}

	public NameTagItem(Properties p, int tool) {
		super(p);
		this.tool = tool;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		var open = tool == 1 ? clientOpenSwap : tool == 2 ? clientOpenBulk : clientOpen;
		if (level.isClientSide() && open != null) open.accept(hand == InteractionHand.MAIN_HAND ? player.getInventory().getSelectedSlot() : 40); // 40: the off hand
		return InteractionResult.SUCCESS;
	}
}
