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
	/** CS2's HUD icons (RuntimePack draws them into gui/mode/): the buy zone cart, the bomb, the planted bomb, the MVP star. */
	public static final String[][] ICONS = {{"buyzone", "icons/ui/buyzone"}, {"bomb_c4", "icons/ui/bomb_c4"},
			{"bomb_planted", "hud/teamcounter/teamcounter_bomb-planted"}, {"mvp", "icons/ui/star"}};
	/** hudmoney: added / taken money shows for AddMoneyTime 2.9 s in AddMoneyColor #8fff6f / RemoveMoneyColor #fd3535. */
	private static final long MONEY_MS = 2900;
	private static final int ADD = 0xFF8FFF6F, REMOVE = 0xFFFD3535;
	private static int lastMoney = Integer.MIN_VALUE, moneyDelta;
	private static long moneyAt;
	/** The team menu opened once for this mode on its own (later: the team key). */
	private static String teamMenuFor = "";
	private static net.minecraft.client.KeyMapping TEAM_KEY;

	private ModeHud() {}

	/** The lowest point of the top of the HUD this frame (GUI units; 0 when it isn't drawn): panels go under it. */
	public static volatile int topBottom;

	private static int lastRound = -1;

	public static JsonObject state() {
		return state;
	}

	/** Players on each side now: [CT, T]. */
	public static int[] teamCounts() {
		return new int[] {teamNames(false).size(), teamNames(true).size()};
	}

	/**
	 * The names on a side (T or CT): as the running mode's state lists them; with no mode, every player and bot this
	 * client knows of, by the side the server gave them (CsTeam.SIDE, sent with every entity).
	 */
	public static java.util.List<String> teamNames(boolean t) {
		java.util.List<String> out = new java.util.ArrayList<>();
		JsonObject s = state;
		if (s.has("players")) {
			for (JsonElement el : s.getAsJsonArray("players")) {
				JsonObject p = el.getAsJsonObject();
				if (p.get("t").getAsBoolean() == t) out.add(p.get("name").getAsString());
			}
			return out;
		}
		var mc = Minecraft.getInstance();
		if (mc.level == null) return out;
		for (var p : mc.level.players()) if (dev.strafemc.weapon.CsTeam.isT(p) == t) out.add(p.getScoreboardName());
		for (var e : mc.level.entitiesForRendering())
			if (e instanceof net.minecraft.world.entity.decoration.Mannequin && e.hasAttached(dev.strafemc.weapon.CsTeam.SIDE) && dev.strafemc.weapon.CsTeam.isT(e) == t)
				out.add(e.getName().getString());
		return out;
	}

	private static Identifier icon(String name) {
		return Identifier.fromNamespaceAndPath("strafemc", "textures/gui/mode/" + name + ".png");
	}

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
			JsonObject s = state;
			int round = s.has("round") ? s.get("round").getAsInt() : -1;
			if (round != lastRound) { // a new round: CS2's kill flags start empty again
				if (round >= 0 && lastRound >= 0) ctx.client().execute(KillCards::clear);
				lastRound = round;
			}
			if (s.has("money")) { // what was added or taken, shown by the money for a moment
				int m = s.get("money").getAsInt();
				if (lastMoney != Integer.MIN_VALUE && m != lastMoney) {
					moneyDelta = m - lastMoney;
					moneyAt = System.currentTimeMillis();
				}
				lastMoney = m;
			} else lastMoney = Integer.MIN_VALUE;
			// a team mode joined without a side picked: CS2's team menu, once
			String mode = s.has("mode") ? s.get("mode").getAsString() : "";
			if (s.has("ffa") && !s.get("ffa").getAsBoolean() && s.has("picked") && !s.get("picked").getAsBoolean() && !mode.equals(teamMenuFor)) {
				teamMenuFor = mode;
				var mc = Minecraft.getInstance();
				if (mc.screen == null) mc.setScreen(new TeamSelectScreen());
			}
			if (mode.isEmpty()) teamMenuFor = "";
			ctx.client().execute(() -> EomScreen.update(s)); // the match's end: CS2's end of match screen
		});
		TEAM_KEY = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(new net.minecraft.client.KeyMapping("key.strafemc.team",
				com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, org.lwjgl.glfw.GLFW.GLFW_KEY_M, dev.strafemc.client.console.ConsoleKeys.CATEGORY));
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (TEAM_KEY.consumeClick()) if (mc.screen == null && mc.player != null) mc.setScreen(new TeamSelectScreen());
		});
		// dead in a round mode: mouse 1 / mouse 2 watch the next / previous teammate (before Minecraft's spectator clicks see them)
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			if (mc.player == null || mc.screen != null || !mc.player.isSpectator() || !state.has("watching") && !state.has("freeCam")) return;
			while (mc.options.keyAttack.consumeClick()) spectate("spec_next");
			while (mc.options.keyUse.consumeClick()) spectate("spec_prev");
		});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> state = new JsonObject());
		// last, not after the hotbar: a spectator (the dead, watching) has no hotbar, and its attachments are skipped with it
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("strafemc", "game_mode"), ModeHud::draw);
		for (Identifier vanilla : new Identifier[] {VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR, VanillaHudElements.ARMOR_BAR,
				VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.INFO_BAR})
			HudElementRegistry.replaceElement(vanilla, old -> (g, delta) -> {
				if (!active()) old.extractRenderState(g, delta);
			});
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, Identifier.fromNamespaceAndPath("strafemc", "game_mode_health"), ModeHud::health);
	}

	private static void spectate(String action) {
		if (ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE)) ClientPlayNetworking.send(new StrafePayloads.Action(action, ""));
	}

	private static void draw(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		JsonObject s = state;
		topBottom = 0;
		if (mc.player == null || mc.options.hideGui || !s.has("mode") || !s.has("players")) return;
		String mode = s.get("mode").getAsString();
		double px = mc.getWindow().getHeight() / 1080.0 / mc.getWindow().getGuiScale(); // one CS2 1080p pixel in GUI units
		var font = mc.font;
		int cx = g.guiWidth() / 2, top = (int) Math.round(2 * px);
		String phase = s.get("phase").getAsString();
		int time = s.get("time").getAsInt();
		boolean teams = s.has("scoreT");
		int cardY = top;
		// the clock (and in team modes the two scores beside it, CT left, T right)
		int bw = (int) Math.round(84 * px), bh = (int) Math.round(34 * px), sw = (int) Math.round(42 * px);
		g.fill(cx - bw / 2, top, cx + bw / 2, top + bh, 0xF0000000);
		String clock = time / 60 + ":" + String.format("%02d", time % 60);
		boolean bomb = s.has("bomb") && s.get("bomb").getAsBoolean();
		if (bomb) { // the planted bomb hides the clock (CS2's teamcounter_bomb-planted), blinking red
			int ih = (int) Math.round(28 * px);
			blitIcon(g, "bomb_planted", cx, top + (bh - ih) / 2, ih, System.currentTimeMillis() / 500 % 2 == 0 ? 0xFFFF3030 : 0xFFB00000);
		}
		else text(g, clock, cx, top + bh / 2, 28 * px, phase.equals("warmup") ? 0xFFB0B0B0 : phase.equals("freeze") ? 0xFFEAD18A : WHITE);
		if (s.has("blackout") && mc.player.isSpectator()) // mp_forcecamera 2: no watching, CS2 fades to black
			g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0xFF000000);
		if ((s.has("watching") || s.has("freeCam")) && mc.player.isSpectator()) { // dead: whose view this is, and how to change it
			int pw = (int) Math.round(420 * px), ph = (int) Math.round(56 * px), py = g.guiHeight() - (int) Math.round(140 * px);
			g.fill(cx - pw / 2, py, cx + pw / 2, py + ph, 0xC0000000);
			g.fill(cx - pw / 2, py, cx + pw / 2, py + Math.max(1, (int) Math.round(2 * px)), GOLD);
			text(g, s.has("watching") ? "Spectating " + s.get("watching").getAsString() : "Free camera", cx, py + (int) Math.round(20 * px), 22 * px, WHITE);
			boolean free = "0".equals(dev.strafemc.client.ClientGameplay.cvar("mp_forcecamera")); // mp_forcecamera 0: sneak lets go
			String hint = !s.has("watching") ? "[Mouse 1] Watch a player" : free ? "[Mouse 1] Next     [Mouse 2] Previous     [Sneak] Free camera"
					: "[Mouse 1] Next     [Mouse 2] Previous";
			text(g, hint, cx, py + (int) Math.round(42 * px), 16 * px, 0xFFB0B0B0);
		}
		// a side with nobody (the round can't start): the alert bar says WAITING FOR PLAYERS (AlertBar)
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
			cardY = y0;
			for (int i = 0; i < n; i++) player(g, mc, players.get(i).getAsJsonObject(), x0 + i * (col + gap), y0, col, px, pulse);
		}
		// how far down the top of the HUD reaches (a card: face, health, value, weapon), for what goes under it
		int bottom = players.isEmpty() ? top + bh : cardY + col + (int) Math.round(62 * px);
		// Deathmatch's bonus weapon: CS2's own panel at the top left (DmBonusPanel)
		topBottom = bottom;
		if (s.has("money")) { // your money (hudmoney: lower left, 38 px bold white), above health and armor
			String m = "$" + s.get("money").getAsInt();
			int mx = (int) Math.round(20 * px), my = g.guiHeight() - (int) Math.round(110 * px);
			int end = left(g, m, mx, my, 38 * px, WHITE);
			int ih = (int) Math.round(30 * px);
			if (s.has("buyZone") && s.get("buyZone").getAsBoolean()) // money__in-buy-zone: the cart, washed light green
				blitIcon(g, "buyzone", end + (int) Math.round(6 * px) + ih / 2, my - ih / 2, ih, 0xFF90EE90);
			long since = System.currentTimeMillis() - moneyAt;
			if (moneyDelta != 0 && since < MONEY_MS) { // +$300 / -$2700 rising off the money
				float a = since < 300 ? since / 300f : since > MONEY_MS - 600 ? (MONEY_MS - since) / 600f : 1;
				int mc2 = ((int) (a * 255) << 24) | ((moneyDelta > 0 ? ADD : REMOVE) & 0xFFFFFF);
				left(g, (moneyDelta > 0 ? "+$" : "-$") + Math.abs(moneyDelta), mx, my - (int) Math.round((40 + 20 * Math.min(1, since / 400.0)) * px), 26 * px, mc2);
			}
		}
		if (s.has("carrier") && s.get("carrier").getAsBoolean()) { // carrying the bomb: its icon, blinking inside a bomb site
			boolean zone = s.has("bombZone") && s.get("bombZone").getAsBoolean();
			int ih = (int) Math.round(36 * px);
			if (!zone || System.currentTimeMillis() / 400 % 2 == 0)
				blitIcon(g, "bomb_c4", (int) Math.round(40 * px), g.guiHeight() - (int) Math.round(170 * px), ih, zone ? 0xFFFF4040 : 0xFFE0E0E0);
		}
		if (phase.equals("roundover")) WinPanel.draw(g, s, false, px); // the round's winner (the match's: EomScreen)
		else WinPanel.hide();
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
		if (!p.has("ally") || p.get("ally").getAsBoolean()) // enemies' health isn't sent
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

	/** One of ICONS, centred at x, h high, in a colour (its PNG is white). */
	private static void blitIcon(GuiGraphicsExtractor g, String name, int x, int y, int h, int color) {
		int[] sz = iconSize(name);
		if (sz == null) return;
		int w = Math.max(1, Math.round((float) sz[0] / sz[1] * h));
		g.blit(RenderPipelines.GUI_TEXTURED, icon(name), x - w / 2, y, 0, 0, w, h, sz[0], sz[1], sz[0], sz[1], color);
	}

	private static final java.util.Map<String, int[]> SIZES = new java.util.concurrent.ConcurrentHashMap<>();

	private static int[] iconSize(String name) {
		return SIZES.computeIfAbsent(name, n -> {
			var png = dev.strafemc.client.assets.RuntimePack.DIR.resolve("assets/strafemc/textures/gui/mode/" + n + ".png");
			try (var in = javax.imageio.ImageIO.createImageInputStream(png.toFile())) {
				var readers = javax.imageio.ImageIO.getImageReaders(in);
				if (!readers.hasNext()) return null;
				var r = readers.next();
				r.setInput(in);
				return new int[] {r.getWidth(0), r.getHeight(0)};
			} catch (Exception e) {
				return null;
			}
		});
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
