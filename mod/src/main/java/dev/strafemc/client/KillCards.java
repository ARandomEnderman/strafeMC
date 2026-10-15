package dev.strafemc.client;

import dev.strafemc.config.StrafeConfig;
import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.KillFeed;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * CS2's round kills above the hotbar (hudhealthammocenter's kill flags): a card per kill of yours, fanned like a hand
 * (12 degrees apart, the outer ones a little lower), each a dark card (kill_mask) with CS2's outline (kill_1 to kill_5)
 * and a pip for how the kill was made (headshot, blast, burn, slash, shock, default); past five, the extra card with
 * the count. New cards slide up into place (CS2: 42 px over 0.2 s). They stay strafemc_killcards_time seconds each, or
 * (0) until you die.
 */
public final class KillCards {
	private record Card(String pip, long time) {}

	private static final List<Card> CARDS = new ArrayList<>();
	/** Card art: rasterized from CS2's svgs (48 x 126 viewbox) into the runtime pack at this height. */
	public static final int ART_H = 252, ART_W = 96;
	private static final double SLIDE_S = 0.2;

	private KillCards() {}

	/** Every kill notice: yours add a card; your own death clears them when they last until death. */
	public static void notice(StrafePayloads.KillNotice m) {
		var p = Minecraft.getInstance().player;
		if (p == null) return;
		int me = p.getId();
		synchronized (CARDS) {
			if (m.victimId() == me) {
				if (StrafeConfig.killCardSeconds <= 0) CARDS.clear();
				return;
			}
			if (m.attackerId() == me) CARDS.add(new Card(pip(m), System.nanoTime()));
		}
	}

	public static void clear() {
		synchronized (CARDS) {
			CARDS.clear();
		}
	}

	/** How the kill was made, as CS2's pip: headshot, blast (HE), burn (fire), slash (knife), shock (Zeus), else default. */
	private static String pip(StrafePayloads.KillNotice m) {
		String w = m.icon() == null ? "" : m.icon();
		if ((m.flags() & KillFeed.HEADSHOT) != 0) return "headshot";
		if (w.contains("knife") || w.contains("bayonet")) return "slash";
		if (w.contains("taser")) return "shock";
		if (w.contains("hegrenade")) return "blast";
		if (w.contains("inferno") || w.contains("molotov") || w.contains("incgrenade")) return "burn";
		return "default";
	}

	public static void render(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.hideGui || mc.player == null) return;
		long now = System.nanoTime();
		if (StrafeConfig.killCardSeconds <= 0 && mc.player.isDeadOrDying()) clear(); // until you die
		List<Card> list;
		synchronized (CARDS) {
			if (StrafeConfig.killCardSeconds > 0) CARDS.removeIf(c -> (now - c.time) / 1e9 > StrafeConfig.killCardSeconds);
			if (CARDS.isEmpty()) return;
			list = new ArrayList<>(CARDS);
		}
		double px = mc.getWindow().getHeight() / 1080.0 / mc.getWindow().getGuiScale(); // one CS2 1080p pixel in GUI units
		float h = (float) (152 * px), w = h * ART_W / ART_H; // CS2: card-svg-height 152px
		// above the hotbar and the health / food rows
		float cx = g.guiWidth() / 2f, cy = g.guiHeight() - 46 - h * 0.18f;
		int n = list.size(), shown = Math.min(n, 5);
		var pose = g.pose();
		for (int i = 0; i < shown; i++) {
			boolean extra = n > 5 && i == shown - 1;
			Card c = extra ? list.get(n - 1) : list.get(i);
			double angle = (i + 1 - (shown + 1) / 2.0) * 12; // CS2: -6 / +6 for two, -12 / 0 / +12 for three...
			double slide = Math.max(0, 1 - (now - c.time) / 1e9 / SLIDE_S);
			slide = slide * slide; // ease out
			float dy = (float) ((Math.abs(angle) / 3 + 42 * slide) * px), dx = (float) (angle * 0.29 * px);
			int alpha = (int) Math.round(255 * (1 - slide));
			pose.pushMatrix();
			pose.translate(cx + dx, cy + dy);
			pose.rotate((float) Math.toRadians(angle));
			int x0 = Math.round(-w / 2), y0 = Math.round(-h / 2), iw = Math.round(w), ih = Math.round(h);
			card(g, "kill_mask", x0, y0, iw, ih, alpha << 24 | 0x101418, alpha * 0xB0 / 255);
			card(g, extra ? "kill_extra" : "kill_" + (i + 1), x0, y0, iw, ih, alpha << 24 | 0xFFFFFF, alpha);
			if (!extra) card(g, "kill_pip_" + c.pip, x0, y0, iw, ih, alpha << 24 | 0xFFFFFF, alpha);
			else {
				String count = String.valueOf(n);
				pose.pushMatrix();
				pose.translate(0, (float) (y0 + 21 * px * h / (152 * px) * 0.83));
				g.text(mc.font, count, -mc.font.width(count) / 2, 0, (alpha << 24) | 0xFFFFFF, false);
				pose.popMatrix();
			}
			pose.popMatrix();
		}
	}

	private static void card(GuiGraphicsExtractor g, String name, int x, int y, int w, int h, int rgb, int alpha) {
		if (alpha <= 0) return;
		g.blit(RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath("strafemc", "textures/gui/killcards/" + name + ".png"), x, y, 0, 0, w, h, ART_W,
				ART_H, ART_W, ART_H, (alpha << 24) | (rgb & 0xFFFFFF));
	}
}
