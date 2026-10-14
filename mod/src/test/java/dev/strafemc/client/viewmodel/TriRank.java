package dev.strafemc.client.viewmodel;

import dev.strafemc.data.AssetCache;

/** Ranks Marble Fade seeds by yellow on the paint with the exact triplanar projection, per axis-sign variant. */
public final class TriRank {
	static final int[] GEMS = {412, 16, 146, 241, 359, 393, 541, 602, 649, 688, 701};

	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		if (a.length > 2) System.setProperty("strafemc.region", a[2]);
		String cls = a[0], kit = a[1];
		var e = dev.strafemc.weapon.WeaponTable.get(cls);
		VmModel m = VmModel.load(Glb.read(AssetCache.exportGlb(e.model(), true)), VmModel.AUTO_MESH);
		var pm = WeaponSkinCache.paintable(e.model(), m.parts);
		var parts = new java.util.ArrayList<VmModel.Part>();
		for (VmModel.Part p : m.parts) if (p.texture != null && p.texture.getFileName().toString().startsWith(pm.color())) parts.add(p);
		var pos = WeaponSkinBaker.positions(parts, 128);
		var ci = GloveSkinBaker.Params.parse(java.nio.file.Files.readAllLines(AssetCache.dataBlock(
				java.util.regex.Pattern.compile("\"composite_inputs\"[\\s\\S]*?m_value = \"([^\"]+)\"").matcher(java.nio.file.Files.readString(AssetCache.dataBlock(pm.material() + "_c"))).results().findFirst().get().group(1) + "_c")));
		var masks = GloveSkinCache.load(ci.textures.get("g_tMasks"));
		var kp = WeaponSkinCache.paintParams(kit);
		var pat = GloveSkinCache.load(kp.textures.get("g_tPattern"));
		var bounds = WeaponSkinCache.rollBounds(kit);
		double scale = Math.floor((kp.f("g_flPatternTexCoordScale", 1) + 0.005) * 100) / 100, wl = ci.f("g_flWeaponLength1", 32);
		java.util.List<double[]> px = new java.util.ArrayList<>();
		for (int y = 0; y < 128; y++)
			for (int x = 0; x < 128; x++) {
				int mk = masks.getRGB(x * masks.getWidth() / 128, y * masks.getHeight() / 128);
				double u = (x + 0.5) / 128, v = (y + 0.5) / 128;
				// karambit blade islands only (top-left and bottom-right in its UV layout), when asked
				boolean blade = !"blade".equals(System.getProperty("strafemc.region")) || (u < 0.47 && v > 0.06 && v < 0.36) || (u > 0.74 && v > 0.5);
				if ((mk >> 16 & 255) > 128 && blade) px.add(new double[] {u, v});
			}
		var inp = new WeaponSkinBaker.Inputs(masks, null, ci.f("g_flUvScale1", 1), wl, WeaponSkinCache.posMap(ci), WeaponSkinCache.image(ci, "g_tSurface"));
		System.out.println("posMap " + (inp.posMap() == null ? "none" : (int) inp.posMap()[0]) + " surface " + (inp.surface() != null));
		for (String v : new String[] {"000", "001"}) {
			System.setProperty("strafemc.tri", v);
			System.setProperty("strafemc.tri", v);
			double[][] scs = new double[3][1001];
			for (int s = 0; s <= 1000; s++) {
				var pl = dev.strafemc.weapon.PatternRoll.roll(s, bounds);
				double rot = Math.toRadians(Math.floor((pl.rotation() + 0.005) * 100) / 100), ox = Math.floor((pl.offsetX() + 0.005) * 100) / 100,
						oy = Math.floor((pl.offsetY() + 0.005) * 100) / 100;
				double inv = 0.5 / scale, cn = Math.cos(-rot), sn = Math.sin(-rot), ex = inv * cn - inv * sn, ey = ex * sn + inv * cn, cr = Math.cos(rot), sr = Math.sin(rot);
				double[] xf0 = {scale * cr, -scale * sr, scale * cr * ex - scale * sr * ey + ox - 0.5}, xf1 = {scale * sr, scale * cr, scale * sr * ex + scale * cr * ey + oy - 0.5};
				double[] sum = new double[3];
				for (double[] q : px) {
					int c = WeaponSkinBaker.triplanar(inp, pos, q[0], q[1], pat, xf0, xf1, 0, 0, new double[] {1, 1, 0});
					sum[0] += c >> 16 & 255;
					sum[1] += c >> 8 & 255;
					sum[2] += c & 255;
				}
				for (int k = 0; k < 3; k++) scs[k][s] = -sum[k];
			}
			StringBuilder line = new StringBuilder(cls + " " + v);
			for (int k = 0; k < 3; k++) {
				double[] sc = scs[k];
				Integer[] idx = new Integer[1001];
				for (int i = 0; i <= 1000; i++) idx[i] = i;
				java.util.Arrays.sort(idx, (i, j) -> Double.compare(sc[j], sc[i]));
				var l = java.util.Arrays.asList(idx);
				double mean = 0;
				for (int g : GEMS) mean += l.indexOf(g);
				line.append(String.format("  least-%s mean %.0f 412@%d", "RGB".charAt(k), mean / GEMS.length, l.indexOf(412)));
			}
			System.out.println(line);
		}
	}
}
