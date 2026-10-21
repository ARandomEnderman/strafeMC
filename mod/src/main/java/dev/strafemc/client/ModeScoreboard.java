package dev.strafemc.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The game mode's scoreboard on the player list key (Tab): CS2's layout as a table per side (Counter-Terrorists over
 * Terrorists, each under its side's colour and round score) or one table in a free-for-all, a row per player with
 * their money (teammates only, as CS2 shows it), kills, assists, deaths, MVP stars and, where the mode counts it, their
 * score or level; you in gold, the dead dimmed.
 */
public final class ModeScoreboard {
	private static final int CT = 0xFF5D79AE, T = 0xFFDE9B35, ROW = 12;
	private static final Identifier STAR = Identifier.fromNamespaceAndPath("strafemc", "textures/gui/mode/mvp.png");

	private ModeScoreboard() {}

	public static void register() {
		HudElementRegistry.replaceElement(VanillaHudElements.PLAYER_LIST, old -> (g, delta) -> {
			Minecraft mc = Minecraft.getInstance();
			if (ModeHud.active() && mc.options.keyPlayerList.isDown()) draw(g, mc);
			else old.extractRenderState(g, delta);
		});
	}

	private static void draw(GuiGraphicsExtractor g, Minecraft mc) {
		JsonObject s = ModeHud.state();
		var players = s.getAsJsonArray("players");
		boolean ffa = s.has("ffa") && s.get("ffa").getAsBoolean(), meT = s.has("t") && s.get("t").getAsBoolean();
		String you = s.has("you") ? s.get("you").getAsString() : "";
		int w = Math.min(g.guiWidth() - 20, 360), x = (g.guiWidth() - w) / 2, y = 24;
		var font = mc.font;
		String title = dev.strafemc.mode.GameModes.NAMES.getOrDefault(s.get("mode").getAsString(), "");
		if (s.has("round")) title += "   Round " + s.get("round").getAsInt() + " of " + s.get("rounds").getAsInt();
		g.fill(x, y - 14, x + w, y, 0xE0000000);
		g.text(font, title, x + 6, y - 11, 0xFFFFFFFF, true);
		y += 2;
		if (ffa) y = table(g, mc, players, null, "Players", 0xFF6A6A6A, "", x, y, w, you, meT, s);
		else {
			y = table(g, mc, players, false, "Counter-Terrorists", CT, s.has("scoreCT") ? String.valueOf(s.get("scoreCT").getAsInt()) : "", x, y, w, you, meT, s);
			y = table(g, mc, players, true, "Terrorists", T, s.has("scoreT") ? String.valueOf(s.get("scoreT").getAsInt()) : "", x, y + 4, w, you, meT, s);
		}
	}

	/** One side's table (t null: everyone); returns where it ends. */
	private static int table(GuiGraphicsExtractor g, Minecraft mc, com.google.gson.JsonArray players, Boolean t, String name, int color, String score, int x, int y,
			int w, String you, boolean meT, JsonObject s) {
		var font = mc.font;
		List<JsonObject> rows = new ArrayList<>();
		for (JsonElement el : players) {
			JsonObject p = el.getAsJsonObject();
			if (t == null || p.get("t").getAsBoolean() == t) rows.add(p);
		}
		boolean money = t != null && t == meT && s.has("money"); // your side's money (round modes)
		boolean value = rows.stream().anyMatch(p -> p.has("value") && !p.get("value").getAsString().isEmpty());
		// header: the side, its round score, the columns
		g.fill(x, y, x + w, y + 14, (color & 0xFFFFFF) | 0xD0000000);
		g.text(font, name, x + 6, y + 3, 0xFFFFFFFF, true);
		if (!score.isEmpty()) g.text(font, score, x + w - 6 - font.width(score), y + 3, 0xFFFFFFFF, true);
		y += 14;
		int cK = x + w - 150, cA = cK + 24, cD = cA + 24, cMvp = cD + 26, cVal = cMvp + 36, cMoney = cK - 50;
		g.fill(x, y, x + w, y + 10, 0xC0101010);
		head(g, font, "K", cK, y);
		head(g, font, "A", cA, y);
		head(g, font, "D", cD, y);
		head(g, font, "MVP", cMvp, y);
		if (value) head(g, font, s.get("mode").getAsString().equals("armsrace") ? "LVL" : "SCORE", cVal, y);
		if (money) head(g, font, "$", cMoney, y);
		y += 10;
		for (JsonObject p : rows) {
			boolean me = p.get("id").getAsString().equals(you), dead = p.get("hp").getAsDouble() <= 0;
			g.fill(x, y, x + w, y + ROW, me ? 0xC0403818 : 0xB0000000);
			int c = dead ? 0xFF808080 : me ? 0xFFEABE54 : 0xFFFFFFFF;
			String n = (p.get("bot").getAsBoolean() ? "BOT " : "") + p.get("name").getAsString();
			g.text(font, n, x + 6, y + 2, c, true);
			cell(g, font, String.valueOf(p.get("k").getAsInt()), cK, y, c);
			cell(g, font, String.valueOf(p.get("a").getAsInt()), cA, y, c);
			cell(g, font, String.valueOf(p.get("d").getAsInt()), cD, y, c);
			int mvp = p.get("mvp").getAsInt();
			if (mvp > 0) {
				g.blit(RenderPipelines.GUI_TEXTURED, STAR, cMvp - 4, y + 2, 0, 0, 8, 8, 8, 8, 8, 8, 0xFFEABE54);
				if (mvp > 1) g.text(font, String.valueOf(mvp), cMvp + 6, y + 2, c, true);
			}
			if (value && p.has("value")) cell(g, font, p.get("value").getAsString(), cVal, y, c);
			if (money && p.has("money")) cell(g, font, "$" + p.get("money").getAsInt(), cMoney, y, 0xFF8FFF6F);
			y += ROW;
		}
		return y;
	}

	private static void head(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, String s, int cx, int y) {
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(cx - font.width(s) * 0.35f, y + 2);
		pose.scale(0.7f, 0.7f);
		g.text(font, s, 0, 0, 0xFFB0B0B0, false);
		pose.popMatrix();
	}

	private static void cell(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, String s, int cx, int y, int color) {
		g.text(font, s, cx - font.width(s) / 2, y + 2, color, true);
	}
}
