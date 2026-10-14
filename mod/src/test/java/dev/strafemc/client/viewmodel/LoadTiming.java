package dev.strafemc.client.viewmodel;

import dev.strafemc.data.AssetCache;

import java.nio.file.Files;
import java.nio.file.Path;

/** Times the steps of loading a viewmodel (as Viewmodel.loadAsync does them), on files already in the cache. */
public final class LoadTiming {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		for (int run = 0; run < 2; run++) { // the second run is with the JVM warm
			long t0 = System.nanoTime();
			VmModel arms = VmModel.load(Glb.read(AssetCache.exportGlb(Viewmodel.ARMS, true)), ".viewmodel");
			long t1 = System.nanoTime();
			VmModel gun = VmModel.load(Glb.read(AssetCache.exportGlb(a[0], true)), VmModel.AUTO_MESH);
			long t2 = System.nanoTime();
			int clips = 0;
			try (var s = Files.list(dev.strafemc.config.StrafeConfig.cacheDir().resolve(a[1]))) {
				for (Path p : s.filter(f -> f.toString().endsWith(".glb")).toList()) {
					new VmClip(p.getFileName().toString(), Glb.read(p));
					clips++;
				}
			}
			long t3 = System.nanoTime();
			long png = 0, masks = 0;
			int textures = 0;
			for (VmModel m : new VmModel[] {arms, gun})
				for (VmModel.Part part : m.parts) {
					if (part.texture == null) continue;
					textures++;
					long p0 = System.nanoTime();
					try (var in = Files.newInputStream(Viewmodel.downscaledPath(part.texture))) {
						com.mojang.blaze3d.platform.NativeImage.read(in).close();
					}
					long p1 = System.nanoTime();
					Path mask = part.texture.resolveSibling(part.texture.getFileName().toString().replace(".png", ".vm1024m.png"));
					if (Files.exists(mask)) javax.imageio.ImageIO.read(mask.toFile());
					Viewmodel.metalPerVertex(part);
					png += p1 - p0;
					masks += System.nanoTime() - p1;
				}
			System.out.printf("run %d: arms %.0f ms, gun %.0f ms, %d clips %.0f ms, %d textures: colour %.0f ms, metal masks %.0f ms%n", run,
					(t1 - t0) / 1e6, (t2 - t1) / 1e6, clips, (t3 - t2) / 1e6, textures, png / 1e6, masks / 1e6);
		}
	}
}
