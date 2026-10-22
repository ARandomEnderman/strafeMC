package dev.strafemc.client;

import dev.strafemc.client.ui.Style;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Picking a side in a team game mode, laid out like CS2's team menu: a card per side (Counter-Terrorists left,
 * Terrorists right) with CS2's team logo, the side's colour, how many are on it and who, and auto-select under them;
 * keys 1, 2 and 3 pick the same. Sends CS2's jointeam; the server balances (mp_limitteams) and says no to a full side.
 */
public final class TeamSelectScreen extends Screen {
	/** The sides' colours as the mode HUD shows them (its team counter). */
	private static final int CT = 0xFFB5D4EE, T = 0xFFEAD18A, CT_BAND = 0xFF3A5478, T_BAND = 0xFF7A5A22, CARD = 0xE01A1E24, CARD_HOVER = 0xE0262C34;
	private static final int CARD_W = 170, CARD_H = 210, GAP = 16, AUTO_H = 24, NAMES = 8;

	public TeamSelectScreen() {
		super(Component.literal("Choose a team"));
	}

	private int top() {
		return Math.max(Style.BAR_H + 12, height / 2 - (CARD_H + 8 + AUTO_H) / 2);
	}

	/** Card rects: 0 Counter-Terrorists, 1 Terrorists, 2 auto-select. */
	private int[] rect(int i) {
		int cx = width / 2, y = top();
		if (i == 0) return new int[] {cx - GAP / 2 - CARD_W, y, cx - GAP / 2, y + CARD_H};
		if (i == 1) return new int[] {cx + GAP / 2, y, cx + GAP / 2 + CARD_W, y + CARD_H};
		return new int[] {cx - GAP / 2 - CARD_W, y + CARD_H + 8, cx + GAP / 2 + CARD_W, y + CARD_H + 8 + AUTO_H};
	}

	private static boolean in(double mx, double my, int[] r) {
		return mx >= r[0] && mx < r[2] && my >= r[1] && my < r[3];
	}

	private void pick(String side) {
		dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/submenu_leveloptions_select_01", 0.4f);
		if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(dev.strafemc.net.StrafePayloads.Action.TYPE))
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.strafemc.net.StrafePayloads.Action("jointeam", side));
		if (!side.equals("auto")) {
			dev.strafemc.config.StrafeConfig.team = side;
			try {
				dev.strafemc.config.StrafeConfig.save();
			} catch (Exception ignored) {
			}
		}
		onClose();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (e.button() == 0)
			for (int i = 0; i < 3; i++)
				if (in(e.x(), e.y(), rect(i))) {
					pick(i == 0 ? "ct" : i == 1 ? "t" : "auto");
					return true;
				}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		switch (e.key()) {
			case GLFW.GLFW_KEY_1 -> pick("ct");
			case GLFW.GLFW_KEY_2 -> pick("t");
			case GLFW.GLFW_KEY_3 -> pick("auto");
			default -> {
				return super.keyPressed(e);
			}
		}
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		Style.backdrop(g, width, height);
		var font = minecraft.font;
		String title = "CHOOSE A TEAM";
		g.text(font, title, width / 2 - font.width(title) / 2, (Style.BAR_H - 8) / 2, Style.TEXT, false);
		int[] counts = ModeHud.teamCounts();
		for (int i = 0; i < 2; i++) {
			boolean t = i == 1;
			int[] r = rect(i);
			boolean over = in(mx, my, r);
			int color = t ? T : CT;
			g.fill(r[0], r[1], r[2], r[3], over ? CARD_HOVER : CARD);
			g.fill(r[0], r[1], r[2], r[1] + 3, t ? T_BAND : CT_BAND);
			if (over) g.outline(r[0] - 1, r[1] - 1, r[2] - r[0] + 2, r[3] - r[1] + 2, color);
			// CS2's team logo
			int logo = 72, lx = (r[0] + r[2]) / 2 - logo / 2, ly = r[1] + 14;
			Identifier tex = Identifier.fromNamespaceAndPath("strafemc", "textures/gui/mode/" + (t ? "t" : "ct") + "_logo.png");
			g.blit(RenderPipelines.GUI_TEXTURED, tex, lx, ly, 0, 0, logo, logo, logo, logo);
			String name = t ? "TERRORISTS" : "COUNTER-TERRORISTS";
			g.text(font, name, (r[0] + r[2]) / 2 - font.width(name) / 2, ly + logo + 8, color, false);
			String n = counts[i] + (counts[i] == 1 ? " player" : " players");
			g.text(font, n, (r[0] + r[2]) / 2 - font.width(n) / 2, ly + logo + 20, Style.MUTED, false);
			// who's on it
			List<String> names = ModeHud.teamNames(t);
			int ny = ly + logo + 36;
			g.fill(r[0] + 10, ny - 4, r[2] - 10, ny - 3, Style.LINE);
			for (int k = 0; k < Math.min(NAMES, names.size()); k++) {
				String s = font.plainSubstrByWidth(names.get(k), r[2] - r[0] - 24);
				g.text(font, s, r[0] + 12, ny + k * 10, 0xFFD0D0D0, false);
			}
			if (names.size() > NAMES) g.text(font, "+" + (names.size() - NAMES) + " more", r[0] + 12, ny + NAMES * 10, Style.MUTED, false);
			String key = String.valueOf(i + 1);
			g.text(font, key, r[0] + 6, r[1] + 7, 0x66FFFFFF, false);
		}
		int[] a = rect(2);
		boolean over = in(mx, my, a);
		g.fill(a[0], a[1], a[2], a[3], over ? CARD_HOVER : CARD);
		if (over) g.outline(a[0] - 1, a[1] - 1, a[2] - a[0] + 2, a[3] - a[1] + 2, Style.GOLD);
		String auto = "AUTO-SELECT";
		g.text(font, auto, (a[0] + a[2]) / 2 - font.width(auto) / 2, (a[1] + a[3]) / 2 - 4, Style.GOLD, false);
		g.text(font, "3", a[0] + 6, (a[1] + a[3]) / 2 - 4, 0x66FFFFFF, false);
		super.extractRenderState(g, mx, my, delta);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
