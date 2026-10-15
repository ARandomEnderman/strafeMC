package dev.strafemc.client.assets;

import dev.strafemc.client.viewmodel.Glb;
import dev.strafemc.client.viewmodel.VmModel;
import dev.strafemc.data.AssetCache;
import dev.strafemc.weapon.WeaponTable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Magazine item icons drawn from the guns themselves (CS2 has no magazine pictures): the triangles of a gun's model
 * skinned to its magazine bone (clip; shotguns: shell; the M249: ammobox) and the bones under it, in the rest pose,
 * seen from the magazine's flattest side, lit softly and textured with the gun's own colour texture.
 */
final class MagIcons {
	static final int SIZE = 64, SS = 4; // icon pixels, supersampling
	/** Part of the icon file names: raised when the drawing changes, so cached icons are redrawn. */
	static final String VERSION = "_v2";

	private MagIcons() {}

	/** The icon for a gun's magazine (written once), or false without the model. */
	static boolean render(WeaponTable.Entry e, Path out) throws Exception {
		return render(e, out, false);
	}

	/**
	 * The gun base's icon: the gun without its magazine, lying on its side, in plain gunmetal.
	 */
	static boolean renderBase(WeaponTable.Entry e, Path out) throws Exception {
		return render(e, out, true);
	}

	private static boolean render(WeaponTable.Entry e, Path out, boolean base) throws Exception {
		if (Files.exists(out)) return true;
		Glb glb = Glb.read(AssetCache.exportGlb(e.model(), true));
		VmModel m = VmModel.load(glb, VmModel.AUTO_MESH);
		java.util.List<float[]> tris = new java.util.ArrayList<>(); // x y z nx ny nz u v per corner, 3 corners
		BufferedImage tex = null;
		for (VmModel.Part p : m.parts) {
			if (p.jnt == null || p.wts == null || p.skin >= m.skins.size()) continue;
			VmModel.Skin sk = m.skins.get(p.skin);
			Set<Integer> mag = magJoints(sk);
			if (mag.isEmpty()) continue;
			boolean any = false;
			for (int i = 0; i + 2 < p.idx.length; i += 3) {
				boolean in = true;
				for (int c = 0; c < 3 && in; c++) in = mag.contains(dominant(p, p.idx[i + c])) != base; // the base: everything but the magazine
				if (!in) continue;
				float[] t = new float[24];
				for (int c = 0; c < 3; c++) {
					int v = p.idx[i + c];
					System.arraycopy(p.pos, v * 3, t, c * 8, 3);
					if (p.nrm != null) System.arraycopy(p.nrm, v * 3, t, c * 8 + 3, 3);
					t[c * 8 + 6] = p.uv[v * 2];
					t[c * 8 + 7] = p.uv[v * 2 + 1];
				}
				tris.add(t);
				any = true;
			}
			if (any && tex == null && p.texture != null && Files.exists(p.texture)) tex = ImageIO.read(p.texture.toFile());
		}
		if (tris.isEmpty()) return false;
		BufferedImage img = draw(tris, tex, base);
		Files.createDirectories(out.getParent());
		ImageIO.write(img, "png", out.toFile());
		return true;
	}

	/** The magazine bone and every joint under it. */
	private static Set<Integer> magJoints(VmModel.Skin sk) {
		Set<Integer> out = new HashSet<>();
		// the R8 has no magazine: its speed loader (holder and handle)
		for (String[] names : new String[][] {{"clip"}, {"magazine"}, {"mag"}, {"magazine_r"}, {"ammobox"}, {"shell"}, {"shell1"}, {"loader_holder", "loader_handle"}}) {
			for (String name : names)
				for (int j = 0; j < sk.joints.length; j++) if (sk.joints[j].equalsIgnoreCase(name)) out.add(j);
			if (!out.isEmpty()) break;
		}
		if (out.isEmpty() || sk.parent == null) return out;
		boolean grew = true;
		while (grew) {
			grew = false;
			for (int j = 0; j < sk.parent.length; j++)
				if (!out.contains(j) && sk.parent[j] >= 0 && out.contains(sk.parent[j])) grew = out.add(j);
		}
		return out;
	}

	private static int dominant(VmModel.Part p, int v) {
		int best = 0;
		for (int k = 1; k < 4; k++) if (p.wts[v * 4 + k] > p.wts[v * 4 + best]) best = k;
		return p.jnt[v * 4 + best];
	}

