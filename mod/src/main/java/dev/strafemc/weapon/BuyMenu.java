package dev.strafemc.weapon;

import dev.strafemc.server.StrafeSettings;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The buy menu (server): gear without money (strafeMC never has money). Buying works in creative, or anywhere when an
 * operator turned on strafemc_buymenu. CS2's grenade limits apply here (and only here: creative inventory and console
 * can go past them): ammo_grenade_limit_default 1 of each, ammo_grenade_limit_flashbang 2 flashbangs,
 * ammo_grenade_limit_total 4 grenades in all. Armor is put on at once, like buying it in CS.
 */
public final class BuyMenu {
	private BuyMenu() {}

	public static boolean allowed(ServerPlayer p) {
		return p.getAbilities().instabuild || StrafeSettings.buyAnywhere();
	}

	public static void buy(ServerPlayer p, String what) {
		if (!allowed(p)) {
			p.sendSystemMessage(Component.literal("Buying is off here (creative only, or an operator runs strafemc_buymenu 1)."));
			return;
		}
		switch (what) {
			case "kevlar" -> {
				CsArmor.Armor a = CsArmor.of(p);
				CsArmor.set(p, 100, a.helmet());
				GunServer.soundAt(p.level(), "Player.PickupWeapon", p.position(), p);
			}
			case "assaultsuit" -> {
				CsArmor.set(p, 100, true);
				GunServer.soundAt(p.level(), "Player.PickupWeapon", p.position(), p);
			}
			case "ak47" -> {
				give(p, new ItemStack(WeaponItems.AK47));
				give(p, new ItemStack(WeaponItems.MAG_AK47, 3)); // CS2's 90 reserve rounds: three 30-round magazines
			}
			case "knife" -> give(p, new ItemStack(WeaponItems.KNIFE_FALCHION));
			default -> {
				Grenade g = null;
				for (Grenade x : Grenade.values()) if (x.cls.equals("weapon_" + what) || x.cls.equals(what)) g = x;
				if (g == null) return;
				int have = count(p, g), total = 0;
				for (Grenade x : Grenade.values()) total += count(p, x);
				int limit = g == Grenade.FLASHBANG ? cvar("ammo_grenade_limit_flashbang", 2) : cvar("ammo_grenade_limit_default", 1);
				if (have >= limit || total >= cvar("ammo_grenade_limit_total", 4)) {
					p.sendSystemMessage(Component.literal("You can't carry any more of that (CS2's grenade limits)."));
					return;
				}
				give(p, new ItemStack(WeaponItems.GRENADES.get(g)));
			}
		}
	}

	public static int count(ServerPlayer p, Grenade g) {
		Item item = WeaponItems.GRENADES.get(g);
		int n = 0;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.is(item)) n += s.getCount();
		}
		return n;
	}

	private static void give(ServerPlayer p, ItemStack stack) {
		if (!p.getInventory().add(stack)) p.drop(stack, false);
		GunServer.soundAt(p.level(), "Player.PickupWeapon", p.position(), p);
	}

	private static int cvar(String name, int fallback) {
		String v = dev.strafemc.bridge.Cs2Bridge.INSTANCE.cvar(name);
		try {
			return v == null ? fallback : (int) Double.parseDouble(v);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
