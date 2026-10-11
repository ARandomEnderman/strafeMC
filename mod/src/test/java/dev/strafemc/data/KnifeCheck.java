package dev.strafemc.data;

/** Prints the falchion's viewmodel mesh node names and its clips' events. */
public final class KnifeCheck {
	public static void main(String[] a) throws Exception {
		var g = dev.strafemc.client.viewmodel.Glb.read(AssetCache.exportGlb("weapons/models/knife/knife_falchion/weapon_knife_falchion.vmdl_c", true));
		for (var n : g.arr("nodes")) if (n.getAsJsonObject().has("mesh")) System.out.println("mesh node " + n.getAsJsonObject().get("name"));
		for (String c : new String[] {"draw", "idle1", "light_miss1", "light_hit1", "heavy_miss1", "heavy_hit1", "light_backstab", "heavy_backstab", "lookat01"}) {
			AnimClip clip = AnimClip.load("animation/anims/viewmodel/knife/knife_falchion/" + c + "_falchion.vnmclip_c");
			System.out.println("== " + c + " dur=" + (clip == null ? null : clip.duration()));
			if (clip != null) for (AnimClip.Event e : clip.events()) System.out.println("   " + e.kind() + " " + e.name() + " t=" + e.time() + " " + e.extra());
		}
	}
}
