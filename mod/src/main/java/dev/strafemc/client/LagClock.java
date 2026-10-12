package dev.strafemc.client;

/**
 * Lag compensation, the client half: which server tick this screen is showing other entities at. The server sends its
 * tick number every tick; Minecraft draws other entities a little behind the newest update (it smooths their movement),
 * so the view is the newest tick plus the time since it arrived, minus that smoothing delay. Sent with every shot and
 * knife attack; the server rewinds to it (server.LagHistory).
 */
public final class LagClock {
	/** Ticks Minecraft's entity smoothing shows others behind their newest update (3-step interpolation, about half). */
	private static final float INTERP_TICKS = 2f;
	private static volatile int latest = -1;
	private static volatile long arrivedNs;

	private LagClock() {}

	public static void onServerTick(int tick) {
		latest = tick;
		arrivedNs = System.nanoTime();
	}

	public static void reset() {
		latest = -1;
	}

	/** The server tick on screen right now, or -1 if unknown (an older server: no rewind). */
	public static float viewTick() {
		if (latest < 0) return -1;
		float since = (System.nanoTime() - arrivedNs) / 50_000_000f;
		return latest + Math.min(since, 2f) - INTERP_TICKS;
	}
}
