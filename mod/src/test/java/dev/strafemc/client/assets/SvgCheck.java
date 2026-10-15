package dev.strafemc.client.assets;

import java.nio.file.Files;
import java.nio.file.Path;

/** Rasterizes CS2 svgs headless: gradlew vmCheck -PvmMain=dev.strafemc.client.assets.SvgCheck -PvmArgs=<out dir>,<height>,<vsvg path>,... */
public final class SvgCheck {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		Path out = Path.of(a[0]);
		Files.createDirectories(out);
		int h = Integer.parseInt(a[1]);
		for (int i = 2; i < a.length; i++) {
			String vpk = a[i], exported = vpk.replace(".vsvg_c", ".svg");
			Path src = dev.strafemc.config.StrafeConfig.cacheDir().resolve(exported);
			dev.strafemc.data.AssetCache.ensureDecompiled(vpk, src);
			Path png = out.resolve(src.getFileName().toString().replace(".svg", ".png"));
			boolean add = Boolean.getBoolean("strafemc.additive") && !src.getFileName().toString().contains("pip") && !src.getFileName().toString().contains("mask");
			javax.imageio.ImageIO.write(add ? SvgIcon.rasterizeAdditive(src, h) : SvgIcon.rasterize(src, h, true), "png", png.toFile());
			System.out.println(vpk + " -> " + png);
		}
	}
}
