package dev.strafemc.client.viewmodel;

import dev.strafemc.config.StrafeConfig;
import dev.strafemc.data.AssetCache;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Baked weapon finishes in the cache (cache/weapon_skins/v1/<weapon>/<kit>_<pattern>.png; the pixels in its
 * ".vm1024b.png" sibling, the metalness left after painting in ".vm1024m.png"), made once from the user's CS2 files by
 * WeaponSkinBaker. Blocking: call from a loader thread.
 */
public final class WeaponSkinCache {
	private static final Pattern COMPOSITE = Pattern.compile("\"composite_inputs\"[\\s\\S]*?m_value = \"([^\"]+)\"");
	private static final String PAINTS = "materials/models/weapons/customization/paints/vmats/";

	private WeaponSkinCache() {}

	/** The weapon's material: its model's folder, materials/<model name>.vmat (true for every CS2 knife). */
	public static String material(String model) {
		String m = model.endsWith("_c") ? model.substring(0, model.length() - 2) : model;
		int slash = m.lastIndexOf('/');
		return m.substring(0, slash) + "/materials/" + m.substring(slash + 1).replace(".vmdl", ".vmat");
	}

	/** The material's colour texture file name without extension (what the exported glb names its texture), or null. */
	public static String colorName(String model) throws Exception {
		var p = GloveSkinBaker.Params.parse(Files.readAllLines(AssetCache.dataBlock(material(model) + "_c")));
		String c = p.textures.get("g_tColor");
		if (c == null) return null;
		String f = c.substring(c.lastIndexOf('/') + 1);
		return f.endsWith(".vtex") ? f.substring(0, f.length() - 5) : f;
	}

	public static Path texture(String cls, String kit, int pattern) {
		return StrafeConfig.cacheDir().resolve("weapon_skins/v1/" + cls + "/" + kit + "_" + pattern + ".png");
	}

	/**
	 * Bakes the finish for this weapon if it isn't cached; returns texture(...), or null when the weapon can't wear
	 * finishes (no composite inputs: CS2's default knives). parts: the mesh parts that use the material's colour texture;
	 * base: their texture (the exported colour).
	 */
	public static Path ensure(String cls, String model, String kit, int pattern, List<VmModel.Part> parts, Path base) throws Exception {
		Path tex = texture(cls, kit, pattern), pixels = sibling(tex, ".vm1024b.png"), metal = sibling(tex, ".vm1024m.png");
		if (Files.exists(pixels)) return tex;
		Matcher m = COMPOSITE.matcher(Files.readString(AssetCache.dataBlock(material(model) + "_c")));
		if (!m.find()) return null;
		var ci = GloveSkinBaker.Params.parse(Files.readAllLines(AssetCache.dataBlock(m.group(1) + "_c")));
		var inputs = new WeaponSkinBaker.Inputs(image(ci, "g_tMasks"), image(ci, "g_tAmbientOcclusion"), ci.f("g_flUvScale1", 1),
				ci.f("g_flWeaponLength1", 32));
		var paint = GloveSkinBaker.Params.parse(Files.readAllLines(AssetCache.dataBlock(PAINTS + kit + ".vmat_c")));
		BufferedImage baseImg = ImageIO.read(Viewmodel.downscaledPath(base).toFile());
		var result = WeaponSkinBaker.bake(paint, inputs, baseImg, WeaponSkinBaker.positions(parts, 512), GloveSkinCache::load);
		Files.createDirectories(tex.getParent());
		// metalness: the weapon's own, gone where paint covers it
		Path baseMetal = sibling(base, ".vm1024m.png");
		if (Files.exists(baseMetal)) {
			BufferedImage bm = ImageIO.read(baseMetal.toFile());
			int n = WeaponSkinBaker.SIZE;
			BufferedImage out = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
			for (int y = 0; y < n; y++)
				for (int x = 0; x < n; x++) {
					int v = GloveSkinBaker.sample(bm, (x + 0.5) / n, (y + 0.5) / n) & 255;
					v = Math.round(v * (1 - result.paint()[y * n + x]));
					out.setRGB(x, y, v << 16 | v << 8 | v);
				}
			write(out, metal);
		}
		write(result.color(), pixels);
		return tex;
	}

	private static BufferedImage image(GloveSkinBaker.Params p, String name) {
		String path = p.textures.get(name);
		return path == null || path.startsWith("materials/default/") ? null : GloveSkinCache.load(path);
	}

	private static Path sibling(Path p, String suffix) {
		return p.resolveSibling(p.getFileName().toString().replace(".png", suffix));
	}

	private static void write(BufferedImage img, Path out) throws Exception {
		Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
		ImageIO.write(img, "png", tmp.toFile());
		Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}
}
