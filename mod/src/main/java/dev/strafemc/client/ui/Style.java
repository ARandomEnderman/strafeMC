package dev.strafemc.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;

/** The look strafeMC's full-screen menus share: backdrop, top bar with tabs, gold accent, muted text. */
public final class Style {
	public static final int BG = 0xF0121416, BAR = 0xFF0B0C0D, LINE = 0x30FFFFFF, GOLD = 0xFFEABE54, MUTED = 0xFF9AA29E, TEXT = 0xFFFFFFFF;
	/** The top bar's height: tab buttons sit in it at y 5. */
	public static final int BAR_H = 30;

	private Style() {}

	/** The backdrop and the dark top bar the tabs sit in. */
	public static void backdrop(GuiGraphicsExtractor g, int width, int height) {
		g.fill(0, 0, width, height, BG);
		g.fill(0, 0, width, BAR_H, BAR);
		g.fill(0, BAR_H, width, BAR_H + 1, LINE);
	}

	/** Marks the open tab (or the chosen one of a few buttons): a gold line under it. */
	public static void underline(GuiGraphicsExtractor g, AbstractWidget w) {
		g.fill(w.getX() + 4, w.getY() + w.getHeight() + 1, w.getX() + w.getWidth() - 4, w.getY() + w.getHeight() + 3, GOLD);
	}
}
