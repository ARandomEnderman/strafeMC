package dev.strafemc.client.viewmodel;

/**
 * Picks the C4's starting sticker places: where a sticker of the C4's size has most of itself on its front inside its
 * sticker mask, five that don't overlap much: gradlew vmCheck -PvmMain=dev.strafemc.client.viewmodel.C4Slots
 */
public final class C4Slots {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		VmModel m = VmModel.load(Glb.read(dev.strafemc.data.AssetCache.exportGlb(dev.strafemc.weapon.C4Item.MODEL, true)), VmModel.AUTO_MESH);
		java.awt.image.BufferedImage mask = GloveSkinCache.load("weapons/models/c4/materials/stickers/weapon_c4_sticker_mask_psd_a9cf6509.vtex");
		float[] front = SideView.front(dev.strafemc.weapon.C4Item.CLS, m.parts);
		System.out.println("front " + java.util.Arrays.toString(front));
		int n = 256;
		boolean[] ok = new boolean[n * n];
		for (VmModel.Part p : m.parts) {
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
						boolean in = (GloveSkinBaker.sample(mask, tu - Math.floor(tu), tv - Math.floor(tv)) >> 16 & 255) > 127;
						double nx = wa * p.nrm[v[0] * 3] + wb * p.nrm[v[1] * 3] + wc * p.nrm[v[2] * 3], ny = wa * p.nrm[v[0] * 3 + 1] + wb * p.nrm[v[1] * 3 + 1] + wc * p.nrm[v[2] * 3 + 1],
								nz = wa * p.nrm[v[0] * 3 + 2] + wb * p.nrm[v[1] * 3 + 2] + wc * p.nrm[v[2] * 3 + 2];
						ok[y * n + x] = in && SideView.facesFront(front, nx, ny, nz);
					}
			}
		}
		int half = (int) Math.round(n / dev.strafemc.weapon.StickerSlots.C4_SCALE / 2);
		java.util.List<double[]> cand = new java.util.ArrayList<>();
		for (int y = half; y < n - half; y += 2)
			for (int x = half; x < n - half; x += 2) {
				int c = 0, tot = 0;
				for (int j = -half; j < half; j++)
					for (int i = -half; i < half; i++) {
						tot++;
						if (ok[(y + j) * n + x + i]) c++;
					}
				cand.add(new double[] {(double) c / tot, (x + 0.5) / n, (y + 0.5) / n});
			}
		cand.sort((p, q) -> Double.compare(q[0], p[0]));
		java.util.List<double[]> pick = new java.util.ArrayList<>();
		for (double[] c : cand) {
			boolean far = true;
			for (double[] p : pick) if (Math.abs(p[1] - c[1]) < 1.6 * half / n && Math.abs(p[2] - c[2]) < 1.6 * half / n) far = false;
			if (far) pick.add(c);
			if (pick.size() == 5) break;
		}
		for (double[] p : pick) System.out.printf("slot u %.3f v %.3f covered %.2f%n", p[1], p[2], p[0]);
	}
}
