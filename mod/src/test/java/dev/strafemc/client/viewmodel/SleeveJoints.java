package dev.strafemc.client.viewmodel;

/** Which joints of an agent's first-person sleeves a clip can't pose: gradlew vmCheck -PvmMain=dev.strafemc.client.viewmodel.SleeveJoints -PvmArgs=<agent model>,<class:anim>... */
public final class SleeveJoints {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		VmModel s = VmModel.load(Glb.read(dev.strafemc.data.AssetCache.exportGlb(a[0] + "_c", true)), ".firstperson_sleeves");
		VmModel arms = VmModel.load(Glb.read(dev.strafemc.data.AssetCache.exportGlb(Viewmodel.ARMS, true)), ".viewmodel");
		System.out.println("sleeve parts " + s.parts.size() + " skins " + s.skins.size());
		for (int i = 1; i < a.length; i++) {
			String[] ca = a[i].split(":");
			VmClip c = new VmClip(ca[1], Glb.read(dev.strafemc.data.AssetCache.exportGlb(dev.strafemc.weapon.GunServer.clipPath(ca[0], ca[1]), false)));
			for (VmModel.Part part : s.parts) {
				VmModel.Skin sk = s.skins.get(part.skin);
				java.util.List<String> miss = new java.util.ArrayList<>();
				for (int j = 0; j < sk.joints.length; j++) {
					int k = j, d = 0;
					while (k >= 0 && c.node(sk.joints[k]) == null && !sk.joints[k].contains("_TWIST") && sk.parent != null && d++ < 64) k = sk.parent[k];
					if (k < 0 || c.node(sk.joints[k]) == null && !sk.joints[k].contains("_TWIST")) miss.add(sk.joints[j]);
				}
				System.out.println(a[i] + " joints " + sk.joints.length + " unposed " + miss);
				StringBuilder direct = new StringBuilder(), viaParent = new StringBuilder();
				for (String jn : sk.joints) (c.node(jn) != null || jn.contains("_TWIST") ? direct : viaParent).append(jn).append(' ');
				System.out.println("  direct: " + direct);
				System.out.println("  via parent: " + viaParent);
				// how much of the mesh hangs on which joints
				double[] w = new double[sk.joints.length];
				for (int v = 0; part.wts != null && v * 4 < part.wts.length; v++) for (int q = 0; q < 4; q++) w[part.jnt[v * 4 + q]] += part.wts[v * 4 + q];
				StringBuilder heavy = new StringBuilder();
				for (int j = 0; j < w.length; j++) if (w[j] > 20) heavy.append(sk.joints[j]).append('=').append(Math.round(w[j])).append(c.node(sk.joints[j]) != null ? "" : "(p)").append(' ');
				System.out.println("  weight: " + heavy);
			}
		}
	}
}
