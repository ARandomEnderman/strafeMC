package dev.strafemc.weapon;

import dev.strafemc.server.StrafeSettings;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The buy menu (server): gear without money (strafeMC never has money). Operators choose who may buy
 * (strafemc_buymenu: off, creative players (default), everyone) and for how long after (re)spawning
 * (strafemc_buytime, like CS2's mp_buytime; 0: no limit). CS2's grenade limits apply here (and only here: creative
 * inventory and console can go past them): ammo_grenade_limit_default 1 of each, ammo_grenade_limit_flashbang 2
 * flashbangs, ammo_grenade_limit_total 4 grenades in all. Armor is put on at once, like buying it in CS. Like CS2's
 * buy command, a molotov bought by a CT is an incendiary grenade and the other way round for T.
 */
public final class BuyMenu {
	private BuyMenu() {}

	/** When each player last (re)spawned, in server ticks. */
	private static final Map<UUID, Long> SPAWNED = new ConcurrentHashMap<>();

	/** One purchase (for refunds): the buy command's item, what was handed out, and the armor before it. */
	private record Purchase(String what, List<ItemStack> given, CsArmor.Armor armorBefore) {}

	/** Purchases since the player last (re)spawned, newest last. */
	private static final Map<UUID, java.util.Deque<Purchase>> BOUGHT = new ConcurrentHashMap<>();

	public static void spawned(ServerPlayer p) {
		SPAWNED.put(p.getUUID(), p.level().getGameTime());
		BOUGHT.remove(p.getUUID());
	}

	public static void forget(ServerPlayer p) {
		SPAWNED.remove(p.getUUID());
		BOUGHT.remove(p.getUUID());
	}

	/**
	 * CS2's refund: while you may still buy, a purchase made since you (re)spawned is undone, the items it gave taken
	 * back (those you still have) and the armor put back as it was. what: the buy command's item; "all": everything.
	 */
	public static void refund(ServerPlayer p, String what) {
		String no = refusal(p);
		if (no != null) {
			p.sendSystemMessage(Component.literal(no));
			return;
		}
		var list = BOUGHT.get(p.getUUID());
		if (list == null || list.isEmpty()) return;
		boolean all = what.equals("all");
		for (var it = list.descendingIterator(); it.hasNext(); ) {
			Purchase b = it.next();
			if (!all && !b.what.equals(what)) continue;
			it.remove();
			for (ItemStack g : b.given) take(p, g.getItem(), g.getCount());
			if (b.armorBefore != null) CsArmor.set(p, b.armorBefore.value(), b.armorBefore.helmet());
			if (!all) break;
		}
		GunServer.soundAt(p.level(), "Player.PickupWeapon", p.position(), p);
	}

	/** Takes up to count of an item back from the inventory. */
	private static void take(ServerPlayer p, Item item, int count) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize() && count > 0; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.is(item)) continue;
			int n = Math.min(count, s.getCount());
			s.shrink(n);
			count -= n;
		}
		inv.setChanged();
	}

	public static String modeName(int mode) {
		return switch (mode) {
			case StrafeSettings.BUY_OFF -> "off";
			case StrafeSettings.BUY_EVERYONE -> "everyone";
			default -> "creative only";
		};
	}

	/** Why this player can't buy right now (CS2's wording where it has one), or null if they can. */
	public static String refusal(ServerPlayer p) {
		int mode = StrafeSettings.buyMode();
		if (mode == StrafeSettings.BUY_OFF) return "You cannot buy (the buy menu is off on this server)";
		if (mode == StrafeSettings.BUY_CREATIVE && !p.getAbilities().instabuild) return "You cannot buy (creative only on this server)";
		int time = StrafeSettings.buyTime();
		if (time > 0) {
			long since = p.level().getGameTime() - SPAWNED.getOrDefault(p.getUUID(), Long.MIN_VALUE / 2);
			if (since > time * 20L) return "The " + time + " second buy period has expired";
		}
		return null;
	}

	public static void buy(ServerPlayer p, String what) {
		String no = refusal(p);
		if (no != null) {
			p.sendSystemMessage(Component.literal(no));
			return;
		}
		List<ItemStack> given = new java.util.ArrayList<>();
		CsArmor.Armor before = CsArmor.of(p);
		switch (what) {
			case "kevlar" -> {
				CsArmor.set(p, 100, before.helmet());
				GunServer.soundAt(p.level(), "Player.PickupWeapon", p.position(), p);
				bought(p, what, given, before);
			}
			case "assaultsuit" -> {
				CsArmor.set(p, 100, true);
				GunServer.soundAt(p.level(), "Player.PickupWeapon", p.position(), p);
				bought(p, what, given, before);
			}
			case "knife" -> {
				give(p, new ItemStack(WeaponItems.KNIVES.get(CsTeam.isT(p) ? "weapon_knife_t" : "weapon_knife")), given);
				bought(p, what, given, null);
			}
			default -> {
				WeaponTable.Entry gun = WeaponTable.get("weapon_" + what);
				if (gun != null && !gun.knife()) { // a gun with CS2's reserve: m_nPrimaryReserveAmmoMax magazines (shells for shotguns)
					give(p, new ItemStack(WeaponItems.GUNS.get(gun.cls())), given);
					WeaponStats st = WeaponStats.of(gun.cls());
					int mags = st == null ? 0 : st.reserveAsClips() ? st.reserveMags() : (int) Math.ceil(st.reserveMags() / (double) Math.max(1, st.clip()));
					if (mags > 0) give(p, new ItemStack(WeaponItems.MAGS.get(gun.cls()), mags), given);
					bought(p, what, given, null);
					return;
				}
				Grenade g = null;
				for (Grenade x : Grenade.values()) if (x.cls.equals("weapon_" + what) || x.cls.equals(what)) g = x;
				if (g == null) return;
				if (g == Grenade.MOLOTOV || g == Grenade.INCENDIARY) g = CsTeam.isT(p) ? Grenade.MOLOTOV : Grenade.INCENDIARY;
				int have = count(p, g), total = 0;
				for (Grenade x : Grenade.values()) total += count(p, x);
				int limit = g == Grenade.FLASHBANG ? cvar("ammo_grenade_limit_flashbang", 2) : cvar("ammo_grenade_limit_default", 1);
				if (have >= limit || total >= cvar("ammo_grenade_limit_total", 4)) {
					p.sendSystemMessage(Component.literal("You cannot carry any more"));
					return;
				}
				give(p, new ItemStack(WeaponItems.GRENADES.get(g)), given);
				bought(p, what, given, null);
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

	private static void bought(ServerPlayer p, String what, List<ItemStack> given, CsArmor.Armor armorBefore) {
		BOUGHT.computeIfAbsent(p.getUUID(), u -> new java.util.concurrent.ConcurrentLinkedDeque<>()).add(new Purchase(what, List.copyOf(given), armorBefore));
	}

	private static void give(ServerPlayer p, ItemStack stack, List<ItemStack> given) {
		given.add(new ItemStack(stack.getItem(), stack.getCount()));
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
