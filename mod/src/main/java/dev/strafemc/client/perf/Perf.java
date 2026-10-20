package dev.strafemc.client.perf;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * strafemc_perf: how long strafeMC's own work takes per frame on the render thread, and how much memory it allocates,
 * averaged over the last second. Sections are timed with start()/end(); counters (decals drawn, particles alive...)
 * with count(). Costs one boolean check per section while off.
 */
public final class Perf {
	public static volatile boolean on;

	private static final class Stat {
		long ns, maxNs, frameNs, bytes, count, frameCount;
		// last second's results
		double avgMs, maxMs, kb, avgCount;
	}

	private static final Map<String, Stat> STATS = new LinkedHashMap<>();
	private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
	private static final long[] ALLOC = new long[16];
	private static int depth;
	private static long windowStart, lastFrame, frames, frameNsSum, frameNsMax;
	private static double fps, frameMs, frameMaxMs;

	private Perf() {}

	/** Start of a section (render thread). Returns the start time, or 0 while off. */
	public static long start() {
		if (!on) return 0;
		if (depth < ALLOC.length) ALLOC[depth] = THREADS.getCurrentThreadAllocatedBytes();
		depth++;
		return System.nanoTime();
	}

	public static void end(String id, long t0) {
		if (t0 == 0) return;
		long dt = System.nanoTime() - t0;
		depth = Math.max(0, depth - 1);
		long bytes = depth < ALLOC.length ? THREADS.getCurrentThreadAllocatedBytes() - ALLOC[depth] : 0;
		Stat s = STATS.computeIfAbsent(id, k -> new Stat());
		s.ns += dt;
		s.frameNs += dt;
		s.bytes += bytes;
	}

	/** A per-frame number (things drawn, alive...), averaged over the frames of the last second. */
	public static void count(String id, long n) {
		if (!on) return;
		STATS.computeIfAbsent(id, k -> new Stat()).frameCount += n;
	}

	/** Start of every rendered frame: closes the last frame's numbers, and each second publishes the averages. */
	public static void frame() {
		if (!on) return;
		long now = System.nanoTime();
		if (lastFrame != 0) {
			long ft = now - lastFrame;
			frames++;
			frameNsSum += ft;
			frameNsMax = Math.max(frameNsMax, ft);
		}
		lastFrame = now;
		for (Stat s : STATS.values()) {
			s.maxNs = Math.max(s.maxNs, s.frameNs);
			s.frameNs = 0;
			s.count += s.frameCount;
			s.frameCount = 0;
		}
		if (windowStart == 0) windowStart = now;
		if (now - windowStart >= 1_000_000_000L && frames > 0) {
			fps = frames * 1e9 / (now - windowStart);
			frameMs = frameNsSum / 1e6 / frames;
			frameMaxMs = frameNsMax / 1e6;
			for (Stat s : STATS.values()) {
				s.avgMs = s.ns / 1e6 / frames;
				s.maxMs = s.maxNs / 1e6;
				s.kb = s.bytes / 1024.0 / frames;
				s.avgCount = s.count / (double) frames;
				s.ns = s.maxNs = s.bytes = s.count = 0;
			}
			frames = frameNsSum = frameNsMax = 0;
			windowStart = now;
			shown = lines();
		}
	}

	public static void toggle(boolean v) {
		on = v;
		STATS.clear();
		shown = List.of();
		depth = 0;
		windowStart = lastFrame = frames = frameNsSum = frameNsMax = 0;
	}

	/** The last second's numbers as text lines (also used by the bench's log). */
	public static List<String> lines() {
		List<String> out = new ArrayList<>();
		out.add(String.format("strafeMC perf  %.0f fps  frame %.2f ms (max %.1f)", fps, frameMs, frameMaxMs));
		for (var e : STATS.entrySet()) {
			Stat s = e.getValue();
			if (s.avgCount > 0 && s.avgMs == 0) out.add(String.format("  %-16s %8.1f", e.getKey(), s.avgCount));
			else out.add(String.format("  %-16s %6.3f ms  max %5.2f  %7.1f KB", e.getKey(), s.avgMs, s.maxMs, s.kb));
		}
		return out;
	}

	/** The overlay's text, made once a second when the numbers change. */
	private static List<String> shown = List.of();

	public static void hud(GuiGraphicsExtractor g) {
		if (!on || shown.isEmpty()) return;
		var font = Minecraft.getInstance().font;
		List<String> l = shown;
		int w = 0;
		for (String s : l) w = Math.max(w, font.width(s));
		g.fill(2, 2, 8 + w, 6 + l.size() * 10, 0xA0000000);
		for (int i = 0; i < l.size(); i++) g.text(font, l.get(i), 5, 5 + i * 10, i == 0 ? 0xFFFFC864 : 0xFFE0E0E0, false);
	}
}
