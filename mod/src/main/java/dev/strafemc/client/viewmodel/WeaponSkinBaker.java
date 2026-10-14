package dev.strafemc.client.viewmodel;

import java.awt.image.BufferedImage;
import java.util.function.Function;

/**
 * Bakes a CS2 weapon finish (a paint kit's csgo_customweapon material) into one colour texture in the weapon's UV space,
 * Factory New. CS2 generates g_tColor on the GPU from the paint material plus the weapon's composite inputs (masks,
 * ambient occlusion, object-space positions); this redoes the colour on the CPU from the same inputs.
 * FLAGGED: our reconstruction, checked by eye against CS2's own preview renders, not CS2's shader code. Wear, grunge,
 * roughness, pearlescence and the pattern seed's random roll are left out.
 * <p>
 * Paint styles (F_PAINT_STYLE): 0 solid colour, 1 hydrographic (pattern tiled in UV space), 2 spray paint (pattern
 * projected from the side), 3 anodized (metal tinted), 4 anodized multicolour and 5 anodized airbrushed (projected
 * pattern on tinted metal), 6 custom paint job (the pattern is the finished artwork in UV space), 7 patina (pattern
 * colours on the bare metal), 8 gunsmith (artwork over the weapon by its alpha). Palette styles: colour 0 where the
 * pattern is black, its red / green / blue channels laying colours 1 / 2 / 3 over it.
 * <p>
 * Where it goes (the composite inputs' mask): red marks bare metal, which every style paints; blue marks the rest
 * (grips), painted only by spray paint and the artwork styles (custom, gunsmith); green parts keep the weapon's own
 * look except under artwork. FLAGGED: read off CS2's knife previews.
 */
public final class WeaponSkinBaker {
	public static final int SIZE = 1024;

	private WeaponSkinBaker() {}

	/** The weapon's composite inputs (csgo_composite_inputs): masks, ambient occlusion, size scales. */
	public record Inputs(BufferedImage masks, BufferedImage ao, double uvScale, double weaponLength) {}

	/**
	 * Object-space position per texel (pos[3 * (y * size + x)]), NaN where no triangle covers the texel; made from the
	 * mesh (CS2's g_tPosition holds the same, as a float texture).
	 */
	public record Positions(int size, float[] pos, float[] min, float[] max) {
		/** Per texel (5 floats): object-space x, y, z, then the second UV set's u, v there (NaN: none / not covered). */
		public static final int STRIDE = 5;

		/**
		 * Where a UV-space pattern is read at texel (u, v): the second UV set there when the weapon has one (CS2 guns keep
		 * the old CS:GO layout in it, which the artwork is drawn for), else (u, v).
		 */
		public double[] patternUv(double u, double v) {
			int o = STRIDE * (Math.min(size - 1, (int) (v * size)) * size + Math.min(size - 1, (int) (u * size)));
			return Float.isNaN(pos[o + 3]) ? new double[] {u, v} : new double[] {pos[o + 3], pos[o + 4]};
		}
	}

	/** The baked colour and, per texel, how much of it is paint rather than the weapon's own metal (0..1). */
	public record Result(BufferedImage color, float[] paint) {}

