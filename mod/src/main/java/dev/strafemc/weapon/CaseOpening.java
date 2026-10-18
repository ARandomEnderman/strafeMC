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
	/** rarity: each drop's (null: the rare special item); Classified and up are announced when handed over. */
	private record Pending(java.util.List<ItemStack> stacks, long dueMs, java.util.List<SkinWraps.Rarity> rarity) {}

	/** Longest reel plus slack: after this the drop is handed out without a claim. */
	private static final long TIMEOUT_MS = 15_000;
	private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

	private CaseOpening() {}

	/** Holds a drop for the player (a still-unclaimed earlier one is handed out first). */
	public static void hold(ServerPlayer p, java.util.List<ItemStack> stacks, java.util.List<SkinWraps.Rarity> rarity) {
		claim(p);
		PENDING.put(p.getUUID(), new Pending(stacks.stream().map(ItemStack::copy).toList(), System.currentTimeMillis() + TIMEOUT_MS, rarity));
	}

	/** The reel landed (or was closed): the player gets their drop. */
	public static void claim(ServerPlayer p) {
		Pending d = PENDING.remove(p.getUUID());
		if (d == null) return;
		for (int i = 0; i < d.stacks().size(); i++) {
			ItemStack s = d.stacks().get(i);
			if (!p.getInventory().add(s.copy())) p.drop(s.copy(), false);
			Advancements.unboxed(p, s, d.rarity().get(i));
			if (p.level().getServer() == null) continue;
			// Classified (pink) and up are told to everyone on the server, StatTrak ones from Restricted (purple); Covert and
			// the rare special items stand out
			SkinWraps.Rarity r = d.rarity().get(i);
			SkinWraps.Rarity from = StatTrak.kills(s) >= 0 ? SkinWraps.Rarity.MYTHICAL : SkinWraps.Rarity.LEGENDARY;
			if (r != null && r.ordinal() < from.ordinal()) continue;
			boolean big = r == null || r.ordinal() >= SkinWraps.Rarity.ANCIENT.ordinal();
			var name = net.minecraft.network.chat.Component.literal(p.getScoreboardName());
			var item = s.getHoverName().copy();
			var msg = big ? net.minecraft.network.chat.Component.literal("★ ").withColor(0xE4AE39) : net.minecraft.network.chat.Component.empty();
			p.level().getServer().getPlayerList().broadcastSystemMessage(msg
					.append(big ? name.withStyle(net.minecraft.ChatFormatting.BOLD) : name.withStyle(net.minecraft.ChatFormatting.WHITE))
					.append(net.minecraft.network.chat.Component.literal(" has opened a container and found: ").withStyle(net.minecraft.ChatFormatting.WHITE))
					.append(big ? item.withStyle(net.minecraft.ChatFormatting.BOLD) : item), false);
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
