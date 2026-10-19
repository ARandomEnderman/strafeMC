package dev.strafemc.client.viewmodel;

/** Bakes stickers onto the C4 headless: gradlew vmCheck -PvmMain=dev.strafemc.client.viewmodel.C4StickerCheck -PvmArgs=<out.png>,<sticker ids...> */
public final class C4StickerCheck {
	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		String[] w = Viewmodel.weaponModel(dev.strafemc.weapon.C4Item.CLS);
		VmModel m = VmModel.load(Glb.read(dev.strafemc.data.AssetCache.exportGlb(w[0], true)), w[1]);
		var paint = WeaponSkinCache.stickerable(dev.strafemc.weapon.C4Item.CLS, w[0], m.parts);
		System.out.println("paint " + paint);
		java.util.List<VmModel.Part> parts = new java.util.ArrayList<>();
		java.nio.file.Path base = null;
		for (VmModel.Part p : m.parts)
			if (p.texture != null && p.texture.getFileName().toString().startsWith(paint.color())) {
				parts.add(p);
				base = p.texture;
			}
		Viewmodel.downscaled(base);
		String[] st = new String[dev.strafemc.weapon.StickerSlots.C4_SLOTS.length];
		for (int i = 0; i < st.length; i++) st[i] = a[1 + i % (a.length - 1)];
		var tex = WeaponSkinCache.ensureStickers(dev.strafemc.weapon.C4Item.CLS, paint.material(), null, 0, parts, base, st);
		System.out.println("tex " + tex);
		java.nio.file.Files.copy(tex.resolveSibling(tex.getFileName().toString().replace(".png", ".vm1024b.png")), java.nio.file.Path.of(a[0]), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}
}
