package dev.strafemc.client;

import dev.strafemc.config.StrafeConfig;
import dev.strafemc.weapon.GloveItem;
import dev.strafemc.weapon.Loadout;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/**
 * Which gloves your viewmodel wears: the ones in your loadout's gloves slot (in their finish), else your side's default
 * from CS2's items_game (t_gloves: glove_fingerless, ct_gloves: glove_hardknuckle).
 */
public final class GloveChoice {
	public static final String T_DEFAULT = "agents/models/shared/arms/glove_fingerless/glove_fingerless.vmdl_c",
			CT_DEFAULT = "agents/models/shared/arms/glove_hardknuckle/glove_hardknuckle.vmdl_c";

	private GloveChoice() {}

	private static ItemStack worn() {
		var p = Minecraft.getInstance().player;
		return p == null ? ItemStack.EMPTY : Loadout.get(p, "gloves");
	}

	public static String model() {
		return worn().getItem() instanceof GloveItem g ? g.kind.model : "t".equals(StrafeConfig.team) ? T_DEFAULT : CT_DEFAULT;
	}

	/** The worn finish (paint kit), empty for the default gloves. */
	public static String skin() {
		ItemStack s = worn();
		return s.getItem() instanceof GloveItem g ? g.kit(s) : "";
	}
}
