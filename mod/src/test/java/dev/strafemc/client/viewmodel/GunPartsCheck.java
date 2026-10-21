package dev.strafemc.client.viewmodel;

/** A gun's mesh parts, their textures and where their second UV set (sticker space) lies: gradlew vmCheck -PvmMain=dev.strafemc.client.viewmodel.GunPartsCheck -PvmArgs=weapon_ssg08 */
public final class GunPartsCheck {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		String[] w = Viewmodel.weaponModel(a[0]);
		VmModel m = VmModel.load(Glb.read(dev.strafemc.data.AssetCache.exportGlb(w[0], true)), w[1]);
		var paint = WeaponSkinCache.stickerable(a[0], w[0], m.parts);
		System.out.println("model " + w[0] + " mesh " + w[1] + " paint " + paint);
		for (VmModel.Part p : m.parts) {
			float u0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v0 = Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
			int nan = 0;
			if (p.uv1 != null)
				for (int i = 0; i + 1 < p.uv1.length; i += 2) {
					if (Float.isNaN(p.uv1[i])) {
						nan++;
						continue;
					}
					u0 = Math.min(u0, p.uv1[i]);
					u1 = Math.max(u1, p.uv1[i]);
					v0 = Math.min(v0, p.uv1[i + 1]);
					v1 = Math.max(v1, p.uv1[i + 1]);
				}
			System.out.println("part " + (p.texture == null ? null : p.texture.getFileName()) + " verts " + p.pos.length / 3 + " uv1 "
					+ (p.uv1 == null ? "none" : String.format("u %.3f..%.3f v %.3f..%.3f nan %d", u0, u1, v0, v1, nan)));
		}
		// texels under each default sticker slot, at the bake's 1024 and at 2048 / 4096
		var wp = GloveSkinBaker.Params.parse(java.nio.file.Files.readAllLines(dev.strafemc.data.AssetCache.dataBlock(paint.material() + "_c")));
		java.util.List<VmModel.Part> parts = new java.util.ArrayList<>();
		for (VmModel.Part p : m.parts) if (p.texture != null && p.texture.getFileName().toString().startsWith(paint.color())) parts.add(p);
		for (int n : new int[] {1024, 2048, 4096}) {
			float[] side = WeaponSkinCache.secondUv(parts, n);
			StringBuilder sb = new StringBuilder("res " + n + ":");
			for (int i = 0; i < 5; i++) {
				double[] off = wp.v("g_vSticker" + i + "Offset", 0, 0), sc = wp.v("g_vSticker" + i + "Scale", 0, 0);
				if (sc[0] <= 0) continue;
				double cx = 0.5 + off[0], cy = 0.5 + off[1], half = 0.5 / sc[0];
				int count = 0;
				for (int t = 0; t < n * n; t++)
					if (!Float.isNaN(side[2 * t]) && Math.abs(side[2 * t] - cx) < half && Math.abs(side[2 * t + 1] - cy) < half) count++;
				sb.append(" slot").append(i).append('=').append(count);
			}
			System.out.println(sb);
			// a placed sticker (args: cls, then "u,v" centres): texels in its square, and how many the weapon's sticker mask lets through
			java.awt.image.BufferedImage mask = WeaponSkinCache.image(wp, "g_tStickerWepInputs");
			for (int k = 1; k < a.length; k++) {
				String[] f = a[k].split(";");
				double cx = Double.parseDouble(f[0]), cy = Double.parseDouble(f[1]), half = 0.5 / wp.v("g_vSticker0Scale", 0, 0)[0];
				int in = 0, masked = 0;
				for (int t = 0; t < n * n; t++) {
					if (Float.isNaN(side[2 * t]) || Math.abs(side[2 * t] - cx) >= half || Math.abs(side[2 * t + 1] - cy) >= half) continue;
					in++;
					if (mask != null && (GloveSkinBaker.sample(mask, (t % n + 0.5) / n, (t / n + 0.5) / n) >> 16 & 255) > 2) masked++;
				}
				System.out.println("  placed " + a[k] + ": " + in + " texels, " + masked + " through the mask (mask " + (mask == null ? "none" : mask.getWidth() + "px") + ")");
			}
	}
}
}
