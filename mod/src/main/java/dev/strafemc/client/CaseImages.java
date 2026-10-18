package dev.strafemc.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.strafemc.StrafeMc;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.data.AssetCache;
import dev.strafemc.weapon.Cases;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CS2's inventory pictures of each gun in its skin (panorama econ/default_generated/<weapon>_<kit>_light_png), for the
 * case screen: converted from the user's CS2 files in one run the first time a case needs them, then cached.
 */
public final class CaseImages {
	private static final Map<String, Identifier> READY = new ConcurrentHashMap<>();
	private static final Set<String> REQUESTED = ConcurrentHashMap.newKeySet();

	private CaseImages() {}

	/** A sticker's picture is square (econ/stickers/<material>); graffiti have none here (their item icon is used). */
	private static String base(Cases.Entry e) {
		if (e.sticker()) {
			var st = dev.strafemc.weapon.Stickers.get(e.kit());
			return st == null ? null : "panorama/images/econ/stickers/" + st.material() + "_png";
		}
		if (e.spray()) return null;
		return "panorama/images/econ/default_generated/" + e.item() + "_" + e.kit() + "_light_png";
	}

	/** The picture's texture once loaded, else null (it starts loading). */
	public static Identifier get(Cases.Entry e) {
		String b = base(e);
		return b == null ? null : image(b);
	}

	/** Any CS2 inventory picture by its panorama path without extension ("panorama/images/econ/weapon_cases/crate_community_8_png"). */
	public static Identifier image(String base) {
		Identifier id = READY.get(base);
		if (id == null) loadPaths(List.of(base));
		return id;
	}

	/** Loads every picture a case shows, in one converter run for the missing ones (off the render thread). */
	public static void load(List<Cases.Entry> entries) {
		List<String> bases = new ArrayList<>();
		for (Cases.Entry e : entries) if (base(e) != null) bases.add(base(e));
		loadPaths(bases);
	}

	private static void loadPaths(List<String> bases) {
		List<String> todo = new ArrayList<>();
		for (String b : bases) if (REQUESTED.add(b)) todo.add(b);
		if (todo.isEmpty()) return;
		Thread t = new Thread(() -> {
			try {
				List<String> missing = new ArrayList<>();
				for (String b : todo) if (!Files.exists(png(b))) missing.add(b + ".vtex_c");
				for (int i = 0; i < missing.size(); i += 64) AssetCache.decompileAll(missing.subList(i, Math.min(missing.size(), i + 64)));
				for (String key : todo) {
					Path p = png(key);
					if (!Files.exists(p)) continue;
					NativeImage img;
					try (var in = Files.newInputStream(p)) {
						img = NativeImage.read(in);
					}
					Minecraft.getInstance().execute(() -> {
						Identifier id = Identifier.fromNamespaceAndPath("strafemc", "case_img/" + Integer.toHexString(key.hashCode()));
						Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "strafemc case picture " + key, img));
						READY.put(key, id);
					});
				}
			} catch (Exception ex) {
				StrafeMc.LOG.warn("[strafemc] case pictures: {}", ex.toString());
			}
		}, "strafemc-case-images");
		t.setDaemon(true);
		t.start();
	}

	private static Path png(String base) {
		return StrafeConfig.cacheDir().resolve(base + ".png");
	}
}
