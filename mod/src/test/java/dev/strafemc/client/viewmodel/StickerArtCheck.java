package dev.strafemc.client.viewmodel;

/** A sticker's art as the bake sees it: gradlew vmCheck -PvmMain=dev.strafemc.client.viewmodel.StickerArtCheck -PvmArgs=<sticker ids...> */
public final class StickerArtCheck {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		for (String id : a) {
			var art = dev.strafemc.client.StickerWear.art(id);
			if (art == null) {
				System.out.println(id + ": no art");
				continue;
			}
			var img = art.image();
			int opaque = 0, shown = 0;
			for (int y = 0; y < img.getHeight(); y++)
				for (int x = 0; x < img.getWidth(); x++) {
					int raw = img.getRGB(x, y);
					if ((raw >>> 24) > 8) opaque++;
					int px = dev.strafemc.client.StickerWear.pixel(raw, 0.5, 0, art, (x + 0.5) / img.getWidth(), (y + 0.5) / img.getHeight());
					if ((px >>> 24) > 8) shown++;
				}
			System.out.println(id + ": " + img.getWidth() + "x" + img.getHeight() + " alpha>8 " + opaque + " shown new " + shown + " holo "
					+ dev.strafemc.client.StickerWear.holo(art) + " " + art);
		}
	}
}
