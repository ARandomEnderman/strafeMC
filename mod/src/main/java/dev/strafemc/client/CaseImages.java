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

	private static String base(Cases.Entry e) {
		return "panorama/images/econ/default_generated/" + e.item() + "_" + e.kit() + "_light_png";
	}

	/** The picture's texture once loaded, else null (it starts loading). */
	public static Identifier get(Cases.Entry e) {
		Identifier id = READY.get(base(e));
		if (id == null) load(List.of(e));
		return id;
	}

	/** Loads every picture a case shows, in one converter run for the missing ones (off the render thread). */
	public static void load(List<Cases.Entry> entries) {
		List<Cases.Entry> todo = new ArrayList<>();
		for (Cases.Entry e : entries) if (REQUESTED.add(base(e))) todo.add(e);
		if (todo.isEmpty()) return;
		Thread t = new Thread(() -> {
			try {
				List<String> missing = new ArrayList<>();
				for (Cases.Entry e : todo) if (!Files.exists(png(e))) missing.add(base(e) + ".vtex_c");
				for (int i = 0; i < missing.size(); i += 64) AssetCache.decompileAll(missing.subList(i, Math.min(missing.size(), i + 64)));
				for (Cases.Entry e : todo) {
					Path p = png(e);
					if (!Files.exists(p)) continue;
					NativeImage img;
					try (var in = Files.newInputStream(p)) {
						img = NativeImage.read(in);
					}
					String key = base(e);
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

	private static Path png(Cases.Entry e) {
		return StrafeConfig.cacheDir().resolve(base(e) + ".png");
	}
}
