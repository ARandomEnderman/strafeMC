package dev.strafemc.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.strafemc.data.AssetCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How a CS2 sticker wears when scraped, and its art at a wear for the apply screen: CS2's own sticker shader maths
 * (csgo_weapon_sticker), per pixel. The art's alpha is two things: up to 20/255 the sticker's outline (opacity =
 * clamp(alpha x 12.75)), above that a height h = clamp((alpha - 20/255) x 1.0851). With the wear W, the sticker's
 * scratches strength ws (g_fWearScratchesSticker0) and its wear bias bx, by (g_vWearBiasSticker0):
 * d = clamp(W - h^(bx^2)); s = 1 - min(ws, scratches); S = mix(s, s / 2, W). Paper stickers (g_bPaperBackingSticker0)
 * turn to grey paper mix(0.7, 0.2 + 0.4 S, W) where S falls below clamp(2d) + by W (over a 0.1 smoothstep); all of it
 * goes where S falls below clamp(d (1 + k) - k), k 0.5 on paper, else (by (by > 0 ? 0.5 : 0.25) + 0.5) / 2; and the
 * colour darkens toward x S by 0.3 W. The heights are how CS2's stickers keep their secret scrapes (Blue Gem's "661",
 * Kawaii CT's "why are you scraping me?").
 */
public final class StickerWear {
	public static final String SCRATCHES_PATH = "materials/default/stickers/sticker_default_scratches_psd_a9ad199b.vtex";
	private static final Map<String, Identifier> READY = new ConcurrentHashMap<>();
	private static final Set<String> BUSY = ConcurrentHashMap.newKeySet();
	private static final Map<String, Art> ART = new ConcurrentHashMap<>();

	/**
	 * A sticker's art and its wear settings: scratches strength, wear bias (x, y), paper backing; holo stickers their
	 * mask (g_tSfxMaskSticker0) and spectrum (g_tHoloSpectrumSticker0), and the spectrum's colour boost; metallic (foil)
	 * stickers (g_bMetallicSticker0) are metal on the gun, the rest matte.
	 */
	public record Art(BufferedImage image, double scratches, double biasX, double biasY, boolean paper, BufferedImage holoMask, BufferedImage spectrum,
					  double sfxBoost, boolean metallic) {}

	private StickerWear() {}

	/** The sticker's art and wear settings from its material (blocking: call off the render thread), or null. */
	public static Art art(String sticker) {
		Art a = ART.get(sticker);
		if (a != null) return a;
		var st = dev.strafemc.weapon.Stickers.get(sticker);
		if (st == null) return null;
		try {
			var p = dev.strafemc.client.viewmodel.GloveSkinBaker.Params.parse(Files.readAllLines(AssetCache.dataBlock("stickers/" + st.material() + ".vmat_c")));
			String t = p.textures.get("g_tSticker0");
			BufferedImage img = t == null ? null : dev.strafemc.client.viewmodel.GloveSkinCache.load(t);
			if (img == null) return null;
			double[] bias = p.v("g_vWearBiasSticker0", 1, 1);
			boolean holo = p.f("g_bHolographicSticker0", 0) != 0;
			String mk = p.textures.get("g_tSfxMaskSticker0"), sp = p.textures.get("g_tHoloSpectrumSticker0");
			BufferedImage mask = holo && mk != null && !mk.startsWith("materials/default/") ? dev.strafemc.client.viewmodel.GloveSkinCache.load(mk) : null;
			BufferedImage spec = mask != null && sp != null ? dev.strafemc.client.viewmodel.GloveSkinCache.load(sp) : null;
			a = new Art(img, p.f("g_fWearScratchesSticker0", 0.3), bias[0], bias.length > 1 ? bias[1] : 1, p.f("g_bPaperBackingSticker0", 0) != 0,
					spec == null ? null : mask, spec, p.f("g_flSfxColorBoostSticker0", 1), p.f("g_bMetallicSticker0", 0) != 0);
			ART.put(sticker, a);
			return a;
		} catch (Exception e) {
			dev.strafemc.StrafeMc.LOG.warn("[strafemc] sticker {} art: {}", sticker, e.toString());
			return null;
		}
	}

	private static double smooth(double e0, double e1, double x) {
		double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}

