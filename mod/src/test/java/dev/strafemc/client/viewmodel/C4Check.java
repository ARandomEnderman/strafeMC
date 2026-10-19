package dev.strafemc.client.viewmodel;

/** Dumps what the C4's model and material offer (sticker slots, keychain point): gradlew vmCheck -PvmMain=dev.strafemc.client.viewmodel.C4Check */
public final class C4Check {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		String model = "weapons/models/c4/weapon_c4.vmdl_c";
		Glb glb = Glb.read(dev.strafemc.data.AssetCache.exportGlb(model, true));
		VmModel m = VmModel.load(glb, VmModel.AUTO_MESH);
		for (VmModel.Part p : m.parts) System.out.println("part " + (p.texture == null ? null : p.texture.getFileName()) + " uv1=" + (p.uv1 != null) + " alpha=" + p.alphaMode);
		var mat = GloveSkinBaker.Params.parse(java.nio.file.Files.readAllLines(dev.strafemc.data.AssetCache.dataBlock("weapons/models/c4/materials/weapon_c4.vmat_c")));
		for (var e : mat.values.entrySet()) if (e.getKey().toLowerCase().contains("sticker")) System.out.println(e.getKey() + " " + java.util.Arrays.toString(e.getValue()));
		for (var e : mat.textures.entrySet()) System.out.println("tex " + e.getKey() + " " + e.getValue());
		float[] mn = {9, 9, 9}, mx = {-9, -9, -9};
		for (VmModel.Part p : m.parts) {
			if (p.alphaMode.equals("BLEND")) continue;
			for (int i = 0; i + 2 < p.pos.length; i += 3) for (int k = 0; k < 3; k++) { mn[k] = Math.min(mn[k], p.pos[i + k]); mx[k] = Math.max(mx[k], p.pos[i + k]); }
		}
		System.out.println("bounds " + java.util.Arrays.toString(mn) + " .. " + java.util.Arrays.toString(mx));
		System.out.println("keychain " + java.util.Arrays.toString(Viewmodel.restPoint(model, m, "keychain")));
	}
}
