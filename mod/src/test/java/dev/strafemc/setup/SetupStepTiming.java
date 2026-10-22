package dev.strafemc.setup;

import java.nio.file.Path;
import java.util.Map;

/**
 * One setup step headless, with every progress message timed (counters stripped): finds where a step's time goes.
 * Needs a runtime that already has the converter (SetupTiming's): JAVA_TOOL_OPTIONS=-Dstrafemc.runtime=<dir> gradlew
 * vmCheck -PvmMain=dev.strafemc.setup.SetupStepTiming -PvmArgs=<CS2 folder>,<step>
 */
public final class SetupStepTiming {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		Path cs2 = Path.of(a[0]), converter = Path.of(dev.strafemc.config.StrafeConfig.vrfPath);
		String[] last = {""};
		long[] at = {System.nanoTime()};
		long t0 = at[0];
		Setup s = new Setup(cs2, converter, dev.strafemc.config.StrafeConfig.cacheDir(), (f, msg) -> {
			String name = msg.replaceAll("\\(.*$", "").replaceAll("\\d+", "#").trim();
			if (name.equals(last[0])) return;
			long now = System.nanoTime();
			if (!last[0].isEmpty()) System.out.printf("  %-50s %7.1f s%n", last[0], (now - at[0]) / 1e9);
			last[0] = name;
			at[0] = now;
		});
		var vrfField = Setup.class.getDeclaredField("vrf");
		vrfField.setAccessible(true);
		Map<String, Long> pak = ((Vrf) vrfField.get(s)).list(null);
		System.out.printf("  %-50s %7.1f s%n", "pak list", (System.nanoTime() - t0) / 1e9);
		at[0] = System.nanoTime();
		for (String step : a[1].split(";")) {
			var m = java.util.Arrays.stream(Setup.class.getDeclaredMethods()).filter(x -> x.getName().equals(step)).findFirst().orElseThrow();
			m.setAccessible(true);
			if (m.getParameterCount() == 3) m.invoke(s, pak, 0.0, 1.0);
			else m.invoke(s, 0.0, 1.0);
		}
		System.out.printf("  %-50s %7.1f s%n", last[0], (System.nanoTime() - at[0]) / 1e9);
		System.out.printf("total %.1f s%n", (System.nanoTime() - t0) / 1e9);
	}
}
