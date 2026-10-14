package dev.strafemc.client.assets;

import dev.strafemc.StrafeMc;
import dev.strafemc.client.Placeholders;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.data.AssetCache;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A resource pack generated on the user's machine from their own CS2 install (never shipped with the mod):
 * item icons and HUD icons converted from CS2's Panorama images. Minecraft loads it as an always-on pack.
 */
public final class RuntimePack {
	public static final Path DIR = StrafeConfig.RUNTIME_DIR.resolve("pack");
	/** 26.1.2 resource pack format (version.json resource_major). */
	private static final int FORMAT = 84;

	private RuntimePack() {}

	/** Builds missing files (first run extracts a few images with VRF, ~1 s). Safe to call every start. */
	public static void build() {
		try {
			Files.createDirectories(DIR);
			Files.writeString(DIR.resolve("pack.mcmeta"), "{\"pack\":{\"description\":\"strafeMC: assets converted from your CS2 install\","
					+ "\"pack_format\":" + FORMAT + ",\"min_format\":" + FORMAT + ",\"max_format\":" + FORMAT + "}}");
			Path tex = DIR.resolve("assets/strafemc/textures");
			// each icon falls back to a drawn placeholder without CS2 files (replaced once the real one converts)
			safe(() -> icon("panorama/images/econ/weapons/base_weapons/weapon_ak47_png.vtex_c", "panorama/images/econ/weapons/base_weapons/weapon_ak47_png.png",
					tex.resolve("item/weapon_ak47.png"), 128), tex.resolve("item/weapon_ak47.png"), Placeholders.Icon.GUN, 32);
			safe(() -> icon("panorama/images/econ/weapons/base_weapons/weapon_knife_falchion_png.vtex_c",
					"panorama/images/econ/weapons/base_weapons/weapon_knife_falchion_png.png", tex.resolve("item/weapon_knife_falchion.png"), 128),
					tex.resolve("item/weapon_knife_falchion.png"), Placeholders.Icon.KNIFE, 32);
			for (dev.strafemc.weapon.Grenade g : dev.strafemc.weapon.Grenade.values()) {
				String icon = "panorama/images/econ/weapons/base_weapons/" + g.cls + "_png";
				safe(() -> icon(icon + ".vtex_c", icon + ".png", tex.resolve("item/" + g.cls + ".png"), 128), tex.resolve("item/" + g.cls + ".png"),
						Placeholders.Icon.GRENADE, 32);
			}
			for (dev.strafemc.weapon.GloveItem.Kind k : dev.strafemc.weapon.GloveItem.Kind.values()) {
				// CS2 has inventory pictures only for the default gloves: T's on the studded designs, CT's on the rest
				String icon = "panorama/images/econ/weapons/base_weapons/" + (k.studded ? "t" : "ct") + "_gloves_png";
				safe(() -> icon(icon + ".vtex_c", icon + ".png", tex.resolve("item/" + k.id + ".png"), 128), tex.resolve("item/" + k.id + ".png"),
						Placeholders.Icon.ARMOR, 32);
			}
			gloveSkins(tex);
			wraps(tex);
			// buy menu icons: CS2's white equipment icons, 64 px high
			for (String w : dev.strafemc.client.BuyMenuScreen.ICONS)
				safe(() -> svgH("panorama/images/icons/equipment/" + w + ".vsvg_c", "panorama/images/icons/equipment/" + w + ".svg",
						tex.resolve("gui/buymenu/" + w + ".png"), 64), tex.resolve("gui/buymenu/" + w + ".png"), Placeholders.Icon.BULLET, 32);
			// killfeed icons (CS2's death notices): weapons and the kill modifiers, 48 px high, as wide as they are
			for (String w : dev.strafemc.client.DeathNotices.WEAPON_ICONS)
				safe(() -> svgH("panorama/images/icons/equipment/" + w + ".vsvg_c", "panorama/images/icons/equipment/" + w + ".svg",
						tex.resolve("gui/deathnotice/" + w + ".png"), 48), tex.resolve("gui/deathnotice/" + w + ".png"), Placeholders.Icon.BULLET, 32);
			for (String m : dev.strafemc.client.DeathNotices.MODIFIER_ICONS)
				safe(() -> svgH("panorama/images/hud/deathnotice/" + m + ".vsvg_c", "panorama/images/hud/deathnotice/" + m + ".svg",
						tex.resolve("gui/deathnotice/" + m + ".png"), 48), tex.resolve("gui/deathnotice/" + m + ".png"), Placeholders.Icon.BULLET, 32);
			safe(() -> svg("panorama/images/icons/equipment/healthshot.vsvg_c", "panorama/images/icons/equipment/healthshot.svg",
					tex.resolve("item/weapon_healthshot.png"), 32), tex.resolve("item/weapon_healthshot.png"), Placeholders.Icon.ARMOR, 32);
			for (String a : new String[] {"kevlar", "assaultsuit"}) {
				String item = a.equals("kevlar") ? "item_kevlar" : "item_assaultsuit";
				safe(() -> svg("panorama/images/icons/equipment/" + a + ".vsvg_c", "panorama/images/icons/equipment/" + a + ".svg",
						tex.resolve("item/" + item + ".png"), 32), tex.resolve("item/" + item + ".png"), Placeholders.Icon.ARMOR, 32);
			}
			safe(() -> svg("panorama/images/hud/ammo_reserve_magazine.vsvg_c", "panorama/images/hud/ammo_reserve_magazine.svg", tex.resolve("item/mag_ak47.png"), 32),
					tex.resolve("item/mag_ak47.png"), Placeholders.Icon.MAG, 32);
			safe(() -> svg("panorama/images/hud/ammo_reserve_magazine.vsvg_c", "panorama/images/hud/ammo_reserve_magazine.svg", tex.resolve("gui/hud/ammo_reserve_magazine.png"), 64),
					tex.resolve("gui/hud/ammo_reserve_magazine.png"), Placeholders.Icon.MAG, 64);
			safe(() -> svg("panorama/images/hud/ammo_fullauto.vsvg_c", "panorama/images/hud/ammo_fullauto.svg", tex.resolve("gui/hud/ammo_fullauto.png"), 64),
					tex.resolve("gui/hud/ammo_fullauto.png"), Placeholders.Icon.FULLAUTO, 64);
			safe(() -> svg("panorama/images/hud/ammo_reserve_generic_bullet.vsvg_c", "panorama/images/hud/ammo_reserve_generic_bullet.svg",
					tex.resolve("gui/hud/ammo_bullet.png"), 64), tex.resolve("gui/hud/ammo_bullet.png"), Placeholders.Icon.BULLET, 64);
			StrafeMc.LOG.info("[strafemc] runtime resource pack ready at {}", DIR);
		} catch (Exception e) {
			StrafeMc.LOG.warn("[strafemc] could not build the CS2 asset pack (CS2 copy missing?): {}", e.toString());
		}
	}

