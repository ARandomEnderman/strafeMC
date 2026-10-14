package dev.strafemc.client.viewmodel;

/** Prints a kit's pattern roll bounds and the rolls for a few templates. */
public final class RollDump {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		for (String kit : a) {
			var b = WeaponSkinCache.rollBounds(kit);
			var p = WeaponSkinCache.paintParams(kit);
			System.out.println(kit + " style " + p.f("F_PAINT_STYLE", -1) + " vmat rot " + p.f("g_flPatternTexCoordRotation", 0) + " off "
					+ java.util.Arrays.toString(p.v("g_vPatternTexCoordOffset", 0, 0)) + " bounds " + b);
			if (b != null) for (int s : new int[] {0, 1, 412}) System.out.println("   " + s + " " + dev.strafemc.weapon.PatternRoll.roll(s, b));
		}
	}
}
