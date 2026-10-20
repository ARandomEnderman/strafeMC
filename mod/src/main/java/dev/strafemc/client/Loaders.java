package dev.strafemc.client;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One small pool for the background loaders that start in bursts (particle textures, decal and spray textures, effect
 * definitions): a big impact or a new map no longer starts dozens of threads at once. Only for tasks that never wait on
 * each other.
 */
public final class Loaders {
	private static final AtomicInteger N = new AtomicInteger();
	private static final ExecutorService POOL = Executors.newFixedThreadPool(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)), r -> {
		Thread t = new Thread(r, "strafemc-loader-" + N.incrementAndGet());
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	});

	private Loaders() {}

	public static void run(Runnable task) {
		POOL.execute(task);
	}
}
