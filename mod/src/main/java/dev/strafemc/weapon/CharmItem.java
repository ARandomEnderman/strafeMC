package dev.strafemc.weapon;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.List;
import java.util.function.Consumer;

/** A CS2 charm from a charm collection (Charms): right-click to hang it on a gun wrap. Which charm is the custom model data string. */
public class CharmItem extends Item {
	public CharmItem(Properties p) {
		super(p);
	}

	public static ItemStack stack(Item item, String charm) {
		ItemStack s = new ItemStack(item);
		s.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(charm), List.of()));
		return s;
	}

	public static String charm(ItemStack s) {
		CustomModelData d = s.get(DataComponents.CUSTOM_MODEL_DATA);
		return d == null ? null : d.getString(0);
	}

	@Override
	public net.minecraft.world.InteractionResult use(net.minecraft.world.level.Level level, net.minecraft.world.entity.player.Player player,
			net.minecraft.world.InteractionHand hand) {
		if (level.isClientSide() && StickerItem.clientOpen != null) // the same screen as stickers, with the gun's one charm spot
			StickerItem.clientOpen.accept(hand == net.minecraft.world.InteractionHand.MAIN_HAND ? player.getInventory().getSelectedSlot() : 40);
		return net.minecraft.world.InteractionResult.SUCCESS;
	}

	@Override
	public Component getName(ItemStack stack) {
		Charms.Charm c = Charms.get(charm(stack));
		return c == null ? super.getName(stack) : Component.literal("Charm | " + c.name()).withColor(c.rarity().color);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
		Charms.Charm c = Charms.get(charm(stack));
		if (c != null) out.accept(Component.literal("§7" + Stickers.grade(c.rarity()) + " Charm"));
		double worth = Worth.of(stack);
		if (worth >= 0) out.accept(Component.literal("§7Worth: §a" + Worth.format(worth)));
		out.accept(Component.literal("§8Right-click to hang it on a gun wrap"));
	}
}
