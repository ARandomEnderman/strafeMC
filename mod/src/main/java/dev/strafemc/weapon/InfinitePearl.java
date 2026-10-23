package dev.strafemc.weapon;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * strafemc_infinite_pearl (ours): a Strafe Pearl that never runs out. Throwing doesn't use it up, and a player without
 * one gets one back (offhand if free) every half second, so game modes that empty the inventory on each spawn keep it
 * too. Per player, until the server stops.
 */
public final class InfinitePearl {
	private static final Set<UUID> ON = ConcurrentHashMap.newKeySet();

	private InfinitePearl() {}

	public static boolean on(Player p) {
		return ON.contains(p.getUUID());
	}

	public static void set(ServerPlayer p, boolean on) {
		if (on) {
			ON.add(p.getUUID());
			topUp(p);
		} else ON.remove(p.getUUID());
	}

	public static void tick(MinecraftServer server) {
		if (ON.isEmpty() || server.getTickCount() % 10 != 0) return;
		for (UUID id : ON) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) topUp(p);
		}
	}

	private static void topUp(ServerPlayer p) {
		if (!p.isAlive() || p.isSpectator()) return;
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(WeaponItems.STRAFE_PEARL)) return;
		ItemStack pearl = new ItemStack(WeaponItems.STRAFE_PEARL);
		if (p.getOffhandItem().isEmpty()) p.setItemInHand(InteractionHand.OFF_HAND, pearl);
		else inv.add(pearl);
	}
}
