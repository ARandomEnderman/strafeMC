package dev.strafemc.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.strafemc.client.viewmodel.EomScene;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * CS2's end of match screen (endofmatch, endofmatch-win, endofmatch-characters) over the end of match scene in the
 * world (EomScene: the line-up and CS2's camera path, the HUD off): the result bar on top, 1340 px wide (1080p) fading
 * at both ends, a 40 px score row ("your side - theirs") and the 90 px result row with VICTORY / DEFEAT / TIE (64 px,
 * letter-spaced) in CS2's positiveColor #0F9D58 (won) or negativeColor #DB4437 on grey (lost), white lines and the
 * double arrows after a second, opening over 0.25 s; and each player's card at CS2's stats card places
 * (x = (i + 1) / (n + 1) of 1440 px across the middle). It opens when the match ends (after the last round's win panel)
 * and goes when the next match starts or Esc closes it.
 */
public final class EomScreen extends Screen {
	private static final int POSITIVE = 0xFF0F9D58, NEGATIVE = 0xFFDB4437, GREY = 0xFF808080;
	/** The match this screen was shown (or closed) for: it opens once per match end. */
	private static String shownFor = "";
	private final long openedAt = System.nanoTime();

	private EomScreen() {
		super(Component.literal("End of match"));
	}

	/** From the mode's state: starts the scene when a match ends, ends it when the next one starts. */
	static void update(JsonObject s) {
		var mc = Minecraft.getInstance();
		boolean over = s.has("phase") && s.get("phase").getAsString().equals("over") && s.has("eom") && s.has("eomSpot");
		if (s.has("mode") && s.has("ffa") && !s.get("ffa").getAsBoolean()) { // a team mode: its line-up's clips load ahead
			java.util.List<String> models = new java.util.ArrayList<>();
			for (var m : EomScene.members(s)) models.add(m.model());
			dev.strafemc.client.viewmodel.EomLineup.warm(models);
		}
		if (!over) {
			shownFor = "";
			EomScene.stop();
			if (mc.screen instanceof EomScreen) mc.setScreen(null);
			return;
		}
		String key = s.get("mode").getAsString() + "|" + s.get("winner").getAsString();
		if (key.equals(shownFor)) return;
		shownFor = key;
		if (mc.screen != null && !(mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen)) return;
		EomScene.start(s, false, false);
		mc.setScreen(new EomScreen());
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		EomScene.stop(); // Esc: back to the game's own view
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
		// no backdrop: the scene in the world is the picture
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		JsonObject s = ModeHud.state();
		float px = height / 1080f;
		double sec = (System.nanoTime() - openedAt) / 1e9;
		boolean ffa = s.has("ffa") && s.get("ffa").getAsBoolean(), meT = s.has("t") && s.get("t").getAsBoolean();
		String w = s.has("winner") ? s.get("winner").getAsString() : "";
		int win = ffa ? 0 : w.startsWith("Terrorists") ? 1 : w.startsWith("Counter") ? 2 : 0;
		JsonArray a = s.has("eom") ? s.getAsJsonArray("eom") : new JsonArray();
		// the players' cards (CS2's stats cards: across the middle 1440 px, under the line-up)
		int n = a.size();
		for (int i = 0; i < n; i++) {
			JsonObject x = a.get(i).getAsJsonObject();
			int cx = Math.round(width / 2f + (-720 + 1440f * (i + 1) / (n + 1)) * px), y = Math.round(height - 150 * px);
			int cw = Math.round(220 * px), ch = Math.round(64 * px);
			g.fill(cx - cw / 2, y, cx + cw / 2, y + ch, 0xB0000000);
			g.fill(cx - cw / 2, y, cx + cw / 2, y + Math.max(1, Math.round(2 * px)), x.get("t").getAsBoolean() ? 0xFFEABE54 : 0xFF96C8FA);
			String place = ffa ? new String[] {"1st  ", "2nd  ", "3rd  "}[Math.min(i, 2)] : "";
			text(g, place + x.get("name").getAsString(), cx, y + Math.round(20 * px), 20 * px, 0xFFFFFFFF);
			text(g, x.get("k").getAsInt() + " K   " + x.get("d").getAsInt() + " D", cx, y + Math.round(46 * px), 16 * px, 0xFFB0B0B0);
		}
		// the result bar
		boolean teams = s.has("scoreT") && !ffa;
		boolean tie = teams && win == 0, won = !teams || win != 0 && (win == 1) == meT;
		String me = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getScoreboardName() : "";
		String result = ffa ? (n > 0 && a.get(0).getAsJsonObject().get("name").getAsString().equals(me) ? "VICTORY" : "MATCH OVER")
				: tie ? "TIE" : won ? "VICTORY" : "DEFEAT";
		int colour = tie ? GREY : won ? POSITIVE : NEGATIVE;
		float open = (float) Math.min(1, sec / 0.25);
		int bw = Math.round(1340 * px * open), cx = width / 2, top = Math.round(48 * px);
		int scoreH = Math.round(40 * px), resH = Math.round(90 * px), gap = Math.round(10 * px), fade = Math.round(220 * px * open);
		if (bw <= 0) return;
		if (teams) {
			int mine = meT ? s.get("scoreT").getAsInt() : s.get("scoreCT").getAsInt(), theirs = meT ? s.get("scoreCT").getAsInt() : s.get("scoreT").getAsInt();
			fadedBar(g, cx - bw / 2, top, bw, scoreH, won && !tie ? POSITIVE : 0xFF000000, fade);
			text(g, "§l" + mine + "§r  -  " + theirs, cx, top + scoreH / 2, 30 * px, 0xFFFFFFFF);
		}
		int ry = top + (teams ? scoreH + gap : 0);
		fadedBar(g, cx - bw / 2, ry, bw, resH, won && !tie ? POSITIVE : tie ? 0xFF303030 : GREY, fade);
		int line = Math.max(1, Math.round(1.25f * px));
		fadedBar(g, cx - bw / 2, ry, bw, line, 0xFFFFFFFF, fade);
		fadedBar(g, cx - bw / 2, ry + resH - line, bw, line, 0xFFFFFFFF, fade);
		float grow = (float) Math.min(1, sec);
		int textCol = won && !tie ? 0xFFFFFFFF : colour;
		text(g, "§l" + String.join(" ", result.split("")), cx, ry + resH / 2, 64 * px * (0.85f + 0.15f * grow), textCol);
		if (sec > 1) { // the double arrows come in after a second
			float ak = (float) Math.min(1, (sec - 1) / 0.5);
			int off = Math.round((360 + 40 * (1 - ak)) * px), ac = (Math.round(255 * ak) << 24) | (textCol & 0xFFFFFF);
			text(g, "§l»", cx - off, ry + resH / 2, 32 * px, ac);
			text(g, "§l«", cx + off, ry + resH / 2, 32 * px, ac);
		}
		float flash = (float) Math.max(0, 1 - sec / 0.35);
		if (flash > 0) g.fill(cx - bw / 2, top, cx + bw / 2, ry + resH, ((int) (200 * flash) << 24) | 0xFFFFFF);
	}

	/** A bar whose last fade px at each end thin out to nothing (CS2's fade-both-right-left mask). */
	private static void fadedBar(GuiGraphicsExtractor g, int x, int y, int w, int h, int argb, int fade) {
		if (w <= 0 || h <= 0) return;
		fade = Math.min(fade, w / 2);
		int al0 = argb >>> 24, rgb = argb & 0xFFFFFF;
		g.fill(x + fade, y, x + w - fade, y + h, argb);
		for (int i = 0; i < fade; i++) {
			int al = Math.round(al0 * (i + 0.5f) / fade) << 24 | rgb;
			g.fill(x + i, y, x + i + 1, y + h, al);
			g.fill(x + w - 1 - i, y, x + w - i, y + h, al);
		}
	}

	/** Text of a height (GUI units) centred on (x, y). */
	private static void text(GuiGraphicsExtractor g, String s, int x, int y, float height, int color) {
		var font = Minecraft.getInstance().font;
		float k = Math.max(0.5f, height / 9f);
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(k, k);
		g.text(font, s, -font.width(s) / 2, -4, color, true);
		pose.popMatrix();
	}
}
