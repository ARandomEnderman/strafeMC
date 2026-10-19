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
 * Baked weapon finishes in the cache (cache/weapon_skins/v33/<weapon>/<kit>_<pattern>.png; the pixels in its
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
	public static GloveSkinBaker.Params paintParams(String kit) throws Exception {
		var p = GloveSkinBaker.Params.parse(Files.readAllLines(AssetCache.dataBlock(PAINTS + kit + ".vmat_c")));
		if (!p.shader.isEmpty()) return p;
		Path comp;
		try (var s = Files.walk(StrafeConfig.cacheDir().resolve("weapons/paints"))) {
			comp = s.filter(f -> isComposite(f, kit)).findFirst().orElse(null);
		}
		if (comp == null) // a newer kit the setup didn't extract: find its composite in the pak and convert it now
			for (String path : AssetCache.list("weapons/paints/"))
				if (path.endsWith("/" + kit + ".vcompmat_c") && !path.contains("/legacy/")) {
					comp = AssetCache.dataBlock(path);
					break;
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
			else if ((m = NUM.matcher(body)).find()) {
				// vectors (FLOAT2-4): every component, not only x
				java.util.List<Double> comps = new java.util.ArrayList<>(List.of(Double.parseDouble(m.group(1))));
				for (String c : new String[] {"Y", "Z", "W"}) {
					Matcher cm = Pattern.compile("m_flValueFloat" + c + " = (-?[0-9.eE+-]+)").matcher(body);
					if (!cm.find()) break;
					comps.add(Double.parseDouble(cm.group(1)));
				}
				p.values.put(name, comps.stream().mapToDouble(Double::doubleValue).toArray());
			}
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
		String text = compositeText(kit);
		if (text == null) return null;
		double[] rot = bound(text, "g_flPatternTexCoordRotation", "X"), ox = bound(text, "g_vPatternTexCoordOffset", "X"),
				oy = bound(text, "g_vPatternTexCoordOffset", "Y");
		if (rot == null && ox == null && oy == null) return null;
		return new dev.strafemc.weapon.PatternRoll.Bounds(ox, oy, rot); // null: not rolled (no draw)
	}

	/** The grunge_wear values the kit's composite rolls (PatternRoll.WearBounds order), or null without them. */
	static dev.strafemc.weapon.PatternRoll.WearBounds wearBounds(String kit) {
		String text = compositeText(kit);
		if (text == null || !text.contains("g_flWearTexCoordScale")) return null;
		String[][] vars = {{"g_flWearTexCoordScale", "X"}, {"g_vWearTexCoordOffset", "X"}, {"g_vWearTexCoordOffset", "Y"},
				{"g_flWearTexCoordRotation", "X"}, {"g_flGrungeTexCoordScale", "X"}, {"g_vGrungeTexCoordOffset", "X"},
				{"g_vGrungeTexCoordOffset", "Y"}, {"g_flGrungeTexCoordRotation", "X"}};
		double[][] out = new double[vars.length][];
		for (int i = 0; i < vars.length; i++) {
			double[] b = bound(text, vars[i][0], vars[i][1]);
			out[i] = b != null ? b : i == 0 || i == 4 ? new double[] {1, 1} : new double[] {0, 0};
		}
		return new dev.strafemc.weapon.PatternRoll.WearBounds(out);
	}

	/** The kit's composite (legacy kits: weapons/paints/legacy/<kit>.vcompmat; newer ones: theirs in the cache), or null. */
	private static String compositeText(String kit) {
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
		return text;
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
		return StrafeConfig.cacheDir().resolve("weapon_skins/v33/" + cls + "/" + kit + "_" + pattern + ".png");
	}

	/**
	 * A kit's own final ambient occlusion: a plain grey, opaque map, so it only replaces the shading (G); the cavities (R)
	 * and the edges where paint breaks off (A) stay the weapon's. Read as edges, its opaque alpha stripped the whole paint
	 * at any wear (Tosai).
	 */
	private static BufferedImage finalOcclusion(BufferedImage weapon, BufferedImage own) {
		if (own == null) return weapon;
		int w = weapon != null ? weapon.getWidth() : own.getWidth(), h = weapon != null ? weapon.getHeight() : own.getHeight();
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++) {
				int g = own.getRGB(x * own.getWidth() / w, y * own.getHeight() / h) >> 8 & 255;
				int c = weapon != null ? weapon.getRGB(x, y) : 0;
				out.setRGB(x, y, c & 0xFFFF00FF | g << 8);
			}
		return out;
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
		// pattern: the finish number carries the wrap's pattern seed and its wear (Wear.finish)
		int finish = pattern;
		pattern = dev.strafemc.weapon.Wear.seed(finish);
		double wear = dev.strafemc.weapon.Wear.wear(finish);
		// FLAGGED: how CS2 turns the item's float into the shader's wear amount wasn't found; float^2 matches CS2's own
		// medium / heavy econ renders (paint lost ~14% / ~47%) if those are Field-Tested / Well-Worn floats
		paint.values.put("g_flWearAmount", new double[] {wear * wear});
		// the wear and grunge textures' placement: the same seed's stream, after the pattern's draws (CS2's roll order)
		var wb = wearBounds(kit);
		double[] wv = wb != null ? dev.strafemc.weapon.PatternRoll.rollWear(pattern, wb) : new double[] {1, 0, 0, 0, 1, 0, 0, 0};
		paint.values.put("strafemc_wear_place", wv);
		paint.textures.putIfAbsent("g_tWear", "materials/models/weapons/customization/shared/paint_wear_psd_19141e79.vtex");
		paint.textures.putIfAbsent("g_tGrunge", "materials/models/weapons/customization/shared/gun_grunge_psd_66b72907.vtex");
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
		// it; its paint is darkened in linear light (FLAGGED: factor by eye)
		if (kit.startsWith("am_blackpearl_marbleized")) paint.values.put("strafemc_albedo_scale", new double[] {0.35});
		// Case Hardened only: more saturated blues and golds (FLAGGED: factor by eye)
		if (kit.equals("aq_oiled") || kit.contains("case_hardened")) paint.values.put("strafemc_saturation", new double[] {1.6});
		// newer kits may bring their own masks and ambient occlusion (g_bOverrideDefaultMasks / g_bOverrideAmbientOcclusion)
		BufferedImage masks = paint.f("g_bOverrideDefaultMasks", 0) != 0 && paint.textures.containsKey("g_tPaintByNumberMasks")
				? image(paint, "g_tPaintByNumberMasks") : image(ci, "g_tMasks");
		BufferedImage ao = image(ci, "g_tAmbientOcclusion");
		if (paint.f("g_bOverrideAmbientOcclusion", 0) != 0 && paint.textures.containsKey("g_tFinalAmbientOcclusion"))
			ao = finalOcclusion(ao, image(paint, "g_tFinalAmbientOcclusion"));
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

	/**
	 * The finish with stickers on (cached next to it, "_s" + the stickers' hash). CS2's weapon material has a slot per
	 * sticker (g_vStickerNOffset, g_vStickerNScale, g_flStickerNRotation) in the mesh's second UV set, a side view of the
	 * gun: slot N's sticker covers 1 / scale of it around (0.5 + offset), turned by the rotation, and only where the
	 * weapon's sticker mask (g_tStickerWepInputs red) allows. The sticker's own art is its material's g_tSticker0.
	 * The art's alpha is a height (StickerWear): new, all of it shows; scraped, its lower parts go first. Holo and
	 * lenticular stickers also get HOLO_STEPS pictures along their spectrum, for the view to pick from. FLAGGED ours:
	 * glitter's sparkle is left out.
	 */
	public static Path ensureStickers(String cls, String material, String kit, int pattern, List<VmModel.Part> parts, Path base, String[] stickers) throws Exception {
		// no finish (the C4): the stickers go on the weapon's own texture
		Path plain = kit == null ? plainCopy(cls, base) : ensure(cls, material, kit, pattern, parts, base);
		if (plain == null) return null;
		String sig = Integer.toHexString((String.join(",", stickers) + "|m4").hashCode()); // "|m": with metalness and roughness
		Path tex = plain.resolveSibling(plain.getFileName().toString().replace(".png", "_s" + sig + ".png")), pixels = sibling(tex, ".vm1024b.png");
		if (Files.exists(pixels)) return tex;
		BufferedImage img = ImageIO.read(sibling(plain, ".vm1024b.png").toFile());
		// metalness and roughness: the finish's, with the stickers on top (foil stickers metal, the rest matte)
		BufferedImage metalImg = Files.exists(sibling(plain, ".vm1024m.png")) ? ImageIO.read(sibling(plain, ".vm1024m.png").toFile()) : null;
		BufferedImage roughImg = Files.exists(sibling(plain, ".vm1024r.png")) ? ImageIO.read(sibling(plain, ".vm1024r.png").toFile()) : null;
		var wp = GloveSkinBaker.Params.parse(Files.readAllLines(AssetCache.dataBlock(material + "_c")));
		BufferedImage mask = image(wp, "g_tStickerWepInputs"), scratches = GloveSkinCache.load(dev.strafemc.client.StickerWear.SCRATCHES_PATH);
		int n = img.getWidth();
		float[] side = secondUv(parts, n);
		// the texels by where they are in sticker space (a 64 x 64 grid over it), so each sticker visits only those under it
		final int g = 64;
		int[][] cells = new int[g * g][];
		{
			int[] count = new int[g * g];
			for (int i = 0; i < n * n; i++) if (!Float.isNaN(side[2 * i])) count[cell(side[2 * i], side[2 * i + 1], g)]++;
			for (int k = 0; k < g * g; k++) cells[k] = new int[count[k]];
			java.util.Arrays.fill(count, 0);
			for (int i = 0; i < n * n; i++) if (!Float.isNaN(side[2 * i])) {
				int k = cell(side[2 * i], side[2 * i + 1], g);
				cells[k][count[k]++] = i;
			}
		}
		// holo texels: what they need to be drawn again with the spectrum shifted (holoVariant)
		java.util.List<double[]> holo = new java.util.ArrayList<>();
		java.util.List<dev.strafemc.client.StickerWear.Art> holoArt = new java.util.ArrayList<>();
		// the C4: stickers only on its front (texel normals from the mesh)
		float[] front = SideView.front(cls, parts);
		float[] normals = front == null ? null : WeaponSkinBaker.positions(parts, n).pos();
		for (int i = 0; i < stickers.length; i++) {
			if (stickers[i] == null || stickers[i].isEmpty()) continue;
			double[] off = wp.v("g_vSticker" + i + "Offset", 0, 0), sc = wp.v("g_vSticker" + i + "Scale", 0, 0);
			double scale = sc[0], rot = wp.f("g_flSticker" + i + "Rotation", 0);
			if (scale <= 0 && cls.equals(dev.strafemc.weapon.C4Item.CLS) && i < dev.strafemc.weapon.StickerSlots.C4_SLOTS.length) { // the C4's places are ours
				double[] c4 = dev.strafemc.weapon.StickerSlots.C4_SLOTS[i];
				off = new double[] {c4[0] - 0.5, c4[1] - 0.5};
				scale = dev.strafemc.weapon.StickerSlots.C4_SCALE;
				rot = c4[2];
			}
			if (scale <= 0) continue;
			var stArt = dev.strafemc.client.StickerWear.art(dev.strafemc.weapon.StickerSlots.idOf(stickers[i]));
			if (stArt == null) continue;
			BufferedImage art = stArt.image();
			double cx = 0.5 + off[0], cy = 0.5 + (off.length > 1 ? off[1] : 0);
			double[] placed = dev.strafemc.weapon.StickerSlots.placeOf(stickers[i]); // put somewhere else: its place and turn (the size stays the slot's)
			if (placed != null && placed.length == 3) {
				cx = placed[0];
				cy = placed[1];
				rot = placed[2];
			}
			double cos = Math.cos(rot), sin = Math.sin(rot), wear = dev.strafemc.weapon.StickerSlots.wearOf(stickers[i]);
			boolean isHolo = dev.strafemc.client.StickerWear.holo(stArt);
			// the cells the sticker's square (half-diagonal 0.71 / scale) can reach
			double reach = 0.71 / scale;
			int c0 = Math.max(0, (int) Math.floor((cx - reach) * g)), c1 = Math.min(g - 1, (int) Math.floor((cx + reach) * g));
			int r0 = Math.max(0, (int) Math.floor((cy - reach) * g)), r1 = Math.min(g - 1, (int) Math.floor((cy + reach) * g));
			for (int cr = r0; cr <= r1; cr++)
				for (int cc = c0; cc <= c1; cc++)
					for (int ti : cells[cr * g + cc]) {
						int x = ti % n, y = ti / n, o = 2 * ti;
					double du = (side[o] - cx) * scale, dv = (side[o + 1] - cy) * scale;
					double su = du * cos - dv * sin + 0.5, sv = du * sin + dv * cos + 0.5;
					if (su < 0 || su > 1 || sv < 0 || sv > 1) continue;
					int raw = GloveSkinBaker.sampleBilinear(art, su, sv), sc0;
					// the art's alpha is a height: all of it shows new, scraping strips the low parts first (CS2's secret scrapes)
					double s = scratches == null ? 0.5 : (GloveSkinBaker.sampleBilinear(scratches, su, sv) >> 16 & 255) / 255.0;
					sc0 = dev.strafemc.client.StickerWear.pixel(raw, s, wear, stArt, su, sv); // CS2's scrape: ink, paper, or gone
					double a = (sc0 >>> 24) / 255.0, m0 = 1;
					if (mask != null) a *= m0 = (GloveSkinBaker.sample(mask, (x + 0.5) / n, (y + 0.5) / n) >> 16 & 255) / 255.0;
					if (normals != null) {
						int no = WeaponSkinBaker.Positions.STRIDE * (y * n + x);
						if (!SideView.facesFront(front, normals[no + 5], normals[no + 6], normals[no + 7])) continue;
					}
					if (a <= 0.004) continue;
					int c = img.getRGB(x, y);
					if (isHolo) {
						holo.add(new double[] {ti, raw, s, wear, su, sv, m0, c});
						holoArt.add(stArt);
					}
					int r = (int) Math.round((c >> 16 & 255) * (1 - a) + (sc0 >> 16 & 255) * a), gg = (int) Math.round((c >> 8 & 255) * (1 - a) + (sc0 >> 8 & 255) * a),
							b = (int) Math.round((c & 255) * (1 - a) + (sc0 & 255) * a);
					img.setRGB(x, y, (c & 0xFF000000) | r << 16 | gg << 8 | b);
					if (metalImg != null && x < metalImg.getWidth() && y < metalImg.getHeight()) {
						int m = (int) Math.round((metalImg.getRGB(x, y) & 255) * (1 - a) + (stArt.metallic() ? 255 : 0) * a);
						metalImg.setRGB(x, y, m << 16 | m << 8 | m);
					}
					if (roughImg != null && x < roughImg.getWidth() && y < roughImg.getHeight()) {
						// FLAGGED ours: sticker roughness 0.3 foil / 0.6 paper (CS2 reads it from g_tNormalRoughnessSticker0)
						int rc = roughImg.getRGB(x, y), rr = rc >> 16 & 255, cv = rc >> 8 & 255;
						double was = cv > 0 ? rr / 255.0 : (metalImg != null && (metalImg.getRGB(x, y) & 255) >= 128 ? 0.25 : 0.45);
						double nr = was * (1 - a) + (stArt.metallic() ? 0.3 : 0.6) * a;
						roughImg.setRGB(x, y, (int) Math.round(nr * 255) << 16 | (int) Math.round(Math.max(cv, a * 255)) << 8);
					}
				}
		}
		if (metalImg != null) write(metalImg, sibling(tex, ".vm1024m.png"));
		if (roughImg != null) write(roughImg, sibling(tex, ".vm1024r.png"));
		// holo / lenticular stickers: the same picture with their spectrum shifted a step each, for the viewmodel to show by
		// the view (HOLO_STEPS of a whole turn; only their texels are drawn again)
		if (!holo.isEmpty())
			for (int k = 1; k < HOLO_STEPS; k++) {
				BufferedImage v = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
				v.setData(img.getData());
				for (int h = 0; h < holo.size(); h++) {
					double[] e = holo.get(h);
					int ti = (int) e[0], x = ti % n, y = ti / n, under = (int) (long) e[7];
					int sc0 = dev.strafemc.client.StickerWear.pixel((int) (long) e[1], e[2], e[3], holoArt.get(h), e[4], e[5], k / (double) HOLO_STEPS);
					double a = (sc0 >>> 24) / 255.0 * e[6];
					int r = (int) Math.round((under >> 16 & 255) * (1 - a) + (sc0 >> 16 & 255) * a), gg = (int) Math.round((under >> 8 & 255) * (1 - a) + (sc0 >> 8 & 255) * a),
							b = (int) Math.round((under & 255) * (1 - a) + (sc0 & 255) * a);
					v.setRGB(x, y, (under & 0xFF000000) | r << 16 | gg << 8 | b);
				}
				Path step = holoVariant(tex, k);
				write(v, sibling(step, ".vm1024b.png"));
				for (String sfx : new String[] {".vm1024m.png", ".vm1024r.png"}) // shaded like the finish itself
					if (Files.exists(sibling(tex, sfx))) Files.copy(sibling(tex, sfx), sibling(step, sfx), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		write(img, pixels);
		return tex;
	}

	/** How many steps of the holo spectrum's turn are baked (the viewmodel picks one by the view). */
	public static final int HOLO_STEPS = 6;

	/** Step k (1..HOLO_STEPS-1) of a stickered finish's holo turn (step 0 is the finish itself); exists only with holo stickers. */
	public static Path holoVariant(Path tex, int k) {
		return k == 0 ? tex : tex.resolveSibling(tex.getFileName().toString().replace(".png", "_h" + k + ".png"));
	}

	private static int cell(float u, float v, int g) {
		return Math.max(0, Math.min(g - 1, (int) Math.floor(v * g))) * g + Math.max(0, Math.min(g - 1, (int) Math.floor(u * g)));
	}

	/**
	 * A weapon's own texture (its 1024 colour, metalness) copied as a "finish" to put stickers on, for a weapon that
	 * wears no finish (the C4); null without the converted texture.
	 */
	private static Path plainCopy(String cls, Path base) throws Exception {
		Path colour = Viewmodel.downscaledPath(base);
		if (!Files.exists(colour)) return null;
		Path tex = texture(cls, "plain", 0), pixels = sibling(tex, ".vm1024b.png");
		Files.createDirectories(tex.getParent());
		if (!Files.exists(pixels)) Files.copy(colour, pixels, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		Path metal = sibling(base, ".vm1024m.png");
		if (Files.exists(metal) && !Files.exists(sibling(tex, ".vm1024m.png"))) Files.copy(metal, sibling(tex, ".vm1024m.png"));
		return tex;
	}

	/** The material stickers go through for a weapon: its finish material, or the C4's own (it has no finish). */
	public static Paintable stickerable(String cls, String model, List<VmModel.Part> parts) throws Exception {
		Paintable p = paintable(model, parts);
		if (p != null || !cls.equals(dev.strafemc.weapon.C4Item.CLS)) return p;
		for (VmModel.Part part : parts)
			if (part.texture != null && part.texture.getFileName().toString().startsWith("c4_color"))
				return new Paintable("weapons/models/c4/materials/weapon_c4.vmat", "c4_color");
		return null;
	}

	/** The mesh's second UV set (TEXCOORD_1) per texel of its texture (u, v pairs; NaN where nothing covers it). */
	private static float[] secondUv(List<VmModel.Part> parts, int size) {
		float[] out = new float[size * size * 2];
		java.util.Arrays.fill(out, Float.NaN);
		for (VmModel.Part p : parts) {
			if (p.uv1 == null) continue;
			float[] uv = p.uv, u1 = p.uv1;
			for (int t = 0; t + 2 < p.idx.length; t += 3) {
				int a = p.idx[t], b = p.idx[t + 1], c = p.idx[t + 2];
				double ax = frac(uv[a * 2]) * size, ay = frac(uv[a * 2 + 1]) * size, bx = frac(uv[b * 2]) * size, by = frac(uv[b * 2 + 1]) * size,
						cx = frac(uv[c * 2]) * size, cy = frac(uv[c * 2 + 1]) * size;
				double area = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
				if (Math.abs(area) < 1e-9) continue;
				int x0 = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx)))), x1 = Math.min(size - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
				int y0 = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy)))), y1 = Math.min(size - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
				for (int y = y0; y <= y1; y++)
					for (int x = x0; x <= x1; x++) {
						double px = x + 0.5, py = y + 0.5;
						double wa = ((bx - px) * (cy - py) - (cx - px) * (by - py)) / area, wb = ((cx - px) * (ay - py) - (ax - px) * (cy - py)) / area, wc = 1 - wa - wb;
						if (wa < -0.01 || wb < -0.01 || wc < -0.01) continue;
						int o = 2 * (y * size + x);
						out[o] = (float) (wa * u1[a * 2] + wb * u1[b * 2] + wc * u1[c * 2]);
						out[o + 1] = (float) (wa * u1[a * 2 + 1] + wb * u1[b * 2 + 1] + wc * u1[c * 2 + 1]);
					}
			}
		}
		return out;
	}

	private static double frac(double v) {
		return v - Math.floor(v);
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
