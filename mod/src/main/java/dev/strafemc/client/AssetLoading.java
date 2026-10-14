package dev.strafemc.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * Background asset loads (weapon viewmodels, gloves and their finishes) for the line at the top of the screen, the same
 * place the first-time setup shows its progress. Shown only for loads that take longer than a moment.
 */
public final class AssetLoading {
	private static final Map<String, Long> ACTIVE = new ConcurrentHashMap<>();
	private static final long SHOW_AFTER_NS = 300_000_000L;
	/** How many loads are still queued after the running ones (set by the viewmodel preloader). */
	private static volatile IntSupplier queued = () -> 0;

	private AssetLoading() {}

	public static void begin(String what) {
		ACTIVE.put(what, System.nanoTime());
	}

	public static void end(String what) {
		ACTIVE.remove(what);
	}

	public static void queue(IntSupplier remaining) {
		queued = remaining;
	}

	/** The line to show, or null when nothing has been loading for long. */
	public static String line() {
		long now = System.nanoTime();
		String shown = null;
		for (var e : ACTIVE.entrySet())
			if (now - e.getValue() > SHOW_AFTER_NS) {
				shown = e.getKey();
				break;
			}
		if (shown == null) return null;
		int more = ACTIVE.size() - 1 + Math.max(0, queued.getAsInt());
		return "§7[strafeMC] Loading CS2 assets: §f" + shown + (more > 0 ? "§7 (" + more + " more)" : "");
	}
}