	/** Orthographic, from the magazine's flattest side, its longest extent upright; soft light, z-buffered. */
	private static BufferedImage draw(java.util.List<float[]> tris, BufferedImage tex, boolean lying) {
		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (float[] t : tris)
			for (int c = 0; c < 3; c++)
				for (int k = 0; k < 3; k++) {
					lo[k] = Math.min(lo[k], t[c * 8 + k]);
					hi[k] = Math.max(hi[k], t[c * 8 + k]);
				}
		int depth = 0, up = 0;
		for (int k = 1; k < 3; k++) {
			if (hi[k] - lo[k] < hi[depth] - lo[depth]) depth = k;
			if (hi[k] - lo[k] > hi[up] - lo[up]) up = k;
		}
		int across = 3 - depth - up;
		if (lying) { // longest extent across
			int t = up;
			up = across;
			across = t;
		}
		float span = Math.max(hi[up] - lo[up], hi[across] - lo[across]) * 1.12f;
		float cu = (hi[up] + lo[up]) / 2, ca = (hi[across] + lo[across]) / 2;
		int n = SIZE * SS;
		float[] z = new float[n * n];
		java.util.Arrays.fill(z, -Float.MAX_VALUE);
		int[] rgb = new int[n * n];
		double[] light = norm(0.35, 0.6, 0.72); // across, up, toward the viewer
		for (float[] t : tris) {
			double[] sx = new double[3], sy = new double[3], sz = new double[3];
			for (int c = 0; c < 3; c++) {
				sx[c] = ((t[c * 8 + across] - ca) / span + 0.5) * n;
				sy[c] = (0.5 - (t[c * 8 + up] - cu) / span) * n;
				sz[c] = t[c * 8 + depth];
			}
			double area = (sx[1] - sx[0]) * (sy[2] - sy[0]) - (sx[2] - sx[0]) * (sy[1] - sy[0]);
			if (Math.abs(area) < 1e-9) continue;
			int x0 = (int) Math.max(0, Math.floor(Math.min(sx[0], Math.min(sx[1], sx[2])))), x1 = (int) Math.min(n - 1, Math.ceil(Math.max(sx[0], Math.max(sx[1], sx[2]))));
			int y0 = (int) Math.max(0, Math.floor(Math.min(sy[0], Math.min(sy[1], sy[2])))), y1 = (int) Math.min(n - 1, Math.ceil(Math.max(sy[0], Math.max(sy[1], sy[2]))));
			for (int y = y0; y <= y1; y++)
				for (int x = x0; x <= x1; x++) {
					double px = x + 0.5, py = y + 0.5;
					double w0 = ((sx[1] - px) * (sy[2] - py) - (sx[2] - px) * (sy[1] - py)) / area;
					double w1 = ((sx[2] - px) * (sy[0] - py) - (sx[0] - px) * (sy[2] - py)) / area;
					double w2 = 1 - w0 - w1;
					if (w0 < 0 || w1 < 0 || w2 < 0) continue;
					double d = w0 * sz[0] + w1 * sz[1] + w2 * sz[2];
					// both sides of the magazine look the same: the nearer one is the larger depth value
					if (d <= z[y * n + x]) continue;
					z[y * n + x] = (float) d;
					double u = w0 * t[6] + w1 * t[14] + w2 * t[22], v = w0 * t[7] + w1 * t[15] + w2 * t[23];
					double nx = w0 * t[3 + across] + w1 * t[11 + across] + w2 * t[19 + across];
					double ny = w0 * t[3 + up] + w1 * t[11 + up] + w2 * t[19 + up];
					double nz = w0 * t[3 + depth] + w1 * t[11 + depth] + w2 * t[19 + depth];
					double len = Math.max(1e-6, Math.sqrt(nx * nx + ny * ny + nz * nz));
					double lit = 0.75 + 0.85 * Math.abs((nx * light[0] + ny * light[1] + nz * light[2]) / len);
					int c = tex == null ? 0xFF6E6E6E : tex.getRGB(Math.floorMod((int) (u * tex.getWidth()), tex.getWidth()),
							Math.floorMod((int) (v * tex.getHeight()), tex.getHeight()));
					if (lying) { // plain gunmetal: the texture's light and dark only
						int l = ((c >> 16 & 255) * 3 + (c >> 8 & 255) * 6 + (c & 255)) / 10;
						c = (l * 9 / 10) << 16 | (l * 9 / 10) << 8 | l;
					}
					rgb[y * n + x] = 0xFF000000 | shade(c >> 16 & 255, lit) << 16 | shade(c >> 8 & 255, lit) << 8 | shade(c & 255, lit);
				}
		}
		BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < SIZE; y++)
			for (int x = 0; x < SIZE; x++) { // box filter with alpha: crisp, smooth edges
				int a = 0, r = 0, g = 0, b = 0;
				for (int j = 0; j < SS; j++)
					for (int i = 0; i < SS; i++) {
						int c = rgb[(y * SS + j) * n + x * SS + i];
						if (c == 0) continue;
						a++;
						r += c >> 16 & 255;
						g += c >> 8 & 255;
						b += c & 255;
					}
				if (a > 0) out.setRGB(x, y, (a * 255 / (SS * SS)) << 24 | (r / a) << 16 | (g / a) << 8 | b / a);
			}
		return out;
	}

	/** An icon with the empty magazine's red X (textures/item/mag_empty.png, from the jar) drawn over it, scaled to fit. */
	static BufferedImage withMark(BufferedImage icon) throws Exception {
		BufferedImage out = new BufferedImage(icon.getWidth(), icon.getHeight(), BufferedImage.TYPE_INT_ARGB);
		var g = out.createGraphics();
		g.drawImage(icon, 0, 0, null);
		try (var in = MagIcons.class.getResourceAsStream("/assets/strafemc/textures/item/mag_empty.png")) {
			if (in != null) {
				BufferedImage x = ImageIO.read(in);
				g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
				g.drawImage(x, 0, 0, icon.getWidth(), icon.getHeight(), null);
			}
		}
		g.dispose();
		return out;
	}

	private static int shade(int c, double lit) {
		return (int) Math.max(0, Math.min(255, c * lit));
	}

	private static double[] norm(double x, double y, double z) {
		double l = Math.sqrt(x * x + y * y + z * z);
		return new double[] {x / l, y / l, z / l};
	}
}
