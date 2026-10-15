package dev.strafemc.weapon;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.Level;

import java.util.List;

/** A CS2 weapon case (Cases): right-click shows its contents and unlocks it. Which case is the custom model data string. */
public class CaseItem extends Item {
	/** Opens the case screen (set by the client). */
	public static java.util.function.Consumer<String> clientOpen;

	public CaseItem(Properties p) {
		super(p);
	}

	public static ItemStack stack(Item item, String caseId) {
		ItemStack s = new ItemStack(item);
		s.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(caseId), List.of()));
		return s;
	}

	public static String caseId(ItemStack stack) {
		CustomModelData d = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		return d == null ? null : d.getString(0);
	}

	@Override
	public Component getName(ItemStack stack) {
		Cases.Case c = Cases.get(caseId(stack));
		return c == null ? super.getName(stack) : Component.literal(c.name());
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		String id = caseId(player.getItemInHand(hand));
		if (level.isClientSide() && clientOpen != null && Cases.get(id) != null) clientOpen.accept(id);
		return InteractionResult.SUCCESS;
	}
}
