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
		System.out.println("keychain " + java.util.Arrays.toString(Viewmodel.restPoint(model, m, "keychain")));
	}
}
