package dev.strafemc.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * CS2's round-end win panel (hudwinpanel), drawn while a round mode's round is over (and at a match's end): at y 190
 * (1080p) the 400 x 80 result box opens sideways over 0.25 s, its edges 4 px in the winners' colour (CT, T, or red
 * when your side lost, on a dark red), the title in big capitals with CS2's double arrows either side, and how the
 * round ended under it; 16 px below, the 640 x 90 MVP card opens 0.25 s later on the winners' colour, fading at both
 * ends: the MVP's face (72 px), CS2's reason ("MVP for planting the bomb", "MVP for most kills (3k)", ...) on a
 * coloured tab and their name, each part flashing white as it appears.
 * FLAGGED ours: the end line's place (CS2 puts a fun fact there), the lost colour (#e94b4b), bots' face (a grey tile).
 */
final class WinPanel {
	private static final int CT = 0xFFB5D4EE, T = 0xFFEAD18A, LOST = 0xFFE94B4B;
	/** winPanelBgColorCT / T rgba(9,40,61,.85) / rgba(66,46,8,.85); lost rgba(32,2,2,.877). */
	private static final int BG_CT = 0xD909283D, BG_T = 0xD9422E08, BG_LOST = 0xE0200202;
	private static long shownAt = -1;

	private WinPanel() {}

	static void hide() {
		shownAt = -1;
	}

