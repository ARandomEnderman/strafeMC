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

	/** A wrap; a patterned finish gets pattern seed 0 (CS2's pattern template; cases will roll it). */
	public static ItemStack stack(Item item, String wrap) {
		return stack(item, wrap, 0);
	}

	public static ItemStack stack(Item item, String wrap, int pattern) {
		ItemStack s = new ItemStack(item);
		s.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(wrap), List.of()));
		SkinWraps.Wrap w = SkinWraps.get(wrap);
		if (w != null && w.pattern()) s.set(WeaponItems.PATTERN, pattern);
		return s;
	}

	/** A wrap with this wear (float). */
	public static ItemStack stack(Item item, String wrap, int pattern, float wear) {
		ItemStack s = stack(item, wrap, pattern);
		if (wear > 0) s.set(WeaponItems.WEAR, Float.floatToIntBits(wear));
		return s;
	}

	/** The wrap's wear (0: Factory New, also wraps from before wear existed). */
	public static float wear(ItemStack stack) {
		Integer w = stack.get(WeaponItems.WEAR);
		return w == null ? 0 : Float.intBitsToFloat(w);
	}

	/** Pattern seed and wear as the one number the viewmodel bakes a finish by (Wear.finish). */
	public static int finish(ItemStack stack) {
		return Wear.finish(pattern(stack), wear(stack));
	}

	/** The wrap's pattern seed (0 if it has none). */
	public static int pattern(ItemStack stack) {
		Integer p = stack.get(WeaponItems.PATTERN);
		return p == null ? 0 : p;
	}

	public static String skin(ItemStack stack) {
		CustomModelData d = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		return d == null ? null : d.getString(0);
	}

	/** A patterned wrap without a pattern yet (from a chest): it gets a random one (0-1000) once it's someone's. */
	@Override
	public void inventoryTick(ItemStack stack, net.minecraft.server.level.ServerLevel level, net.minecraft.world.entity.Entity entity,
							  net.minecraft.world.entity.EquipmentSlot slot) {
		if (stack.has(WeaponItems.PATTERN)) return;
		SkinWraps.Wrap w = SkinWraps.get(skin(stack));
		if (w != null && w.pattern()) stack.set(WeaponItems.PATTERN, level.getRandom().nextInt(1001));
	}

	@Override
	public Component getName(ItemStack stack) {
		SkinWraps.Wrap w = SkinWraps.get(skin(stack));
		if (w == null) return super.getName(stack);
		// the name in CS2's rarity colour
		var r = w.rarity();
		var name = Component.literal((StatTrak.kills(stack) >= 0 ? "StatTrak™ " : "") + w.name() + (knife ? " Knife Wrap" : " Wrap"));
		return r == null ? name : name.withColor(r.color);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (player instanceof ServerPlayer sp) {
			// a wrap has one slot (the knife's, or its gun's): put it on, swapping like armor; sneaking opens the loadout
			if (player.isShiftKeyDown()) Loadout.open(sp);
			else if (knife) Loadout.equipKnifeWrap(sp, player.getItemInHand(hand));
			else Loadout.equipGunWrap(sp, player.getItemInHand(hand));
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
		SkinWraps.Wrap w = SkinWraps.get(skin(stack));
		if (w == null) return;
		var stickers = StickerSlots.names(stack);
		if (!stickers.isEmpty()) out.accept(Component.literal("§7Stickers: " + String.join(", ", stickers)));
		Charms.Charm charm = Charms.get(StickerSlots.charm(stack));
		if (charm != null) out.accept(Component.literal("§7Charm: " + charm.name()));
		float wear = wear(stack);
		out.accept(Component.literal("§7Exterior: " + Wear.exterior(wear) + " (" + String.format(java.util.Locale.ROOT, "%.8f", wear) + ")"));
		if (w.pattern()) out.accept(Component.literal("§7Pattern template: " + pattern(stack)));
		int kills = StatTrak.kills(stack);
		if (kills >= 0) out.accept(Component.literal("StatTrak™ Confirmed Kills: " + kills).withColor(StatTrak.COLOR));
		double worth = Worth.of(stack);
		if (worth >= 0) out.accept(Component.literal("§7Worth: §a" + Worth.format(worth)));
	}
}
