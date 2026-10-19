package dev.strafemc.client;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

/** Renders stickers at wears headless: gradlew vmCheck -PvmMain=dev.strafemc.client.StickerLookCheck -PvmArgs=<out dir>,<sticker id>,... */
public final class StickerLookCheck {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		for (int i = 1; i < a.length; i++) {
			StickerWear.Art art = StickerWear.art(a[i]);
			if (art == null) {
				System.out.println(a[i] + ": no art");
				continue;
			}
			BufferedImage scr = dev.strafemc.client.viewmodel.GloveSkinCache.load(StickerWear.SCRATCHES_PATH);
			int n = 256;
			BufferedImage out = new BufferedImage(n * 3, n, BufferedImage.TYPE_INT_ARGB);
			double[] wears = {0, 0.5, 1};
			for (int k = 0; k < 3; k++)
				for (int y = 0; y < n; y++)
					for (int x = 0; x < n; x++) {
						int c = art.image().getRGB(x * art.image().getWidth() / n, y * art.image().getHeight() / n);
						double s = (scr.getRGB(x * scr.getWidth() / n, y * scr.getHeight() / n) >> 16 & 255) / 255.0;
						out.setRGB(k * n + x, y, StickerWear.pixel(c, s, wears[k], art, (x + 0.5) / n, (y + 0.5) / n));
					}
			javax.imageio.ImageIO.write(out, "png", Path.of(a[0], a[i] + ".png").toFile());
			System.out.println(a[i] + ": holo " + (art.holoMask() != null));
		}
	}
}
