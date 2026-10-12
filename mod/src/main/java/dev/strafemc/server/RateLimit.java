package dev.strafemc.server;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player token buckets for strafeMC's client messages, so nobody can flood the server (or its log) with them.
 * Each kind of message gets its own rate; bursts up to one second's worth are fine.
 */
public final class RateLimit {
	private static final Map<String, double[]> BUCKETS = new ConcurrentHashMap<>(); // key -> {tokens, lastNanos}

	private RateLimit() {}

	/** True if this message may be handled; false = drop it silently. */
	public static boolean allow(ServerPlayer p, String kind, double perSecond) {
		String key = p.getUUID() + "/" + kind;
		long now = System.nanoTime();
		double[] b = BUCKETS.computeIfAbsent(key, k -> new double[] {perSecond, now});
		synchronized (b) {
			b[0] = Math.min(perSecond, b[0] + (now - (long) b[1]) / 1e9 * perSecond);
			b[1] = now;
			if (b[0] < 1) return false;
			b[0] -= 1;
			return true;
		}
	}

	public static void forget(ServerPlayer p) {
		String prefix = p.getUUID() + "/";
		BUCKETS.keySet().removeIf(k -> k.startsWith(prefix));
	}

	/** Players with their UUID, for the occasional "denied" log line instead of one per request. */
	private static final Map<UUID, Long> LAST_DENIED_LOG = new ConcurrentHashMap<>();

	/** True at most once per 10 s per player: whether to write a denied request to the log. */
	public static boolean logDenied(ServerPlayer p) {
		long now = System.currentTimeMillis();
		Long last = LAST_DENIED_LOG.get(p.getUUID());
		if (last != null && now - last < 10_000) return false;
		LAST_DENIED_LOG.put(p.getUUID(), now);
		return true;
	}
}
