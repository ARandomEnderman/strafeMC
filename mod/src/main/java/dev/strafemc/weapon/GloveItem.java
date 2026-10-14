package dev.strafemc.weapon;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.function.Consumer;

/**
 * CS2 gloves (items_game wearables) in every CS2 finish (GloveSkins), always Factory New, worn from the gloves slot of
 * your loadout (Loadout): right-click puts them there (the ones worn before come back), sneak + right-click takes them
 * off (your side's default gloves). The finish is the stack's custom model data string, which also picks its picture.
 */
public class GloveItem extends Item {
	/** CS2's wearables: item name, glove model (agents/models/shared/arms/...), and whether it's a T "studded" design. */
	public enum Kind {
		BLOODHOUND("studded_bloodhound_gloves", "glove_bloodhound", true),
		SPORT("sporty_gloves", "glove_sporty", false),
		DRIVER("slick_gloves", "glove_slick", false),
		HAND_WRAPS("leather_handwraps", "glove_handwrap", false),
		MOTO("motorcycle_gloves", "glove_motorcycle", false),
		SPECIALIST("specialist_gloves", "glove_specialist", false),
		HYDRA("studded_hydra_gloves", "glove_hydra", true),
		BROKEN_FANG("studded_brokenfang_gloves", "glove_brokenfang", true);

		public final String id, model;
		public final boolean studded;

		Kind(String id, String folder, boolean studded) {
			this.id = id;
			this.model = "agents/models/shared/arms/" + folder + "/" + folder + ".vmdl_c";
			this.studded = studded;
		}
	}

	public final Kind kind;

	public GloveItem(Properties p, Kind kind) {
		super(p);
		this.kind = kind;
	}

	/** These gloves in a finish. */
	public static ItemStack stack(Item item, String kit) {
		ItemStack s = new ItemStack(item);
		s.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(kit), List.of()));
		return s;
	}

	/** The stack's finish; gloves without one (older stacks) wear their first CS2 finish. */
	public String kit(ItemStack stack) {
		CustomModelData d = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		String k = d == null ? null : d.getString(0);
		if (GloveSkins.get(k) != null && GloveSkins.get(k).kind() == kind) return k;
		List<GloveSkins.Skin> all = GloveSkins.of(kind);
		return all.isEmpty() ? "" : all.get(0).kit();
	}

	@Override
	public Component getName(ItemStack stack) {
		String kit = kit(stack);
		Component base = super.getName(stack);
		if (kit.isEmpty()) return base;
		return Component.empty().append(base).append(" | ")
				.append(Component.translatableWithFallback("strafemc.paintkit." + kit, GloveSkins.fallbackName(kit)));
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
			if (player.isShiftKeyDown()) Loadout.takeOffGloves(sp);
			else Loadout.wearGloves(sp, player.getItemInHand(hand));
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
		out.accept(Component.literal("§7Factory New"));
		out.accept(Component.literal("§7Right click: wear (goes into your loadout)"));
		out.accept(Component.literal("§7Sneak + right click: take off your gloves"));
	}
}
