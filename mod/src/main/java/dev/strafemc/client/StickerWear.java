package dev.strafemc.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.strafemc.config.StrafeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A sticker's picture worn by scraping, for the apply screen: CS2's sticker picture with its sticker scratches
 * (sticker_default_scratches) cut out the way the gun shows it (a pixel stays while the scratches there are above the
 * wear). Made in the background, by tenths of wear.
 */
public final class StickerWear {
	public static final String SCRATCHES_PATH = "materials/default/stickers/sticker_default_scratches_psd_a9ad199b.vtex";
	private static final Map<String, Identifier> READY = new ConcurrentHashMap<>();
	private static final Set<String> BUSY = ConcurrentHashMap.newKeySet();

	private StickerWear() {}

	/** The worn picture of a sticker at this wear (0-1), or null while it's made (or without the CS2 files). */
	public static Identifier texture(String sticker, double wear) {
		int tenth = (int) Math.round(Math.max(0, Math.min(1, wear)) * 10);
		String key = sticker + "#" + tenth;
		Identifier id = READY.get(key);
		if (id != null || !BUSY.add(key)) return id;
		var st = dev.strafemc.weapon.Stickers.get(sticker);
		if (st == null) return null;
		Thread t = new Thread(() -> {
			try {
				Path pic = StrafeConfig.cacheDir().resolve("panorama/images/econ/stickers/" + st.material() + "_png.png");
				if (!Files.exists(pic)) return;
				BufferedImage base = ImageIO.read(pic.toFile()), scr = dev.strafemc.client.viewmodel.GloveSkinCache.load(SCRATCHES_PATH);
				double w = tenth / 10.0;
				NativeImage out = new NativeImage(base.getWidth(), base.getHeight(), false);
				for (int y = 0; y < base.getHeight(); y++)
					for (int x = 0; x < base.getWidth(); x++) {
						int c = base.getRGB(x, y);
						double keep = 1;
						if (scr != null && w > 0) {
							double s = (scr.getRGB(x * scr.getWidth() / base.getWidth(), y * scr.getHeight() / base.getHeight()) >> 16 & 255) / 255.0;
							keep = smooth(w - 0.05, w + 0.05, s);
						}
						int a = (int) Math.round((c >>> 24) * keep);
						out.setPixel(x, y, a << 24 | (c & 0xFFFFFF));
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

	static double smooth(double a, double b, double x) {
		double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
		return t * t * (3 - 2 * t);
	}
}