	static void draw(GuiGraphicsExtractor g, JsonObject s, boolean matchOver, double px) {
		var mc = Minecraft.getInstance();
		var font = mc.font;
		long now = System.currentTimeMillis();
		if (shownAt < 0) shownAt = now;
		long t = now - shownAt;
		boolean teams = s.has("scoreT"), winT = s.has("winT") && s.get("winT").getAsBoolean();
		boolean ffa = s.has("ffa") && s.get("ffa").getAsBoolean();
		boolean meT = s.has("t") && s.get("t").getAsBoolean();
		boolean lost = teams && !ffa && !matchOver && mc.player != null && !mc.player.isSpectator() && meT != winT;
		String title = matchOver ? s.get("winner").getAsString() + " wins the match" : s.has("roundWinner") ? s.get("roundWinner").getAsString() : "";
		if (title.isEmpty()) return;
		int side = !teams || ffa ? 0xFFFFFFFF : winT ? T : CT;
		int colour = lost ? LOST : side, bg = lost ? BG_LOST : !teams || ffa ? 0xC0202020 : winT ? BG_T : BG_CT;
		int cx = g.guiWidth() / 2, y = (int) Math.round(190 * px);
		// the result box, opening sideways over 0.25 s (ease-in), then a white flash fading over 0.2 s
		float open = ease(Math.min(1, t / 250f));
		int w = (int) Math.round(400 * px * open), h = (int) Math.round(80 * px), b = Math.max(1, (int) Math.round(4 * px));
		if (w > 2 * b) {
			g.fill(cx - w / 2, y, cx + w / 2, y + h, bg);
			g.fill(cx - w / 2, y, cx - w / 2 + b, y + h, colour);
			g.fill(cx + w / 2 - b, y, cx + w / 2, y + h, colour);
			if (open >= 1) {
				String up = title.toUpperCase(java.util.Locale.ROOT);
				float ak = (float) (16 * px / 7.0);
				// the title fits between the arrows (each 22 px in from the box's side) with a 10 px gap each side
				double room = 2 * (400 * px / 2 - 22 * px - font.width("§l»") * ak / 2 - 10 * px);
				float k = (float) Math.min(44 * px / 9.0, room / Math.max(1, font.width("§l" + up)));
				drawText(g, "§l" + up, cx, y + (int) Math.round(30 * px), k, colour, true);
				drawText(g, "§l»", cx + (int) Math.round(400 * px / 2 - 22 * px), y + (int) Math.round(30 * px), ak, colour, true);
				drawText(g, "§l«", cx - (int) Math.round(400 * px / 2 - 22 * px), y + (int) Math.round(30 * px), ak, colour, true);
				String end = matchOver ? "" : s.has("endReason") ? s.get("endReason").getAsString() : "";
				if (!end.isEmpty()) drawText(g, end, cx, y + (int) Math.round(62 * px), (float) (17 * px / 9.0), 0xD0FFFFFF, true);
			}
			float flash = 1 - Math.max(0, Math.min(1, (t - 250) / 200f));
			if (flash > 0) g.fill(cx - w / 2, y, cx + w / 2, y + h, ((int) (255 * flash) << 24) | 0xFFFFFF);
		}
		// the MVP card: 16 px under, opening 0.25 s after the result
		if (matchOver || !s.has("mvp")) return;
		long mt = t - 250;
		if (mt <= 0) return;
		float mo = ease(Math.min(1, mt / 250f));
		int my = y + h + (int) Math.round(16 * px), mw = (int) Math.round(640 * px * mo), mh = (int) Math.round(90 * px);
		int cardBg = !teams || ffa ? 0xC0202020 : winT ? BG_T : BG_CT;
		fadedBar(g, cx - mw / 2, my, mw, mh, cardBg, (int) Math.round(120 * px * mo));
		if (mo >= 1) {
			String reason = s.get("mvpReason").getAsString(), name = s.get("mvp").getAsString();
			float rk = (float) (18 * px / 9.0), nk = (float) (28 * px / 9.0);
			int av = (int) Math.round(72 * px), gap = (int) Math.round(8 * px);
			int reasonW = (int) (font.width(reason) * rk) + (int) Math.round(12 * px), nameW = (int) (font.width(name) * nk);
			int contentW = av + gap + Math.max(reasonW, Math.min(nameW, (int) Math.round(250 * px)));
			int x = cx - contentW / 2, ay = my + (mh - av) / 2;
			g.fillGradient(x, ay, x + av, ay + av, 0xFF6D6D6D, 0xFF464646);
			var e = mc.level == null ? null : mc.level.getEntity(s.get("mvpId").getAsInt());
			if (e instanceof net.minecraft.client.player.AbstractClientPlayer cp)
				net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(g, cp.getSkin(), x, ay, av);
			int dx = x + av + gap;
			int rh = (int) Math.round(21 * px), ry = ay + (int) Math.round(6 * px);
			g.fill(dx, ry, dx + reasonW, ry + rh, side);
			drawText(g, reason, dx + reasonW / 2, ry + rh / 2, rk, 0xFF000000, false);
			drawText(g, "§l" + name, dx + (int) (font.width(name) * nk / 2), ay + av - (int) Math.round(18 * px), nk, side, true);
		}
		float flash = 1 - Math.max(0, Math.min(1, (mt - 250) / 200f));
		if (flash > 0 && mw > 0) g.fill(cx - mw / 2, my, cx + mw / 2, my + mh, ((int) (200 * flash) << 24) | 0xFFFFFF);
	}

	private static float ease(float f) {
		return f * f;
	}

	/** A bar whose last fade px at each end thin out to nothing (mvp_banner_fade-both-right-left). */
	private static void fadedBar(GuiGraphicsExtractor g, int x, int y, int w, int h, int argb, int fade) {
		if (w <= 0) return;
		fade = Math.min(fade, w / 2);
		int a = argb >>> 24, rgb = argb & 0xFFFFFF;
		g.fill(x + fade, y, x + w - fade, y + h, argb);
		for (int i = 0; i < fade; i++) {
			int al = Math.round(a * (i + 0.5f) / fade) << 24 | rgb;
			g.fill(x + i, y, x + i + 1, y + h, al);
			g.fill(x + w - 1 - i, y, x + w - i, y + h, al);
		}
	}

	/** Text scaled by k, centred on (x, y). */
	private static void drawText(GuiGraphicsExtractor g, String s, int x, int y, float k, int color, boolean shadow) {
		var font = Minecraft.getInstance().font;
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(k, k);
		g.text(font, s, -font.width(s) / 2, -4, color, shadow);
		pose.popMatrix();
	}
}
