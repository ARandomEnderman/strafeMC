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
 * The running game mode's HUD (strafeGM): CS2's team counter at the top (hudteamcounter): the clock in its dark box
 * (84 x 34, #000000f0, 28 px bold). Arms Race and Deathmatch put every player in one row under it, first place first;
 * Team Deathmatch puts the team scores (42 px boxes, CT #B5D4EE, T #EAD18A) beside the clock with each side's
 * players outward from them. A player is a 54 px column: their face (Minecraft's skin for CS2's avatar) edged in their
 * side's colour, a 4 px red health bar, a 32 px #0000006f box with their level or score (22 px bold; gold and pulsing
 * on the knife level; bots dimmed) and in Arms Race the weapon they're on, CS2's white icon 16 px high with its
 * shadow. Sizes are CS2's 1080p pixels.
 */
public final class ModeHud {
	private static final int GOLD = 0xFFEABE54, WHITE = 0xFFFFFFFF;
	/** The players' name colours on each side (hudteamcounter: CT #a7e3ff, T #ffd063). */
	private static final int CT = 0xFFA7E3FF, T = 0xFFFFD063;
	private static volatile JsonObject state = new JsonObject();

	private ModeHud() {}

	/** A game mode runs: its HUD stands in for Minecraft's hearts, hunger, armor and experience. */
	public static boolean active() {
		JsonObject s = state;
		return s.has("mode") && s.has("players");
	}

	/** Freeze time in a round mode: nobody moves. */
	public static boolean frozen() {
		JsonObject s = state;
		return s.has("frozen") && s.get("frozen").getAsBoolean();
	}

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
		for (Identifier vanilla : new Identifier[] {VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR, VanillaHudElements.ARMOR_BAR,
				VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.INFO_BAR})
			HudElementRegistry.replaceElement(vanilla, old -> (g, delta) -> {
				if (!active()) old.extractRenderState(g, delta);
			});
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, Identifier.fromNamespaceAndPath("strafemc", "game_mode_health"), ModeHud::health);
	}

	private static void draw(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		JsonObject s = state;
		if (mc.player == null || mc.options.hideGui || !s.has("mode") || !s.has("players")) return;
		String mode = s.get("mode").getAsString();
		double px = mc.getWindow().getHeight() / 1080.0 / mc.getWindow().getGuiScale(); // one CS2 1080p pixel in GUI units
		var font = mc.font;
		int cx = g.guiWidth() / 2, top = (int) Math.round(2 * px);
		String phase = s.get("phase").getAsString();
		int time = s.get("time").getAsInt();
		boolean teams = s.has("scoreT");
		// the clock (and in team modes the two scores beside it, CT left, T right)
		int bw = (int) Math.round(84 * px), bh = (int) Math.round(34 * px), sw = (int) Math.round(42 * px);
		g.fill(cx - bw / 2, top, cx + bw / 2, top + bh, 0xF0000000);
		String clock = time / 60 + ":" + String.format("%02d", time % 60);
		boolean bomb = s.has("bomb") && s.get("bomb").getAsBoolean();
		if (bomb) text(g, "BOMB", cx, top + bh / 2, 24 * px, System.currentTimeMillis() / 500 % 2 == 0 ? 0xFFFF3030 : 0xFFB00000); // the planted bomb hides the clock
		else text(g, clock, cx, top + bh / 2, 28 * px, phase.equals("warmup") ? 0xFFB0B0B0 : phase.equals("freeze") ? 0xFFEAD18A : WHITE);
		if (teams) {
			g.fill(cx - bw / 2 - sw, top, cx - bw / 2, top + bh, 0xF0000000);
			g.fill(cx + bw / 2, top, cx + bw / 2 + sw, top + bh, 0xF0000000);
			text(g, String.valueOf(s.get("scoreCT").getAsInt()), cx - bw / 2 - sw / 2, top + bh / 2, 28 * px, 0xFFB5D4EE);
			text(g, String.valueOf(s.get("scoreT").getAsInt()), cx + bw / 2 + sw / 2, top + bh / 2, 28 * px, 0xFFEAD18A);
		}
		// the players
		var players = s.getAsJsonArray("players");
		int col = (int) Math.round(54 * px), gap = Math.max(1, (int) Math.round(2 * px));
		boolean pulse = System.currentTimeMillis() / 500 % 2 == 0;
		if (teams) { // CT to the left of the scores (nearest first), T to the right
			int xl = cx - bw / 2 - sw - gap - col, xr = cx + bw / 2 + sw + gap;
			for (JsonElement el : players) {
				JsonObject p = el.getAsJsonObject();
				if (p.get("t").getAsBoolean()) {
					player(g, mc, p, xr, top, col, px, pulse);
					xr += col + gap;
				} else {
					player(g, mc, p, xl, top, col, px, pulse);
					xl -= col + gap;
				}
			}
		} else {
			int n = Math.min(players.size(), 12);
			int x0 = cx - (n * col + (n - 1) * gap) / 2, y0 = top + bh + (int) Math.round(4 * px);
			for (int i = 0; i < n; i++) player(g, mc, players.get(i).getAsJsonObject(), x0 + i * (col + gap), y0, col, px, pulse);
		}
		// Deathmatch's bonus weapon of the moment, under the clock
		if (s.has("bonus") && !s.get("bonus").getAsString().isEmpty()) {
			int by = teams ? top + bh + (int) Math.round(6 * px) : top + bh + (int) Math.round(130 * px);
			text(g, "BONUS", cx, by + (int) Math.round(6 * px), 14 * px, GOLD);
			icon(g, s.get("bonus").getAsString().replace("weapon_", ""), cx, by + (int) Math.round(14 * px), (int) Math.round(20 * px), GOLD);
		}
		if (s.has("money")) { // your money (hudmoney: lower left, 38 px bold white), above health and armor
			String m = "$" + s.get("money").getAsInt();
			left(g, m, (int) Math.round(20 * px), g.guiHeight() - (int) Math.round(110 * px), 38 * px, WHITE);
		}
		if (phase.equals("roundover") || phase.equals("over")) { // the round's or the match's winner
			String w = phase.equals("over") ? s.get("winner").getAsString() + " wins the match" : s.get("roundWinner").getAsString();
			int y = g.guiHeight() / 4, h = (int) Math.round(60 * px), w2 = Math.max(font.width(w) * 3, (int) Math.round(500 * px));
			g.fill(cx - w2 / 2, y - h / 2, cx + w2 / 2, y + h / 2, 0xC0000000);
			text(g, w, cx, y, 34 * px, GOLD);
		}
	}

	/**
	 * Health and armor while a mode runs (CS2's large 42 px numbers, lower left here because the hotbar has the bottom
	 * centre): health white, red at 20 or less; the armor with a helmet mark.
	 */
	private static void health(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!active() || mc.player == null || mc.options.hideGui || mc.player.isSpectator()) return;
		double px = mc.getWindow().getHeight() / 1080.0 / mc.getWindow().getGuiScale();
		int hp = (int) Math.ceil(mc.player.getHealth() / mc.player.getMaxHealth() * 100);
		int y = g.guiHeight() - (int) Math.round(40 * px), x = (int) Math.round(20 * px);
		x = left(g, "\u271a " + hp, x, y, 42 * px, hp <= 20 ? 0xFFDD0000 : WHITE) + (int) Math.round(40 * px);
		int armor = StrafeMcClient.armor;
		if (armor > 0) left(g, (StrafeMcClient.helmet ? "\u26e8+ " : "\u26e8 ") + armor, x, y, 42 * px, WHITE);
	}

	/** Text from x, centred on y; returns where it ends. */
	private static int left(GuiGraphicsExtractor g, String s, int x, int y, double height, int color) {
		var font = Minecraft.getInstance().font;
		float k = (float) Math.max(0.5, height / 7.0 * 0.72);
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(k, k);
		g.text(font, s, 0, -4, color, true);
		pose.popMatrix();
		return x + Math.round(font.width(s) * k);
	}

	/** One player's column of the team counter: face, health, their level or score, the weapon they're on. */
	private static void player(GuiGraphicsExtractor g, Minecraft mc, JsonObject p, int x, int y, int col, double px, boolean pulse) {
		boolean t = p.get("t").getAsBoolean(), bot = p.get("bot").getAsBoolean();
		int side = t ? T : CT;
		int edge = Math.max(1, (int) Math.round(2 * px));
		g.fill(x, y, x + col, y + col, side);
		var info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(java.util.UUID.fromString(p.get("id").getAsString()));
		if (info != null)
			net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(g, info.getSkin(), x + edge, y + edge, col - 2 * edge);
		else {
			g.fill(x + edge, y + edge, x + col - edge, y + col - edge, 0xFF7F7F7F);
			if (bot) text(g, "BOT", x + col / 2, y + col / 2, 14 * px, 0xFF303030);
		}
		double hp = p.get("hp").getAsDouble();
		if (hp <= 0) g.fill(x + edge, y + edge, x + col - edge, y + col - edge, 0xA0000000); // dead
		int hy = y + col + (int) Math.round(2 * px), hh = Math.max(1, (int) Math.round(4 * px)), hw = (int) Math.round(52 * px);
		g.fill(x + (col - hw) / 2, hy, x + (col - hw) / 2 + (int) Math.round(hw * Math.max(0, Math.min(1, hp))), hy + hh, 0xFFFF0000);
		int ly = hy + hh + (int) Math.round(2 * px), lh = (int) Math.round(32 * px);
		if (p.get("value").getAsString().isEmpty()) return;
		g.fill(x, ly, x + col, ly + lh, 0x6F000000);
		boolean gold = p.has("gold") && p.get("gold").getAsBoolean();
		int color = gold ? (pulse ? GOLD : 0xFFFFF2B0) : bot ? 0x80FFFFFF : WHITE; // bots' numbers dimmed (AvatarL__GGText--Bot)
		text(g, p.get("value").getAsString(), x + col / 2, ly + lh / 2, 22 * px, color);
		if (p.has("weapon"))
			icon(g, p.get("weapon").getAsString().replace("weapon_", ""), x + col / 2, ly + lh + (int) Math.round(6 * px), Math.max(4, (int) Math.round(16 * px)),
					gold ? color : WHITE);
	}

	/** A CS2 weapon icon centred at x, `h` high, with its 1 px shadow. */
	private static void icon(GuiGraphicsExtractor g, String icon, int x, int y, int h, int color) {
		int[] sz = DeathNotices.iconPixels(icon);
		if (sz == null || sz[0] <= 0) return;
		int iw = Math.max(1, Math.round((float) sz[0] / sz[1] * h));
		Identifier tex = Identifier.fromNamespaceAndPath("strafemc", "textures/gui/deathnotice/" + icon + ".png");
		g.blit(RenderPipelines.GUI_TEXTURED, tex, x - iw / 2 + 1, y + 1, 0, 0, iw, h, sz[0], sz[1], sz[0], sz[1], 0x90000000);
		g.blit(RenderPipelines.GUI_TEXTURED, tex, x - iw / 2, y, 0, 0, iw, h, sz[0], sz[1], sz[0], sz[1], color);
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
