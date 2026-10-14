package dev.strafemc.client.viewmodel;

import dev.strafemc.data.AssetCache;

import java.nio.file.Files;
import java.nio.file.Path;

/** Bakes knife finishes headless and copies them to an output folder: gradlew vmCheck -PvmMain=dev.strafemc.client.viewmodel.KnifeBakeCheck -PvmArgs=<out dir>,<weapon class>,<kit>,... */
public final class KnifeBakeCheck {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		Path out = Path.of(a[0]);
		Files.createDirectories(out);
		String cls = a[1];
		var e = dev.strafemc.weapon.WeaponTable.get(cls);
		Glb glb = Glb.read(AssetCache.exportGlb(e.model(), true));
		String tag = "";
		for (int i = 2; i < a.length; i++) {
			if (a[i].startsWith("f=")) { // pattern size factor for comparisons
				System.setProperty("strafemc.patternFactor", a[i].substring(2));
				tag = "_" + a[i];
				continue;
			}
			boolean legacy = WeaponSkinCache.legacy(a[i]) && VmModel.hasMesh(glb, ".body_legacy");
			VmModel m = VmModel.load(glb, legacy ? ".body_legacy" : VmModel.AUTO_MESH);
			var pm = WeaponSkinCache.paintable(e.model(), m.parts);
			var parts = new java.util.ArrayList<VmModel.Part>();
			Path base = null;
			for (VmModel.Part p : m.parts)
				if (pm != null && p.texture != null && p.texture.getFileName().toString().startsWith(pm.color())) {
					parts.add(p);
					base = p.texture;
				}
			Viewmodel.downscaled(base);
			System.out.println(a[i] + (legacy ? " legacy " : " hd ") + pm);
			Path t = WeaponSkinCache.texture(cls, a[i], 0);
			Files.deleteIfExists(t.resolveSibling(a[i] + "_0.vm1024b.png"));
			long t0 = System.nanoTime();
			Path r = WeaponSkinCache.ensure(cls, pm.material(), a[i], 0, parts, base);
			System.out.printf("%s %s -> %s (%.1f s)%n", cls, a[i], r, (System.nanoTime() - t0) / 1e9);
			if (r != null) Files.copy(r.resolveSibling(a[i] + "_0.vm1024b.png"), out.resolve(cls + "_" + a[i] + tag + ".png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
