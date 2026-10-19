package dev.strafemc.client.viewmodel;

/** All of the C4 material's parameters: gradlew vmCheck -PvmMain=dev.strafemc.client.viewmodel.C4Mat */
public final class C4Mat {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		var mat = GloveSkinBaker.Params.parse(java.nio.file.Files.readAllLines(dev.strafemc.data.AssetCache.dataBlock("weapons/models/c4/materials/weapon_c4.vmat_c")));
		for (var e : new java.util.TreeMap<>(mat.values).entrySet()) if (!e.getKey().contains("Sticker") || e.getKey().contains("Offset") || e.getKey().contains("Scale") || e.getKey().contains("Rotation")) System.out.println(e.getKey() + " " + java.util.Arrays.toString(e.getValue()));
		for (var e : mat.textures.entrySet()) System.out.println("tex " + e.getKey() + " " + e.getValue());
	}
}
