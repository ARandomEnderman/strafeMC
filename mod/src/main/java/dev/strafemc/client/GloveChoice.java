package dev.strafemc.client;

import dev.strafemc.config.StrafeConfig;
import dev.strafemc.weapon.GloveItem;
import dev.strafemc.weapon.GloveSkins;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Which gloves your viewmodel wears: the ones you chose (StrafeConfig.glove, in finish StrafeConfig.gloveSkin), else
 * your side's default from CS2's items_game (t_gloves: glove_fingerless, ct_gloves: glove_hardknuckle).
 */
public final class GloveChoice {
	public static final String T_DEFAULT = "agents/models/shared/arms/glove_fingerless/glove_fingerless.vmdl_c",
			CT_DEFAULT = "agents/models/shared/arms/glove_hardknuckle/glove_hardknuckle.vmdl_c";

	private GloveChoice() {}

	private static GloveItem.Kind kind() {
		for (GloveItem.Kind k : GloveItem.Kind.values()) if (k.id.equals(StrafeConfig.glove)) return k;
		return null;
	}

	public static String model() {
		GloveItem.Kind k = kind();
		return k != null ? k.model : "t".equals(StrafeConfig.team) ? T_DEFAULT : CT_DEFAULT;
	}

	/** The worn finish (paint kit), empty for the default gloves. */
	public static String skin() {
		GloveItem.Kind k = kind();
		if (k == null) return "";
		GloveSkins.Skin s = GloveSkins.get(StrafeConfig.gloveSkin);
		if (s != null && s.kind() == k) return s.kit();
		var all = GloveSkins.of(k);
		return all.isEmpty() ? "" : all.get(0).kit();
	}

	/** Puts these gloves on (no change, and no message, if they're already on: holding right-click repeats this). */
	public static void wear(GloveItem.Kind kind, String kit) {
		if (kind.id.equals(StrafeConfig.glove) && kit.equals(StrafeConfig.gloveSkin)) return;
		StrafeConfig.glove = kind.id;
		StrafeConfig.gloveSkin = kit;
		save("Now wearing: ", Component.translatable("item.strafemc." + kind.id).append(" | ")
				.append(Component.translatableWithFallback("strafemc.paintkit." + kit, GloveSkins.fallbackName(kit))));
	}

	public static void wearDefault() {
		if (StrafeConfig.glove.isEmpty()) return;
		StrafeConfig.glove = "";
		StrafeConfig.gloveSkin = "";
		save("Now wearing: ", Component.literal("t".equals(StrafeConfig.team) ? "T default gloves" : "CT default gloves"));
	}

	private static void save(String prefix, Component what) {
		try {
			StrafeConfig.save();
		} catch (Exception ignored) {
		}
		var mc = Minecraft.getInstance();
		// chat, not the action bar: the speed meter draws over action bar messages
		if (mc.player != null) mc.gui.getChat().addClientSystemMessage(Component.literal(prefix).append(what));
	}
}
