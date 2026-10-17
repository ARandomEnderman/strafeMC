package dev.strafemc.client.assets;

import dev.strafemc.StrafeMc;
import dev.strafemc.client.Placeholders;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.data.AssetCache;
import dev.strafemc.weapon.WeaponTable;

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
			// every gun and knife: CS2's inventory picture
			for (var list : java.util.List.of(dev.strafemc.weapon.WeaponTable.guns(), dev.strafemc.weapon.WeaponTable.knives()))
				for (var e : list) {
					String icon = "panorama/images/econ/weapons/base_weapons/" + e.cls() + "_png";
					safe(() -> icon(icon + ".vtex_c", icon + ".png", tex.resolve("item/" + e.cls() + ".png"), 128), tex.resolve("item/" + e.cls() + ".png"),
							e.knife() ? Placeholders.Icon.KNIFE : Placeholders.Icon.GUN, 32);
				}
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
			// CS2's sniper scope overlay (panorama hud/scope): the round mask, the lens tint, the blurred reticle line
			for (String[] sc : new String[][] {{"scope_circle_png", "circle"}, {"scope_lens_tga", "lens"}, {"scope_line_blur_tga", "line_blur"}}) {
				Path out = tex.resolve("gui/scope/" + sc[1] + ".png");
				try {
					raw("panorama/images/hud/scope/" + sc[0] + ".vtex_c", "panorama/images/hud/scope/" + sc[0] + ".png", out);
				} catch (Exception e) {
					StrafeMc.LOG.warn("[strafemc] scope overlay {}: {}", sc[1], e.toString());
				}
			}
			gloveSkins(tex);
			wraps(tex);
			safe(() -> cases(tex), tex.resolve("item/case_key.png"), Placeholders.Icon.MAG, 16);
			// equipment icons for the buy menu and the killfeed: every missing one converted in a single run
			java.util.List<String> svgs = new java.util.ArrayList<>();
			for (String w : java.util.stream.Stream.concat(java.util.Arrays.stream(dev.strafemc.client.BuyMenuScreen.ICONS),
					java.util.Arrays.stream(dev.strafemc.client.DeathNotices.WEAPON_ICONS)).distinct().toList())
				if (!Files.exists(StrafeConfig.cacheDir().resolve("panorama/images/icons/equipment/" + w + ".svg")))
					svgs.add("panorama/images/icons/equipment/" + w + ".vsvg_c");
			if (!svgs.isEmpty() && AssetCache.available())
				try {
					AssetCache.decompileAll(svgs);
				} catch (Exception e) {
					StrafeMc.LOG.warn("[strafemc] equipment icons: {}", e.toString());
				}
			safe(() -> svgH("panorama/images/icons/ui/undo.vsvg_c", "panorama/images/icons/ui/undo.svg", tex.resolve("gui/buymenu/undo.png"), 64),
					tex.resolve("gui/buymenu/undo.png"), Placeholders.Icon.BULLET, 32);
			// the strafeMC menu wheel: CS2's own main menu / UI icons, white, 64 px
			for (String[] m : dev.strafemc.client.QuickMenuScreen.ICONS) {
				Path out = tex.resolve("gui/menu/" + m[0] + ".png");
				safe(() -> svg("panorama/images/" + m[1] + ".vsvg_c", "panorama/images/" + m[1] + ".svg", out, 64), out, Placeholders.Icon.BULLET, 32);
			}
			// buy menu icons: CS2's white equipment icons, 64 px high
			for (String w : dev.strafemc.client.BuyMenuScreen.ICONS)
				safe(() -> svgH("panorama/images/icons/equipment/" + w + ".vsvg_c", "panorama/images/icons/equipment/" + w + ".svg",
						tex.resolve("gui/buymenu/" + w + ".png"), 64), tex.resolve("gui/buymenu/" + w + ".png"), Placeholders.Icon.BULLET, 32);
			// killfeed icons (CS2's death notices): weapons and the kill modifiers, 48 px high, as wide as they are
			for (String w : dev.strafemc.client.DeathNotices.WEAPON_ICONS)
				safe(() -> svgH("panorama/images/icons/equipment/" + w + ".vsvg_c", "panorama/images/icons/equipment/" + w + ".svg",
						tex.resolve("gui/deathnotice/" + w + ".png"), 48), tex.resolve("gui/deathnotice/" + w + ".png"), Placeholders.Icon.BULLET, 32);
			safe(() -> svgH("panorama/images/icons/equipment/flashbang_assist.vsvg_c", "panorama/images/icons/equipment/flashbang_assist.svg",
					tex.resolve("gui/deathnotice/flashbang_assist.png"), 48), tex.resolve("gui/deathnotice/flashbang_assist.png"), Placeholders.Icon.BULLET, 32);
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
			// CS2's round kill cards (hudhealthammocenter): card, outline per kill, pips
			// the card shapes (kill_1..5, extra) are drawn additively in CS2: their own colours, the mask's clip; the backing
			// (kill_mask) and the pips are plain shapes, tinted when drawn
			for (String k : new String[] {"kill_1", "kill_2", "kill_3", "kill_4", "kill_5", "kill_extra", "kill_mask", "kill_pip_default", "kill_pip_headshot",
					"kill_pip_blast", "kill_pip_burn", "kill_pip_slash", "kill_pip_shock"}) {
				Path out = tex.resolve(dev.strafemc.client.KillCards.ART_DIR + k + ".png");
				boolean additive = k.startsWith("kill_") && !k.startsWith("kill_pip") && !k.equals("kill_mask");
				safe(() -> {
					if (Files.exists(out)) return;
					String vpk = "panorama/images/hud/" + k + ".vsvg_c", exported = "panorama/images/hud/" + k + ".svg";
					Path src = StrafeConfig.cacheDir().resolve(exported);
					AssetCache.ensureDecompiled(vpk, src);
					write(additive ? SvgIcon.rasterizeAdditive(src, dev.strafemc.client.KillCards.ART_H) : SvgIcon.rasterize(src, dev.strafemc.client.KillCards.ART_H, true), out);
				}, out, Placeholders.Icon.BULLET, 32);
			}
			try {
				mags(tex);
			} catch (Exception e) {
				StrafeMc.LOG.warn("[strafemc] magazine icons: {}", e.toString());
			}
			safe(() -> svg("panorama/images/hud/ammo_reserve_magazine.vsvg_c", "panorama/images/hud/ammo_reserve_magazine.svg", tex.resolve("gui/hud/ammo_reserve_magazine.png"), 64),
					tex.resolve("gui/hud/ammo_reserve_magazine.png"), Placeholders.Icon.MAG, 64);
			safe(() -> svg("panorama/images/hud/ammo_fullauto.vsvg_c", "panorama/images/hud/ammo_fullauto.svg", tex.resolve("gui/hud/ammo_fullauto.png"), 64),
					tex.resolve("gui/hud/ammo_fullauto.png"), Placeholders.Icon.FULLAUTO, 64);
			safe(() -> svg("panorama/images/hud/ammo_burst.vsvg_c", "panorama/images/hud/ammo_burst.svg", tex.resolve("gui/hud/ammo_burst.png"), 64),
					tex.resolve("gui/hud/ammo_burst.png"), Placeholders.Icon.FULLAUTO, 64);
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

	/** Icons drawn before this (when only SVG paths were drawn: no rects, polygons or transforms) are drawn again. */
	private static final long SVG_RENDERER = java.time.Instant.parse("2026-10-16T00:00:00Z").toEpochMilli();

	private static boolean drawn(Path out) throws java.io.IOException {
		return Files.exists(out) && Files.getLastModifiedTime(out).toMillis() >= SVG_RENDERER;
	}

	private static void svgH(String vpk, String exported, Path out, int height) throws Exception {
		if (drawn(out)) return;
		Path src = StrafeConfig.cacheDir().resolve(exported);
		AssetCache.ensureDecompiled(vpk, src);
		write(SvgIcon.rasterize(src, height, true), out);
	}

	private static void svg(String vpk, String exported, Path out, int size) throws Exception {
		if (drawn(out)) return;
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

	/**
	 * Weapon cases: CS2's own case pictures (panorama econ/weapon_cases, converted in one run when missing), picked per
	 * case by the item's custom model data string; the container key on Minecraft's trial key.
	 */
	/**
	 * Each gun's magazine: its own icon drawn from the gun's model (MagIcons; the generic magazine icon until then), and
	 * an empty one (custom model data flag 0) with the red X (textures/item/mag_empty.png) on top.
	 */
	private static void mags(Path tex) throws Exception {
		try { // the gun base: an AK-47's body
			MagIcons.renderBase(WeaponTable.get("weapon_ak47"), tex.resolve("item/gun_base.png"));
		} catch (Exception ex) {
			StrafeMc.LOG.warn("[strafemc] gun base icon: {}", ex.toString());
		}
		Path models = DIR.resolve("assets/strafemc/models/item/mag"), items = DIR.resolve("assets/strafemc/items");
		Files.createDirectories(models);
		Files.createDirectories(items);
		for (WeaponTable.Entry e : WeaponTable.guns()) {
			if (e.cls().equals("weapon_taser")) continue;
			String id = "mag_" + e.shortName();
			boolean drawn;
			try {
				drawn = MagIcons.render(e, tex.resolve("item/mag/" + e.cls() + MagIcons.VERSION + ".png"));
			} catch (Exception ex) {
				StrafeMc.LOG.warn("[strafemc] magazine icon {}: {}", e.cls(), ex.toString());
				drawn = false;
			}
			String icon = drawn ? "strafemc:item/mag/" + e.cls() + MagIcons.VERSION : "strafemc:item/mag_ak47";
			Files.writeString(models.resolve(e.cls() + ".json"), "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"" + icon + "\"}}");
			// the empty one: the red X drawn into its own picture (a second model layer can end up under the first)
			Path iconPng = drawn ? tex.resolve("item/mag/" + e.cls() + MagIcons.VERSION + ".png") : tex.resolve("item/mag_ak47.png");
			Path emptyPng = tex.resolve("item/mag/" + e.cls() + MagIcons.VERSION + "_empty.png");
			String emptyIcon = icon;
			try {
				if (!Files.exists(emptyPng) && Files.exists(iconPng)) write(MagIcons.withMark(ImageIO.read(iconPng.toFile())), emptyPng);
				if (Files.exists(emptyPng)) emptyIcon = "strafemc:item/mag/" + e.cls() + MagIcons.VERSION + "_empty";
			} catch (Exception ex) {
				StrafeMc.LOG.warn("[strafemc] empty magazine icon {}: {}", e.cls(), ex.toString());
			}
			Files.writeString(models.resolve(e.cls() + "_empty.json"), "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"" + emptyIcon + "\"}}");
			Files.writeString(items.resolve(id + ".json"), "{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:custom_model_data\",\"index\":0,"
					+ "\"on_true\":{\"type\":\"minecraft:model\",\"model\":\"strafemc:item/mag/" + e.cls() + "_empty\"},"
					+ "\"on_false\":{\"type\":\"minecraft:model\",\"model\":\"strafemc:item/mag/" + e.cls() + "\"}}}");
		}
		// the mag pouch's empty magazine slot outline, in the style of the armor slots': the AK-47's magazine
		try {
			Path ak = tex.resolve("item/mag/weapon_ak47" + MagIcons.VERSION + ".png");
			if (!Files.exists(ak)) ak = tex.resolve("item/mag_ak47.png");
			if (Files.exists(ak)) write(slotIcon(ImageIO.read(ak.toFile())), tex.resolve("gui/sprites/container/slot/mag.png"));
		} catch (Exception ex) {
			StrafeMc.LOG.warn("[strafemc] mag pouch slot icons: {}", ex.toString());
		}
	}

	/** An empty-slot outline like vanilla's: the picture's shape fitted into 16 px, its edge in flat #555555. */
	static java.awt.image.BufferedImage slotIcon(java.awt.image.BufferedImage src) {
		int x0 = src.getWidth(), y0 = src.getHeight(), x1 = -1, y1 = -1;
		for (int y = 0; y < src.getHeight(); y++)
			for (int x = 0; x < src.getWidth(); x++)
				if ((src.getRGB(x, y) >>> 24) >= 128) {
					x0 = Math.min(x0, x);
					y0 = Math.min(y0, y);
					x1 = Math.max(x1, x);
					y1 = Math.max(y1, y);
				}
		java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		if (x1 < 0) return out;
		int w = x1 - x0 + 1, h = y1 - y0 + 1, box = src.getWidth() <= 16 ? 16 : 14; // pixel art stays 1:1
		double sc = Math.min(1.0 * box / w, 1.0 * box / h);
		int ow = Math.max(1, (int) Math.round(w * sc)), oh = Math.max(1, (int) Math.round(h * sc)), ox = (16 - ow) / 2, oy = (16 - oh) / 2;
		for (int y = 0; y < oh; y++)
			for (int x = 0; x < ow; x++) { // the share of covered source pixels under each output pixel
				int sx0 = x0 + (int) (x / sc), sx1 = Math.max(sx0 + 1, x0 + (int) ((x + 1) / sc));
				int sy0 = y0 + (int) (y / sc), sy1 = Math.max(sy0 + 1, y0 + (int) ((y + 1) / sc));
				int on = 0, all = 0;
				for (int sy = sy0; sy < Math.min(sy1, y1 + 1); sy++)
					for (int sx = sx0; sx < Math.min(sx1, x1 + 1); sx++) {
						all++;
						if ((src.getRGB(sx, sy) >>> 24) >= 128) on++;
					}
				if (all > 0 && on * 2 >= all) out.setRGB(ox + x, oy + y, 0xFF555555);
			}
		// only the shape's edge: vanilla's slot icons are line drawings
		java.awt.image.BufferedImage line = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 16; y++)
			for (int x = 0; x < 16; x++)
				if (filled(out, x, y) && !(filled(out, x - 1, y) && filled(out, x + 1, y) && filled(out, x, y - 1) && filled(out, x, y + 1)))
					line.setRGB(x, y, 0xFF555555);
		return line;
	}

	private static boolean filled(java.awt.image.BufferedImage im, int x, int y) {
		return x >= 0 && y >= 0 && x < im.getWidth() && y < im.getHeight() && (im.getRGB(x, y) >>> 24) > 0;
	}

	private static void cases(Path tex) throws Exception {
		Path models = DIR.resolve("assets/strafemc/models/item"), items = DIR.resolve("assets/strafemc/items");
		Files.createDirectories(models.resolve("case"));
		Files.createDirectories(items);
		java.util.List<String> missing = new java.util.ArrayList<>();
		for (var c : dev.strafemc.weapon.Cases.all()) {
			String vpk = "panorama/images/" + c.image() + "_png.vtex_c";
			if (!Files.exists(StrafeConfig.cacheDir().resolve("panorama/images/" + c.image() + "_png.png"))) missing.add(vpk);
		}
		if (!missing.isEmpty()) AssetCache.decompileAll(missing);
		StringBuilder cases = new StringBuilder();
		for (var c : dev.strafemc.weapon.Cases.all()) {
			String img = "panorama/images/" + c.image() + "_png";
			Path out = tex.resolve("item/case/" + c.id() + ".png");
			try {
				icon(img + ".vtex_c", img + ".png", out, 128);
			} catch (Exception e) {
				continue;
			}
			Files.writeString(models.resolve("case/" + c.id() + ".json"), "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"strafemc:item/case/" + c.id() + "\"}}");
			if (cases.length() > 0) cases.append(',');
			cases.append("{\"when\":\"").append(c.id()).append("\",\"model\":{\"type\":\"minecraft:model\",\"model\":\"strafemc:item/case/").append(c.id()).append("\"}}");
		}
		Files.writeString(items.resolve("weapon_case.json"), "{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:custom_model_data\","
				+ "\"index\":0,\"cases\":[" + cases + "],\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/chest\"}}}");
		// each case's own key: CS2's key picture (econ/tools/...), picked by the key's custom model data string
		Files.createDirectories(models.resolve("key"));
		java.util.List<String> keyMissing = new java.util.ArrayList<>();
		for (var k : dev.strafemc.weapon.Cases.keys())
			if (!k.image().isEmpty() && !Files.exists(StrafeConfig.cacheDir().resolve("panorama/images/" + k.image() + "_png.png")))
				keyMissing.add("panorama/images/" + k.image() + "_png.vtex_c");
		if (!keyMissing.isEmpty()) AssetCache.decompileAll(keyMissing);
		StringBuilder keys = new StringBuilder();
		for (var k : dev.strafemc.weapon.Cases.keys()) {
			if (k.image().isEmpty()) continue;
			String img = "panorama/images/" + k.image() + "_png";
			try {
				icon(img + ".vtex_c", img + ".png", tex.resolve("item/key/" + k.id() + ".png"), 128);
			} catch (Exception e) {
				continue;
			}
			Files.writeString(models.resolve("key/" + k.id() + ".json"), "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"strafemc:item/key/" + k.id() + "\"}}");
			if (keys.length() > 0) keys.append(',');
			keys.append("{\"when\":\"").append(k.id()).append("\",\"model\":{\"type\":\"minecraft:model\",\"model\":\"strafemc:item/key/").append(k.id()).append("\"}}");
		}
		Files.writeString(items.resolve("case_key.json"), "{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:custom_model_data\","
				+ "\"index\":0,\"cases\":[" + keys + "],\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/trial_key\"}}}");
	}

	/** A CS2 texture as it is (no resizing). */
	private static void raw(String vpk, String exported, Path out) throws Exception {
		if (Files.exists(out)) return;
		Path src = StrafeConfig.cacheDir().resolve(exported);
		AssetCache.ensureDecompiled(vpk, src);
		Files.createDirectories(out.getParent());
		Files.copy(src, out);
	}

	/** Whether the runtime pack has this texture (a path under assets/strafemc/textures). */
	public static boolean hasTexture(String rel) {
		return Files.exists(DIR.resolve("assets/strafemc/textures").resolve(rel));
	}

	private static void write(BufferedImage img, Path out) throws Exception {
		Files.createDirectories(out.getParent());
		ImageIO.write(img, "png", out.toFile());
	}
}
