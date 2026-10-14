package dev.strafemc.client.viewmodel;

/** Prints a kit's paint parameters as the baker sees them. */
public final class ParamDump {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		for (String kit : a) {
			var p = WeaponSkinCache.paintParams(kit);
			System.out.println("== " + kit);
			p.values.forEach((k, v) -> System.out.println("  " + k + " " + java.util.Arrays.toString(v)));
			p.textures.forEach((k, v) -> System.out.println("  " + k + " " + v));
		}
	}
}
