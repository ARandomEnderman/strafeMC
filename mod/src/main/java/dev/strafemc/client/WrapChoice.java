package dev.strafemc.client;

import dev.strafemc.weapon.Loadout;
import dev.strafemc.weapon.SkinWrapItem;
import dev.strafemc.weapon.SkinWraps;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/** Which finish your viewmodel's knife wears: the knife wrap in your loadout, on whichever knife you hold. */
public final class WrapChoice {
	private WrapChoice() {}

	private static ItemStack knifeWrap() {
		var p = Minecraft.getInstance().player;
		return p == null ? ItemStack.EMPTY : Loadout.get(p, "knife");
	}

	/** The paint kit for this knife (its own version of the wrap's skin, else another knife's), or null for none. */
	public static String knifeKit(String cls) {
		ItemStack s = knifeWrap();
		if (!(s.getItem() instanceof SkinWrapItem w) || !w.knife()) return null;
		return SkinWraps.kitFor(SkinWrapItem.skin(s), cls);
	}

	/** The worn knife wrap's pattern seed. */
	public static int knifePattern() {
		return SkinWrapItem.pattern(knifeWrap());
	}
}
