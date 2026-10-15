package dev.strafemc.client;

import dev.strafemc.client.assets.RuntimePack;
import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.KillFeed;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CS2's killfeed (huddeathnotice): top right, newest at the bottom. From CS2's panorama style sheet: entries last
 * 5 s (x1.5 when you're in them) and fade out over 1 s; CT names #6f9ce6, T names #eabe54; your kills on near-black
 * with a 2 px #e10000 border, your death on #630606d2; 16 px names, 24 px icons, rows 3 px apart, 72 px from the
 * top; icons in CS2's order (blind, attacker, in-air, weapon, through smoke, wallbang, headshot, victim). Sizes are
 * CS2's 1080p pixels, scaled with the window height. FLAGGED: the plain row background (CS2 uses a blur) and
 * Minecraft's font for CS2's Stratum.
 */
public final class DeathNotices {
	public static final String[] WEAPON_ICONS = java.util.stream.Stream.concat(
			java.util.stream.Stream.of("knife", "hegrenade", "inferno", "decoy", "flashbang", "smokegrenade"),
			java.util.stream.Stream.concat(dev.strafemc.weapon.WeaponTable.guns().stream(), dev.strafemc.weapon.WeaponTable.knives().stream())
					.map(dev.strafemc.weapon.WeaponTable.Entry::shortName)).distinct().toArray(String[]::new);
	public static final String[] MODIFIER_ICONS = {"icon_headshot", "penetrate", "smoke_kill", "blind_kill", "inairkill", "noscope", "icon_suicide", "revenge",
			"domination"};
	private static final double LIFETIME = 5, LOCAL_MOD = 1.5, FADE = 1;
	private static final int CT = 0xFF6F9CE6, T = 0xFFEABE54, MOB = 0xFFFFFFFF;

	private record Notice(StrafePayloads.KillNotice msg, long time) {}

	private static final List<Notice> NOTICES = new ArrayList<>();
	private static final Map<String, int[]> SIZES = new ConcurrentHashMap<>();

	private DeathNotices() {}

	public static void receive(StrafePayloads.KillNotice msg) {
		synchronized (NOTICES) {
			NOTICES.add(new Notice(msg, System.nanoTime()));
			while (NOTICES.size() > 8) NOTICES.remove(0);
		}
	}

	public static void clear() {
		synchronized (NOTICES) {
			NOTICES.clear();
		}
	}

	public static void render(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.hideGui || mc.player == null) return;
		List<Notice> list;
		synchronized (NOTICES) {
			if (NOTICES.isEmpty()) return;
			list = new ArrayList<>(NOTICES);
		}
		long now = System.nanoTime();
		int me = mc.player.getId();
		double px = mc.getWindow().getHeight() / 1080.0 / mc.getWindow().getGuiScale(); // one CS2 1080p pixel in GUI units
		int icon = Math.max(6, (int) Math.round(24 * px)), padX = (int) Math.round(10 * px), padTop = (int) Math.round(6 * px),
				padBottom = (int) Math.round(3 * px), gap = Math.max(1, (int) Math.round(3 * px)), border = Math.max(1, (int) Math.round(2 * px));
		int rowH = icon + padTop + padBottom, y = (int) Math.round(72 * px), right = g.guiWidth() - (int) Math.round(10 * px);
		var font = mc.font;
		List<Notice> expired = new ArrayList<>();
		for (Notice n : list) {
			var m = n.msg;
			boolean mine = m.attackerId() == me || m.victimId() == me;
			double life = LIFETIME * (mine ? LOCAL_MOD : 1), t = (now - n.time) / 1e9;
			if (t > life + FADE) {
				expired.add(n);
				continue;
			}
			float alpha = (float) (t <= life ? 1 : 1 - (t - life) / FADE);
			// the row, built right to left: victim, modifiers, weapon, in-air, attacker, blind
			List<Object> parts = new ArrayList<>(); // String (name, colour in the next Integer) or icon name
			if ((m.flags() & KillFeed.REVENGE) != 0) parts.add("@revenge");
			if ((m.flags() & KillFeed.DOMINATION) != 0) parts.add("@domination");
			if ((m.flags() & KillFeed.ATTACKER_BLIND) != 0) parts.add("@blind_kill");
			parts.add(m.attacker());
			parts.add(m.attackerT() ? T : CT);
			if (!m.assister().isEmpty()) { // CS2: "+", the flashbang for a flash assist, the assister
				parts.add("+");
				parts.add(0xFFFFFFFF);
				if ((m.flags() & KillFeed.FLASH_ASSIST) != 0) parts.add("@flashbang_assist");
				parts.add(m.assister());
				parts.add(m.assisterT() ? T : CT);
			}
			if ((m.flags() & KillFeed.IN_AIR) != 0) parts.add("@inairkill");
			parts.add("@" + m.icon());
			if ((m.flags() & KillFeed.NOSCOPE) != 0) parts.add("@noscope");
			if ((m.flags() & KillFeed.THROUGH_SMOKE) != 0) parts.add("@smoke_kill");
			if ((m.flags() & KillFeed.PENETRATE) != 0) parts.add("@penetrate");
			if ((m.flags() & KillFeed.HEADSHOT) != 0) parts.add("@icon_headshot");
			if ((m.flags() & KillFeed.SUICIDE) != 0) parts.add("@icon_suicide");
			parts.add(m.victim());
			parts.add(!m.victimPlayer() ? MOB : m.victimT() ? T : CT);
			int width = padX * 2;
			for (int i = 0; i < parts.size(); i++) {
				Object o = parts.get(i);
				if (o instanceof String s && s.startsWith("@")) width += iconWidth(s.substring(1), icon) + 2;
				else if (o instanceof String s) {
					width += font.width(s) + 2;
					i++; // its colour
				}
			}
			int x = right - width;
			int bg = m.attackerId() == me ? 0xE7000000 : m.victimId() == me ? 0xD2630606 : 0x8C000000;
			g.fill(x, y, right, y + rowH, fade(bg, alpha));
			if (m.attackerId() == me) { // your kill: CS2's red border
				int red = fade(0xFFE10000, alpha);
				g.fill(x, y, right, y + border, red);
				g.fill(x, y + rowH - border, right, y + rowH, red);
				g.fill(x, y, x + border, y + rowH, red);
				g.fill(right - border, y, right, y + rowH, red);
			}
			int cx = x + padX, mid = y + padTop + icon / 2;
			for (int i = 0; i < parts.size(); i++) {
				Object o = parts.get(i);
				if (o instanceof String s && s.startsWith("@")) {
					String name = s.substring(1);
					int w = iconWidth(name, icon);
					int[] sz = SIZES.get(name);
					if (sz != null && sz[0] > 0)
						g.blit(RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath("strafemc", "textures/gui/deathnotice/" + name + ".png"), cx,
								mid - icon / 2, 0, 0, w, icon, sz[0], sz[1], sz[0], sz[1], fade(0xFFFFFFFF, alpha));
					cx += w + 2;
				} else if (o instanceof String s) {
					int col = (Integer) parts.get(++i);
					g.text(font, s, cx + 1, mid - font.lineHeight / 2 + 1, fade(col, alpha), true);
					cx += font.width(s) + 2;
				}
			}
			y += rowH + gap;
		}
		if (!expired.isEmpty()) synchronized (NOTICES) {
			NOTICES.removeAll(expired);
		}
	}

	private static int fade(int argb, float alpha) {
		return Math.round((argb >>> 24) * alpha) << 24 | (argb & 0xFFFFFF);
	}

	/** An icon's width at the given height (its PNG's aspect, read once from the runtime pack). */
	private static int iconWidth(String name, int height) {
		int[] sz = SIZES.computeIfAbsent(name, n -> {
			Path png = RuntimePack.DIR.resolve("assets/strafemc/textures/gui/deathnotice/" + n + ".png");
			if (!Files.exists(png)) return new int[] {0, 0};
			try (ImageInputStream in = ImageIO.createImageInputStream(png.toFile())) {
				var readers = ImageIO.getImageReaders(in);
				if (!readers.hasNext()) return new int[] {0, 0};
				var r = readers.next();
				r.setInput(in);
				return new int[] {r.getWidth(0), r.getHeight(0)};
			} catch (Exception e) {
				return new int[] {0, 0};
			}
		});
		return sz[0] <= 0 ? height : Math.max(1, Math.round((float) sz[0] / sz[1] * height));
	}
}
