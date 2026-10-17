package dev.strafemc.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * An item's name on a picture tile, as CS2 lays it out: the weapon on one line and the finish under it ("AK-47 | Redline"
 * -> "AK-47" / "Redline"), each line shrunk to fit the tile instead of cut off.
 */
public final class TileText {
	/** Height of the two lines (scaled 0.75). */
	public static final int HEIGHT = 16;

	private TileText() {}

	/** The name without our item suffix (a tile shows the finish, not "... Wrap"). */
	public static String clean(String name) {
		return name.replace(" Knife Wrap", "").replace(" Wrap", "");
	}

	/** Draws the name centred on cx with its top at y, in at most maxW wide. */
	public static void draw(GuiGraphicsExtractor g, Font font, String name, int cx, int y, int maxW, int color) {
		name = clean(name);
		int bar = name.indexOf(" | ");
		String a = bar < 0 ? name : name.substring(0, bar), b = bar < 0 ? null : name.substring(bar + 3);
		if (b == null && font.width(a) * 0.75f > maxW) { // one long name: break at the space nearest the middle
			int mid = a.length() / 2, best = -1;
			for (int i = 0; i < a.length(); i++) if (a.charAt(i) == ' ' && (best < 0 || Math.abs(i - mid) < Math.abs(best - mid))) best = i;
			if (best > 0) {
				b = a.substring(best + 1);
				a = a.substring(0, best);
			}
		}
		line(g, font, a, cx, b == null ? y + 4 : y, maxW, b == null ? color : 0xFF9AA3AD);
		if (b != null) line(g, font, b, cx, y + 8, maxW, color);
	}

	private static void line(GuiGraphicsExtractor g, Font font, String s, int cx, int y, int maxW, int color) {
		float k = Math.min(0.75f, maxW / (float) Math.max(1, font.width(s)));
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(cx, y);
		pose.scale(k, k);
		g.text(font, s, -font.width(s) / 2, 0, color, false);
		pose.popMatrix();
	}
}
