package dev.strafemc.client.viewmodel;

import org.joml.Matrix4f;

/** Camera-space bounds of the arms and an agent's sleeves through a clip: gradlew vmCheck -PvmMain=dev.strafemc.client.viewmodel.SleeveBounds -PvmArgs=<agent model>,<class:anim>... */
public final class SleeveBounds {
	static String bounds(VmModel m, VmClip c, Matrix4f[] pose) {
		Matrix4f[][] sk = Viewmodel.skins(m, c, pose, null);
		float[] mn = {99, 99, 99}, mx = {-99, -99, -99};
		for (VmModel.Part p : m.parts) {
			Matrix4f[] s = p.skin >= 0 && p.skin < sk.length ? sk[p.skin] : new Matrix4f[0];
			float[] v = Viewmodel.skinVertices(p, s, Viewmodel.toView())[0];
			for (int i = 0; i + 2 < v.length; i += 3) for (int k = 0; k < 3; k++) { mn[k] = Math.min(mn[k], v[i + k]); mx[k] = Math.max(mx[k], v[i + k]); }
		}
		return String.format("x %.2f..%.2f y %.2f..%.2f z %.2f..%.2f", mn[0], mx[0], mn[1], mx[1], mn[2], mx[2]);
	}

	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		VmModel s = VmModel.load(Glb.read(dev.strafemc.data.AssetCache.exportGlb(a[0] + "_c", true)), ".firstperson_sleeves");
		for (VmModel.Part p : s.parts) Viewmodel.armsOnly(s, p);
		VmModel arms = VmModel.load(Glb.read(dev.strafemc.data.AssetCache.exportGlb(Viewmodel.ARMS, true)), ".viewmodel");
		for (int i = 1; i < a.length; i++) {
			String[] ca = a[i].split(":");
			VmClip c = new VmClip(ca[1], Glb.read(dev.strafemc.data.AssetCache.exportGlb(dev.strafemc.weapon.GunServer.clipPath(ca[0], ca[1]), false)));
			for (double t : new double[] {0, 0.1, 0.3, 0.6}) {
				Matrix4f[] pose = c.pose((float) t);
				System.out.println(a[i] + " t" + t + " arms " + bounds(arms, c, pose) + " | sleeves " + bounds(s, c, pose));
			}
		}
	}
}
