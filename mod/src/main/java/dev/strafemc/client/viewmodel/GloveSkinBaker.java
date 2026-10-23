package dev.strafemc.client.viewmodel;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bakes a CS2 glove finish into one colour texture in the glove's UV space, at a wear (0: Factory New).
 * CS2 composites gloves on the GPU from layered materials; this reads the finish's material parameters and redoes
 * the colour part on the CPU. FLAGGED: our reconstruction from the parameters and textures, not CS2's shader code
 * (roughness, sheen and normals are left out; the detail-texture shading, blend strengths and how wear shows are ours).
 * <p>
 * Wear (older finishes): each textile layer has CS2's damage settings (g_fDamageBrightness / Saturation / Bleaching,
 * g_fCurvatureWearBoost / Power, g_fGrungeMax and its g_tGrunge). Worn spots are where the material's noise and the
 * exposed parts (the surface map's occlusion) pass a threshold that falls as the wear rises; there the colour gets the
 * layer's damage brightness, saturation and bleaching, and grunge darkens the whole layer by up to g_fGrungeMax x wear.
 * <p>
 * Older finishes (csgo_customglove): the layer mask's alpha is a colour-ID map (8 tints); the ID named by
 * g_nPatternReplaceIndex shows the pattern instead, either mapped through the palette (mode 0: base, then the R, G, B
 * channels pick g_vPatternPaletteIndices) or in its own colours (mode 2: fades, marble, case hardened); the mask's
 * R/G/B pick one of four textile layers whose detail texture shades the colour; the surface map's green is the
 * ambient occlusion.
 * <p>
 * Newer finishes (csgo_customglove_preview): the layer-ID map's R/G/B/A pick one of four surface colour textures
 * (tinted, brightness and saturation adjusted); the tint-ID map's IDs multiply by g_vIdNColor, and IDs marked
 * g_bIdNPattern take the pattern texture over it by its alpha.
 */
public final class GloveSkinBaker {
	public static final int SIZE = 1024;

	private GloveSkinBaker() {}

	/** Material parameters from VRF's DATA dump of a .vmat_c. */
	public static final class Params {
		public final Map<String, String> textures = new HashMap<>();
		public final Map<String, double[]> values = new HashMap<>();
		public String shader = "";

		public double f(String name, double fallback) {
			double[] v = values.get(name);
			return v == null || v.length == 0 ? fallback : v[0];
		}

		public double[] v(String name, double... fallback) {
			double[] v = values.get(name);
			return v == null ? fallback : v;
		}

		public static Params parse(List<String> lines) {
			Params p = new Params();
			Pattern name = Pattern.compile("m_name = \"([^\"]+)\""), res = Pattern.compile("m_pValue = resource:\"([^\"]+)\""),
					num = Pattern.compile("m_(?:fl|n)Value = (-?[0-9.eE+-]+)"), vec = Pattern.compile("m_value = \\[([^\\]]*)\\]"),
					shader = Pattern.compile("m_shaderName = \"([^\"]+)\"");
			String last = null;
			for (String l : lines) {
				Matcher m;
				if ((m = shader.matcher(l)).find()) p.shader = m.group(1);
				else if ((m = name.matcher(l)).find()) last = m.group(1);
				else if (last == null) continue;
				else if ((m = res.matcher(l)).find()) p.textures.put(last, m.group(1));
				else if ((m = num.matcher(l)).find()) p.values.put(last, new double[] {Double.parseDouble(m.group(1))});
				else if ((m = vec.matcher(l)).find()) {
					String[] parts = m.group(1).split(",");
					double[] v = new double[parts.length];
					for (int i = 0; i < v.length; i++) v[i] = Double.parseDouble(parts[i].trim());
					p.values.put(last, v);
				}
			}
			return p;
		}
	}

	/** Bakes the finish; images come from {@code tex} by CS2 resource path (".vtex"), null when missing. */
	public static BufferedImage bake(Params p, Function<String, BufferedImage> tex) {
		return bake(p, tex, 0);
	}

	public static BufferedImage bake(Params p, Function<String, BufferedImage> tex, double wear) {
		return p.shader.contains("preview") ? bakeNew(p, tex, wear) : bakeOld(p, tex, wear);
	}

	private static double smooth(double a, double b, double x) {
		double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
		return t * t * (3 - 2 * t);
	}

	/** How worn a spot is (0-1) at this wear: noise and exposure past a threshold that drops as the wear rises. */
	private static double damage(double wear, double noise, double exposed, double boost) {
		if (wear <= 0) return 0;
		double e = noise * 0.6 + exposed * (0.4 + boost);
		double t = 1.1 - wear * 1.35;
		return smooth(t, t + 0.2, e);
	}

	/** A layer's damage look on a colour: brightness, saturation, bleaching toward a pale grey. */
	private static double[] damaged(double[] c, double d, double bright, double sat, double bleach) {
		if (d <= 0) return c;
		double l = 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2];
		double[] o = new double[3];
		for (int k = 0; k < 3; k++) {
			double x = l + (c[k] - l) * (1 + sat * d);
			x *= 1 + bright * d;
			x += ((l * 0.6 + 0.35) - x) * bleach * d * 0.35;
			o[k] = Math.max(0, Math.min(1, x));
		}
		return o;
	}

	private static BufferedImage bakeOld(Params p, Function<String, BufferedImage> tex, double wear) {
		BufferedImage mask = load(tex, p, "g_tLayerMask"), surface = load(tex, p, "g_tSurface"), pattern = load(tex, p, "g_tPattern");
		BufferedImage[] detail = new BufferedImage[4];
		double[] detailScale = new double[4], blackPoint = new double[4];
		for (int i = 0; i < 4; i++) {
			detail[i] = load(tex, p, "g_tDetail" + (i + 1));
			detailScale[i] = p.f("g_fDetailScale" + (i + 1), 1);
			blackPoint[i] = p.f("g_fDetailBlackPoint" + (i + 1), 0);
		}
		BufferedImage noiseTex = wear > 0 ? load(tex, p, "g_tNoise") : null;
		BufferedImage[] grunge = new BufferedImage[4];
		double[] dBright = new double[4], dSat = new double[4], dBleach = new double[4], cBoost = new double[4], cPow = new double[4], gMax = new double[4];
		for (int i = 0; i < 4 && wear > 0; i++) {
			grunge[i] = load(tex, p, "g_tGrunge" + (i + 1));
			dBright[i] = p.f("g_fDamageBrightness" + (i + 1), -0.3);
			dSat[i] = p.f("g_fDamageSaturation" + (i + 1), -0.2);
			dBleach[i] = p.f("g_fDamageBleaching" + (i + 1), 0);
			cBoost[i] = p.f("g_fCurvatureWearBoost" + (i + 1), 0);
			cPow[i] = p.f("g_fCurvaturePower" + (i + 1), 1);
			gMax[i] = p.f("g_fGrungeMax" + (i + 1), 0.2);
		}
		double[][] tint = new double[9][];
		for (int i = 1; i <= 8; i++) tint[i] = p.v("g_vColorTint" + i, 0.5, 0.5, 0.5);
		boolean usePattern = p.f("F_PATTERN", 0) != 0 && pattern != null;
		int mode = (int) p.f("g_nPatternMode", 0), replace = (int) p.f("g_nPatternReplaceIndex", 0);
		double[] pal = p.v("g_vPatternPaletteIndices", 1, 2, 3, 4);
		double pScale = p.f("g_fPatternTexCoordScale", 1), pRot = Math.toRadians(p.f("g_fPatternTexCoordRotation", 0));
		double[] pOff = p.v("g_fPatternTexCoordOffset", 0, 0);
		BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < SIZE; y++)
			for (int x = 0; x < SIZE; x++) {
				double u = (x + 0.5) / SIZE, v = (y + 0.5) / SIZE;
				int m = mask == null ? 0xFF000000 : sample(mask, u, v);
				int id = Math.max(1, Math.min(8, (m >>> 24) * 8 / 255 + 1));
				double[] c = tint[id].clone();
				if (usePattern && id == replace) {
					double[] uv = patternUv(u, v, pScale, pRot, pOff);
					int pc = sampleBilinear(pattern, uv[0], uv[1]);
					double r = (pc >> 16 & 255) / 255.0, g = (pc >> 8 & 255) / 255.0, b = (pc & 255) / 255.0;
					if (mode == 2) c = new double[] {r, g, b};
					else {
						c = palette(tint, pal[0]);
						c = lerp(c, palette(tint, pal[1]), r);
						c = lerp(c, palette(tint, pal.length > 2 ? pal[2] : 1), g);
						c = lerp(c, palette(tint, pal.length > 3 ? pal[3] : 1), b);
					}
				}
				int mr = m >> 16 & 255, mg = m >> 8 & 255, mb = m & 255;
				int layer = mr >= 128 ? 0 : mg >= 128 ? 1 : mb >= 128 ? 2 : 3;
				if (detail[layer] != null) {
					double d = (sampleBilinear(detail[layer], u * detailScale[layer], v * detailScale[layer]) >> 16 & 255) / 255.0;
					d = Math.max(0, (d - blackPoint[layer]) / Math.max(0.05, 1 - blackPoint[layer]));
					c = scale(c, 0.55 + 0.9 * d); // detail shading around 1 (its mean is ~0.5)
				}
				double ao = surface == null ? 1 : (sample(surface, u, v) >> 8 & 255) / 255.0;
				if (wear > 0) { // worn: the layer's damage on its worn spots, its grunge over all of it
					double n = noiseTex == null ? 0.5 : (sampleBilinear(noiseTex, u * 2, v * 2) >> 16 & 255) / 255.0;
					c = damaged(c, damage(wear, n, Math.pow(ao, Math.max(0.1, cPow[layer])), cBoost[layer]), dBright[layer], dSat[layer], dBleach[layer]);
					if (grunge[layer] != null) {
						int gc = sampleBilinear(grunge[layer], u, v);
						double gl = (0.299 * (gc >> 16 & 255) + 0.587 * (gc >> 8 & 255) + 0.114 * (gc & 255)) / 255.0;
						c = scale(c, 1 - Math.min(1, gMax[layer] * wear * 1.5) * (1 - gl) * (0.5 + 0.5 * (1 - ao)));
					}
				}
				if (surface != null) c = scale(c, 0.25 + 0.75 * ao);
				out.setRGB(x, y, rgb(c));
			}
		return out;
	}

	private static BufferedImage bakeNew(Params p, Function<String, BufferedImage> tex, double wear) {
		BufferedImage layerId = load(tex, p, "g_tLayerId"), tintId = load(tex, p, "g_tTintId"), props = load(tex, p, "g_tObjectProperties"),
				pattern = load(tex, p, "g_tPattern");
		BufferedImage[] surf = new BufferedImage[4];
		double[][] uvScale = new double[4][], tintC = new double[4][];
		double[] bright = new double[4], sat = new double[4], contrast = new double[4];
		for (int i = 0; i < 4; i++) {
			surf[i] = load(tex, p, "g_tSurface" + (i + 1));
			uvScale[i] = p.v("g_vUvScale" + (i + 1), 1, 1);
			tintC[i] = p.v("g_vSurfaceColorTint" + (i + 1), 1, 1, 1);
			bright[i] = p.f("g_fSurfaceColorBrightness" + (i + 1), 1);
			sat[i] = p.f("g_fSurfaceColorSaturation" + (i + 1), 1);
			contrast[i] = p.f("g_fSurfaceColorContrast" + (i + 1), 1);
		}
		double[][] idColor = new double[9][];
		boolean[] idPattern = new boolean[9];
		for (int i = 1; i <= 8; i++) {
			idColor[i] = p.v("g_vId" + i + "Color", 1, 1, 1);
			idPattern[i] = p.f("g_bId" + i + "Pattern", 0) != 0;
		}
		boolean usePattern = p.f("F_PATTERN", 0) != 0 && pattern != null, useTint = p.f("F_TINT_ID", 0) != 0 && tintId != null;
		double[] pScale = p.v("g_vPatternTexCoordScale", 1, 1), pOff = p.v("g_vPatternTexCoordOffset", 0, 0),
				pCenter = p.v("g_vPatternTexCoordCenter", 0.5, 0.5);
		double pRot = Math.toRadians(p.f("g_fPatternTexCoordRotation", 0)), threshold = p.f("g_fPatternTranslucencyThreshold", 0.5);
		BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < SIZE; y++)
			for (int x = 0; x < SIZE; x++) {
				double u = (x + 0.5) / SIZE, v = (y + 0.5) / SIZE;
				int l = layerId == null ? 0xFF000000 : sample(layerId, u, v);
				double[] w = {(l >> 16 & 255) / 255.0, (l >> 8 & 255) / 255.0, (l & 255) / 255.0, 0};
				w[3] = Math.max(0, 1 - w[0] - w[1] - w[2]);
				double[] c = {0, 0, 0};
				for (int i = 0; i < 4; i++) {
					if (w[i] <= 0.004) continue;
					double[] s = surf[i] == null ? new double[] {0.5, 0.5, 0.5} : unpack(sampleBilinear(surf[i], u * uvScale[i][0], v * uvScale[i][uvScale[i].length > 1 ? 1 : 0]));
					if (LINEAR) s = toLinear(s);
					s = adjust(s, bright[i], contrast[i], sat[i]);
					for (int k = 0; k < 3; k++) c[k] += w[i] * s[k] * tintC[i][k];
				}
				if (useTint) {
					int id = Math.max(1, Math.min(8, (sample(tintId, u, v) >> 16 & 255) * 8 / 255 + 1));
					for (int k = 0; k < 3; k++) c[k] *= idColor[id][k];
					if (usePattern && idPattern[id]) {
						double cu = (u - pCenter[0]) * pScale[0], cv = (v - pCenter[1]) * pScale[pScale.length > 1 ? 1 : 0];
						double ru = cu * Math.cos(pRot) - cv * Math.sin(pRot) + pCenter[0] + pOff[0], rv = cu * Math.sin(pRot) + cv * Math.cos(pRot) + pCenter[1] + pOff[1];
						int pc = sampleBilinear(pattern, ru, rv);
						double a = Math.min(1, (pc >>> 24) / 255.0 / Math.max(0.05, threshold)); // "translucency threshold": solid above it
						c = lerp(c, LINEAR ? toLinear(unpack(pc)) : unpack(pc), a);
					}
				}
				double occ = props == null ? 1 : (sample(props, u, v) >> 16 & 255) / 255.0; // occlusion in red here
				if (wear > 0) { // newer finishes have no damage settings: darker, duller worn spots (ours)
					double n = (Math.sin(u * 91.7) * Math.sin(v * 77.3) * 0.5 + 0.5) * 0.5 + ((x * 73856093 ^ y * 19349663) & 1023) / 1023.0 * 0.5;
					c = damaged(c, damage(wear, n, occ, 0), -0.3, -0.25, 0.3);
					c = scale(c, 1 - 0.25 * wear * (1 - occ));
				}
				if (props != null) c = scale(c, 0.25 + 0.75 * occ);
				out.setRGB(x, y, rgb(LINEAR ? toSrgb(c) : c));
			}
		return out;
	}

	/** Newer finishes in linear light: textures sRGB to linear, the material's colours taken as linear, back to sRGB at the end. */
	static boolean LINEAR = "1".equals(System.getProperty("strafemc.gloveLinear")); // off until the shader is decoded (an experiment: not CS2's look yet)

	static double[] toLinear(double[] c) {
		double[] o = new double[3];
		for (int k = 0; k < 3; k++) o[k] = c[k] <= 0.04045 ? c[k] / 12.92 : Math.pow((c[k] + 0.055) / 1.055, 2.4);
		return o;
	}

	static double[] toSrgb(double[] c) {
		double[] o = new double[3];
		for (int k = 0; k < 3; k++) {
			double x = Math.max(0, c[k]);
			o[k] = x <= 0.0031308 ? x * 12.92 : 1.055 * Math.pow(x, 1 / 2.4) - 0.055;
		}
		return o;
	}

	private static BufferedImage load(Function<String, BufferedImage> tex, Params p, String name) {
		String path = p.textures.get(name);
		if (path == null || path.startsWith("materials/default/")) return null;
		return tex.apply(path);
	}

	private static double[] patternUv(double u, double v, double scale, double rot, double[] off) {
		double su = u * scale, sv = v * scale;
		return new double[] {su * Math.cos(rot) - sv * Math.sin(rot) + off[0], su * Math.sin(rot) + sv * Math.cos(rot) + (off.length > 1 ? off[1] : 0)};
	}

	private static double[] palette(double[][] tint, double index) {
		return tint[Math.max(1, Math.min(8, (int) Math.round(index)))];
	}

	private static double[] adjust(double[] c, double bright, double contrast, double sat) {
		double l = 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2];
		double[] o = new double[3];
		for (int k = 0; k < 3; k++) {
			double x = l + (c[k] - l) * sat;
			x = (x - 0.5) * contrast + 0.5;
			o[k] = Math.max(0, x * bright);
		}
		return o;
	}

	static double[] lerp(double[] a, double[] b, double t) {
		return new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
	}

	static double[] scale(double[] c, double s) {
		return new double[] {c[0] * s, c[1] * s, c[2] * s};
	}

	static double[] unpack(int c) {
		return new double[] {(c >> 16 & 255) / 255.0, (c >> 8 & 255) / 255.0, (c & 255) / 255.0};
	}

	static int rgb(double[] c) {
		int r = (int) Math.round(Math.max(0, Math.min(1, c[0])) * 255), g = (int) Math.round(Math.max(0, Math.min(1, c[1])) * 255),
				b = (int) Math.round(Math.max(0, Math.min(1, c[2])) * 255);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	static int sample(BufferedImage img, double u, double v) {
		int w = img.getWidth(), h = img.getHeight();
		int x = Math.floorMod((int) Math.floor(u * w), w), y = Math.floorMod((int) Math.floor(v * h), h);
		return img.getRGB(x, y);
	}

	static int sampleBilinear(BufferedImage img, double u, double v) {
		int w = img.getWidth(), h = img.getHeight();
		double fx = u * w - 0.5, fy = v * h - 0.5;
		int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
		double tx = fx - x0, ty = fy - y0;
		int[] c = {img.getRGB(Math.floorMod(x0, w), Math.floorMod(y0, h)), img.getRGB(Math.floorMod(x0 + 1, w), Math.floorMod(y0, h)),
				img.getRGB(Math.floorMod(x0, w), Math.floorMod(y0 + 1, h)), img.getRGB(Math.floorMod(x0 + 1, w), Math.floorMod(y0 + 1, h))};
		int out = 0;
		for (int sh = 0; sh <= 24; sh += 8) {
			double a = (c[0] >>> sh & 255) * (1 - tx) + (c[1] >>> sh & 255) * tx, b = (c[2] >>> sh & 255) * (1 - tx) + (c[3] >>> sh & 255) * tx;
			out |= (int) Math.round(a * (1 - ty) + b * ty) << sh;
		}
		return out;
	}
}
