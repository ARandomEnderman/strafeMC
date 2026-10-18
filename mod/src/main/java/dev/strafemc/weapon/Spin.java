package dev.strafemc.weapon;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 360 kills (ours): how far each player has turned, tick by tick, over the last WINDOW ticks. A kill is a
 * 360 when, looking back from it, the view turned SPIN degrees or more one way (in any stretch ending now).
 */
public final class Spin {
	/** The last 2 seconds; 270 degrees or more counts. */
	static final int WINDOW = 40;
	static final double SPIN = 270;

	private static final class Track {
		final float[] turns = new float[WINDOW];
		int at;
		float lastYaw;
		boolean started;
	}

	private static final Map<UUID, Track> TRACKS = new ConcurrentHashMap<>();

	private Spin() {}

	public static void tick(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Track t = TRACKS.computeIfAbsent(p.getUUID(), k -> new Track());
			float yaw = p.getYRot();
			float d = t.started ? net.minecraft.util.Mth.wrapDegrees(yaw - t.lastYaw) : 0;
			t.started = true;
			t.lastYaw = yaw;
			t.turns[t.at] = d;
			t.at = (t.at + 1) % WINDOW;
		}
		if (server.getTickCount() % 200 == 0) TRACKS.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
	}

	/** Whether the player has turned SPIN degrees one way within the last WINDOW ticks (any stretch up to now). */
	public static boolean spun(ServerPlayer p) {
		Track t = TRACKS.get(p.getUUID());
		if (t == null) return false;
		double sum = 0;
		for (int k = 1; k <= WINDOW; k++) {
			sum += t.turns[Math.floorMod(t.at - k, WINDOW)];
			if (Math.abs(sum) >= SPIN) return true;
		}
		return false;
	}
}
