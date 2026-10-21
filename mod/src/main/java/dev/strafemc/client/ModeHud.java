package dev.strafemc.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.strafemc.net.StrafePayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * The running game mode's HUD (strafeGM): CS2's team counter at the top as Arms Race lays it out (hudteamcounter, the
 * gungameprogressive classes): the clock in its dark box (84 x 34, #000000f0, 28 px bold), and under it every player
 * in one row, highest level first, each a 54 px column: their face (Minecraft's skin for CS2's avatar) edged in their
 * side's colour, a 4 px red health bar, a 32 px #0000006f box with the level (22 px bold, gold and pulsing on the knife
 * level) and the weapon they're on, CS2's white icon 16 px high with its shadow. Sizes are CS2's 1080p pixels.
 */
public final class ModeHud {
	private static final int GOLD = 0xFFEABE54, WHITE = 0xFFFFFFFF;
	/** The players' name colours on each side (hudteamcounter: CT #a7e3ff, T #ffd063). */
	private static final int CT = 0xFFA7E3FF, T = 0xFFFFD063;
	private static volatile JsonObject state = new JsonObject();

	private ModeHud() {}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(StrafePayloads.ModeState.TYPE, (msg, ctx) -> {
			try {
				state = JsonParser.parseString(msg.json()).getAsJsonObject();
			} catch (Exception e) {
				state = new JsonObject();
			}
		});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> state = new JsonObject());
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, Identifier.fromNamespaceAndPath("strafemc", "game_mode"), ModeHud::draw);
	}

	private static void draw(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		JsonObject s = state;
		if (mc.player == null || mc.options.hideGui || !s.has("mode") || !"armsrace".equals(s.get("mode").getAsString())) return;
		double px = mc.getWindow().getHeight() / 1080.0 / mc.getWindow().getGuiScale(); // one CS2 1080p pixel in GUI units
		var font = mc.font;
		int cx = g.guiWidth() / 2, top = (int) Math.round(2 * px);
		String phase = s.get("phase").getAsString();
		int time = s.get("time").getAsInt();
		// the clock
		int bw = (int) Math.round(84 * px), bh = (int) Math.round(34 * px);
		g.fill(cx - bw / 2, top, cx + bw / 2, top + bh, 0xF0000000);
		String clock = time / 60 + ":" + String.format("%02d", time % 60);
		text(g, clock, cx, top + bh / 2, 28 * px, phase.equals("warmup") ? 0xFFB0B0B0 : WHITE);
		// the players
		JsonElement list = s.get("players");
		if (list != null && list.isJsonArray()) {
			var players = list.getAsJsonArray();
			int col = (int) Math.round(54 * px), gap = Math.max(1, (int) Math.round(2 * px)), n = players.size();
			int x0 = cx - (n * col + (n - 1) * gap) / 2, y0 = top + bh + (int) Math.round(4 * px);
			int levels = s.get("levels").getAsInt();
			boolean pulse = System.currentTimeMillis() / 500 % 2 == 0;
			for (int i = 0; i < n; i++) {
				JsonObject p = players.get(i).getAsJsonObject();
				int x = x0 + i * (col + gap);
				player(g, mc, p, x, y0, col, px, p.get("level").getAsInt() == levels, pulse);
			}
		}
		if (phase.equals("over")) { // the match's end: who won, the next one's countdown
			String w = s.get("winner").getAsString() + " wins";
			int y = g.guiHeight() / 4, h = (int) Math.round(60 * px), w2 = Math.max(font.width(w) * 3, (int) Math.round(500 * px));
			g.fill(cx - w2 / 2, y - h / 2, cx + w2 / 2, y + h / 2, 0xC0000000);
			text(g, w, cx, y, 34 * px, GOLD);
		}
	}

	/** One player's column of the team counter. */
	private static void player(GuiGraphicsExtractor g, Minecraft mc, JsonObject p, int x, int y, int col, double px, boolean knifeLevel, boolean pulse) {
		boolean t = p.get("t").getAsBoolean();
		int side = t ? T : CT;
		// the face, edged in the side's colour
		int edge = Math.max(1, (int) Math.round(2 * px));
		g.fill(x, y, x + col, y + col, side);
		var info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(java.util.UUID.fromString(p.get("id").getAsString()));
		if (info != null)
			net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(g, info.getSkin(), x + edge, y + edge, col - 2 * edge);
		else g.fill(x + edge, y + edge, x + col - edge, y + col - edge, 0xFF7F7F7F);
		double hp = p.get("hp").getAsDouble();
		if (hp <= 0) g.fill(x + edge, y + edge, x + col - edge, y + col - edge, 0xA0000000); // dead
		// the health bar
		int hy = y + col + (int) Math.round(2 * px), hh = Math.max(1, (int) Math.round(4 * px)), hw = (int) Math.round(52 * px);
		g.fill(x + (col - hw) / 2, hy, x + (col - hw) / 2 + (int) Math.round(hw * Math.max(0, Math.min(1, hp))), hy + hh, 0xFFFF0000);
		// the level
		int ly = hy + hh + (int) Math.round(2 * px), lh = (int) Math.round(32 * px);
		g.fill(x, ly, x + col, ly + lh, 0x6F000000);
		int levelColor = knifeLevel ? (pulse ? GOLD : 0xFFFFF2B0) : WHITE;
		text(g, String.valueOf(p.get("level").getAsInt()), x + col / 2, ly + lh / 2, 22 * px, levelColor);
		// the weapon
		String icon = p.get("weapon").getAsString().replace("weapon_", "");
		int ih = Math.max(4, (int) Math.round(16 * px)), iy = ly + lh + (int) Math.round(6 * px);
		int[] sz = DeathNotices.iconPixels(icon);
		if (sz != null && sz[0] > 0) {
			int iw = Math.min(col, Math.max(1, Math.round((float) sz[0] / sz[1] * ih)));
			int h = Math.round((float) sz[1] / sz[0] * iw);
			Identifier tex = Identifier.fromNamespaceAndPath("strafemc", "textures/gui/deathnotice/" + icon + ".png");
			int ix = x + (col - iw) / 2;
			g.blit(RenderPipelines.GUI_TEXTURED, tex, ix + 1, iy + 1, 0, 0, iw, h, sz[0], sz[1], sz[0], sz[1], 0x90000000); // img-shadow 1px 1px
			g.blit(RenderPipelines.GUI_TEXTURED, tex, ix, iy, 0, 0, iw, h, sz[0], sz[1], sz[0], sz[1], knifeLevel ? levelColor : WHITE);
		}
	}

	/** Text centred on (x, y), its capitals about `height` GUI units tall (Minecraft's font for CS2's Stratum, bold by its shadow). */
	private static void text(GuiGraphicsExtractor g, String s, int x, int y, double height, int color) {
		var font = Minecraft.getInstance().font;
		float k = (float) Math.max(0.5, height / 7.0 * 0.72);
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(k, k);
		g.text(font, s, -font.width(s) / 2, -4, color, true);
		pose.popMatrix();
	}
}