	private interface Step {
		void run() throws Exception;
	}

	/**
	 * Converts one icon; if that fails (no CS2 files), writes a drawn placeholder plus a marker, so the real icon replaces
	 * it on a later start once CS2 files are available.
	 */
	private static void safe(Step step, Path out, Placeholders.Icon kind, int size) {
		Path marker = Path.of(out + ".placeholder");
		try {
			if (Files.exists(marker) && AssetCache.available()) {
				Files.deleteIfExists(out);
				Files.deleteIfExists(marker);
			}
			step.run();
		} catch (Exception e) {
			try {
				if (Files.exists(out) && !Files.exists(marker)) return;
				Files.createDirectories(out.getParent());
				write(Placeholders.icon(kind, size), out);
				Files.writeString(marker, "drawn placeholder: " + e);
			} catch (Exception e2) {
				StrafeMc.LOG.warn("[strafemc] placeholder icon {} failed: {}", out, e2.toString());
			}
		}
	}

	/** Raster image -> square, transparent-padded, downscaled item texture. */
	private static void icon(String vpk, String exported, Path out, int size) throws Exception {
		if (Files.exists(out)) return;
		Path src = StrafeConfig.cacheDir().resolve(exported);
		AssetCache.ensureDecompiled(vpk, src);
		BufferedImage in = ImageIO.read(src.toFile());
		int side = Math.max(in.getWidth(), in.getHeight());
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		double sc = (double) size / side;
		int w = (int) Math.round(in.getWidth() * sc), h = (int) Math.round(in.getHeight() * sc);
		g.drawImage(in, (size - w) / 2, (size - h) / 2, w, h, null);
		g.dispose();
		write(img, out);
	}

