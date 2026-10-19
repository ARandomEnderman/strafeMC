package dev.strafemc.client.viewmodel;

import java.awt.image.BufferedImage;

/** Draws the C4 mesh in its second UV set (sticker space) with its colour texture: gradlew vmCheck -PvmMain=dev.strafemc.client.viewmodel.C4Uv1 -PvmArgs=<out.png> */
public final class C4Uv1 {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		VmModel m = VmModel.load(Glb.read(dev.strafemc.data.AssetCache.exportGlb(dev.strafemc.weapon.C4Item.MODEL, true)), VmModel.AUTO_MESH);
		int n = 512;
		BufferedImage out = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
		double umin = 9, umax = -9, vmin = 9, vmax = -9;
		for (VmModel.Part p : m.parts) {
			if (p.uv1 == null || p.alphaMode.equals("BLEND")) continue;
			Viewmodel.downscaled(p.texture);
			BufferedImage tex = javax.imageio.ImageIO.read(Viewmodel.downscaledPath(p.texture).toFile());
			for (int i = 0; i + 1 < p.uv1.length; i += 2) {
				umin = Math.min(umin, p.uv1[i]); umax = Math.max(umax, p.uv1[i]); vmin = Math.min(vmin, p.uv1[i + 1]); vmax = Math.max(vmax, p.uv1[i + 1]);
			}
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
						out.setRGB(x, y, 0xFF000000 | GloveSkinBaker.sample(tex, tu - Math.floor(tu), tv - Math.floor(tv)));
					}
			}
		}
		System.out.printf("uv1 u %.3f..%.3f v %.3f..%.3f%n", umin, umax, vmin, vmax);
		javax.imageio.ImageIO.write(out, "png", new java.io.File(a[0]));
	}
}
