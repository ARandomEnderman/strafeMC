package dev.strafemc.client.viewmodel;

import dev.strafemc.data.AssetCache;

/** Ranks a UV-space kit's 1001 templates by blue on the painted blade, for both rotation directions. */
public final class BlueRank {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		String cls = a[0], kit = a[1];
		int[] gems = java.util.Arrays.stream(a[2].split("/")).mapToInt(Integer::parseInt).toArray();
		var e = dev.strafemc.weapon.WeaponTable.get(cls);
		VmModel m = VmModel.load(Glb.read(AssetCache.exportGlb(e.model(), true)), VmModel.AUTO_MESH);
		var pm = WeaponSkinCache.paintable(e.model(), m.parts);
		var ci = GloveSkinBaker.Params.parse(java.nio.file.Files.readAllLines(AssetCache.dataBlock(
				java.util.regex.Pattern.compile("\"composite_inputs\"[\\s\\S]*?m_value = \"([^\"]+)\"").matcher(java.nio.file.Files.readString(AssetCache.dataBlock(pm.material() + "_c"))).results().findFirst().get().group(1) + "_c")));
		var masks = GloveSkinCache.load(ci.textures.get("g_tMasks"));
		var kp = WeaponSkinCache.paintParams(kit);
		var pat = GloveSkinCache.load(kp.textures.get("g_tPattern"));
		var bounds = WeaponSkinCache.rollBounds(kit);
		double scale = Math.floor((kp.f("g_flPatternTexCoordScale", 1) + 0.005) * 100) / 100 * (kp.f("g_bIgnoreWeaponSizeScale", 0) != 0 ? 1 : ci.f("g_flUvScale1", 1));
		scale = Math.floor((scale + 0.005) * 100) / 100;
		java.util.List<double[]> px = new java.util.ArrayList<>();
		for (int y = 0; y < 128; y++)
			for (int x = 0; x < 128; x++) {
				int mk = masks.getRGB(x * masks.getWidth() / 128, y * masks.getHeight() / 128);
				if ((mk >> 16 & 255) > 128 && (mk >> 8 & 255) < 64) px.add(new double[] {(x + 0.5) / 128, (y + 0.5) / 128});
			}
		for (int variant = 0; variant < 4; variant++) {
			int sign = variant % 2 == 0 ? 1 : -1;
			boolean flipV = variant >= 2;
			double[] sc = new double[1001];
			for (int s = 0; s <= 1000; s++) {
				var pl = dev.strafemc.weapon.PatternRoll.roll(s, bounds);
				double rot = sign * Math.toRadians(Math.floor((pl.rotation() + 0.005) * 100) / 100), ox = Math.floor((pl.offsetX() + 0.005) * 100) / 100,
						oy = Math.floor((pl.offsetY() + 0.005) * 100) / 100;
				double inv = 0.5 / scale, cn = Math.cos(-rot), sn = Math.sin(-rot), ex = inv * cn - inv * sn, ey = ex * sn + inv * cn, cr = Math.cos(rot), sr = Math.sin(rot);
				double[] xf0 = {scale * cr, -scale * sr, scale * cr * ex - scale * sr * ey + ox - 0.5}, xf1 = {scale * sr, scale * cr, scale * sr * ex + scale * cr * ey + oy - 0.5};
				double sum = 0;
				for (double[] q : px) {
					double qv = flipV ? 1 - q[1] : q[1];
					double ru = xf0[0] * q[0] + xf0[1] * qv + xf0[2], rv = xf1[0] * q[0] + xf1[1] * qv + xf1[2];
					int c = GloveSkinBaker.sampleBilinear(pat, ru - Math.floor(ru), rv - Math.floor(rv));
					sum += (c & 255) - ((c >> 16 & 255) + (c >> 8 & 255)) / 2.0;
				}
				sc[s] = sum / px.size();
			}
			Integer[] idx = new Integer[1001];
			for (int i = 0; i <= 1000; i++) idx[i] = i;
			java.util.Arrays.sort(idx, (i, j) -> Double.compare(sc[j], sc[i]));
			var l = java.util.Arrays.asList(idx);
			StringBuilder sb = new StringBuilder("sign " + sign + (flipV ? " flipV" : "") + " top:");
			for (int i = 0; i < 12; i++) sb.append(' ').append(idx[i]);
			sb.append("  gems at:");
			for (int g : gems) sb.append(' ').append(g).append('@').append(l.indexOf(g));
			System.out.println(sb);
		}
	}
}
