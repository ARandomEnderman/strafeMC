package dev.strafemc.weapon;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A rolled case drop waits until the client's reel lands on it (or the player closes the reel), as in CS2: the case and
 * key are used up at once, the item handed out on the claim. Unclaimed drops go out anyway after a while or when the
 * player leaves, so nothing is ever lost.
 */
public final class CaseOpening {
	/** announce: Covert or a rare special item, told to everyone when it's handed over (as CS2 does). */
	private record Pending(java.util.List<ItemStack> stacks, long dueMs, java.util.List<Boolean> announce) {}

	/** Longest reel plus slack: after this the drop is handed out without a claim. */
	private static final long TIMEOUT_MS = 15_000;
	private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

	private CaseOpening() {}

	/** Holds a drop for the player (a still-unclaimed earlier one is handed out first). */
	public static void hold(ServerPlayer p, java.util.List<ItemStack> stacks, java.util.List<Boolean> announce) {
		claim(p);
		PENDING.put(p.getUUID(), new Pending(stacks.stream().map(ItemStack::copy).toList(), System.currentTimeMillis() + TIMEOUT_MS, announce));
	}

	/** The reel landed (or was closed): the player gets their drop. */
	public static void claim(ServerPlayer p) {
		Pending d = PENDING.remove(p.getUUID());
		if (d == null) return;
		for (int i = 0; i < d.stacks().size(); i++) {
			ItemStack s = d.stacks().get(i);
			if (!p.getInventory().add(s.copy())) p.drop(s.copy(), false);
			if (d.announce().get(i) && p.level().getServer() != null) // the big one: everyone hears about it
				p.level().getServer().getPlayerList().broadcastSystemMessage(net.minecraft.network.chat.Component.literal("★ ").withColor(0xE4AE39)
						.append(net.minecraft.network.chat.Component.literal(p.getScoreboardName()).withStyle(net.minecraft.ChatFormatting.BOLD))
						.append(net.minecraft.network.chat.Component.literal(" has opened a container and found: "))
						.append(s.getHoverName().copy().withStyle(net.minecraft.ChatFormatting.BOLD)), false);
		}
	}

	/** Every second: drops nobody claimed in time go out. */
	public static void tick(MinecraftServer server) {
		if (PENDING.isEmpty() || server.getTickCount() % 20 != 0) return;
		long now = System.currentTimeMillis();
		for (var e : PENDING.entrySet()) {
			if (e.getValue().dueMs() > now) continue;
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p != null) claim(p);
		}
	}
}
