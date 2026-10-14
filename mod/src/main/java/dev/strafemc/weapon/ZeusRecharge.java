package dev.strafemc.weapon;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * CS2's Zeus x27: it can't be reloaded; an empty Zeus recharges mp_taser_recharge_time seconds after it fired (CS2's
 * default 30; a negative value: never), held or not. The game tick it's charged again is the stack's RECHARGE component,
 * which also drives the item's charging bar.
 */
public final class ZeusRecharge {
	public static final String CLS = "weapon_taser";
	private static final double DEFAULT_SECONDS = 30;

	/** The client's game time, for the charging bar (set by the client; the server never draws item bars). */
	public static volatile java.util.function.LongSupplier clientGameTime = () -> 0;

	private ZeusRecharge() {}

	public static double seconds() {
		String v = dev.strafemc.bridge.Cs2Bridge.INSTANCE.cvar("mp_taser_recharge_time");
		try {
			return v == null ? DEFAULT_SECONDS : Double.parseDouble(v);
		} catch (NumberFormatException e) {
			return DEFAULT_SECONDS;
		}
	}

	/** The Zeus just fired its shot: start recharging. */
	static void fired(ServerPlayer p, ItemStack zeus) {
		double s = seconds();
		if (s < 0) return;
		zeus.set(WeaponItems.RECHARGE, (int) (p.level().getGameTime() + Math.round(s * 20)));
	}

	/** Every server tick: charged Zeuses get their shot back. */
	public static void tick(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			long now = p.level().getGameTime();
			var inv = p.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack s = inv.getItem(i);
				Integer at = s.get(WeaponItems.RECHARGE);
				if (at == null || now < at) continue;
				s.remove(WeaponItems.RECHARGE);
				s.set(WeaponItems.CLIP, 1);
			}
		}
	}

	/** Charging progress 0..1 (1 when charged or not charging), from the stack's RECHARGE tick. */
	public static float progress(ItemStack s, long gameTime) {
		Integer at = s.get(WeaponItems.RECHARGE);
		if (at == null) return 1;
		double total = Math.max(1, seconds() * 20);
		return (float) Math.max(0, Math.min(1, 1 - (at - gameTime) / total));
	}
}
