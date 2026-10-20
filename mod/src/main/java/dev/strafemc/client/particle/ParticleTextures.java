package dev.strafemc.client.particle;

import com.mojang.blaze3d.platform.NativeImage;
import dev.strafemc.StrafeMc;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.data.AssetCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Particle textures from the CS2 copy: the vtex decompiled to PNG (downscaled to at most 2048 px, per channel) and its
 * sprite sheet (VTEX_EXTRA_DATA_SHEET: sequences of frames with UV rects and display times, from VRF's DATA dump).
 */
public final class ParticleTextures {
	/** u0..v1: the frame's uncropped rect (what the quad shows); c*: the cropped rect VRF exports as the frame PNG. */
	public record Frame(float displayTime, float u0, float v0, float u1, float v1, float cu0, float cv0, float cu1, float cv1) {}

	/** Sheet sequences can hold only colour (m_bNoAlpha) or only alpha (m_bNoColor): RGB and A then have different layouts. */
	public record Sequence(boolean clamp, float totalTime, List<Frame> frames, boolean noColor, boolean noAlpha) {
		/** Frame index at a time position along the sequence. */
		public int frameAt(float position) {
			float t = 0;
			for (int i = 0; i < frames.size(); i++) {
				t += frames.get(i).displayTime();
				if (position < t) return i;
			}
			return frames.size() - 1;
		}
	}

	public record Tex(Identifier id, List<Sequence> sequences) {}

	private static final Map<String, Tex> READY = new ConcurrentHashMap<>();
	private static final Map<String, Object[]> PENDING = new ConcurrentHashMap<>();
	private static final Map<String, Boolean> LOADING = new ConcurrentHashMap<>();

	/** The texture as a layer reads it: its channel mode picks the variant (alpha-only, brightness as alpha, or as is). */
	public static String variant(String vtex, String channels) {
		if (vtex == null) return null;
		if ("SPRITECARD_TEXTURE_CHANNEL_MIX_A".equals(channels)) return vtex + ALPHA_ONLY;
		if (channels != null && channels.endsWith("RGBALPHA")) return vtex + LUMA_ALPHA;
		if ("SPRITECARD_TEXTURE_CHANNEL_MIX_RALPHA".equals(channels)) return vtex + RED_ALPHA;
		if ("SPRITECARD_TEXTURE_CHANNEL_MIX_GALPHA".equals(channels)) return vtex + GREEN_ALPHA;
		return vtex;
	}

	/** Suffix on a texture name: the same texture with its alpha taken from the colour's brightness (..._RGBALPHA channels). */
	public static final String LUMA_ALPHA = "#luma";
	/** Suffix on a texture name: the same texture with its alpha taken from the red channel (..._MIX_RALPHA, e.g. sparks on black). */
	public static final String RED_ALPHA = "#ra";
	/** Suffix on a texture name: alpha taken from the green channel (..._MIX_GALPHA). */
	public static final String GREEN_ALPHA = "#ga";
	/** Suffix on a texture name: its brightness looked up in a colour gradient (a layer's 1D colour lookup), then the gradient key. */
	public static final String GRADIENT = "#g";
	private static final Map<String, float[]> GRADIENTS = new ConcurrentHashMap<>();
	private static final Map<String, Object> PLAIN_BUILDS = new ConcurrentHashMap<>();
	/**
	 * A gradient layer's lookup also counts how faded the particle is (its alpha): faint flames slide down the ramp (the
	 * blue edges of a fire). Baked as this many textures, one per fade level ("...#g<key>@<level>"). FLAGGED: our reading.
	 */
	public static final int GRADIENT_LEVELS = 8;

	/**
	 * The texture with its brightness replaced by a colour from a gradient (stops as position, r, g, b with r/g/b 0-255):
	 * CS2's m_bReplaceTextureWithGradient, e.g. a fire's flames going blue and purple where they're faint.
	 */
	public static String withGradient(String vtex, float[] stops) {
		if (vtex == null || stops.length < 4) return vtex;
		String key = Integer.toHexString(java.util.Arrays.hashCode(stops));
		GRADIENTS.put(key, stops);
		return vtex + GRADIENT + key;
	}

	/** Colour (0xRRGGBB) of a gradient at t (0..1). */
	static int gradientAt(float[] s, float t) {
		int n = s.length / 4;
		if (t <= s[0]) return rgb(s, 0);
		for (int i = 1; i < n; i++) {
			if (t <= s[i * 4]) {
				float a = s[(i - 1) * 4], b = s[i * 4], k = b > a ? (t - a) / (b - a) : 1;
				int r = Math.round(s[(i - 1) * 4 + 1] + (s[i * 4 + 1] - s[(i - 1) * 4 + 1]) * k);
				int g = Math.round(s[(i - 1) * 4 + 2] + (s[i * 4 + 2] - s[(i - 1) * 4 + 2]) * k);
				int bl = Math.round(s[(i - 1) * 4 + 3] + (s[i * 4 + 3] - s[(i - 1) * 4 + 3]) * k);
				return r << 16 | g << 8 | bl;
			}
		}
		return rgb(s, n - 1);
	}

	private static int rgb(float[] s, int i) {
		return Math.round(s[i * 4 + 1]) << 16 | Math.round(s[i * 4 + 2]) << 8 | Math.round(s[i * 4 + 3]);
	}

	/** Suffix on a texture name: the same texture with RGB forced white (alpha-only texture channel). */
	public static final String ALPHA_ONLY = "#a";

	private ParticleTextures() {}

	/** Starts loading a texture in the background (any thread); it registers on its first render-thread use. */
	public static void prefetch(String vtex) {
		if (vtex == null || READY.containsKey(vtex) || PENDING.containsKey(vtex) || LOADING.putIfAbsent(vtex, true) != null) return;
		load(vtex);
	}

	/** The texture if ready (render thread); otherwise starts loading it and returns null. */
	public static Tex get(String vtex) {
		if (vtex == null) return null;
		Tex t = READY.get(vtex);
		if (t != null) return t;
		Object[] p = PENDING.remove(vtex);
		if (p != null) {
			Identifier id = Identifier.fromNamespaceAndPath("strafemc", "particle/" + Integer.toHexString(vtex.hashCode()));
			Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "strafemc particle " + vtex, (NativeImage) p[0]));
			@SuppressWarnings("unchecked") Tex tex = new Tex(id, (List<Sequence>) p[1]);
			READY.put(vtex, tex);
			return tex;
		}
		if (LOADING.putIfAbsent(vtex, true) == null) {
			dev.strafemc.client.Loaders.run(() -> load(vtex));
		}
		return null;
	}

	private static void load(String vtex) {
		int gi = vtex.indexOf(GRADIENT);
		String gkey = gi >= 0 ? vtex.substring(gi + GRADIENT.length()) : "";
		int at = gkey.indexOf('@');
		float level = at >= 0 ? Integer.parseInt(gkey.substring(at + 1)) / (float) GRADIENT_LEVELS : 1; // how faded: the lookup's scale
		if (at >= 0) gkey = gkey.substring(0, at);
		float[] gradient = gi >= 0 ? GRADIENTS.get(gkey) : null;
		String full = vtex;
		if (gi >= 0) vtex = vtex.substring(0, gi);
		try {
			boolean alphaOnly = vtex.endsWith(ALPHA_ONLY), luma = vtex.endsWith(LUMA_ALPHA), red = vtex.endsWith(RED_ALPHA),
					green = vtex.endsWith(GREEN_ALPHA);
			String name = alphaOnly ? vtex.substring(0, vtex.length() - ALPHA_ONLY.length())
					: luma ? vtex.substring(0, vtex.length() - LUMA_ALPHA.length())
					: red ? vtex.substring(0, vtex.length() - RED_ALPHA.length())
					: green ? vtex.substring(0, vtex.length() - GREEN_ALPHA.length()) : vtex;
			String c = name.endsWith("_c") ? name : name + "_c";
			String base = c.substring(0, c.length() - "vtex_c".length());
			Path png = StrafeConfig.cacheDir().resolve(base + "png");
			// the finished texture (atlas built, channels moved, downscaled) from an earlier session: no VRF, no rebuild
			String variant = (alphaOnly ? "a" : luma ? "l" : red ? "r" : green ? "g" : "n") + (gradient != null ? "g" + full.substring(gi + GRADIENT.length()).replace('@', '_') : "");
			Path ready = StrafeConfig.cacheDir().resolve(base + "strafemc1" + variant + ".png");
			// a texture CS2's files have no image or sheet for: not converted again every session
			Path none = StrafeConfig.cacheDir().resolve(base + "strafemc1none");
			if (Files.exists(none)) return;
			if (Files.exists(ready)) {
				List<Sequence> sheet = sheet(c);
				try (var in = Files.newInputStream(ready)) {
					PENDING.put(full, new Object[] {NativeImage.read(in), sheet});
				}
				return;
			}
			// sheet textures come out as <name>_seq<S>_<F>.png (or _seq<S>.png), never <name>.png: those count as done too
			String stem = base.substring(0, base.length() - 1);
			if (!Files.exists(png) && !Files.exists(StrafeConfig.cacheDir().resolve(stem + "_seq0_0.png"))
					&& !Files.exists(StrafeConfig.cacheDir().resolve(stem + "_seq0.png")))
				AssetCache.ensureDecompiled(c, png);
			List<Sequence> sheet = sheet(c);
			// sheet textures: VRF writes each (alpha-cropped) frame as <name>_seq<S>_<F>.png; rebuild the atlas from them
			BufferedImage img;
			boolean finished = false;
			Path plain = StrafeConfig.cacheDir().resolve(base + "strafemc1" + variant.substring(0, 1) + ".png");
			if (gradient != null && !Files.exists(plain)) // the fade levels share one plain build (the atlas is the slow part)
				synchronized (PLAIN_BUILDS.computeIfAbsent(plain.toString(), k -> new Object())) {
					if (!Files.exists(plain)) load(vtex);
				}
			if (gradient != null && Files.exists(plain)) { // the finished texture, just recoloured
				img = ImageIO.read(plain.toFile());
				finished = true;
			}
			else if (Files.exists(png)) img = ImageIO.read(png.toFile());
			else try {
				img = atlas(c, base, sheet);
			} catch (java.io.IOException e) {
				if (String.valueOf(e.getMessage()).startsWith("no image and no sheet")) Files.writeString(none, e.getMessage());
				throw e;
			}
			int f = 1;
			while (Math.max(img.getWidth(), img.getHeight()) / f > 2048) f *= 2;
			if (f > 1) img = boxDown(img, f);
			if ((luma || red || green) && !finished) {
				int[] px = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
				for (int i = 0; i < px.length; i++) {
					int r = px[i] >> 16 & 255, gr = px[i] >> 8 & 255, b = px[i] & 255, a = px[i] >>> 24;
					int l = red ? r : green ? gr : Math.max(r, Math.max(gr, b));
					px[i] = (a * l / 255) << 24 | (px[i] & 0xFFFFFF);
				}
				BufferedImage w = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
				w.setRGB(0, 0, img.getWidth(), img.getHeight(), px, 0, img.getWidth());
				img = w;
			}
			if (alphaOnly && !finished) {
				int[] px = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
				for (int i = 0; i < px.length; i++) px[i] |= 0xFFFFFF;
				BufferedImage w = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
				w.setRGB(0, 0, img.getWidth(), img.getHeight(), px, 0, img.getWidth());
				img = w;
			}
			if (gradient != null) { // brightness -> the gradient's colour; alpha kept
				int[] px = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
				for (int i = 0; i < px.length; i++) {
					int l = Math.max(px[i] >> 16 & 255, Math.max(px[i] >> 8 & 255, px[i] & 255));
					px[i] = (px[i] & 0xFF000000) | gradientAt(gradient, l / 255f * level);
				}
				BufferedImage w = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
				w.setRGB(0, 0, img.getWidth(), img.getHeight(), px, 0, img.getWidth());
				img = w;
			}
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			ImageIO.write(img, "png", bytes);
			Files.write(ready, bytes.toByteArray());
			NativeImage ni = NativeImage.read(new ByteArrayInputStream(bytes.toByteArray()));
			PENDING.put(full, new Object[] {ni, sheet});
		} catch (Exception e) {
			StrafeMc.LOG.warn("[strafePE] particle texture {} failed: {}", vtex, e.toString());
		}
	}

	private static final Pattern WIDTH = Pattern.compile("Width\\s*=\\s*(\\d+)"), HEIGHT = Pattern.compile("Height\\s*=\\s*(\\d+)");

	private static BufferedImage atlas(String vtexC, String base, List<Sequence> sheet) throws Exception {
		String text = Files.readString(AssetCache.dataBlock(vtexC));
		Matcher wm = WIDTH.matcher(text), hm = HEIGHT.matcher(text);
		if (!wm.find() || !hm.find() || sheet.isEmpty()) throw new java.io.IOException("no image and no sheet for " + vtexC);
		int W = Integer.parseInt(wm.group(1)), H = Integer.parseInt(hm.group(1));
		// colour and alpha composed separately: colour-only sequences only write RGB, alpha-only ones only A
		int[] rgb = new int[W * H], alpha = new int[W * H];
		String stem = base.substring(0, base.length() - 1); // "materials/x/name." -> "materials/x/name"
		for (int s = 0; s < sheet.size(); s++)
			for (int f = 0; f < sheet.get(s).frames().size(); f++) {
				Path fp = StrafeConfig.cacheDir().resolve(stem + "_seq" + s + "_" + f + ".png");
				if (!Files.exists(fp) && f == 0) fp = StrafeConfig.cacheDir().resolve(stem + "_seq" + s + ".png"); // single-frame sequence
				if (!Files.exists(fp)) continue;
				Frame fr = sheet.get(s).frames().get(f);
				Sequence sq = sheet.get(s);
				BufferedImage im = ImageIO.read(fp.toFile());
				int ox = Math.round(fr.cu0() * W), oy = Math.round(fr.cv0() * H);
				for (int y = 0; y < im.getHeight(); y++)
					for (int x = 0; x < im.getWidth(); x++) {
						int X = ox + x, Y = oy + y;
						if (X < 0 || Y < 0 || X >= W || Y >= H) continue;
						int p = im.getRGB(x, y);
						if (!sq.noColor()) rgb[Y * W + X] = p & 0xFFFFFF;
						if (!sq.noAlpha()) alpha[Y * W + X] = p >>> 24;
					}
			}
		BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
		int[] px = new int[W * H];
		for (int i = 0; i < px.length; i++) px[i] = alpha[i] << 24 | rgb[i];
		out.setRGB(0, 0, W, H, px, 0, W);
		return out;
	}

	private static BufferedImage boxDown(BufferedImage in, int f) {
		int w = in.getWidth() / f, h = in.getHeight() / f, n = f * f;
		int[] src = in.getRGB(0, 0, in.getWidth(), in.getHeight(), null, 0, in.getWidth());
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++) {
				long a = 0, r = 0, g = 0, b = 0;
				for (int dy = 0; dy < f; dy++)
					for (int dx = 0; dx < f; dx++) {
						int p = src[(y * f + dy) * in.getWidth() + x * f + dx];
						int pa = p >>> 24;
						a += pa;
						r += (long) (p >> 16 & 255) * pa;
						g += (long) (p >> 8 & 255) * pa;
						b += (long) (p & 255) * pa;
					}
				// colour weighted by alpha so transparent (often black) texels don't darken soft edges
				out.setRGB(x, y, a == 0 ? 0 : (int) (a / n) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a));
			}
		return out;
	}

	private static final Pattern SEQ = Pattern.compile("\\[Sequence (\\d+)\\]:"), CLAMP = Pattern.compile("m_bClamp\\s*=\\s*(\\w+)"),
			TOTAL = Pattern.compile("m_flTotalTime\\s*=\\s*([-0-9.eE]+)"), DISPLAY = Pattern.compile("m_flDisplayTime\\s*=\\s*([-0-9.eE]+)"),
			CROPPED = Pattern.compile("uvCropped\\s*=\\s*\\{\\s*\\(\\s*([-0-9.eE]+),\\s*([-0-9.eE]+)\\s*\\),\\s*\\(\\s*([-0-9.eE]+),\\s*([-0-9.eE]+)\\s*\\)"),
			UNCROPPED = Pattern.compile("uvUncropped\\s*=\\s*\\{\\s*\\(\\s*([-0-9.eE]+),\\s*([-0-9.eE]+)\\s*\\),\\s*\\(\\s*([-0-9.eE]+),\\s*([-0-9.eE]+)\\s*\\)");

	/** Sprite sheet from VRF's text dump of the texture's DATA block (empty if the texture has none). */
	static List<Sequence> sheet(String vtexC) throws Exception {
		List<Sequence> seqs = new ArrayList<>();
		String text = Files.readString(AssetCache.dataBlock(vtexC));
		int at = text.indexOf("Sheet Sequences");
		if (at < 0) return seqs;
		String[] parts = text.substring(at).split("\\[Sequence \\d+\\]:");
		for (int i = 1; i < parts.length; i++) {
			String s = parts[i];
			boolean noColor = s.matches("(?s).*m_bNoColor[ 	]*=[ 	]*True.*"), noAlpha = s.matches("(?s).*m_bNoAlpha[ 	]*=[ 	]*True.*");
			Matcher cm = CLAMP.matcher(s), tm = TOTAL.matcher(s);
			boolean clamp = cm.find() && cm.group(1).equalsIgnoreCase("true");
			float total = tm.find() ? Float.parseFloat(tm.group(1)) : 0;
			List<Frame> frames = new ArrayList<>();
			String[] fr = s.split("\\[Sequence \\d+ Frame \\d+\\]:");
			for (int k = 1; k < fr.length; k++) {
				Matcher dm = DISPLAY.matcher(fr[k]), um = UNCROPPED.matcher(fr[k]), cm2 = CROPPED.matcher(fr[k]);
				if (!um.find()) continue;
				float d = dm.find() ? Float.parseFloat(dm.group(1)) : 1;
				float u0 = Float.parseFloat(um.group(1)), v0 = Float.parseFloat(um.group(2)), u1 = Float.parseFloat(um.group(3)), v1 = Float.parseFloat(um.group(4));
				boolean cr = cm2.find();
				frames.add(new Frame(d, u0, v0, u1, v1, cr ? Float.parseFloat(cm2.group(1)) : u0, cr ? Float.parseFloat(cm2.group(2)) : v0,
						cr ? Float.parseFloat(cm2.group(3)) : u1, cr ? Float.parseFloat(cm2.group(4)) : v1));
			}
			if (total <= 0) for (Frame f : frames) total += f.displayTime();
			seqs.add(new Sequence(clamp, total, frames, noColor, noAlpha));
		}
		return seqs;
	}
}
