package dev.strafemc.weapon;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

/**
 * CS2 gloves (items_game wearables, no skins yet): right-click to wear them on your viewmodel, again to go back to
 * your side's default gloves. Kept, not used up; only your own first-person view shows them (saved on your client).
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

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level.isClientSide()) dev.strafemc.client.GloveChoice.toggle(kind);
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
		out.accept(Component.literal("§7Right click to wear on your viewmodel (again: your side's default gloves)"));
	}
}
