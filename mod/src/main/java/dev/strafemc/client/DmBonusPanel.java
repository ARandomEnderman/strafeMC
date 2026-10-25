package dev.strafemc.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * CS2's Deathmatch bonus panel (panorama huddmbonus), top left under the radar (HudTopLeft: radar, then this): a 400 x
 * 100 px black gradient that slides in from the left over 0.5 s (UI.DeathMatchBonusAlertStart; ...End as it goes); a
 * 50 px pie with a 6 px ring running out over the bonus's 30 s (green, yellow from 30-55 %, red from 60-85 %) and the
 * seconds in it, "+N POINTS" (what a kill with it gives) under it; "BONUS WEAPON:" (#c2c2c2) and the gun's name
 * (white, 20 px) with its 64 px icon; on your own bonus kill a "+N" (32 px) pops over 2 s with UI.DeathMatchBonusKill.
 */
public final class DmBonusPanel {
	private static String shown = "";
	private static long changedAt;
	private static boolean active;
	private static int seenKills = -1;
	private static long killAt = -1;
	private static int killPoints;

	private DmBonusPanel() {}

	public static void register() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("strafemc", "dm_bonus"), (g, delta) -> draw(g));
	}

	private static void draw(net.minecraft.client.gui.GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		var s = ModeHud.state();
		var p = mc.player;
		if (p == null || s == null) return;
		String bonus = s.has("bonus") ? s.get("bonus").getAsString() : "";
		long now = System.currentTimeMillis();
		boolean on = !bonus.isEmpty();
		if (on != active) { // in or out, with CS2's sounds
			active = on;
			changedAt = now;
			dev.strafemc.client.sound.CsSounds.playOn(on ? "UI.DeathMatchBonusAlertStart" : "UI.DeathMatchBonusAlertEnd", p, 1.0, false);
		}
		if (on) shown = bonus;
		int kills = s.has("bonusKills") ? s.get("bonusKills").getAsInt() : 0;
		if (seenKills >= 0 && kills > seenKills) {
			killAt = now;
			killPoints = s.has("bonusKillPoints") ? s.get("bonusKillPoints").getAsInt() : 0;
			dev.strafemc.client.sound.CsSounds.playOn("UI.DeathMatchBonusKill", p, 1.0, false);
		}
		seenKills = kills;
		float slide = Math.min(1, (now - changedAt) / 500f);
		slide = slide < 0.5f ? 2 * slide * slide : 1 - (float) Math.pow(-2 * slide + 2, 2) / 2; // ease-in-out
		float in = active ? slide : 1 - slide;
		if (in <= 0 || shown.isEmpty() || mc.options.hideGui) return;
		double u = g.guiHeight() / 1080.0;
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate((float) (-400 * u * (1 - in)), (float) (340 * u));
		int alpha = (int) (255 * in);
		// the background: black from the left fading out (ED -> 20 at 80% -> 00), and the 2 px lines top and bottom
		int w = (int) Math.round(400 * u), h = (int) Math.round(100 * u), steps = 40;
		for (int i = 0; i < steps; i++) {
			double f = (i + 0.5) / steps;
			int a = (int) (f < 0.8 ? 0xED + (0x20 - 0xED) * f / 0.8 : 0x20 * (1 - (f - 0.8) / 0.2));
			int line = (int) (0xBB * (1 - f));
			int x0 = w * i / steps, x1 = w * (i + 1) / steps;
			g.fill(x0, 0, x1, h, (a * alpha / 255) << 24);
			int lh = Math.max(1, (int) Math.round(2 * u));
			g.fill(x0, 0, x1, lh, (line * alpha / 255) << 24);
			g.fill(x0, h - lh, x1, h, (line * alpha / 255) << 24);
		}
		double len = s.has("bonusLength") ? s.get("bonusLength").getAsDouble() : 30, left = s.has("bonusLeft") ? s.get("bonusLeft").getAsDouble() : 0;
		double f = Math.max(0, Math.min(1, left / len)); // what's left
		int pieX = (int) Math.round(10 * u + 32 * u), pieY = (int) Math.round(10 * u + 25 * u), r = (int) Math.round(25 * u), th = Math.max(1, (int) Math.round(6 * u));
		ring(g, pieX, pieY, r, th, 0, 360, (0x05 * alpha / 255) << 24 | 0xFFFFFF);
		ring(g, pieX, pieY, r, th, 180 * (1 - f), 360 * f, a(timerColour(1 - f), alpha));
		var font = mc.font;
		text(g, font, String.valueOf((int) Math.ceil(left)), pieX, pieY - 12 * u * 0.5, 24 * u, a(0xFFFFFFFF, alpha), 0);
		if (s.has("bonusPoints"))
			text(g, font, "+" + s.get("bonusPoints").getAsInt() + " POINTS", pieX, pieY + r + 8 * u, 14 * u, a(0xFFFFFFFF, alpha), 0);
		// the title, the gun's name and its icon
		double ix = 10 * u + 64 * u + 14 * u, iy = 10 * u;
		String title = CsText.get("Panorama_DMBonusPanel_Title", "Bonus Weapon:").toUpperCase(java.util.Locale.ROOT);
		double tw = text(g, font, title, ix, iy, 20 * u, a(0xFFC2C2C2, alpha), -1);
		text(g, font, dev.strafemc.weapon.WeaponTable.name(shown), ix + tw + 4 * u, iy, 20 * u, a(0xFFFFFFFF, alpha), -1);
		String rel = "gui/deathnotice/" + shown.replace("weapon_", "") + ".png";
		if (dev.strafemc.client.assets.RuntimePack.hasTexture(rel)) {
			int[] sz = BuyMenuScreen.iconSize(rel);
			if (sz[1] > 0) {
				int ih = (int) Math.round(64 * u * 0.8), iw = (int) Math.round((double) sz[0] / sz[1] * ih);
				g.blit(RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath("strafemc", "textures/" + rel), (int) Math.round(ix), (int) Math.round(iy + 26 * u), 0, 0,
						iw, ih, iw, ih, iw, ih, a(0xFFFFFFFF, alpha));
			}
		}
		// your bonus kill: "+N" grows in, slides right, holds, slides back and fades (bonuspanel-kill-animation, 2 s)
		if (killAt >= 0 && now - killAt < 2000) {
			float t = (now - killAt) / 2000f, op, dx, sc;
			if (t < 0.2f) { op = t / 0.2f; dx = 100 * op; sc = 1 + op; }
			else if (t < 0.3f) { op = 1; dx = 100; sc = 2 - (t - 0.2f) / 0.1f; }
			else if (t < 0.8f) { op = 1; dx = 100; sc = 1; }
			else { op = 1 - (t - 0.8f) / 0.2f; dx = 100 * op; sc = 1; }
			text(g, font, "+" + killPoints, 292 * u + dx * u + 42 * u, 40 * u + 42 * u - 16 * u * sc, 32 * u * sc, a(0xFFFFFFFF, (int) (alpha * op)), 0);
		}
		pose.popMatrix();
	}

	/** CS2's timer colours by how much of the bonus has passed: green to 30 %, to yellow by 55 %, yellow to 60 %, to red by 85 %. */
	private static int timerColour(double passed) {
		int green = 0xFF00FF00, yellow = 0xFFFFFF00, red = 0xFFFF0000;
		if (passed <= 0.3) return green;
		if (passed <= 0.55) return mix(green, yellow, (passed - 0.3) / 0.25);
		if (passed <= 0.6) return yellow;
		if (passed <= 0.85) return mix(yellow, red, (passed - 0.6) / 0.25);
		return red;
	}

	private static int mix(int a, int b, double t) {
		int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
		int gr = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
		int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
		return 0xFF000000 | r << 16 | gr << 8 | bl;
	}

	/** A ring of thickness th from start, sweep degrees long (0 = up, clockwise), as small squares along it. */
	private static void ring(net.minecraft.client.gui.GuiGraphicsExtractor g, int cx, int cy, int r, int th, double start, double sweep, int colour) {
		if (sweep <= 0) return;
		int n = Math.max(8, (int) (sweep * r * 0.05));
		double rm = r - th / 2.0;
		for (int i = 0; i <= n; i++) {
			double ang = Math.toRadians(start + sweep * i / n);
			int x = (int) Math.round(cx + Math.sin(ang) * rm), y = (int) Math.round(cy - Math.cos(ang) * rm);
			g.fill(x - th / 2, y - th / 2, x - th / 2 + th, y - th / 2 + th, colour);
		}
	}

	/** Text at size px: align -1 left from x, 0 centred, 1 right; returns its width. */
	private static double text(net.minecraft.client.gui.GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, String s, double x, double y, double size, int colour, int align) {
		var pose = g.pose();
		float k = (float) (size / 9.0);
		double w = font.width(s) * k;
		pose.pushMatrix();
		pose.translate((float) (align < 0 ? x : align == 0 ? x - w / 2 : x - w), (float) y);
		pose.scale(k, k);
		g.text(font, s, 0, 0, colour, true);
		pose.popMatrix();
		return w;
	}

	private static int a(int argb, int alpha) {
		return (((argb >>> 24) * alpha / 255) << 24) | (argb & 0xFFFFFF);
	}
}
