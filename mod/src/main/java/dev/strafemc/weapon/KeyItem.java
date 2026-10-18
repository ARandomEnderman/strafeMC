package dev.strafemc.weapon;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;

import java.util.List;

/** A CS2 case key (Cases.Key): opens the cases it belongs to. Which key is the custom model data string. */
public class KeyItem extends Item {
	public KeyItem(Properties p) {
		super(p);
	}

	public static ItemStack stack(Item item, String keyId) {
		ItemStack s = new ItemStack(item);
		s.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(keyId), List.of()));
		return s;
	}

	public static String keyId(ItemStack stack) {
		CustomModelData d = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		return d == null ? null : d.getString(0);
	}

	@Override
	public Component getName(ItemStack stack) {
		if (CaseKeys.UNIVERSAL.equals(keyId(stack))) return Component.literal("Universal Key");
		Cases.Key k = Cases.key(keyId(stack));
		return k == null ? super.getName(stack) : Component.literal(k.name());
	}
}
