package dev.strafemc.client.viewmodel;

/** Each mesh part's bounds and its sticker-space u range: gradlew vmCheck -PvmMain=dev.strafemc.client.viewmodel.PartBounds -PvmArgs=<class> */
public final class PartBounds {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		String[] w = Viewmodel.weaponModel(a[0]);
		VmModel m = VmModel.load(Glb.read(dev.strafemc.data.AssetCache.exportGlb(w[0], true)), w[1]);
		for (VmModel.Part p : m.parts) {
			float[] mn = {9, 9, 9}, mx = {-9, -9, -9};
			for (int i = 0; i + 2 < p.pos.length; i += 3) for (int k = 0; k < 3; k++) { mn[k] = Math.min(mn[k], p.pos[i + k]); mx[k] = Math.max(mx[k], p.pos[i + k]); }
			// vertices by sticker-space half: left (u < 0.5) and right
			double lx = 0, rx = 0; int ln = 0, rn = 0;
			if (p.uv1 != null) for (int i = 0; i * 2 < p.uv1.length; i++) { if (p.uv1[i * 2] < 0.5) { lx += p.pos[i * 3]; ln++; } else { rx += p.pos[i * 3]; rn++; } }
			System.out.printf("%s %s..%s left-u mean x %.3f (%d) right-u mean x %.3f (%d)%n", p.texture == null ? null : p.texture.getFileName(), java.util.Arrays.toString(mn), java.util.Arrays.toString(mx), ln == 0 ? 0 : lx / ln, ln, rn == 0 ? 0 : rx / rn, rn);
		}
	}
}
