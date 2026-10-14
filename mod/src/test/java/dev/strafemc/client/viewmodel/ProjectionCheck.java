package dev.strafemc.client.viewmodel;

import dev.strafemc.data.AssetCache;

import java.awt.image.BufferedImage;

/**
 * Finds how CS2 projects side-projected patterns (anodized multicolour: Marble Fade) by ranking all 1001 seeds of a
 * Karambit Marble Fade by how little yellow (pattern red channel = colour 1) lands on its paint, for several projection
 * variants: the right one puts the known Fire and Ice seeds on top. gradlew vmCheck -PvmMain=dev.strafemc.client.viewmodel.ProjectionCheck
 */
public final class ProjectionCheck {
	static final int[] GEMS = {412, 16, 146, 241, 359, 393, 541, 602, 649, 688, 701};

	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		String cls = a.length > 0 ? a[0] : "weapon_knife_karambit", kit = a.length > 1 ? a[1] : "am_marble_fade";
		var e = dev.strafemc.weapon.WeaponTable.get(cls);
		Glb glb = Glb.read(AssetCache.exportGlb(e.model(), true));
		VmModel m = VmModel.load(glb, VmModel.AUTO_MESH);
		var pm = WeaponSkinCache.paintable(e.model(), m.parts);
		var parts = new java.util.ArrayList<VmModel.Part>();
		for (VmModel.Part p : m.parts) if (p.texture != null && p.texture.getFileName().toString().startsWith(pm.color())) parts.add(p);
		var pos = WeaponSkinBaker.positions(parts, 256);
		var ci = GloveSkinBaker.Params.parse(java.nio.file.Files.readAllLines(AssetCache.dataBlock(
				java.util.regex.Pattern.compile("\"composite_inputs\"[\\s\\S]*?m_value = \"([^\"]+)\"").matcher(java.nio.file.Files.readString(AssetCache.dataBlock(pm.material() + "_c"))).results().findFirst().get().group(1) + "_c")));
		BufferedImage masks = GloveSkinCache.load(ci.textures.get("g_tMasks"));
		BufferedImage pat = GloveSkinCache.load(WeaponSkinCache.paintParams(kit).textures.get("g_tPattern"));
		var bounds = WeaponSkinCache.rollBounds(kit);
		var kp = WeaponSkinCache.paintParams(kit);
		double scale = kp.f("g_flPatternTexCoordScale", 1.6), wl = ci.f("g_flWeaponLength1", 32);
		// painted texels' positions
		java.util.List<float[]> pts = new java.util.ArrayList<>();
		int n = pos.size();
		for (int y = 0; y < n; y++)
			for (int x = 0; x < n; x++) {
				int o = WeaponSkinBaker.Positions.STRIDE * (y * n + x);
				if (Float.isNaN(pos.pos()[o])) continue;
				int mk = masks.getRGB(x * masks.getWidth() / n, y * masks.getHeight() / n);
				if ((mk >> 16 & 255) < 128) continue;
				pts.add(new float[] {pos.pos()[o], pos.pos()[o + 1], pos.pos()[o + 2]});
			}
		System.out.println("painted texels " + pts.size() + " bounds min " + java.util.Arrays.toString(pos.min()) + " max " + java.util.Arrays.toString(pos.max()) + " wl " + wl);
		String[] axes = {"xy", "yx", "xz", "zx", "yz", "zy"};
		for (String ax : axes)
			for (int flipU = 0; flipU < 2; flipU++)
				for (int flipV = 0; flipV < 2; flipV++)
					for (int norm = 0; norm < 4; norm++) {
						double[] sc = new double[1001];
						for (int s = 0; s <= 1000; s++) {
							var pl = dev.strafemc.weapon.PatternRoll.roll(s, bounds);
							double rot = Math.toRadians(r2(pl.rotation())), ox = r2(pl.offsetX()), oy = r2(pl.offsetY());
							double inv = 0.5 / scale, cn = Math.cos(-rot), sn = Math.sin(-rot), ex = inv * cn - inv * sn, ey = ex * sn + inv * cn;
							double cr = Math.cos(rot), sr = Math.sin(rot);
							double sum = 0;
							for (float[] p : pts) {
								double pu = coord(p, ax.charAt(0), pos, norm, wl), pv = coord(p, ax.charAt(1), pos, norm, wl);
								if (flipU == 1) pu = -pu;
								if (flipV == 1) pv = -pv;
								double ru = scale * cr * pu - scale * sr * pv + scale * cr * ex - scale * sr * ey + ox - 0.5;
								double rv = scale * sr * pu + scale * cr * pv + scale * sr * ex + scale * cr * ey + oy - 0.5;
								int c = pat.getRGB(Math.floorMod((int) Math.floor(ru * pat.getWidth()), pat.getWidth()), Math.floorMod((int) Math.floor(rv * pat.getHeight()), pat.getHeight()));
								sum += (c >> 16 & 255) / 255.0;
							}
							sc[s] = -sum / pts.size(); // less yellow = better
						}
						Integer[] idx = new Integer[1001];
						for (int i = 0; i <= 1000; i++) idx[i] = i;
						java.util.Arrays.sort(idx, (i, j) -> Double.compare(sc[j], sc[i]));
						double mean = 0;
						int r412 = 0;
						for (int g : GEMS) {
							int r = java.util.Arrays.asList(idx).indexOf(g);
							mean += r;
							if (g == 412) r412 = r;
						}
						System.out.printf("V %s%d%d%d %d%n", ax, flipU, flipV, norm, r412);
					}
	}

	/** One position axis as a projection coordinate: 0 relative to the model origin / weapon length; 1 centred / span; 2 from the minimum / span. */
	static double coord(float[] p, char axis, WeaponSkinBaker.Positions pos, int norm, double wl) {
		int k = axis - 'x';
		double span = Math.max(1e-6, Math.max(pos.max()[0] - pos.min()[0], Math.max(pos.max()[1] - pos.min()[1], pos.max()[2] - pos.min()[2])));
		return switch (norm) {
			case 0 -> p[k] / (wl * 0.0254); // glb metres -> inches
			case 1 -> (p[k] - (pos.min()[k] + pos.max()[k]) / 2) / span;
			case 3 -> (p[k] - (pos.min()[k] + pos.max()[k]) / 2) / span + 0.5; // what the baker does
			default -> (p[k] - pos.min()[k]) / span;
		};
	}

	static double r2(double x) {
		return Math.floor((x + 0.005) * 100) / 100;
	}
}