	public static Result bake(GloveSkinBaker.Params p, Inputs in, BufferedImage base, Positions positions, Function<String, BufferedImage> tex) {
		int style = (int) p.f("F_PAINT_STYLE", 0);
		BufferedImage pattern = load(tex, p, "g_tPattern");
		double[][] col = new double[4][];
		for (int i = 0; i < 4; i++) col[i] = p.v("g_vColor" + i, 0.5, 0.5, 0.5);
		double bright = p.f("g_flColorBrightness", 1);
		boolean ignoreSize = p.f("g_bIgnoreWeaponSizeScale", 0) != 0;
		// pattern size: the weapon's size scale (composite inputs' g_flUvScale1) for hydrographic, patina and anodized multicolour
		// (Tiger Tooth / Freehand checked in game by the user); spray paint denser than its raw scale; the rest as they are (FLAGGED)
		boolean projected0 = style == 2 || style == 4 || style == 5;
		// CS2's own pattern transform (csgo_customweapon's g_vPatternTexCoordXform0/1 expressions, decoded from the shader):
		// values rounded to 0.01; scale = pattern scale x (ignore weapon size ? 1 : the UV scale, or weapon length / 36 for the
		// projected styles - which variant goes with which style is ours, FLAGGED)
		double scale = round2(round2(p.f("g_flPatternTexCoordScale", 1)) * (ignoreSize ? 1 : projected0 ? in.weaponLength / 36 : in.uvScale));
		if (scale == 0) scale = 1;
		double rot = Math.toRadians(round2(p.f("g_flPatternTexCoordRotation", 0)));
		double[] off0 = p.v("g_vPatternTexCoordOffset", 0, 0); // the rolled offset (PatternRoll)
		double offX = round2(off0[0]), offY = round2(off0.length > 1 ? off0[1] : 0);
		// the expression's extra offset: inv = 0.5 / scale turned by -rotation, its y using the already turned x (as in CS2)
		double inv = 0.5 / scale, cn = Math.cos(-rot), sn = Math.sin(-rot);
		double ex = inv * cn - inv * sn, ey = ex * sn + inv * cn;
		double cr = Math.cos(rot), sr = Math.sin(rot);
		double[] xf0 = {scale * cr, -scale * sr, scale * cr * ex - scale * sr * ey + offX - 0.5};
		double[] xf1 = {scale * sr, scale * cr, scale * sr * ex + scale * cr * ey + offY - 0.5};
		int sampU = (int) p.f("g_nPatternTextureHorizontalSampling", 0), sampV = (int) p.f("g_nPatternTextureVerticalSampling", 0);
		boolean projected = style == 2 || style == 4 || style == 5, metalStyle = style == 3 || style == 4 || style == 5 || style == 7,
				artwork = style == 6 || style == 8;
		// anodized multicolour (Dopplers, Marble Fade): more saturated than a plain blend, the pattern's alpha (stretched to its
		// own maximum) marking the dark marbling (FLAGGED: tuned by eye to CS2's previews, which are deep, not pastel)
		double[] chMax = {1, 1, 1, 1};
		if (style == 4 && pattern != null) {
			java.util.Arrays.fill(chMax, 1e-3);
			for (int y = 0; y < pattern.getHeight(); y += 2)
				for (int x = 0; x < pattern.getWidth(); x += 2) {
					int c = pattern.getRGB(x, y);
					chMax[0] = Math.max(chMax[0], (c >> 16 & 255) / 255.0);
					chMax[1] = Math.max(chMax[1], (c >> 8 & 255) / 255.0);
					chMax[2] = Math.max(chMax[2], (c & 255) / 255.0);
					chMax[3] = Math.max(chMax[3], (c >>> 24) / 255.0);
				}
		}
		// side projection: the weapon's longest axis across, the next one up (positions centred, spanning the weapon length)
		int ax = 0, ay = 1;
		if (positions != null) {
			float[] ext = new float[3];
			for (int k = 0; k < 3; k++) ext[k] = positions.max[k] - positions.min[k];
			ax = ext[0] >= ext[1] && ext[0] >= ext[2] ? 0 : ext[1] >= ext[2] ? 1 : 2;
			ay = -1;
			for (int k = 0; k < 3; k++) if (k != ax && (ay < 0 || ext[k] > ext[ay])) ay = k;
		}
		double span = positions == null ? 1 : Math.max(1e-6, positions.max[ax] - positions.min[ax]);
		BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		float[] paint = new float[SIZE * SIZE];
		for (int y = 0; y < SIZE; y++)
			for (int x = 0; x < SIZE; x++) {
				double u = (x + 0.5) / SIZE, v = (y + 0.5) / SIZE;
				int bc = GloveSkinBaker.sampleBilinear(base, u, v);
				double[] b = GloveSkinBaker.unpack(bc);
				int m = in.masks == null ? 0xFFFF0000 : GloveSkinBaker.sample(in.masks, u, v);
				double mr = (m >> 16 & 255) / 255.0, mg = (m >> 8 & 255) / 255.0, mb = (m & 255) / 255.0;
				double w = artwork ? 1 : style == 2 ? Math.max(mr, mb) : mr;
				if (!artwork) w *= 1 - mg;
				if (w <= 0.002 || pattern == null && style != 0 && style != 3) {
					out.setRGB(x, y, bc | 0xFF000000);
					continue;
				}
				// pattern coordinates: UV space, or the side projection for spray / anodized styles
				double pu = u, pv = v;
				if (!projected && positions != null) {
					double[] puv = positions.patternUv(u, v);
					pu = puv[0];
					pv = puv[1];
				}
				if (projected && positions != null) {
					int px = Math.min(positions.size - 1, (int) (u * positions.size)), py = Math.min(positions.size - 1, (int) (v * positions.size));
					int o = Positions.STRIDE * (py * positions.size + px);
					if (!Float.isNaN(positions.pos[o])) {
						// the object-space position (glb metres -> inches) over the weapon length, on the model's y / z axes: with this
						// the known Karambit Marble Fade Fire and Ice seeds (412, 359, 602, 393, 649) rank least yellow of all 1001
						double inches = in.weaponLength * 0.0254;
						pu = positions.pos[o + 1] / inches;
						pv = positions.pos[o + 2] / inches;
					}
				}
				double ru = xf0[0] * pu + xf0[1] * pv + xf0[2], rv = xf1[0] * pu + xf1[1] * pv + xf1[2];
				ru = address(ru, sampU);
				rv = address(rv, sampV);
				double[] c;
				double alpha = 1;
				int pc = pattern == null ? 0 : GloveSkinBaker.sampleBilinear(pattern, ru, rv);
				double[] pt = GloveSkinBaker.unpack(pc);
				switch (style) {
					case 0 -> c = col[0];
					case 3 -> c = col[0];
					case 6 -> c = pt;
					case 7 -> c = new double[] {pt[0] * col[0][0], pt[1] * col[0][1], pt[2] * col[0][2]};
					case 8 -> { // gunsmith: the artwork covers the paint mask fully (its alpha is not coverage: Slate, Searing Rage)
						c = pt;
						// colour adjustment mode 1: a greyscale artwork in linear light, tinted by colour 0 (Slate's dark polymer; FLAGGED)
						if (p.f("g_nColorAdjustmentMode", 0) == 1)
							for (int k = 0; k < 3; k++) c[k] = Math.pow(pt[k], 2.2) * col[0][k];
					}
					default -> { // palette styles: hydrographic, spray, anodized multicolour / airbrushed
						if (style == 4) // anodized multicolour: crisper colour borders than the pattern's soft gradients (by eye, FLAGGED)
							for (int k = 0; k < 3; k++) pt[k] = smooth(0.15, 0.75, pt[k]);
						c = col[0];
						c = GloveSkinBaker.lerp(c, col[1], pt[0]);
						c = GloveSkinBaker.lerp(c, col[2], pt[1]);
						c = GloveSkinBaker.lerp(c, col[3], pt[2]);
					}
				}
				// metal styles keep the metal's own light and dark (anodizing and patina tint it); paint covers it
				if (metalStyle) {
					double lum = 0.299 * b[0] + 0.587 * b[1] + 0.114 * b[2];
					c = GloveSkinBaker.scale(c, Math.min(1.0, lum / 0.6));
				}
				if (style == 4) { // deep anodized colour: saturated, darkened by the marbling in the pattern's alpha
					double ma = Math.min(1, (pc >>> 24) / 255.0 / chMax[3]);
					double l = 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2];
					for (int k = 0; k < 3; k++) c[k] = Math.max(0, l + (c[k] - l) * 1.9) * (1 - 0.6 * ma);
				}
				c = GloveSkinBaker.scale(c, bright);
				double a = in.ao == null ? 1 : 0.35 + 0.65 * ((GloveSkinBaker.sample(in.ao, u, v) >> 8 & 255) / 255.0);
				c = GloveSkinBaker.scale(c, a);
				double k = w * alpha;
				out.setRGB(x, y, GloveSkinBaker.rgb(GloveSkinBaker.lerp(b, c, k)));
				paint[y * SIZE + x] = (float) (metalStyle ? 0 : k);
			}
		return new Result(out, paint);
	}

	private static double smooth(double e0, double e1, double x) {
		double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}

	private static double round2(double x) {
		return Math.floor((x + 0.005) * 100) / 100;
	}

	/** Pattern addressing per axis: 0 wrap, 1 mirror, 2 clamp (CS2's g_nPatternTexture*Sampling; FLAGGED meaning). */
	private static double address(double t, int mode) {
		return switch (mode) {
			case 2 -> Math.max(0.001, Math.min(0.999, t));
			case 1 -> {
				double f = t - 2 * Math.floor(t / 2);
				yield f > 1 ? 2 - f : f;
			}
			default -> t;
		};
	}

	private static BufferedImage load(Function<String, BufferedImage> tex, GloveSkinBaker.Params p, String name) {
		String path = p.textures.get(name);
		if (path == null || path.startsWith("materials/default/")) return null;
		return tex.apply(path);
	}

	/**
	 * Object-space positions (and second-set UVs) per texel of the texture, rasterised from the mesh triangles in UV
	 * space, gaps at seams filled outward.
	 */
	public static Positions positions(java.util.List<VmModel.Part> parts, int size) {
		final int st = Positions.STRIDE;
		float[] pos = new float[size * size * st];
		java.util.Arrays.fill(pos, Float.NaN);
		float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (VmModel.Part p : parts) {
			for (int i = 0; i + 2 < p.pos.length; i += 3)
				for (int k = 0; k < 3; k++) {
					min[k] = Math.min(min[k], p.pos[i + k]);
					max[k] = Math.max(max[k], p.pos[i + k]);
				}
			float[] puv = p.uv;
			for (int t = 0; t + 2 < p.idx.length; t += 3) {
				int a = p.idx[t], b = p.idx[t + 1], c = p.idx[t + 2];
				double ax = frac(puv[a * 2]) * size, ay = frac(puv[a * 2 + 1]) * size, bx = frac(puv[b * 2]) * size, by = frac(puv[b * 2 + 1]) * size,
						cx = frac(puv[c * 2]) * size, cy = frac(puv[c * 2 + 1]) * size;
				double area = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
				if (Math.abs(area) < 1e-9) continue;
				int x0 = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx)))), x1 = Math.min(size - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
				int y0 = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy)))), y1 = Math.min(size - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
				for (int y = y0; y <= y1; y++)
					for (int x = x0; x <= x1; x++) {
						double px = x + 0.5, py = y + 0.5;
						double wa = ((bx - px) * (cy - py) - (cx - px) * (by - py)) / area, wb = ((cx - px) * (ay - py) - (ax - px) * (cy - py)) / area, wc = 1 - wa - wb;
						if (wa < -0.01 || wb < -0.01 || wc < -0.01) continue;
						int o = st * (y * size + x);
						for (int k = 0; k < 3; k++) pos[o + k] = (float) (wa * p.pos[a * 3 + k] + wb * p.pos[b * 3 + k] + wc * p.pos[c * 3 + k]);
					}
			}
		}
		for (int pass = 0; pass < 4; pass++) { // grow into empty texels so seams don't sample nothing
			float[] next = pos.clone();
			for (int y = 0; y < size; y++)
				for (int x = 0; x < size; x++) {
					int o = st * (y * size + x);
					if (!Float.isNaN(pos[o])) continue;
					for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
						int nx = x + d[0], ny = y + d[1];
						if (nx < 0 || ny < 0 || nx >= size || ny >= size) continue;
						int n = st * (ny * size + nx);
						if (Float.isNaN(pos[n])) continue;
						System.arraycopy(pos, n, next, o, st);
						break;
					}
				}
			pos = next;
		}
		return new Positions(size, pos, min, max);
	}

	private static double frac(double t) {
		return t - Math.floor(t);
	}
}
