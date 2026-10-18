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

/** A CS2 sticker from a sticker capsule (Stickers): no use yet, but it has a worth. Which sticker is the custom model data string. */
public class StickerItem extends Item {
	public StickerItem(Properties p) {
		super(p);
	}

	public static ItemStack stack(Item item, String sticker) {
		ItemStack s = new ItemStack(item);
		s.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(sticker), List.of()));
		return s;
	}

	public static String sticker(ItemStack s) {
		CustomModelData d = s.get(DataComponents.CUSTOM_MODEL_DATA);
		return d == null ? null : d.getString(0);
	}

	@Override
	public Component getName(ItemStack stack) {
		Stickers.Sticker st = Stickers.get(sticker(stack));
		return st == null ? super.getName(stack) : Component.literal("Sticker | " + st.name()).withColor(st.rarity().color);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
		Stickers.Sticker st = Stickers.get(sticker(stack));
		if (st != null) out.accept(Component.literal("§7" + Stickers.grade(st.rarity()) + " Sticker"));
	}
}
