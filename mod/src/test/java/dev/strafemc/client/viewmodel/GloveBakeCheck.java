package dev.strafemc.client.viewmodel;

import java.nio.file.Files;
import java.nio.file.Path;

/** Bakes glove finishes headless (fresh) and copies them to an output folder: gradlew vmCheck -PvmMain=dev.strafemc.client.viewmodel.GloveBakeCheck -PvmArgs=<out dir>,<kit>,... */
public final class GloveBakeCheck {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		Path out = Path.of(a[0]);
		Files.createDirectories(out);
		for (int i = 1; i < a.length; i++) {
			Path tex = GloveSkinCache.texture(a[i]), pixels = tex.resolveSibling(a[i].replace('@', '_') + ".vm1024b.png");
			Files.deleteIfExists(pixels);
			long t0 = System.nanoTime();
			Path r = GloveSkinCache.ensure(a[i]);
			System.out.println(a[i] + " -> " + r + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
			if (r != null) Files.copy(pixels, out.resolve(a[i].replace('@', '_') + ".png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
