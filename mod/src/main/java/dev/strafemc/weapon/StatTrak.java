package dev.strafemc.weapon;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * CS2's StatTrak: a wrap that counts its owner's Confirmed Kills (items_game's "strange" quality, name prefix
 * StatTrak™). The count lives on the wrap (WeaponItems.STATTRAK), so it stays with the wrap when it's traded. A kill with
 * a gun counts on the wrap in that gun's loadout slot, a knife kill on the knife wrap; the viewmodel shows it on CS2's
 * StatTrak module.
 */
public final class StatTrak {
	/** CS2 case odds for a StatTrak drop (Valve's published odds: 10%); gloves never are. */
	public static final double CASE_ODDS = 0.10;
	/** The module's six digits (CS2's g_nStatTrakValue range). */
	public static final int MAX = 999_999;
	/** items_game quality "strange": hexColor. */
	public static final int COLOR = 0xCF6A32;

	private StatTrak() {}

	/** The wrap's Confirmed Kills, or -1 if it isn't StatTrak. */
	public static int kills(ItemStack wrap) {
		Integer k = wrap.isEmpty() ? null : wrap.get(WeaponItems.STATTRAK);
		return k == null ? -1 : k;
	}

	/** A kill with the weapon of this loadout slot (a gun's class, or "knife"): its wrap counts it if it's StatTrak. */
	static void kill(ServerPlayer p, String slot) {
		if (!ModeWrap.ownGun(p, slot)) return; // someone else's gun (game modes): their StatTrak doesn't count your kills
		ItemStack wrap = Loadout.worn(p, slot);
		int k = kills(wrap);
		if (k < 0 || k >= MAX) return;
		wrap = wrap.copy();
		wrap.set(WeaponItems.STATTRAK, k + 1);
		Loadout.setWorn(p, slot, wrap);
		ModeWrap.refresh(p, slot);
		if (k + 1 >= 10) Advancements.award(p, "stattrak_10");
		if (k + 1 >= 100) Advancements.award(p, "century");
		if (k + 1 >= 1000) Advancements.award(p, "stattrak_1000");
	}
}
