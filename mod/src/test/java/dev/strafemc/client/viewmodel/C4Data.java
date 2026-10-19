package dev.strafemc.client.viewmodel;

/** Prints CS2's C4 weapon data and its viewmodel clips' timings: gradlew vmCheck -PvmMain=dev.strafemc.client.viewmodel.C4Data */
public final class C4Data {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		var d = dev.strafemc.data.WeaponData.get("weapon_c4");
		if (d != null) for (var e : new java.util.TreeMap<>(d).entrySet()) if (!(e.getValue() instanceof java.util.Map)) System.out.println("vdata " + e.getKey() + " = " + e.getValue());
		for (String c : new String[] {"draw", "idle", "plant"}) {
			var clip = dev.strafemc.data.AnimClip.load("animation/anims/viewmodel/equipment/c4/" + c + "_c4.vnmclip_c");
			System.out.println("clip " + c + " " + clip.duration());
			for (var e : clip.events()) System.out.println("   " + e);
		}
	}
}
