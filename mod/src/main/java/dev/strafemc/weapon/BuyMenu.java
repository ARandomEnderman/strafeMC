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
	private record Purchase(String what, List<ItemStack> given, CsArmor.Armor armorBefore, int paid) {}

	/** Purchases since the player last (re)spawned, newest last. */
	private static final Map<UUID, java.util.Deque<Purchase>> BOUGHT = new ConcurrentHashMap<>();

	public static void spawned(ServerPlayer p) {
		SPAWNED.put(p.getUUID(), p.level().getGameTime());
		BOUGHT.remove(p.getUUID());
		sendRefundable(p);
	}

	/** Tells the player what they can still refund (their buy menu shows exactly this). */
	private static void sendRefundable(ServerPlayer p) {
		List<String> items = new java.util.ArrayList<>();
		var list = BOUGHT.get(p.getUUID());
		if (list != null) for (Purchase b : list) items.add(b.what);
		if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, dev.strafemc.net.StrafePayloads.Refundable.TYPE))
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new dev.strafemc.net.StrafePayloads.Refundable(items));
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
			dev.strafemc.server.Toasts.warn(p, no);
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
			if (b.paid > 0) dev.strafemc.mode.GameModes.refund(p, b.paid);
			if (!all) break;
		}
		GunServer.soundAt(p.level(), "Player.PickupWeapon", p.position(), p);
		sendRefundable(p);
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
		return refusal(p, "");
	}

	/** Why this player can't buy this right now; a running game mode decides for its players. */
	public static String refusal(ServerPlayer p, String what) {
		String byMode = dev.strafemc.mode.GameModes.buyRefusal(p, what);
		if (byMode != null) return byMode.isEmpty() ? null : byMode;
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
		String no = refusal(p, what);
		if (no != null) {
			dev.strafemc.server.Toasts.warn(p, no);
			return;
		}
		List<ItemStack> given = new java.util.ArrayList<>();
		CsArmor.Armor before = CsArmor.of(p);
		paid = 0;
		switch (what) {
			case "kevlar" -> {
				if (!dev.strafemc.mode.GameModes.charge(p, price("item_kevlar", 650))) return;
				paid = price("item_kevlar", 650);
				CsArmor.set(p, 100, before.helmet());
				GunServer.soundAt(p.level(), "Player.PickupWeapon", p.position(), p);
				bought(p, what, given, before);
			}
			case "assaultsuit" -> { // with kevlar on already, only the helmet is paid for (CS's price difference)
				int cost = before.value() >= 100 ? price("item_assaultsuit", 1000) - price("item_kevlar", 650) : price("item_assaultsuit", 1000);
				if (!dev.strafemc.mode.GameModes.charge(p, cost)) return;
				paid = cost;
				CsArmor.set(p, 100, true);
				GunServer.soundAt(p.level(), "Player.PickupWeapon", p.position(), p);
				bought(p, what, given, before);
			}
			case "defuser" -> { // CS2: Counter-Terrorists only, one at a time
				if (CsTeam.isT(p) || count(p, WeaponItems.DEFUSE_KIT) > 0) {
					dev.strafemc.server.Toasts.warn(p, "You cannot carry any more");
					return;
				}
				if (!dev.strafemc.mode.GameModes.charge(p, price("item_defuser", 400))) return;
				paid = price("item_defuser", 400);
				give(p, new ItemStack(WeaponItems.DEFUSE_KIT), given);
				bought(p, what, given, null);
			}
			case "knife" -> {
				give(p, MenuLoadout.knife(p), given);
				bought(p, what, given, null);
			}
			default -> {
				WeaponTable.Entry gun = WeaponTable.get("weapon_" + what);
				if (gun != null && !gun.knife()) { // a gun with CS2's reserve: m_nPrimaryReserveAmmoMax magazines (shells for shotguns)
					if (!MenuLoadout.inBuyMenu(p, gun.cls())) { // CS2 sells only what your loadout puts in the buy menu
						dev.strafemc.server.Toasts.warn(p, WeaponTable.name(gun.cls()) + " isn't in your loadout");
						return;
					}
					if (!dev.strafemc.mode.GameModes.charge(p, price(gun.cls(), 0))) return;
					paid = price(gun.cls(), 0);
					int slot = dev.strafemc.mode.GameModes.dropSameKind(p, gun.cls()); // CS2: the gun of that kind you had drops
					ItemStack bought = new ItemStack(WeaponItems.GUNS.get(gun.cls()));
					if (dev.strafemc.mode.GameModes.buyRefusal(p, what) != null) ModeWrap.stamp(bought, p); // in a game mode the gun keeps its buyer's finish
					if (slot >= 0 && p.getInventory().getItem(slot).isEmpty()) {
						given.add(bought.copy());
						p.getInventory().setItem(slot, bought);
						GunServer.soundAt(p.level(), "Player.PickupWeapon", p.position(), p);
					} else give(p, bought, given);
					// a round mode's gun carries its spare magazines itself; elsewhere loose magazines (buyRefusal is "" when
					// allowed in a game mode, null outside one)
					if (!dev.strafemc.mode.GameModes.spareMags(p, gun.cls())) {
						String no2 = dev.strafemc.mode.GameModes.buyRefusal(p, what);
						ItemStack mags = reserve(gun.cls());
						if (!mags.isEmpty() && (no2 == null || no2.isEmpty())) give(p, mags, given);
					}
					bought(p, what, given, null);
					dev.strafemc.mode.GameModes.bought(p, gun.cls());
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
					dev.strafemc.server.Toasts.warn(p, "You cannot carry any more");
					return;
				}
				if (!dev.strafemc.mode.GameModes.charge(p, price(g.cls, 0))) return;
				paid = price(g.cls, 0);
				give(p, new ItemStack(WeaponItems.GRENADES.get(g)), given);
				bought(p, what, given, null);
			}
		}
	}

	/** A gun's CS2 reserve as magazines: m_nPrimaryReserveAmmoMax rounds in clips (shells for shotguns); empty if none. */
	public static ItemStack reserve(String cls) {
		WeaponStats st = WeaponStats.of(cls);
		var mag = WeaponItems.MAGS.get(cls);
		// in clips (m_bReserveAmmoAsClips), else rounds: one item per shell for shell-by-shell shotguns (it was rounds / clip)
		int mags = st == null || mag == null ? 0 : st.reserveAsClips() || st.q().singleShells() ? st.reserveMags()
				: (int) Math.ceil(st.reserveMags() / (double) Math.max(1, st.clip()));
		return mags > 0 ? new ItemStack(mag, mags) : ItemStack.EMPTY;
	}

	public static int count(ServerPlayer p, Grenade g) {
		return count(p, WeaponItems.GRENADES.get(g));
	}

	static int count(ServerPlayer p, Item item) {
		int n = 0;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.is(item)) n += s.getCount();
		}
		return n;
	}

	/** What the purchase being made cost (game modes with money), for its refund. */
	private static int paid;

	/** CS2's price of an item (weapons.vdata m_nPrice). */
	public static int price(String cls, int fallback) {
		var w = dev.strafemc.data.WeaponData.get(cls);
		Object v = w == null ? null : w.get("m_nPrice");
		return v instanceof Number n ? n.intValue() : fallback;
	}

	private static void bought(ServerPlayer p, String what, List<ItemStack> given, CsArmor.Armor armorBefore) {
		BOUGHT.computeIfAbsent(p.getUUID(), u -> new java.util.concurrent.ConcurrentLinkedDeque<>()).add(new Purchase(what, List.copyOf(given), armorBefore, paid));
		sendRefundable(p);
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
