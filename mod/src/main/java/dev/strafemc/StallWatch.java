package dev.strafemc;

/**
 * Finds out where a freeze is: the game (client ticks) and the server (server ticks) each beat here; when one hasn't
 * beaten for STALL_MS while its thread is busy (running or blocked, not sleeping as a paused singleplayer server does),
 * that thread's stack goes to the log once, and how long it stalled when it beats again.
 */
public final class StallWatch {
	private static final long STALL_MS = 3000;
	private static final Beat CLIENT = new Beat("game"), SERVER = new Beat("server");
	private static volatile boolean started;

	private StallWatch() {}

	private static final class Beat {
		final String name;
		volatile Thread thread;
		volatile long at;
		volatile boolean reported;

		Beat(String name) {
			this.name = name;
		}

		void beat() {
			long now = System.currentTimeMillis();
			if (reported) StrafeMc.LOG.warn("[strafemc] stall watch: the {} thread runs again after {} ms", name, now - at);
			reported = false;
			thread = Thread.currentThread();
			at = now;
			start();
		}

		void check(long now) {
			Thread t = thread;
			if (t == null || reported || now - at < STALL_MS || !t.isAlive()) return;
			Thread.State s = t.getState();
			if (s != Thread.State.RUNNABLE && s != Thread.State.BLOCKED) return;
			reported = true;
			StringBuilder b = new StringBuilder();
			for (StackTraceElement e : t.getStackTrace()) {
				b.append("\n\tat ").append(e);
				if (b.length() > 12000) break;
			}
			StrafeMc.LOG.warn("[strafemc] stall watch: the {} thread ({}) has been stuck {} ms, {}:{}", name, t.getName(), now - at, s, b);
		}
	}

	public static void clientTick() {
		CLIENT.beat();
	}

	public static void serverTick() {
		SERVER.beat();
	}

	/** The server stopped: nothing to watch until it runs again. */
	public static void serverStopped() {
		SERVER.thread = null;
	}

	private static synchronized void start() {
		if (started) return;
		started = true;
		Thread w = new Thread(() -> {
			while (true) {
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					return;
				}
				long now = System.currentTimeMillis();
				CLIENT.check(now);
				SERVER.check(now);
			}
		}, "strafemc-stallwatch");
		w.setDaemon(true);
		w.start();
	}
}
