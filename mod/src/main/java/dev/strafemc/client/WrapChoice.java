package dev.strafemc.client;

import dev.strafemc.weapon.Loadout;
import dev.strafemc.weapon.SkinWrapItem;
import dev.strafemc.weapon.SkinWraps;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/** Which finish your viewmodel weapon wears: the knife wrap in your loadout on any knife, a gun's own slot's wrap on that gun. */
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

	/** The worn knife wrap's finish (pattern seed and wear, Wear.finish). */
	public static int knifePattern() {
		return SkinWrapItem.finish(knifeWrap());
	}

	private static ItemStack gunWrap(String cls) {
		var p = Minecraft.getInstance().player;
		return p == null ? ItemStack.EMPTY : Loadout.get(p, cls);
	}

	/** The paint kit of the wrap in this gun's loadout slot, or null for none. */
	public static String gunKit(String cls) {
		ItemStack s = gunWrap(cls);
		if (!(s.getItem() instanceof SkinWrapItem w) || w.knife()) return null;
		return SkinWraps.kitFor(SkinWrapItem.skin(s), cls);
	}

	public static int gunPattern(String cls) {
		return SkinWrapItem.finish(gunWrap(cls));
	}
}
