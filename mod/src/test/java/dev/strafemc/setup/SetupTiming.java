package dev.strafemc.setup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The first-time setup headless and timed, as the setup screen runs it: the converter download, then every step into
 * an empty cache. Run with a fresh runtime folder: JAVA_TOOL_OPTIONS=-Dstrafemc.runtime=<empty dir> gradlew vmCheck
 * -PvmMain=dev.strafemc.setup.SetupTiming -PvmArgs=<CS2 folder>
 */
public final class SetupTiming {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		Path runtime = dev.strafemc.config.StrafeConfig.RUNTIME_DIR, cs2 = Path.of(a[0]);
		long t0 = System.nanoTime();
		Path converter = ConverterDownload.install(runtime.resolve("tools").resolve("vrf"), (f, s) -> {});
		dev.strafemc.config.StrafeConfig.vrfPath = converter.toString();
		dev.strafemc.config.StrafeConfig.cs2Path = cs2.toString();
		dev.strafemc.config.StrafeConfig.save(); // later headless checks on this runtime find the converter and CS2
		long t1 = System.nanoTime();
		System.out.printf("converter download: %.1f s%n", (t1 - t0) / 1e9);
		Map<String, Long> stepStart = new LinkedHashMap<>();
		String[] last = {""};
		long[] lastAt = {t1};
		new Setup(cs2, converter, dev.strafemc.config.StrafeConfig.cacheDir(), (f, s) -> {
			// the step's name is the progress text up to its first ':' or '(' (the rest counts files)
			String name = s.replaceAll("[:(\\d].*$", "").trim();
			if (!name.equals(last[0])) {
				long now = System.nanoTime();
				if (!last[0].isEmpty()) System.out.printf("  %-45s %6.1f s%n", last[0], (now - lastAt[0]) / 1e9);
				last[0] = name;
				lastAt[0] = now;
				stepStart.put(name, now);
			}
		}).run(true);
		long t2 = System.nanoTime();
		System.out.printf("  %-45s %6.1f s%n", last[0], (t2 - lastAt[0]) / 1e9);
		System.out.printf("setup: %.1f s, total with download: %.1f s%n", (t2 - t1) / 1e9, (t2 - t0) / 1e9);
		long[] size = {0, 0};
		try (var s = Files.walk(dev.strafemc.config.StrafeConfig.cacheDir())) {
			s.filter(Files::isRegularFile).forEach(p -> {
				size[0]++;
				try {
					size[1] += Files.size(p);
				} catch (Exception ignored) {
				}
			});
		}
		System.out.printf("cache: %d files, %.0f MB%n", size[0], size[1] / 1e6);
		System.out.println("status: " + Setup.status(dev.strafemc.config.StrafeConfig.cacheDir()));
	}
}
