package dev.strafemc.weapon;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A skin wrap (SkinWraps): put it in a gun's (or the knife) slot of your loadout and every gun of that type you hold
 * wears the skin, Factory New. Never used up: take it out again, trade it. Which skin is the stack's custom model data
 * string, which also picks its picture (a piece of the skin's own texture). Gun wraps and knife wraps are separate items.
 */
public class SkinWrapItem extends Item {
	private final boolean knife;

	public SkinWrapItem(Properties p, boolean knife) {
		super(p);
		this.knife = knife;
	}

	public boolean knife() {
		return knife;
	}

	public static ItemStack stack(Item item, String wrap) {
		ItemStack s = new ItemStack(item);
		s.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(wrap), List.of()));
		return s;
	}

	public static String skin(ItemStack stack) {
		CustomModelData d = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		return d == null ? null : d.getString(0);
	}

	@Override
	public Component getName(ItemStack stack) {
		SkinWraps.Wrap w = SkinWraps.get(skin(stack));
		if (w == null) return super.getName(stack);
		return Component.literal(w.name() + (knife ? " Knife Wrap" : " Wrap"));
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (player instanceof ServerPlayer sp) Loadout.open(sp);
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
		SkinWraps.Wrap w = SkinWraps.get(skin(stack));
		if (w == null) return;
		if (knife) out.accept(Component.literal("§7Fits every knife"));
		else {
			List<String> guns = new ArrayList<>();
			for (String cls : w.kits().keySet()) guns.add(SkinWraps.WEAPON_NAMES.getOrDefault(cls, cls));
			out.accept(Component.literal("§7Fits: " + String.join(", ", guns)));
		}
		out.accept(Component.literal("§7Right click: open your loadout"));
	}
}
