package dev.strafemc.client;

import dev.strafemc.config.StrafeConfig;
import dev.strafemc.weapon.GloveItem;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Which gloves your viewmodel wears: the ones you chose (StrafeConfig.glove), else your side's default from CS2's
 * items_game (t_gloves: glove_fingerless, ct_gloves: glove_hardknuckle).
 */
public final class GloveChoice {
	public static final String T_DEFAULT = "agents/models/shared/arms/glove_fingerless/glove_fingerless.vmdl_c",
			CT_DEFAULT = "agents/models/shared/arms/glove_hardknuckle/glove_hardknuckle.vmdl_c";

	private GloveChoice() {}

	public static String model() {
		for (GloveItem.Kind k : GloveItem.Kind.values()) if (k.id.equals(StrafeConfig.glove)) return k.model;
		return "t".equals(StrafeConfig.team) ? T_DEFAULT : CT_DEFAULT;
	}

	public static void toggle(GloveItem.Kind kind) {
		StrafeConfig.glove = kind.id.equals(StrafeConfig.glove) ? "" : kind.id;
		try {
			StrafeConfig.save();
		} catch (Exception ignored) {
		}
		var p = Minecraft.getInstance().player;
		if (p != null) p.sendOverlayMessage(Component.literal(StrafeConfig.glove.isEmpty() ? "Default gloves" : "Wearing " + kind.name().replace('_', ' ').toLowerCase()));
	}
}
