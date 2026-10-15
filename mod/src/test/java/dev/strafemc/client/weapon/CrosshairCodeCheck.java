package dev.strafemc.client.weapon;

/** Export then import a crosshair share code: every setting should come back. gradlew vmCheck -PvmMain=dev.strafemc.client.weapon.CrosshairCodeCheck */
public final class CrosshairCodeCheck {
	public static void main(String[] a) throws Exception {
		// a throwaway settings folder: this saves, and must never touch the real strafemc.properties
		System.setProperty("strafemc.runtime", java.nio.file.Files.createTempDirectory("strafemc-test").toString());
		var c = dev.strafemc.config.StrafeConfig.crosshair;
		c.put("cl_crosshairstyle", 4.0); c.put("cl_crosshairsize", 2.5); c.put("cl_crosshairthickness", 1.0); c.put("cl_crosshairgap", -2.0);
		c.put("cl_crosshaircolor", 5.0); c.put("cl_crosshaircolor_r", 255.0); c.put("cl_crosshaircolor_g", 0.0); c.put("cl_crosshaircolor_b", 128.0);
		c.put("cl_crosshairdot", 1.0); c.put("cl_crosshair_t", 1.0); c.put("cl_crosshair_recoil", 1.0); c.put("cl_crosshair_outlinethickness", 1.5);
		java.util.Map<String, Double> before = new java.util.TreeMap<>();
		for (String k : Crosshair.DEFAULTS.keySet()) before.put(k, Crosshair.get(k));
		String code = Crosshair.export();
		System.out.println("code " + code);
		c.clear();
		System.out.println("applied " + Crosshair.apply(code));
		for (var e : before.entrySet()) {
			double now = Crosshair.get(e.getKey());
			if (Math.abs(now - e.getValue()) > 1e-6) System.out.println("DIFF " + e.getKey() + " " + e.getValue() + " -> " + now);
		}
		System.out.println("done");
	}
}
