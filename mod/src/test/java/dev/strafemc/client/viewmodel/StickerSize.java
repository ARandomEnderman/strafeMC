package dev.strafemc.client.viewmodel;

import java.awt.image.BufferedImage;

/**
 * How big a sticker is on the gun in world size (metres per sticker-space unit / the slot's scale) for some guns, the
 * C4's metres per sticker-space unit, and the C4's sticker mask drawn in its sticker space:
 * gradlew vmCheck -PvmMain=dev.strafemc.client.viewmodel.StickerSize -PvmArgs=<out.png>
 */
public final class StickerSize {
	static double ratio(VmModel m) { // median world length / sticker-space length over the mesh's edges
		java.util.List<Double> r = new java.util.ArrayList<>();
		for (VmModel.Part p : m.parts) {
			if (p.uv1 == null || p.alphaMode.equals("BLEND")) continue;
			for (int t = 0; t + 2 < p.idx.length; t += 3)
				for (int k = 0; k < 3; k++) {
					int a = p.idx[t + k], b = p.idx[t + (k + 1) % 3];
					double dw = Math.sqrt(Math.pow(p.pos[a * 3] - p.pos[b * 3], 2) + Math.pow(p.pos[a * 3 + 1] - p.pos[b * 3 + 1], 2) + Math.pow(p.pos[a * 3 + 2] - p.pos[b * 3 + 2], 2));
					double du = Math.hypot(p.uv1[a * 2] - p.uv1[b * 2], p.uv1[a * 2 + 1] - p.uv1[b * 2 + 1]);
					if (du > 1e-4 && dw > 1e-5 && p.uv1[a * 2] >= 0 && p.uv1[a * 2] <= 1) r.add(dw / du);
				}
		}
		java.util.Collections.sort(r);
		return r.isEmpty() ? Double.NaN : r.get(r.size() / 2);
	}

	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		for (String cls : new String[] {"weapon_deagle", "weapon_glock", "weapon_ak47", "weapon_awp", "weapon_mp9", "weapon_p250"}) {
			String[] w = Viewmodel.weaponModel(cls);
			VmModel m = VmModel.load(Glb.read(dev.strafemc.data.AssetCache.exportGlb(w[0], true)), w[1]);
			var paint = WeaponSkinCache.paintable(w[0], m.parts);
			var mp = GloveSkinBaker.Params.parse(java.nio.file.Files.readAllLines(dev.strafemc.data.AssetCache.dataBlock(paint.material() + "_c")));
			double sc = mp.v("g_vSticker0Scale", 0, 0)[0], ratio = ratio(m);
			System.out.printf("%s ratio %.4f m/unit scale %.3f sticker %.4f m%n", cls, ratio, sc, ratio / sc);
		}
		VmModel c4 = VmModel.load(Glb.read(dev.strafemc.data.AssetCache.exportGlb(dev.strafemc.weapon.C4Item.MODEL, true)), VmModel.AUTO_MESH);
		System.out.printf("weapon_c4 ratio %.4f m/unit%n", ratio(c4));
		// the mask in sticker space
		BufferedImage mask = GloveSkinCache.load("weapons/models/c4/materials/stickers/weapon_c4_sticker_mask_psd_a9cf6509.vtex");
		int n = 256;
		BufferedImage out = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
		for (VmModel.Part p : c4.parts) {
			if (p.uv1 == null || p.alphaMode.equals("BLEND")) continue;
			for (int t = 0; t + 2 < p.idx.length; t += 3) {
				int[] v = {p.idx[t], p.idx[t + 1], p.idx[t + 2]};
				double ax = p.uv1[v[0] * 2] * n, ay = p.uv1[v[0] * 2 + 1] * n, bx = p.uv1[v[1] * 2] * n, by = p.uv1[v[1] * 2 + 1] * n, cx = p.uv1[v[2] * 2] * n, cy = p.uv1[v[2] * 2 + 1] * n;
				double area = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
				if (Math.abs(area) < 1e-9) continue;
				for (int y = (int) Math.max(0, Math.min(ay, Math.min(by, cy))); y <= Math.min(n - 1, Math.max(ay, Math.max(by, cy))); y++)
					for (int x = (int) Math.max(0, Math.min(ax, Math.min(bx, cx))); x <= Math.min(n - 1, Math.max(ax, Math.max(bx, cx))); x++) {
						double px = x + .5, py = y + .5;
						double wa = ((bx - px) * (cy - py) - (cx - px) * (by - py)) / area, wb = ((cx - px) * (ay - py) - (ax - px) * (cy - py)) / area, wc = 1 - wa - wb;
						if (wa < 0 || wb < 0 || wc < 0) continue;
						double tu = wa * p.uv[v[0] * 2] + wb * p.uv[v[1] * 2] + wc * p.uv[v[2] * 2], tv = wa * p.uv[v[0] * 2 + 1] + wb * p.uv[v[1] * 2 + 1] + wc * p.uv[v[2] * 2 + 1];
						int mk = mask == null ? 0 : GloveSkinBaker.sample(mask, tu - Math.floor(tu), tv - Math.floor(tv)) >> 16 & 255;
						out.setRGB(x, y, mk > 127 ? 0xFF40C040 : 0xFF505050);
					}
			}
		}
		javax.imageio.ImageIO.write(out, "png", new java.io.File(a[0]));
	}
}
