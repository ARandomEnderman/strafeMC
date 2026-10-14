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
	public record Inputs(BufferedImage masks, BufferedImage ao, double uvScale, double weaponLength, float[] posMap, BufferedImage surface) {
		public Inputs(BufferedImage masks, BufferedImage ao, double uvScale, double weaponLength) {
			this(masks, ao, uvScale, weaponLength, null, null);
		}
	}

	/**
	 * Object-space position per texel (pos[3 * (y * size + x)]), NaN where no triangle covers the texel; made from the
	 * mesh (CS2's g_tPosition holds the same, as a float texture).
	 */
	public record Positions(int size, float[] pos, float[] min, float[] max) {
		/** Per texel (8 floats): object-space x, y, z, the second UV set's u, v (NaN: none), the object-space normal x, y, z. */
		public static final int STRIDE = 8;

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
	/**
	 * The baked colour; per texel how much of it is paint rather than the weapon's own metal (0..1); and the finish's
	 * roughness where it covers the weapon (NaN: the weapon's own), with how much it covers.
	 */
	public record Result(BufferedImage color, float[] paint, float[] rough, float[] cover) {}

	public static Result bake(GloveSkinBaker.Params p, Inputs in, BufferedImage base, Positions positions, Function<String, BufferedImage> tex) {
		int style = (int) p.f("F_PAINT_STYLE", 0);
		BufferedImage pattern = load(tex, p, "g_tPattern");
		double[][] col = new double[4][];
		for (int i = 0; i < 4; i++) col[i] = p.v("g_vColor" + i, 0.5, 0.5, 0.5);
		double bright = p.f("g_flColorBrightness", 1);
		boolean ignoreSize = p.f("g_bIgnoreWeaponSizeScale", 0) != 0;
		// pattern size: the weapon's size scale (composite inputs' g_flUvScale1) for hydrographic, patina and anodized multicolour
		// (Tiger Tooth / Freehand checked in game by the user); spray paint denser than its raw scale; the rest as they are (FLAGGED)
		// projected (triplanar) only for spray paint and anodized airbrushed: CS2's shader variants for every other style read
		// the pattern at the transformed mesh UV (its vertex shader: out = (uv, Xform(uv)))
		boolean projected0 = style == 2 || style == 5;
		// CS2's own pattern transform (csgo_customweapon's g_vPatternTexCoordXform0/1 expressions, decoded from the shader):
		// values rounded to 0.01; scale = pattern scale x (ignore weapon size ? 1 : the UV scale, or weapon length / 36 for the
		// projected styles - which variant goes with which style is ours, FLAGGED)
		double scale = round2(round2(p.f("g_flPatternTexCoordScale", 1)) * (ignoreSize ? 1 : projected0 ? in.weaponLength / 36 : in.uvScale));
		if (scale == 0) scale = 1;
		double[] blend = p.v("g_vSprayBlend", 1, 1, 0); // triplanar plane weights (CS2's default [1, 1, 0])
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
		boolean projected = style == 2 || style == 5, metalStyle = style == 3 || style == 4 || style == 5 || style == 7,
				artwork = style == 6 || style == 8;
		// CS2's composite (csgo_customweapon ps, decoded) works in linear light: colours are SrgbGammaToLinear(g_vColorN), the
		// weapon's colour texture is read as sRGB, the pattern raw; the result is stored as sRGB
		double[][] lc = new double[4][];
		for (int i = 0; i < 4; i++) lc[i] = lin(col[i]);
		double[] durIn = p.v("g_vPaintDurability", 0, 0, 0, 0), dur = new double[4];
		for (int i = 0; i < 4; i++) dur[i] = 1 - (i < durIn.length ? durIn[i] : 0);
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
		float[] paint = new float[SIZE * SIZE], rough = new float[SIZE * SIZE], cover = new float[SIZE * SIZE];
		java.util.Arrays.fill(rough, Float.NaN);
		double paintRough = p.f("g_flPaintRoughness", 0.6);
		double albedoScale = p.f("strafemc_albedo_scale", 1); // 1 for every kit but Black Pearl (WeaponSkinCache)
		for (int y = 0; y < SIZE; y++)
			for (int x = 0; x < SIZE; x++) {
				double u = (x + 0.5) / SIZE, v = (y + 0.5) / SIZE;
				int bc = GloveSkinBaker.sampleBilinear(base, u, v);
				double[] b = GloveSkinBaker.unpack(bc);
				int m = in.masks == null ? 0xFFFF0000 : GloveSkinBaker.sample(in.masks, u, v);
				double mr = (m >> 16 & 255) / 255.0, mg = (m >> 8 & 255) / 255.0, mb = (m & 255) / 255.0;
				// coverage of the older styles (patina; gunsmith covers all): the paint mask
				double w = artwork ? 1 : style == 7 ? mr * (1 - mg) : 1;
				// anodized styles paint mask R only
				boolean anodized = style == 3 || style == 4 || style == 5;
				if (w <= 0.002 || anodized && mr <= 0.002 || pattern == null && style != 0 && style != 3) {
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
				int pc;
				if (projected && (in.posMap() != null || positions != null) && pattern != null)
					pc = triplanar(in, positions, u, v, pattern, xf0, xf1, sampU, sampV, blend);
				else {
					double ru = address(xf0[0] * pu + xf0[1] * pv + xf0[2], sampU), rv = address(xf1[0] * pu + xf1[1] * pv + xf1[2], sampV);
					pc = pattern == null ? 0 : GloveSkinBaker.sampleBilinear(pattern, ru, rv);
				}
				double[] pt = GloveSkinBaker.unpack(pc);
				double pa = (pc >>> 24) / 255.0, band = smooth(0.5, 0.6, pa) * smooth(1, 0.9, pa);
				// ambient occlusion's alpha: bare metal at the edges (a knife's sharpened edge) where the paint isn't durable
				int aoc = in.ao == null ? 0 : GloveSkinBaker.sample(in.ao, u, v);
				double edge = (aoc >>> 24) / 255.0;
				double a = in.ao == null ? 1 : 0.35 + 0.65 * ((aoc >> 8 & 255) / 255.0);
				if (style <= 5) { // CS2's composite at factory new (wear 0), per style
					double[] bl = lin(b), c;
					double k;
					switch (style) {
						case 0 -> { // solid colours by the masks
							c = chain(lc, mr, mg, mb);
							k = 1 - smooth(0.58, 0.68, edge * chain(dur, mr, mg, mb));
						}
						case 1 -> { // hydrographic: the pattern's channels pick the colours, masks G / B colours 2 / 3
							c = clamp(GloveSkinBaker.scale(maskColours(chain(lc, pt[0], pt[1], pt[2]), lc, mg, mb), bright));
							double d = mask(chain(dur, pt[0], pt[1], pt[2]), dur, mg, mb);
							k = 1 - smooth(0.58, 0.68, (edge + band) * Math.max(Math.min(1, mg + mb), smooth(0, 0.5, pa)) * d);
						}
						case 2 -> { // spray paint: the (projected) pattern's channels pick the colours
							c = clamp(GloveSkinBaker.scale(chain(lc, pt[0], pt[1], pt[2]), bright));
							k = 1 - smooth(0.58, 0.68, edge * chain(dur, pt[0], pt[1], pt[2]));
						}
						case 3 -> { // anodized: colour 0 over mask R
							c = clamp(GloveSkinBaker.scale(clamp(GloveSkinBaker.scale(GloveSkinBaker.lerp(bl, lc[0], mr), bright)), bright));
							k = 1 - Math.max(1 - mr, smooth(0.58, 0.68, edge));
						}
						default -> { // anodized multicolour (pattern gated by mask R) / airbrushed (projected)
							double g = style == 4 ? mr : 1;
							double[] pal = maskColours(chain(lc, pt[0] * g, pt[1] * g, pt[2] * g), lc, mg, mb);
							c = clamp(GloveSkinBaker.scale(clamp(GloveSkinBaker.scale(GloveSkinBaker.lerp(bl, pal, mr), bright)), bright));
							double d = mask(chain(dur, pt[0], pt[1], pt[2]), dur, mg, mb);
							k = 1 - Math.max(1 - mr, smooth(0.58, 0.68, (edge + (style == 4 ? band : 0)) * d));
						}
					}
					c = GloveSkinBaker.scale(c, albedoScale);
					out.setRGB(x, y, GloveSkinBaker.rgb(srgb(GloveSkinBaker.lerp(bl, GloveSkinBaker.scale(c, a), k))));
					paint[y * SIZE + x] = (float) (anodized ? 0 : k);
					// CS2's roughness output: anodized multicolour reads it from the pattern's alpha (below 0.5; the
					// marbling), outside colour regions 2 / 3; everything else the kit's paint roughness
					double r = paintRough;
					if (style == 4 && pa < 0.5) {
						double t = 1 - Math.min(1, pa * 2);
						r += (t * t * 0.85 + 0.15 - paintRough) * (1 - Math.max(mg, mb));
					}
					rough[y * SIZE + x] = (float) Math.min(1, r);
					cover[y * SIZE + x] = (float) k;
					continue;
				}
				double[] c;
				double alpha = 1;
				switch (style) {
					case 6 -> { // custom artwork: bare metal only at durable-less edges under the artwork's alpha
						c = pt;
						w = 1 - smooth(0.58, 0.68, (edge + band) * smooth(0, 0.5, pa));
					}
					case 7 -> c = new double[] {pt[0] * col[0][0], pt[1] * col[0][1], pt[2] * col[0][2]};
					case 8 -> { // gunsmith: the artwork covers the paint mask fully (its alpha is not coverage: Slate, Searing Rage)
						c = pt;
						// colour adjustment mode 1: a greyscale artwork in linear light, tinted by colour 0 (Slate's dark polymer; FLAGGED)
						if (p.f("g_nColorAdjustmentMode", 0) == 1)
							for (int k = 0; k < 3; k++) c[k] = Math.pow(pt[k], 2.2) * col[0][k];
					}
					default -> c = col[0];
				}
				// patina tints the metal: it keeps the metal's own light and dark
				if (metalStyle) {
					double lum = 0.299 * b[0] + 0.587 * b[1] + 0.114 * b[2];
					c = GloveSkinBaker.scale(c, Math.min(1.0, lum / 0.6));
				}
				double sat = p.f("strafemc_saturation", 1); // 1 for every kit but Case Hardened (WeaponSkinCache)
				if (sat != 1) {
					double l = 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2];
					c = new double[] {Math.max(0, l + (c[0] - l) * sat), Math.max(0, l + (c[1] - l) * sat), Math.max(0, l + (c[2] - l) * sat)};
				}
				c = GloveSkinBaker.scale(c, bright);
				c = GloveSkinBaker.scale(c, a);
				double k = w * alpha;
				out.setRGB(x, y, GloveSkinBaker.rgb(GloveSkinBaker.lerp(b, c, k)));
				paint[y * SIZE + x] = (float) (metalStyle ? 0 : k);
				// patina (Case Hardened) is the polished steel itself: it keeps the weapon's own (glossy) roughness
				if (style != 7) {
					rough[y * SIZE + x] = (float) paintRough;
					cover[y * SIZE + x] = (float) (w * alpha);
				}
			}
		return new Result(out, paint, rough, cover);
	}

	/**
	 * CS2's projection for spray / anodized multicolour / airbrushed (decoded from csgo_customweapon's pixel shader): the
	 * composite's position map holds (Source-space position - the weapon's bounds centre) / weapon length + 0.5 (checked
	 * against the Karambit's g_tPosition: exact), the shader doubles it and samples the pattern on three planes (yz, xz, yx)
	 * through the pattern transform, blending to xz by blend.y * |n.y|^7 and to yx by blend.z * |n.z|^7 (n the object
	 * normal). Our positions are glb (x, y, z) = Source (x, z, -y), in metres.
	 */
	static int triplanar(Inputs in, Positions pp, double u, double v, BufferedImage pattern, double[] xf0, double[] xf1, int sampU, int sampV, double[] blend) {
		float[] pm = in.posMap();
		if (pm != null) { // CS2's own position map (x2, as its shader does) and object-space normal map (g_tSurface)
			int side = (int) pm[0], tx = Math.min(side - 1, (int) (u * side)), ty = Math.min(side - 1, (int) (v * side)), o = 1 + 4 * (ty * side + tx);
			double X = 2 * pm[o], Y = 2 * pm[o + 1], Z = 2 * pm[o + 2];
			double ny = 0, nz = 0;
			if (in.surface() != null) {
				int sc = GloveSkinBaker.sample(in.surface(), u, v);
				double nx = (sc >> 16 & 255) / 127.5 - 1;
				ny = (sc >> 8 & 255) / 127.5 - 1;
				nz = (sc & 255) / 127.5 - 1;
				double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
				if (nl > 1e-6) {
					ny /= nl;
					nz /= nl;
				}
			}
			return blendPlanes(pattern, X, Y, Z, ny, nz, xf0, xf1, sampU, sampV, blend);
		}
		double weaponLength = in.weaponLength();
		int px = Math.min(pp.size - 1, (int) (u * pp.size)), py = Math.min(pp.size - 1, (int) (v * pp.size));
		int o = Positions.STRIDE * (py * pp.size + px);
		if (Float.isNaN(pp.pos[o])) return 0;
		double inch = 1 / 0.0254;
		double sx = pp.pos[o] * inch, sy = -pp.pos[o + 2] * inch, sz = pp.pos[o + 1] * inch;
		double cx = (pp.min[0] + pp.max[0]) / 2 * inch, cy = -(pp.min[2] + pp.max[2]) / 2 * inch, cz = (pp.min[1] + pp.max[1]) / 2 * inch;
		double len = weaponLength > 0 ? weaponLength : 32;
		double X = 2 * ((sx - cx) / len + 0.5), Y = 2 * ((sy - cy) / len + 0.5), Z = 2 * ((sz - cz) / len + 0.5);
		double nx = pp.pos[o + 5], ny = -pp.pos[o + 7], nz = pp.pos[o + 6], nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (nl > 1e-6) {
			ny /= nl;
			nz /= nl;
		}
		return blendPlanes(pattern, X, Y, Z, ny, nz, xf0, xf1, sampU, sampV, blend);
	}

	/** The three plane samples (yz, xz, yx) blended by the normal like CS2's shader. */
	private static int blendPlanes(BufferedImage pattern, double X, double Y, double Z, double ny, double nz, double[] xf0, double[] xf1, int sampU, int sampV,
								   double[] blend) {
		double[] a = plane(pattern, Y, Z, xf0, xf1, sampU, sampV), b = plane(pattern, X, Z, xf0, xf1, sampU, sampV),
				c = plane(pattern, Y, X, xf0, xf1, sampU, sampV);
		double t1 = blend[1] * Math.pow(Math.abs(ny), 7), t2 = (blend.length > 2 ? blend[2] : 0) * Math.pow(Math.abs(nz), 7);
		int out = 0;
		for (int k = 0; k < 4; k++) {
			double m = (a[k] + (b[k] - a[k]) * t1) * (1 - t2) + c[k] * t2;
			out |= (int) Math.round(Math.max(0, Math.min(255, m))) << (k == 3 ? 24 : 16 - 8 * k);
		}
		return out;
	}

	/** One plane's pattern sample (r, g, b, a in 0..255) at the pattern transform of (p, q). */
	private static double[] plane(BufferedImage pattern, double p, double q, double[] xf0, double[] xf1, int sampU, int sampV) {
		double ru = address(xf0[0] * p + xf0[1] * q + xf0[2], sampU), rv = address(xf1[0] * p + xf1[1] * q + xf1[2], sampV);
		int c = GloveSkinBaker.sampleBilinear(pattern, ru, rv);
		return new double[] {c >> 16 & 255, c >> 8 & 255, c & 255, c >>> 24};
	}

	private static double smooth(double e0, double e1, double x) {
		double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}

	/** CS2's palette blend: colour 0, to 1 by r, to 2 by g, to 3 by b. */
	private static double[] chain(double[][] c, double r, double g, double b) {
		return GloveSkinBaker.lerp(GloveSkinBaker.lerp(GloveSkinBaker.lerp(c[0], c[1], r), c[2], g), c[3], b);
	}

	private static double chain(double[] c, double r, double g, double b) {
		return ((c[0] + (c[1] - c[0]) * r) * (1 - g) + c[2] * g) * (1 - b) + c[3] * b;
	}

	/** Mask G paints colour 2, mask B colour 3. */
	private static double[] maskColours(double[] c, double[][] lc, double mg, double mb) {
		return GloveSkinBaker.lerp(GloveSkinBaker.lerp(c, lc[2], mg), lc[3], mb);
	}

	private static double mask(double v, double[] per, double mg, double mb) {
		return (v * (1 - mg) + per[2] * mg) * (1 - mb) + per[3] * mb;
	}

	private static double[] clamp(double[] c) {
		return new double[] {Math.max(0, Math.min(1, c[0])), Math.max(0, Math.min(1, c[1])), Math.max(0, Math.min(1, c[2]))};
	}

	private static double[] lin(double[] c) {
		double[] o = new double[3];
		for (int k = 0; k < 3; k++) o[k] = c[k] <= 0.04045 ? c[k] / 12.92 : Math.pow((c[k] + 0.055) / 1.055, 2.4);
		return o;
	}

	private static double[] srgb(double[] c) {
		double[] o = new double[3];
		for (int k = 0; k < 3; k++) {
			double x = Math.max(0, Math.min(1, c[k]));
			o[k] = x <= 0.0031308 ? x * 12.92 : 1.055 * Math.pow(x, 1 / 2.4) - 0.055;
		}
		return o;
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
						for (int k = 0; k < 3; k++) pos[o + 5 + k] = (float) (wa * p.nrm[a * 3 + k] + wb * p.nrm[b * 3 + k] + wc * p.nrm[c * 3 + k]);
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