	private static double clamp(double v) {
		return Math.max(0, Math.min(1, v));
	}

	/** CS2's g_vAlbedoLevels (compiled: 0.045, -log2(0.6), 2 - 1.15), which the holo colours are fitted into. */
	private static final double[] LEVELS = {0.045, 0.7369656, 0.85};

	private static double lin(double c) {
		return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}

	private static double srgb(double c) {
		c = clamp(c);
		return c <= 0.0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
	}

	private static int sample(BufferedImage img, double u, double v) {
		int x = (int) Math.floor((u - Math.floor(u)) * img.getWidth()), y = (int) Math.floor(clamp(v) * (img.getHeight() - 1));
		return img.getRGB(Math.min(img.getWidth() - 1, x), Math.min(img.getHeight() - 1, y));
	}

	/**
	 * A holo sticker's colour at (u, v) (linear rgb in c): CS2's sticker shader reads the mask (R how much is holo, G / B the
	 * spectrum coordinate), moves along the spectrum by the view (dot(view, normal) + dot(view, light); seen head-on that
	 * is a whole turn of the repeating spectrum, so none here), and fits the spectrum colour into the albedo levels.
	 */
	private static void holo(double[] c, double u, double v, Art st, double shift) {
		int m = sample(st.holoMask(), u, v);
		double amt = (m >> 16 & 255) / 255.0;
		if (amt <= 0) return;
		int sc = sample(st.spectrum(), (m >> 8 & 255) / 255.0 + shift, (m & 255) / 255.0);
		double boost = st.sfxBoost();
		double[] s = {lin((sc >> 16 & 255) / 255.0), lin((sc >> 8 & 255) / 255.0), lin((sc & 255) / 255.0)};
		double luma = 0.2125 * clamp(s[0] * boost) + 0.7154 * clamp(s[1] * boost) + 0.0721 * clamp(s[2] * boost);
		double[] n = {Math.max(3e-4, s[0]), Math.max(3e-4, s[1]), Math.max(3e-4, s[2])};
		double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
		for (int i = 0; i < 3; i++) n[i] = n[i] / len * 1.06;
		double nl = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]), nLuma = 0.2125 * n[0] + 0.7154 * n[1] + 0.0721 * n[2];
		double smax = Math.max(s[0], Math.max(s[1], s[2]));
		double lv = LEVELS[0] + (LEVELS[2] - LEVELS[0]) * clamp(Math.pow(Math.max(0, smax * boost), LEVELS[1]));
		double f = Math.sqrt(smooth(3e-4, LEVELS[0], luma)), extra = Math.max(0, boost - 1);
		for (int i = 0; i < 3; i++) {
			double fit = Math.max(n[i] * LEVELS[0] * 1.732 / nl / Math.max(1e-6, nLuma), n[i] * lv);
			fit += (Math.min(LEVELS[2], fit + luma * boost) - fit) * Math.min(1, extra);
			double h = LEVELS[0] + (fit - LEVELS[0]) * f;
			c[i] += (h - c[i]) * amt;
		}
	}

	/** pixel without the art's place (no holo). */
	public static int pixel(int art, double scratch, double wear, Art st) {
		return pixel(art, scratch, wear, st, -1, -1);
	}

	/**
	 * A sticker pixel at a wear (ARGB), CS2's maths: art is the art's ARGB at (u, v) of the sticker (negative: unknown),
	 * scratch the sticker scratches there (0-1).
	 */
	public static int pixel(int art, double scratch, double wear, Art st, double u, double v) {
		return pixel(art, scratch, wear, st, u, v, 0);
	}

	/** Whether the sticker has a holo (or lenticular) layer, which moves along its spectrum with the view. */
	public static boolean holo(Art st) {
		return st != null && st.holoMask() != null;
	}

	/**
	 * pixel with the holo seen from a view: shift moves along the spectrum as CS2's dot(view, normal) + dot(view, light)
	 * does (the spectrum repeats each 1): holo colours slide, lenticular frames flip.
	 */
	public static int pixel(int art, double scratch, double wear, Art st, double u, double v, double shift) {
		double alpha = (art >>> 24) / 255.0, opacity = clamp(alpha * 12.75), w = clamp(wear);
		double r = (art >> 16 & 255) / 255.0, g = (art >> 8 & 255) / 255.0, b = (art & 255) / 255.0, a = opacity;
		if (st.holoMask() != null && u >= 0) {
			double[] c = {lin(r), lin(g), lin(b)};
			holo(c, u, v, st, shift);
			r = srgb(c[0]);
			g = srgb(c[1]);
			b = srgb(c[2]);
		}
		if (w > 0) {
			double h = clamp((alpha - 0.0784314) * 1.08511), d = clamp(w - Math.pow(h, st.biasX() * st.biasX()));
			double s = 1 - Math.min(st.scratches(), scratch), sv = s + (s * 0.5 - s) * w;
			if (st.paper()) { // the ink comes off to grey paper
				double e1 = clamp(2 * d) + st.biasY() * w, ink = smooth(e1, e1 + 0.1, sv), grey = 0.7 + (0.2 + 0.4 * sv - 0.7) * w;
				r = grey + (r - grey) * ink;
				g = grey + (g - grey) * ink;
				b = grey + (b - grey) * ink;
			}
			double k = st.paper() ? 0.5 : (st.biasY() * (st.biasY() > 0 ? 0.5 : 0.25) + 0.5) * 0.5;
			double e2 = clamp(d * (1 + k) - k);
			a = opacity * smooth(e2, e2 + 0.1, sv);
			double dark = 1 - w * 0.3 * (1 - sv);
			r *= dark;
			g *= dark;
			b *= dark;
		}
		return (int) Math.round(a * 255) << 24 | (int) Math.round(clamp(r) * 255) << 16 | (int) Math.round(clamp(g) * 255) << 8 | (int) Math.round(clamp(b) * 255);
	}

	/** The sticker's art at this wear (0-1) as a GUI texture (square), or null while it's made (or without the CS2 files). */
	public static Identifier texture(String sticker, double wear) {
		int tenth = (int) Math.round(Math.max(0, Math.min(1, wear)) * 10);
		Identifier id = READY.get(sticker + "#" + tenth);
		if (id != null) return id;
		// not made yet: make every step of this sticker (the scrape slider moves through them) and meanwhile show the
		// closest step that's ready, not the unscraped picture
		for (int k = 0; k <= 10; k++) make(sticker, k);
		for (int d = 1; d <= 10; d++)
			for (int k : new int[] {tenth - d, tenth + d})
				if (k >= 0 && k <= 10 && (id = READY.get(sticker + "#" + k)) != null) return id;
		return null;
	}

	/** Starts making the sticker's art at tenth / 10 wear in the background (once). */
	private static void make(String sticker, int tenth) {
		String key = sticker + "#" + tenth;
		if (READY.containsKey(key) || !BUSY.add(key)) return;
		Thread t = new Thread(() -> {
			try {
				Art art = art(sticker);
				if (art == null) return;
				BufferedImage base = art.image();
				BufferedImage scr = tenth > 0 ? dev.strafemc.client.viewmodel.GloveSkinCache.load(SCRATCHES_PATH) : null;
				int n = Math.min(256, base.getWidth());
				double w = tenth / 10.0;
				NativeImage out = new NativeImage(n, n, false);
				for (int y = 0; y < n; y++)
					for (int x = 0; x < n; x++) {
						int c = base.getRGB(x * base.getWidth() / n, y * base.getHeight() / n);
						double s = scr == null ? 0.5 : (scr.getRGB(x * scr.getWidth() / n, y * scr.getHeight() / n) >> 16 & 255) / 255.0;
						out.setPixel(x, y, pixel(c, s, w, art, (x + 0.5) / n, (y + 0.5) / n));
					}
				Identifier tex = Identifier.fromNamespaceAndPath("strafemc", "sticker_wear/" + Integer.toHexString(key.hashCode()));
				Minecraft.getInstance().execute(() -> {
					Minecraft.getInstance().getTextureManager().register(tex, new DynamicTexture(() -> "strafemc worn sticker " + key, out));
					READY.put(key, tex);
				});
			} catch (Exception e) {
				dev.strafemc.StrafeMc.LOG.warn("[strafemc] worn sticker {}: {}", sticker, e.toString());
			}
		}, "strafemc-sticker-wear");
		t.setDaemon(true);
		t.start();
	}
}
