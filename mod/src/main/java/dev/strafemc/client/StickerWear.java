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

	/** A sticker's art and its wear settings: scratches strength, wear bias (x, y), paper backing. */
	public record Art(BufferedImage image, double scratches, double biasX, double biasY, boolean paper) {}

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
			a = new Art(img, p.f("g_fWearScratchesSticker0", 0.3), bias[0], bias.length > 1 ? bias[1] : 1, p.f("g_bPaperBackingSticker0", 0) != 0);
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

	/** A sticker pixel at a wear (ARGB), CS2's maths: art is the art's ARGB there, scratch the sticker scratches there (0-1). */
	public static int pixel(int art, double scratch, double wear, Art st) {
		double alpha = (art >>> 24) / 255.0, opacity = clamp(alpha * 12.75), w = clamp(wear);
		double r = (art >> 16 & 255) / 255.0, g = (art >> 8 & 255) / 255.0, b = (art & 255) / 255.0, a = opacity;
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
		String key = sticker + "#" + tenth;
		Identifier id = READY.get(key);
		if (id != null || !BUSY.add(key)) return id;
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
						out.setPixel(x, y, pixel(c, s, w, art));
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
		return null;
	}
}
