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
		var p = dev.strafemc.client.viewmodel.Viewmodel.holder(); // you, or the player you watch
		return p == null ? ItemStack.EMPTY : Loadout.worn(p, "gloves");
	}

	public static String model() {
		if (worn().getItem() instanceof GloveItem g) return g.kind.model;
		boolean t = dev.strafemc.client.viewmodel.Viewmodel.watching() ? dev.strafemc.weapon.CsTeam.isT(dev.strafemc.client.viewmodel.Viewmodel.holder())
				: "t".equals(StrafeConfig.team);
		return t ? T_DEFAULT : CT_DEFAULT;
	}

	/** The worn finish (paint kit, "kit@band" past Factory New: its exterior's wear), empty for the default gloves. */
	public static String skin() {
		ItemStack s = worn();
		if (!(s.getItem() instanceof GloveItem g)) return "";
		int band = dev.strafemc.weapon.Wear.bandOf(GloveItem.wear(s));
		return band > 0 ? g.kit(s) + "@" + band : g.kit(s);
	}
}
