package dev.strafemc.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.strafemc.client.viewmodel.EomLineup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * CS2's end of match screen (endofmatch + endofmatch-win + endofmatch-characters), over the whole view with no HUD:
 * your side's agents lined up (you in the middle, five at most; free for all: the top three) playing CS2's end-of-round
 * clips, celebrating if their side won and in defeat if it lost, the side's logo faint behind them (5%), and on top
 * the result bar: 1340 px wide (1080p), fading at both ends, a 40 px black score row ("your side - theirs"), then the
 * 90 px result row with VICTORY / DEFEAT / TIE (64 px, letter-spaced) in CS2's positiveColor #0F9D58 (won) or
 * negativeColor #DB4437 on grey (lost), white lines and double arrows, opening over 0.25 s as CS2's does. Names under
 * the agents. It opens when the match ends (after the last round's win panel) and goes when the next match starts or
 * Esc closes it. FLAGGED ours: the line's spacing, the backdrop (CS2 stands them in a lit scene), no weapons in hand.
 */
public final class EomScreen extends Screen {
	private static final int POSITIVE = 0xFF0F9D58, NEGATIVE = 0xFFDB4437, GREY = 0xFF808080;
	/** The match this screen was shown (or closed) for: it opens once per match end. */
	private static String shownFor = "";
	private final long openedAt = System.nanoTime();

	private EomScreen() {
		super(Component.literal("End of match"));
	}

	/** From the mode's state: opens the screen when a match ends, closes it when the next one starts. */
	static void update(JsonObject s) {
		var mc = Minecraft.getInstance();
		boolean over = s.has("phase") && s.get("phase").getAsString().equals("over") && s.has("eom");
		if (!over) {
			shownFor = "";
			if (mc.screen instanceof EomScreen) mc.setScreen(null);
			return;
		}
		List<String> models = new ArrayList<>();
		for (EomLineup.Member m : line(s)) models.add(m.model());
		EomLineup.warm(models);
		String key = s.get("mode").getAsString() + "|" + s.get("winner").getAsString();
		if (key.equals(shownFor)) return;
		shownFor = key;
		if (mc.screen == null || mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen) mc.setScreen(new EomScreen());
	}

	/** The line-up the server sent: each one's agent model, side, whether their side won, their place. */
	static List<EomLineup.Member> line(JsonObject s) {
		List<EomLineup.Member> out = new ArrayList<>();
		if (!s.has("eom")) return out;
		JsonArray a = s.getAsJsonArray("eom");
		int winner = winner(s);
		for (int i = 0; i < a.size(); i++) {
			JsonObject x = a.get(i).getAsJsonObject();
			boolean t = x.get("t").getAsBoolean();
			Agents.Agent ag = Agents.get(x.get("agent").getAsString());
			String model = ag != null ? ag.model() : t ? dev.strafemc.client.viewmodel.AgentModels.T_DEFAULT : dev.strafemc.client.viewmodel.AgentModels.CT_DEFAULT;
			boolean won = winner == 0 || (winner == 1) == t; // free for all and a tie: everyone cheers (CS2 has no losing side there)
			out.add(new EomLineup.Member(model, t, won, i));
		}
		return out;
	}

	/** 1 Terrorists won, 2 Counter-Terrorists, 0 nobody (a tie or free for all). */
	private static int winner(JsonObject s) {
		String w = s.has("winner") ? s.get("winner").getAsString() : "";
		boolean ffa = s.has("ffa") && s.get("ffa").getAsBoolean();
		if (ffa) return 0;
		return w.startsWith("Terrorists") ? 1 : w.startsWith("Counter") ? 2 : 0;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		JsonObject s = ModeHud.state();
		float px = height / 1080f;
		double sec = (System.nanoTime() - openedAt) / 1e9;
		// the backdrop: dark, a little lighter in the middle where they stand
		g.fillGradient(0, 0, width, height, 0xFF0B0E12, 0xFF1A1F26);
		boolean ffa = s.has("ffa") && s.get("ffa").getAsBoolean(), meT = s.has("t") && s.get("t").getAsBoolean();
		int win = winner(s);
		// the side's logo, faint (5%), behind them
		String logo = ffa ? null : meT ? "t_logo" : "ct_logo";
		if (logo != null && dev.strafemc.client.assets.RuntimePack.hasTexture("gui/mode/" + logo + ".png")) {
			int ls = Math.round(height * 0.5f);
			g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath("strafemc", "textures/gui/mode/" + logo + ".png"),
					width / 2 - ls / 2, Math.round(100 * px + 100 * px), 0, 0, ls, ls, ls, ls, 0x0DFFFFFF);
		}
		// the agents
		List<EomLineup.Member> line = line(s);
		ScreenRectangle box = new ScreenRectangle(0, 0, width, height);
		((dev.strafemc.mixin.client.GuiGraphicsExtractorAccessor) g).strafemc$renderState()
				.addPicturesInPictureState(new State(0, 0, width, height, line, sec, box, box));
		// names (and kills / deaths) under them
		JsonArray a = s.has("eom") ? s.getAsJsonArray("eom") : new JsonArray();
		for (int i = 0; i < a.size(); i++) {
			JsonObject x = a.get(i).getAsJsonObject();
			int slot = i == 0 ? 0 : (i + 1) / 2 * (i % 2 == 1 ? 1 : -1);
			int cx = width / 2 + Math.round(slot * width * 0.17f), y = Math.round(height * 0.93f);
			String place = ffa ? new String[] {"1st", "2nd", "3rd"}[Math.min(i, 2)] + "  " : "";
			text(g, place + x.get("name").getAsString(), cx, y, 22 * px, 0xFFFFFFFF);
			text(g, x.get("k").getAsInt() + " / " + x.get("d").getAsInt(), cx, y + Math.round(24 * px), 16 * px, 0xFFB0B0B0);
		}
		// the result bar
		boolean teams = s.has("scoreT") && !ffa;
		boolean won = teams ? win != 0 && (win == 1) == meT : true, tie = teams && win == 0;
		String result = ffa ? (a.size() > 0 && Minecraft.getInstance().player != null
				&& a.get(0).getAsJsonObject().get("name").getAsString().equals(Minecraft.getInstance().player.getScoreboardName()) ? "VICTORY" : "MATCH OVER")
				: tie ? "TIE" : won ? "VICTORY" : "DEFEAT";
		int colour = tie ? GREY : won ? POSITIVE : NEGATIVE;
		float open = (float) Math.min(1, sec / 0.25);
		int bw = Math.round(1340 * px * open), cx = width / 2, top = Math.round(48 * px);
		int scoreH = Math.round(40 * px), resH = Math.round(90 * px), gap = Math.round(10 * px);
		if (bw > 0) {
			if (teams) {
				int mine = meT ? s.get("scoreT").getAsInt() : s.get("scoreCT").getAsInt(), theirs = meT ? s.get("scoreCT").getAsInt() : s.get("scoreT").getAsInt();
				fadedBar(g, cx - bw / 2, top, bw, scoreH, won && !tie ? POSITIVE : 0xFF000000, Math.round(220 * px * open));
				text(g, "§l" + mine + "§r  -  " + theirs, cx, top + scoreH / 2, 30 * px, 0xFFFFFFFF);
			}
			int ry = top + (teams ? scoreH + gap : 0);
			fadedBar(g, cx - bw / 2, ry, bw, resH, won && !tie ? POSITIVE : tie ? 0xFF303030 : GREY, Math.round(220 * px * open));
			int line1 = Math.max(1, Math.round(1.25f * px));
			fadedBar(g, cx - bw / 2, ry, bw, line1, 0xFFFFFFFF, Math.round(220 * px * open));
			fadedBar(g, cx - bw / 2, ry + resH - line1, bw, line1, 0xFFFFFFFF, Math.round(220 * px * open));
			// the result text: grows in over a second, letter-spaced
			float grow = (float) Math.min(1, sec);
			String spaced = String.join(" ", result.split(""));
			int textCol = won && !tie ? 0xFFFFFFFF : colour;
			text(g, "§l" + spaced, cx, ry + resH / 2, 64 * px * (0.85f + 0.15f * grow), textCol);
			if (sec > 1) { // the double arrows come in after a second
				float ak = (float) Math.min(1, (sec - 1) / 0.5);
				int off = Math.round((360 + 40 * (1 - ak)) * px);
				text(g, "§l»", cx - off, ry + resH / 2, 32 * px, (Math.round(255 * ak) << 24) | (textCol & 0xFFFFFF));
				text(g, "§l«", cx + off, ry + resH / 2, 32 * px, (Math.round(255 * ak) << 24) | (textCol & 0xFFFFFF));
			}
			float flash = (float) Math.max(0, 1 - sec / 0.35);
			if (flash > 0) g.fill(cx - bw / 2, top, cx + bw / 2, ry + resH, ((int) (200 * flash) << 24) | 0xFFFFFF);
		}
	}

	/** A bar whose last fade px at each end thin out to nothing (CS2's fade-both-right-left mask). */
	private static void fadedBar(GuiGraphicsExtractor g, int x, int y, int w, int h, int argb, int fade) {
		if (w <= 0 || h <= 0) return;
		fade = Math.min(fade, w / 2);
		int a = argb >>> 24, rgb = argb & 0xFFFFFF;
		g.fill(x + fade, y, x + w - fade, y + h, argb);
		for (int i = 0; i < fade; i++) {
			int al = Math.round(a * (i + 0.5f) / fade) << 24 | rgb;
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

	/** The line-up's 3D picture. */
	public record State(int x0, int y0, int x1, int y1, List<EomLineup.Member> line, double sec, ScreenRectangle scissorArea, ScreenRectangle bounds)
			implements PictureInPictureRenderState {
		@Override
		public float scale() {
			return 1;
		}
	}

	public static final class Renderer extends PictureInPictureRenderer<State> {
		public Renderer(MultiBufferSource.BufferSource buffers) {
			super(buffers);
		}

		@Override
		public Class<State> getRenderStateClass() {
			return State.class;
		}

		@Override
		protected void renderToTexture(State s, PoseStack ps) {
			Minecraft.getInstance().gameRenderer.getLighting().setupFor(com.mojang.blaze3d.platform.Lighting.Entry.ENTITY_IN_UI);
			EomLineup.draw(s.line(), s.sec(), ps, bufferSource, s.x1() - s.x0(), s.y1() - s.y0());
			bufferSource.endBatch();
		}

		@Override
		protected float getTranslateY(int height, int guiScale) {
			return height / 2f;
		}

		@Override
		protected String getTextureLabel() {
			return "strafemc end of match";
		}
	}

	public static void register() {
		net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry.register(ctx -> new Renderer(ctx.minecraft().renderBuffers().bufferSource()));
	}
}
