package dev.strafemc.client.viewmodel;

import dev.strafemc.config.StrafeConfig;
import dev.strafemc.data.AssetCache;
import dev.strafemc.weapon.GloveSkins;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Baked glove finishes in the cache (cache/glove_skins/v1/<kit>.vm1024b.png, made once from the user's CS2 files by
 * GloveSkinBaker). Blocking: call from a loader thread.
 */
public final class GloveSkinCache {
	private static final Pattern MATERIAL = Pattern.compile("resource_name:\"([^\"]+\\.vmat)\"");

	private GloveSkinCache() {}

	/** The texture path the viewmodel uses for this finish (its ".vm1024b.png" sibling holds the pixels). */
	public static Path texture(String kit) {
		return StrafeConfig.cacheDir().resolve("glove_skins/v1/" + kit + ".png");
	}

	/** Bakes the finish if it isn't cached; returns texture(kit), or null when it can't be made. */
	public static Path ensure(String kit) throws Exception {
		GloveSkins.Skin skin = GloveSkins.get(kit);
		if (skin == null) return null;
		Path tex = texture(kit), pixels = tex.resolveSibling(kit + ".vm1024b.png");
		if (Files.exists(pixels)) return tex;
		// the composite names the finish's material; the material holds the colours, textures and pattern settings
		Matcher m = MATERIAL.matcher(Files.readString(AssetCache.dataBlock(skin.composite())));
		if (!m.find()) throw new IllegalStateException("no material in " + skin.composite());
		var params = GloveSkinBaker.Params.parse(Files.readAllLines(AssetCache.dataBlock(m.group(1) + "_c")));
		BufferedImage img = GloveSkinBaker.bake(params, GloveSkinCache::load);
		Files.createDirectories(pixels.getParent());
		Path tmp = pixels.resolveSibling(kit + ".tmp.png");
		ImageIO.write(img, "png", tmp.toFile());
		Files.move(tmp, pixels, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		return tex;
	}

	/** A CS2 texture (".vtex" path) as an image: converted by VRF, or read directly when VRF can't (8-bit ID maps). */
	private static BufferedImage load(String vtex) {
		try {
			String base = vtex.substring(0, vtex.length() - ".vtex".length());
			Path png = StrafeConfig.cacheDir().resolve(base + ".png");
			AssetCache.ensureDecompiled(vtex + "_c", png);
			if (Files.exists(png)) return ImageIO.read(png.toFile());
			return VtexR8.read(Files.readAllBytes(AssetCache.raw(vtex + "_c")));
		} catch (Exception e) {
			dev.strafemc.StrafeMc.LOG.warn("[strafemc] glove texture {} unavailable: {}", vtex, e.toString());
			return null;
		}
	}
}
