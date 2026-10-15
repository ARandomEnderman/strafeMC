package dev.strafemc.client.assets;

import java.nio.file.Files;
import java.nio.file.Path;

/** Renders magazine icons headless: gradlew vmCheck -PvmMain=dev.strafemc.client.assets.MagIconCheck -PvmArgs=<out dir>,<weapon class>,... */
public final class MagIconCheck {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		Path out = Path.of(a[0]);
		Files.createDirectories(out);

		for (int i = 1; i < a.length; i++) {
			Path p = out.resolve(a[i] + ".png");
			Files.deleteIfExists(p);
			System.out.println(a[i] + " -> " + MagIcons.render(dev.strafemc.weapon.WeaponTable.get(a[i]), p));
		}
	}
}
