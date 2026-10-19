package dev.strafemc.client.viewmodel;

/** Whether a weapon's meshes have the second UV set: gradlew vmCheck -PvmMain=dev.strafemc.client.viewmodel.Uv1Check -PvmArgs=<class>,<mesh>,... */
public final class Uv1Check {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		String[] w = Viewmodel.weaponModel(a[0]);
		Glb glb = Glb.read(dev.strafemc.data.AssetCache.exportGlb(w[0], true));
		for (int i = 1; i < a.length; i++) {
			String mesh = a[i].equals("default") ? w[1] : a[i];
			VmModel m = VmModel.load(glb, mesh);
			for (VmModel.Part p : m.parts) System.out.println(mesh + " " + p.texture.getFileName() + " uv1=" + (p.uv1 != null));
		}
	}
}
