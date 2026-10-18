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

	/** A sticker's picture (econ/stickers/<material>, 4:3 like the guns'); graffiti have none here (draw() uses their decal). */
	private static String base(Cases.Entry e) {
		if (e.sticker()) {
			var st = dev.strafemc.weapon.Stickers.get(e.kit());
			return st == null ? null : "panorama/images/econ/stickers/" + st.material() + "_png";
		}
		if (e.spray()) return null;
		if (e.charm()) {
			var c = dev.strafemc.weapon.Charms.get(e.kit());
			return c == null ? null : "panorama/images/" + c.image() + "_png";
		}
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

	/** CS2's inventory picture for an item (a case or capsule, key, gun / knife wrap, gloves, sticker; all 4:3), or null. */
	public static Identifier picture(net.minecraft.world.item.ItemStack s) {
		if (s.getItem() instanceof dev.strafemc.weapon.CaseItem) {
			Cases.Case c = Cases.get(dev.strafemc.weapon.CaseItem.caseId(s));
			return c == null ? null : image("panorama/images/" + c.image() + "_png");
		}
		if (s.getItem() instanceof dev.strafemc.weapon.KeyItem) {
			Cases.Key k = Cases.key(dev.strafemc.weapon.KeyItem.keyId(s));
			return k == null || k.image().isEmpty() ? null : image("panorama/images/" + k.image() + "_png");
		}
		if (s.getItem() instanceof dev.strafemc.weapon.GloveItem g) return get(new Cases.Entry(g.kit(s), g.kind.id));
		if (s.getItem() instanceof dev.strafemc.weapon.StickerItem) {
			String id = dev.strafemc.weapon.StickerItem.sticker(s);
			return id == null ? null : get(new Cases.Entry(id, "sticker"));
		}
		if (s.getItem() instanceof dev.strafemc.weapon.CharmItem) {
			String id = dev.strafemc.weapon.CharmItem.charm(s);
			return id == null ? null : get(new Cases.Entry(id, "charm"));
		}
		if (s.getItem() instanceof dev.strafemc.weapon.SkinWrapItem) {
			var w = dev.strafemc.weapon.SkinWraps.get(dev.strafemc.weapon.SkinWrapItem.skin(s));
			if (w == null || w.kits().isEmpty()) return null;
			var e = w.kits().entrySet().iterator().next();
			return get(new Cases.Entry(e.getValue(), e.getKey()));
		}
		return null;
	}

	/**
	 * Draws an item's picture as large as fits the box (x, y, w, h) without stretching it, centred: CS2's 4:3 pictures,
	 * graffiti square in their own tint; anything else (or a picture still loading) as its item icon, scaled up.
	 */
	public static void draw(net.minecraft.client.gui.GuiGraphicsExtractor g, net.minecraft.world.item.ItemStack s, int x, int y, int w, int h) {
		if (s.isEmpty() || w <= 0 || h <= 0) return;
		var pipe = net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED;
		if (s.getItem() instanceof dev.strafemc.weapon.SprayItem) {
			var sp = dev.strafemc.weapon.Sprays.get(dev.strafemc.weapon.SprayItem.spray(s));
			Identifier tex = sp == null ? null : dev.strafemc.client.impact.SprayDecals.texture(sp.material());
			if (tex != null) {
				var t = sp.tintable() ? dev.strafemc.weapon.Sprays.tint(dev.strafemc.weapon.SprayItem.tint(s)) : null;
				int side = Math.min(w, h);
				g.blit(pipe, tex, x + (w - side) / 2, y + (h - side) / 2, 0, 0, side, side, side, side, side, side, t != null ? 0xFF000000 | t.color() : -1);
				return;
			}
		}
		Identifier pic = picture(s);
		if (pic != null) {
			int pw = Math.min(w, h * 4 / 3), ph = pw * 3 / 4;
			g.blit(pipe, pic, x + (w - pw) / 2, y + (h - ph) / 2, 0, 0, pw, ph, pw, ph);
			return;
		}
		float sc = Math.max(1, Math.min(4, Math.min(w, h) / 16f));
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x + w / 2f - 8 * sc, y + h / 2f - 8 * sc);
		pose.scale(sc, sc);
		g.item(s, 0, 0);
		pose.popMatrix();
	}

	/** The rarity colour under an item's tile (wraps, stickers, graffiti), else the fallback. */
	public static int rarityColor(net.minecraft.world.item.ItemStack s, int fallback) {
		dev.strafemc.weapon.SkinWraps.Rarity r = null;
		if (s.getItem() instanceof dev.strafemc.weapon.SkinWrapItem) {
			var w = dev.strafemc.weapon.SkinWraps.get(dev.strafemc.weapon.SkinWrapItem.skin(s));
			r = w == null ? null : w.rarity();
		} else if (s.getItem() instanceof dev.strafemc.weapon.StickerItem) {
			var st = dev.strafemc.weapon.Stickers.get(dev.strafemc.weapon.StickerItem.sticker(s));
			r = st == null ? null : st.rarity();
		} else if (s.getItem() instanceof dev.strafemc.weapon.SprayItem) {
			var sp = dev.strafemc.weapon.Sprays.get(dev.strafemc.weapon.SprayItem.spray(s));
			r = sp == null ? null : sp.rarity();
		} else if (s.getItem() instanceof dev.strafemc.weapon.CharmItem) {
			var c = dev.strafemc.weapon.Charms.get(dev.strafemc.weapon.CharmItem.charm(s));
			r = c == null ? null : c.rarity();
		}
		return r == null ? fallback : 0xFF000000 | r.color;
	}

	private static Path png(String base) {
		return StrafeConfig.cacheDir().resolve(base + ".png");
	}
}