	private static void svgH(String vpk, String exported, Path out, int height) throws Exception {
		if (Files.exists(out)) return;
		Path src = StrafeConfig.cacheDir().resolve(exported);
		AssetCache.ensureDecompiled(vpk, src);
		write(SvgIcon.rasterize(src, height, true), out);
	}

	private static void svg(String vpk, String exported, Path out, int size) throws Exception {
		if (Files.exists(out)) return;
		Path src = StrafeConfig.cacheDir().resolve(exported);
		AssetCache.ensureDecompiled(vpk, src);
		write(SvgIcon.rasterize(src, size), out);
	}

	/**
	 * Glove finishes: CS2's Factory New inventory pictures (converted in one run), an item model per finish that the
	 * glove items pick by their custom model data string, and the finishes' English names from CS2's localization.
	 */
	private static void gloveSkins(Path tex) throws Exception {
		String prefix = "panorama/images/econ/default_generated/";
		java.util.List<String> missing = new java.util.ArrayList<>();
		for (var s : dev.strafemc.weapon.GloveSkins.ALL) {
			String name = s.kind().id + "_" + s.kit() + "_light_png";
			if (!Files.exists(tex.resolve("item/glove/" + s.kit() + ".png")) && !Files.exists(StrafeConfig.cacheDir().resolve(prefix + name + ".png")))
				missing.add(prefix + name + ".vtex_c");
		}
		if (!missing.isEmpty() && AssetCache.available())
			try {
				AssetCache.decompileAll(missing);
			} catch (Exception e) {
				StrafeMc.LOG.warn("[strafemc] glove pictures: {}", e.toString());
			}
		Path models = DIR.resolve("assets/strafemc/models/item/glove"), items = DIR.resolve("assets/strafemc/items");
		Files.createDirectories(models);
		Files.createDirectories(items);
		java.util.Map<dev.strafemc.weapon.GloveItem.Kind, StringBuilder> cases = new java.util.EnumMap<>(dev.strafemc.weapon.GloveItem.Kind.class);
		for (var s : dev.strafemc.weapon.GloveSkins.ALL) {
			String name = s.kind().id + "_" + s.kit() + "_light_png";
			Path out = tex.resolve("item/glove/" + s.kit() + ".png");
			safe(() -> icon(prefix + name + ".vtex_c", prefix + name + ".png", out, 128), out, Placeholders.Icon.ARMOR, 32);
			Files.writeString(models.resolve(s.kit() + ".json"),
					"{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"strafemc:item/glove/" + s.kit() + "\"}}");
			StringBuilder c = cases.computeIfAbsent(s.kind(), k -> new StringBuilder());
			if (c.length() > 0) c.append(',');
			c.append("{\"when\":\"").append(s.kit()).append("\",\"model\":{\"type\":\"minecraft:model\",\"model\":\"strafemc:item/glove/").append(s.kit())
					.append("\"}}");
		}
		for (var e : cases.entrySet())
			Files.writeString(items.resolve(e.getKey().id + ".json"), "{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:custom_model_data\","
					+ "\"index\":0,\"cases\":[" + e.getValue() + "],\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"strafemc:item/" + e.getKey().id + "\"}}}");
		// names: CS2's "PaintKit_<kit>_Tag" strings (the localization file is converted once)
		Path lang = DIR.resolve("assets/strafemc/lang/en_us.json");
		if (Files.exists(lang) || !AssetCache.available()) return;
		java.util.Map<String, String> tags = new java.util.HashMap<>();
		var line = java.util.regex.Pattern.compile("^[ \\t]*\"PaintKit_([^\"]+?)_tag\"[ \\t]+\"([^\"]*)\"", java.util.regex.Pattern.CASE_INSENSITIVE);
		for (String l : Files.readAllLines(AssetCache.raw("resource/csgo_english.txt"), java.nio.charset.StandardCharsets.UTF_8)) {
			var m = line.matcher(l);
			if (m.find()) tags.put(m.group(1).toLowerCase(java.util.Locale.ROOT), m.group(2));
		}
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		for (var s : dev.strafemc.weapon.GloveSkins.ALL) {
			String t = tags.get(s.kit().toLowerCase(java.util.Locale.ROOT));
			if (t != null && !t.isEmpty()) o.addProperty("strafemc.paintkit." + s.kit(), t);
		}
		Files.createDirectories(lang.getParent());
		Files.writeString(lang, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o));
	}

	/**
	 * Skin wraps: the plain gun / knife wrap pictures, every wrap's own picture (the setup's skin piece on the wrap
	 * template: Minecraft's old banner pattern picture from an older Minecraft on this PC, else our own drawing) and the
	 * item models that pick them by the stack's custom model data. Pictures are redone when the template changes.
	 */
	private static void wraps(Path tex) throws Exception {
		Path models = DIR.resolve("assets/strafemc/models/item/wrap"), items = DIR.resolve("assets/strafemc/items"),
				swatches = StrafeConfig.cacheDir().resolve("wrap_swatches"), cachedTemplate = StrafeConfig.cacheDir().resolve("wrap_template.png");
		Files.createDirectories(models);
		Files.createDirectories(items);
		BufferedImage template = Files.exists(cachedTemplate) ? ImageIO.read(cachedTemplate.toFile()) : OldBannerPattern.find();
		if (template != null && !Files.exists(cachedTemplate)) write(template, cachedTemplate); // local copy: found once, no rescans
		String kind = template != null ? "banner" : "drawn";
		Path marker = tex.resolve("item/wrap/template.txt");
		boolean redo = !Files.exists(marker) || !Files.readString(marker).equals(kind);
		for (boolean knife : new boolean[] {false, true}) {
			String base = knife ? "knife_wrap" : "skin_wrap";
			write(template != null ? dev.strafemc.setup.WrapIcons.make(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB) {
				{
					for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) setRGB(x, y, 0xFFE8E8E8); // blank paper
				}
			}, template, knife) : dev.strafemc.setup.WrapIcons.base(knife), tex.resolve("item/" + base + ".png"));
			Files.writeString(DIR.resolve("assets/strafemc/models/item/" + base + ".json"),
					"{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"strafemc:item/" + base + "\"}}");
			StringBuilder cases = new StringBuilder();
			for (var w : dev.strafemc.weapon.SkinWraps.all()) {
				if (w.knife() != knife) continue;
				String f = dev.strafemc.setup.WrapIcons.fileName(w.id());
				Path src = swatches.resolve(f + ".png"), dst = tex.resolve("item/wrap/" + f + ".png");
				if (!Files.exists(src)) continue; // not converted yet: the plain picture
				if (redo || !Files.exists(dst) || Files.getLastModifiedTime(src).compareTo(Files.getLastModifiedTime(dst)) > 0)
					write(dev.strafemc.setup.WrapIcons.make(ImageIO.read(src.toFile()), template, knife), dst);
				Files.writeString(models.resolve(f + ".json"), "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"strafemc:item/wrap/" + f + "\"}}");
				if (cases.length() > 0) cases.append(',');
				cases.append("{\"when\":\"").append(w.id()).append("\",\"model\":{\"type\":\"minecraft:model\",\"model\":\"strafemc:item/wrap/").append(f).append("\"}}");
			}
			Files.writeString(items.resolve(base + ".json"), "{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:custom_model_data\","
					+ "\"index\":0,\"cases\":[" + cases + "],\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"strafemc:item/" + base + "\"}}}");
		}
		Files.createDirectories(marker.getParent());
		Files.writeString(marker, kind);
	}

	private static void write(BufferedImage img, Path out) throws Exception {
		Files.createDirectories(out.getParent());
		ImageIO.write(img, "png", out.toFile());
	}
}
