package dev.strafemc.client.viewmodel;

import dev.strafemc.data.AssetCache;

/** Prints vertex / index counts of viewmodel meshes (per frame work of the CPU path). */
public final class MeshStats {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		for (String model : a) {
			VmModel m = VmModel.load(Glb.read(AssetCache.exportGlb(model, true)), VmModel.AUTO_MESH);
			int v = 0, i = 0;
			for (VmModel.Part p : m.parts) {
				v += p.pos.length / 3;
				i += p.idx.length;
			}
			System.out.printf("%s parts %d verts %d indices %d (tris %d)%n", model, m.parts.size(), v, i, i / 3);
		}
	}
}
