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
 * Baked weapon finishes in the cache (cache/weapon_skins/v29/<weapon>/<kit>_<pattern>.png; the pixels in its
 * ".vm1024b.png" sibling, the metalness left after painting in ".vm1024m.png"), made once from the user's CS2 files by
 * WeaponSkinBaker. Blocking: call from a loader thread.
 */
public final class WeaponSkinCache {
	private static final Pattern COMPOSITE = Pattern.compile("\"composite_inputs\"[\\s\\S]*?m_value = \"([^\"]+)\"");
	private static final String PAINTS = "materials/models/weapons/customization/paints/vmats/";

	private WeaponSkinCache() {}

	private static final Pattern VMAT = Pattern.compile("[A-Za-z0-9_/.-]+\\.vmat\\b");
	private static final java.util.Set<String> LEGACY = legacyKits();

	/** CS2 paint kits with use_legacy_model (items_game): a weapon wearing one switches to its legacy mesh. */
	private static java.util.Set<String> legacyKits() {
		java.util.Set<String> out = new java.util.HashSet<>();
		var in = WeaponSkinCache.class.getResourceAsStream("/strafemc/legacy_kits.txt");
		if (in == null) return out;
		try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
			r.lines().filter(l -> !l.isBlank() && !l.startsWith("#")).forEach(l -> out.add(l.trim()));
		} catch (Exception ignored) {
		}
		return out;
	}

	public static boolean legacy(String kit) {
		return LEGACY.contains(kit.toLowerCase(java.util.Locale.ROOT));
	}

	/** A paintable material: its path (".vmat") and its colour texture's file name without extension (as the glb names it). */
	public record Paintable(String material, String color) {}

	/**
	 * The model's material (from its MDAT material list) that has composite inputs and whose colour texture these mesh
	 * parts use: guns have one for the HD mesh and one for the legacy mesh, each with its own composite inputs.
	 */
	public static Paintable paintable(String model, List<VmModel.Part> parts) throws Exception {
		Matcher vm = VMAT.matcher(Files.readString(AssetCache.block(model, "MDAT")));
		java.util.Set<String> seen = new java.util.LinkedHashSet<>();
		while (vm.find()) seen.add(vm.group());
		for (String mat : seen) {
			Path data;
			try {
				data = AssetCache.dataBlock(mat + "_c");
			} catch (Exception e) {
				continue;
			}
			String text = Files.readString(data);
			if (!COMPOSITE.matcher(text).find()) continue;
			String c = GloveSkinBaker.Params.parse(text.lines().toList()).textures.get("g_tColor");
			if (c == null) continue;
			String f = c.substring(c.lastIndexOf('/') + 1);
			String name = f.endsWith(".vtex") ? f.substring(0, f.length() - 5) : f;
			for (VmModel.Part part : parts)
				if (part.texture != null && part.texture.getFileName().toString().startsWith(name)) return new Paintable(mat, name);
		}
		return null;
	}

	private static final Pattern LOOSE = Pattern.compile("m_strName = \"(\\w+)\"(.*?)(?=m_strName = |\\z)", Pattern.DOTALL);
	private static final Pattern TEX = Pattern.compile("m_strTextureRuntimeResourcePath = resource_name:\"([^\"]+\\.vtex)\"");
	private static final Pattern COLOR = Pattern.compile("m_cValueColor4 = \\[ *(\\d+), *(\\d+), *(\\d+)");
	private static final Pattern NUM = Pattern.compile("m_(?:flValueFloatX|nValueIntX) = (-?[0-9.eE+-]+)");
	private static final Pattern BOOL = Pattern.compile("m_bValueBoolean = (true|false)");
	private static final Pattern TEMPLATE = Pattern.compile("templates/([a-z]+)[a-z_]*_template\\.vmat"); // gs_template, so_extended_template...

	/**
	 * A paint kit's material parameters: its paints/vmats material, or for newer kits (composites only) the composite's
	 * loose variables over the style its template names (workshop/paintkits/templates/<style>_template.vmat).
	 */
	static GloveSkinBaker.Params paintParams(String kit) throws Exception {
		var p = GloveSkinBaker.Params.parse(Files.readAllLines(AssetCache.dataBlock(PAINTS + kit + ".vmat_c")));
		if (!p.shader.isEmpty()) return p;
		Path comp;
		try (var s = Files.walk(StrafeConfig.cacheDir().resolve("weapons/paints"))) {
			comp = s.filter(f -> isComposite(f, kit)).findFirst().orElse(null);
		}
		if (comp == null) return p;
		String text = Files.readString(comp);
		Matcher t = TEMPLATE.matcher(text);
		String style = t.find() ? t.group(1) : "cu";
		int f = switch (style) {
			case "so" -> 0;
			case "hy" -> 1;
			case "sp" -> 2;
			case "an" -> 3;
			case "am" -> 4;
			case "aa" -> 5;
			case "aq" -> 7;
			case "gs" -> 8;
			default -> 6;
		};
		p.values.put("F_PAINT_STYLE", new double[] {f});
		Matcher v = LOOSE.matcher(text);
		while (v.find()) {
			String name = v.group(1), body = v.group(2);
			Matcher m;
			if ((m = TEX.matcher(body)).find()) {
				if (!m.group(1).startsWith("materials/default/")) p.textures.put(name, m.group(1));
			} else if ((m = COLOR.matcher(body)).find())
				p.values.put(name, new double[] {Integer.parseInt(m.group(1)) / 255.0, Integer.parseInt(m.group(2)) / 255.0, Integer.parseInt(m.group(3)) / 255.0});
			else if ((m = BOOL.matcher(body)).find()) p.values.put(name, new double[] {m.group(1).equals("true") ? 1 : 0});
			else if ((m = NUM.matcher(body)).find()) p.values.put(name, new double[] {Double.parseDouble(m.group(1))});
		}
		p.shader = "composite " + style;
		p.values.put("strafemc_opaque_pattern", new double[] {1}); // their albedo replaces the colour; its alpha is not coverage
		return p;
	}

	/** The kit's composite in the cache: not the empty stub a missing legacy lookup leaves behind. */
	private static boolean isComposite(Path f, String kit) {
		try {
			return f.getFileName().toString().equals(kit + ".vcompmat_c.DATA.kv3") && Files.size(f) > 64;
		} catch (java.io.IOException e) {
			return false;
		}
	}

	/**
	 * The kit's pattern roll bounds: its composite's econ_instance g_vPatternTexCoordOffset / g_flPatternTexCoordRotation
	 * bounds (legacy kits: weapons/paints/legacy/<kit>.vcompmat; newer ones: their composite in the cache), or null.
	 */
	static dev.strafemc.weapon.PatternRoll.Bounds rollBounds(String kit) {
		String text = null;
		try {
			text = Files.readString(AssetCache.dataBlock("weapons/paints/legacy/" + kit + ".vcompmat_c"));
		} catch (Exception ignored) {
		}
		if (text == null || !text.contains("econ_instance")) {
			try (var s = Files.walk(StrafeConfig.cacheDir().resolve("weapons/paints"))) {
				Path comp = s.filter(f -> isComposite(f, kit)).findFirst().orElse(null);
				text = comp == null ? null : Files.readString(comp);
			} catch (Exception e) {
				return null;
			}
		}
		if (text == null) return null;
		double[] rot = bound(text, "g_flPatternTexCoordRotation", "X"), ox = bound(text, "g_vPatternTexCoordOffset", "X"),
				oy = bound(text, "g_vPatternTexCoordOffset", "Y");
		if (rot == null && ox == null && oy == null) return null;
		return new dev.strafemc.weapon.PatternRoll.Bounds(ox, oy, rot); // null: not rolled (no draw)
	}

	/** A loose variable's {min, max} for one component (X / Y), or null. */
	private static double[] bound(String text, String var, String axis) {
		Matcher v = Pattern.compile("m_strName = \"" + var + "\"(.*?)(?=m_strName = |\\z)", Pattern.DOTALL).matcher(text);
		if (!v.find()) return null;
		Matcher lo = Pattern.compile("m_flValueFloat" + axis + "_Min = (-?[0-9.eE+-]+)").matcher(v.group(1)),
				hi = Pattern.compile("m_flValueFloat" + axis + "_Max = (-?[0-9.eE+-]+)").matcher(v.group(1));
		return lo.find() && hi.find() ? new double[] {Double.parseDouble(lo.group(1)), Double.parseDouble(hi.group(1))} : null;
	}

	public static Path texture(String cls, String kit, int pattern) {
		return StrafeConfig.cacheDir().resolve("weapon_skins/v29/" + cls + "/" + kit + "_" + pattern + ".png");
	}

	/**
	 * Bakes the finish for this weapon if it isn't cached; returns texture(...), or null when the weapon can't wear
	 * finishes (no composite inputs: CS2's default knives). parts: the mesh parts that use the material's colour texture;
	 * base: their texture (the exported colour).
	 */
	public static Path ensure(String cls, String material, String kit, int pattern, List<VmModel.Part> parts, Path base) throws Exception {
		Path tex = texture(cls, kit, pattern), pixels = sibling(tex, ".vm1024b.png"), metal = sibling(tex, ".vm1024m.png");
		if (Files.exists(pixels)) return tex;
		Matcher m = COMPOSITE.matcher(Files.readString(AssetCache.dataBlock(material + "_c")));
		if (!m.find()) return null;
		var ci = GloveSkinBaker.Params.parse(Files.readAllLines(AssetCache.dataBlock(m.group(1) + "_c")));
		var paint = paintParams(kit);
		// the wrap's pattern template, rolled like CS2 within the kit's bounds, places the pattern
		var bounds = rollBounds(kit);
		if (bounds != null) {
			var place = dev.strafemc.weapon.PatternRoll.roll(pattern, bounds);
			// only what the kit's composite rolls replaces the material's value (Fade keeps its offset -0.7, -0.7)
			if (bounds.offsetX() != null || bounds.offsetY() != null) {
				double[] own = paint.v("g_vPatternTexCoordOffset", 0, 0);
				paint.values.put("g_vPatternTexCoordOffset", new double[] {bounds.offsetX() != null ? place.offsetX() : own[0],
						bounds.offsetY() != null ? place.offsetY() : (own.length > 1 ? own[1] : 0)});
			}
			if (bounds.rotation() != null) paint.values.put("g_flPatternTexCoordRotation", new double[] {place.rotation()});
		}
		// Black Pearl only: its pattern is mostly strong colour weights, so CS2's maths alone reads lighter than CS2 shows
		// it; its paint is darkened in linear light (FLAGGED: factor by eye, user-reported)
		if (kit.startsWith("am_blackpearl_marbleized")) paint.values.put("strafemc_albedo_scale", new double[] {0.35});
		// Case Hardened only: more saturated blues and golds (FLAGGED: factor by eye, user-reported)
		if (kit.equals("aq_oiled") || kit.contains("case_hardened")) paint.values.put("strafemc_saturation", new double[] {1.6});
		// newer kits may bring their own masks and ambient occlusion (g_bOverrideDefaultMasks / g_bOverrideAmbientOcclusion)
		BufferedImage masks = paint.f("g_bOverrideDefaultMasks", 0) != 0 && paint.textures.containsKey("g_tPaintByNumberMasks")
				? image(paint, "g_tPaintByNumberMasks") : image(ci, "g_tMasks");
		BufferedImage ao = paint.f("g_bOverrideAmbientOcclusion", 0) != 0 && paint.textures.containsKey("g_tFinalAmbientOcclusion")
				? image(paint, "g_tFinalAmbientOcclusion") : image(ci, "g_tAmbientOcclusion");
		var inputs = new WeaponSkinBaker.Inputs(masks, ao, ci.f("g_flUvScale1", 1), ci.f("g_flWeaponLength1", 32), posMap(ci), image(ci, "g_tSurface"));
		BufferedImage baseImg = ImageIO.read(Viewmodel.downscaledPath(base).toFile());
		var positions = WeaponSkinBaker.positions(parts, WeaponSkinBaker.SIZE);
		var result = WeaponSkinBaker.bake(paint, inputs, baseImg, positions, GloveSkinCache::load);
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
		// roughness where the finish covers the weapon (R) and how much it covers (G)
		int n = WeaponSkinBaker.SIZE;
		BufferedImage rough = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
		for (int i = 0; i < n * n; i++) {
			float r = result.rough()[i];
			int cv = Float.isNaN(r) ? 0 : Math.round(result.cover()[i] * 255), rv = Float.isNaN(r) ? 0 : Math.round(r * 255);
			rough.setRGB(i % n, i / n, rv << 16 | cv << 8);
		}
		write(rough, sibling(tex, ".vm1024r.png"));
		write(result.color(), pixels);
		return tex;
	}

	/** The composite's position map (g_tPosition, half floats), or null. */
	static float[] posMap(GloveSkinBaker.Params ci) {
		String path = ci.textures.get("g_tPosition");
		if (path == null || path.startsWith("materials/default/")) return null;
		try {
			return VtexR8.readHalf4(Files.readAllBytes(AssetCache.raw(path + "_c")));
		} catch (Exception e) {
			dev.strafemc.StrafeMc.LOG.warn("[strafemc] position map {} unreadable: {}", path, e.toString());
			return null;
		}
	}

	static BufferedImage image(GloveSkinBaker.Params p, String name) {
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
