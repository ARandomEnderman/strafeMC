package dev.strafemc.client.viewmodel;

import java.nio.file.Path;

/** Renders wrap pictures headless: gradlew vmCheck -PvmMain=dev.strafemc.client.viewmodel.WrapPictureCheck -PvmArgs=<out dir>,<class:kit:finish>,... */
public final class WrapPictureCheck {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		for (int i = 1; i < a.length; i++) {
			String[] p = a[i].split(":");
			long t0 = System.nanoTime();
			var img = WrapPictures.render(p[0], p[1], Integer.parseInt(p[2]), null);
			System.out.printf("%s -> %s (%.1f s)%n", a[i], img == null ? "none" : img.getWidth() + "x" + img.getHeight(), (System.nanoTime() - t0) / 1e9);
			if (img != null) javax.imageio.ImageIO.write(img, "png", Path.of(a[0], p[0] + "_" + p[1] + "_" + p[2] + ".png").toFile());
		}
	}
}
