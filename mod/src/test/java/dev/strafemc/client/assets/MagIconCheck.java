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
			Path p = out.resolve(a[i].replace("base:", "") + ".png");
			Files.deleteIfExists(p);
			boolean base = a[i].startsWith("base:");
			var e = dev.strafemc.weapon.WeaponTable.get(base ? a[i].substring(5) : a[i]);
			System.out.println(a[i] + " -> " + (base ? MagIcons.renderBase(e, out.resolve(a[i].substring(5) + "_base.png")) : MagIcons.render(e, p)));
		}
	}
}
