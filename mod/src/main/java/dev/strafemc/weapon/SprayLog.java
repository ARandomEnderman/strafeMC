package dev.strafemc.weapon;

import dev.strafemc.net.StrafePayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * The graffiti sprayed in the last LIFE_S seconds, kept by the server so a player who joins (or comes back, or changes
 * dimension) sees them too, with their age so they fade on time. Only in memory: a server restart forgets them.
 */
public final class SprayLog {
	/** How long a graffiti stays (SprayDecals fades it out over its last two minutes). FLAGGED ours. */
	public static final int LIFE_S = 15 * 60;

	private record Entry(ResourceKey<Level> dim, StrafePayloads.SprayPlaced msg, long atMs) {}

	private static final List<Entry> ALL = new ArrayList<>();

	private SprayLog() {}

	static synchronized void add(Level level, StrafePayloads.SprayPlaced msg) {
		long now = System.currentTimeMillis();
		ALL.removeIf(e -> now - e.atMs() >= LIFE_S * 1000L);
		ALL.add(new Entry(level.dimension(), msg, now));
		while (ALL.size() > 2048) ALL.remove(0);
	}

	/** Sends the player every graffiti still up in their dimension. */
	public static synchronized void sendTo(ServerPlayer p) {
		if (!ServerPlayNetworking.canSend(p, StrafePayloads.SprayPlaced.TYPE)) return;
		long now = System.currentTimeMillis();
		ALL.removeIf(e -> now - e.atMs() >= LIFE_S * 1000L);
		for (Entry e : ALL) {
			if (e.dim() != p.level().dimension()) continue;
			var m = e.msg();
			ServerPlayNetworking.send(p, new StrafePayloads.SprayPlaced(m.spray(), m.tint(), m.x(), m.y(), m.z(), m.face(), m.block(), m.yaw(), m.owner(),
					(int) ((now - e.atMs()) / 1000)));
		}
	}

	public static void register() {
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((h, sender, server) -> server.execute(() -> sendTo(h.player)));
		net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((p, from, to) -> sendTo(p));
		net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((old, p, alive) -> sendTo(p));
	}
}
