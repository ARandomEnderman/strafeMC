package dev.strafemc.client.viewmodel;

/**
 * Bakes stickers onto a gun's finish headless and counts the texels they changed:
 * gradlew vmCheck -PvmMain=dev.strafemc.client.viewmodel.GunStickerCheck -PvmArgs=<cls>,<kit>,<pattern>,<out.png>,<sticker entries ("|" between)>
 */
public final class GunStickerCheck {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		String cls = a[0], kit = a[1];
		int pattern = Integer.parseInt(a[2]);
		String[] w = Viewmodel.weaponModel(cls);
		VmModel m = VmModel.load(Glb.read(dev.strafemc.data.AssetCache.exportGlb(w[0], true)), w[1]);
		var paint = WeaponSkinCache.stickerable(cls, w[0], m.parts);
		java.util.List<VmModel.Part> parts = new java.util.ArrayList<>();
		java.nio.file.Path base = null;
		for (VmModel.Part p : m.parts)
			if (p.texture != null && p.texture.getFileName().toString().startsWith(paint.color())) {
				parts.add(p);
				base = p.texture;
			}
		Viewmodel.downscaled(base);
		String[] st = new String[5];
		java.util.Arrays.fill(st, "");
		String[] given = a[4].split("\\|");
		for (int i = 0; i < given.length && i < st.length; i++) st[i] = given[i].replace(';', ',');
		long t0 = System.nanoTime();
		var tex = WeaponSkinCache.ensureStickers(cls, paint.material(), kit, pattern, parts, base, st);
		var plain = WeaponSkinCache.ensure(cls, paint.material(), kit, pattern, parts, base);
		var A = javax.imageio.ImageIO.read(plain.resolveSibling(plain.getFileName().toString().replace(".png", ".vm1024b.png")).toFile());
		var B = javax.imageio.ImageIO.read(tex.resolveSibling(tex.getFileName().toString().replace(".png", ".vm1024b.png")).toFile());
		int changed = 0;
		for (int y = 0; y < A.getHeight(); y++)
			for (int x = 0; x < A.getWidth(); x++) if ((A.getRGB(x, y) & 0xFFFFFF) != (B.getRGB(x, y) & 0xFFFFFF)) changed++;
		System.out.println("tex " + tex.getFileName() + " changed " + changed + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
		javax.imageio.ImageIO.write(B, "png", new java.io.File(a[3]));
	}
}
