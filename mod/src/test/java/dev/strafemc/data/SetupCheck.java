package dev.strafemc.data;

import java.nio.file.Path;

/** Runs the whole first-time setup into a separate folder and reports size and time: gradlew vmCheck -PvmMain=...SetupCheck -PvmArgs=<cs2>,<converter>,<out> */
public final class SetupCheck {
	public static void main(String[] a) throws Exception {
		long t = System.nanoTime();
		var setup = new dev.strafemc.setup.Setup(Path.of(a[0]), Path.of(a[1]), Path.of(a[2]),
				(f, what) -> System.out.printf("[%5.1f%%] %s (%.0f s)%n", f * 100, what, (System.nanoTime() - t) / 1e9));
		setup.run(true);
		var st = dev.strafemc.setup.Setup.status(Path.of(a[2]));
		long bytes;
		try (var s = java.nio.file.Files.walk(Path.of(a[2]))) {
			bytes = s.filter(java.nio.file.Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
		}
		System.out.printf("done in %.0f s, %.0f MB, status %s%n", (System.nanoTime() - t) / 1e9, bytes / 1e6, st);
	}
}
