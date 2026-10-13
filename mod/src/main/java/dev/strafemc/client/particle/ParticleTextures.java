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

	/** Suffix on a texture name: the same texture with its alpha taken from the colour's brightness (..._RGBALPHA channels). */
	public static final String LUMA_ALPHA = "#luma";
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
			Thread th = new Thread(() -> load(vtex), "strafemc-ptex");
			th.setDaemon(true);
			th.start();
		}
		return null;
	}

	private static void load(String vtex) {
		try {
			boolean alphaOnly = vtex.endsWith(ALPHA_ONLY), luma = vtex.endsWith(LUMA_ALPHA);
			String name = alphaOnly ? vtex.substring(0, vtex.length() - ALPHA_ONLY.length())
					: luma ? vtex.substring(0, vtex.length() - LUMA_ALPHA.length()) : vtex;
			String c = name.endsWith("_c") ? name : name + "_c";
			String base = c.substring(0, c.length() - "vtex_c".length());
			Path png = StrafeConfig.cacheDir().resolve(base + "png");
			AssetCache.ensureDecompiled(c, png);
			List<Sequence> sheet = sheet(c);
			// sheet textures: VRF writes each (alpha-cropped) frame as <name>_seq<S>_<F>.png; rebuild the atlas from them
			BufferedImage img = Files.exists(png) ? ImageIO.read(png.toFile()) : atlas(c, base, sheet);
			int f = 1;
			while (Math.max(img.getWidth(), img.getHeight()) / f > 2048) f *= 2;
			if (f > 1) img = boxDown(img, f);
			if (luma) {
				int[] px = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
				for (int i = 0; i < px.length; i++) {
					int r = px[i] >> 16 & 255, gr = px[i] >> 8 & 255, b = px[i] & 255, a = px[i] >>> 24;
					int l = Math.max(r, Math.max(gr, b));
					px[i] = (a * l / 255) << 24 | (px[i] & 0xFFFFFF);
				}
				BufferedImage w = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
				w.setRGB(0, 0, img.getWidth(), img.getHeight(), px, 0, img.getWidth());
				img = w;
			}
			if (alphaOnly) {
				int[] px = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
				for (int i = 0; i < px.length; i++) px[i] |= 0xFFFFFF;
				BufferedImage w = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
				w.setRGB(0, 0, img.getWidth(), img.getHeight(), px, 0, img.getWidth());
				img = w;
			}
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			ImageIO.write(img, "png", bytes);
			NativeImage ni = NativeImage.read(new ByteArrayInputStream(bytes.toByteArray()));
			PENDING.put(vtex, new Object[] {ni, sheet});
		} catch (Exception e) {
			StrafeMc.LOG.warn("[strafemc] particle texture {} failed: {}", vtex, e.toString());
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
