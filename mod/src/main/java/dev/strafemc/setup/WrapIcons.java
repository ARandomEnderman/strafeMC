package dev.strafemc.setup;

import dev.strafemc.client.viewmodel.GloveSkinBaker;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A skin wrap's picture: a piece of the skin's own pattern texture (not a render of the weapon), coloured the way the
 * skin's paint style uses it, on a rolled sheet (16 px, Minecraft item style; knife wraps mirrored). Paint styles
 * (the material's F_PAINT_STYLE): 0 solid colours, 1 hydrographic, 2 spray paint, 3 anodized,
 * 4 anodized multicolour, 5 anodized airbrush, 6 custom paint, 7 patina, 8 gunsmith. Styles 1, 2, 4 and 5 use the
 * pattern's channels as a mask for the four colours; 6 to 8 carry their colours in the texture; 0 and 3 are colours only.
 * FLAGGED: our reading of the styles, made for a recognisable icon, not the in-game look.
 */
public final class WrapIcons {
	public static final int SIZE = 16;

	private WrapIcons() {}

	/** The skin piece (SIZE x SIZE, no shape): pattern is the converted g_tPattern image (null if none). */
	public static BufferedImage swatch(GloveSkinBaker.Params p, BufferedImage pattern) {
		int style = (int) p.f("F_PAINT_STYLE", 6);
		double[][] col = new double[4][];
		for (int i = 0; i < 4; i++) col[i] = p.v("g_vColor" + i, 0.5, 0.5, 0.5);
		BufferedImage swatch = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		// the middle third of the texture: enough to tell the skin, not the whole sheet
		int side = pattern == null ? 0 : Math.max(1, Math.min(pattern.getWidth(), pattern.getHeight()) / 3);
		int ox = pattern == null ? 0 : (pattern.getWidth() - side) / 2, oy = pattern == null ? 0 : (pattern.getHeight() - side) / 2;
		for (int y = 0; y < SIZE; y++)
			for (int x = 0; x < SIZE; x++) {
				double[] c;
				if (pattern == null || style == 0 || style == 3) {
					c = col[Math.min(3, (x + y) * 4 / (2 * SIZE))]; // the paint's colours as diagonal bands
				} else {
					int px = pattern.getRGB(ox + x * side / SIZE, oy + y * side / SIZE);
					double r = (px >> 16 & 255) / 255.0, g = (px >> 8 & 255) / 255.0, b = (px & 255) / 255.0;
					if (style == 1 || style == 2 || style == 4 || style == 5) {
						c = col[0];
						c = lerp(c, col[1], r);
						c = lerp(c, col[2], g);
						c = lerp(c, col[3], b);
					} else c = new double[] {r, g, b};
				}
				swatch.setRGB(x, y, rgb(c));
			}
		return swatch;
	}

	/**
	 * The wrap picture: the skin piece on the paper of a wrap template. template: Minecraft's old banner pattern item
	 * picture (read from an older Minecraft on the player's PC, never shipped), or null for our own drawn scroll. On the
	 * template, the light, colourless paper takes the skin, shaded like the paper was; everything else stays.
	 * Knife wraps are the mirror image.
	 */
	public static BufferedImage make(BufferedImage swatch, BufferedImage template, boolean knife) {
		if (template == null) return shape(swatch, knife);
		int w = template.getWidth(), h = template.getHeight();
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++) {
				int c = template.getRGB(x, y), a = c >>> 24, r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255;
				int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
				if (a > 0 && max >= 190 && max - min <= 24) { // paper
					int s = swatch.getRGB(x * SIZE / w, y * SIZE / h);
					c = shade(s, Math.min(1.0, max / 232.0));
				}
				out.setRGB(knife ? w - 1 - x : x, y, c);
			}
		return out;
	}

	/** The plain wrap item pictures (no skin): blank paper, same shapes. */
	public static BufferedImage base(boolean knife) {
		BufferedImage s = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < SIZE; y++)
			for (int x = 0; x < SIZE; x++) s.setRGB(x, y, (x + y) % 4 == 0 ? 0xFFDCD3BC : 0xFFEDE6D2);
		return shape(s, knife);
	}

	/**
	 * A rolled-up sheet lying diagonally (our own Minecraft-style drawing): the skin on the paper, rolled tan ends and a
	 * dark outline. Gun wraps run top left to bottom right, knife wraps are the mirror image.
	 */
	private static BufferedImage shape(BufferedImage swatch, boolean knife) {
		BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < SIZE; y++)
			for (int x = 0; x < SIZE; x++) {
				if (!inSheet(x, y)) continue;
				int a = x + y, b = x - y, c;
				boolean edge = !inSheet(x - 1, y) || !inSheet(x + 1, y) || !inSheet(x, y - 1) || !inSheet(x, y + 1);
				if (edge) c = 0xFF4A3A28;
				else if (a <= 7 || a >= 23) c = (b & 1) == 0 ? 0xFFC9A877 : 0xFFA88A5C; // the rolls
				else c = swatch.getRGB(x, y);
				out.setRGB(knife ? SIZE - 1 - x : x, y, c);
			}
		return out;
	}

	private static boolean inSheet(int x, int y) {
		if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) return false;
		int a = x + y, b = x - y;
		return a >= 4 && a <= 26 && Math.abs(b) <= (a <= 6 || a >= 24 ? 5 : 4);
	}

	public static void write(BufferedImage img, Path out) throws Exception {
		Files.createDirectories(out.getParent());
		javax.imageio.ImageIO.write(img, "png", out.toFile());
	}

	/** File-safe name of a wrap id ("gun:asiimov" -> "gun_asiimov"). */
	public static String fileName(String wrapId) {
		return wrapId.replace(':', '_');
	}

	private static double[] lerp(double[] a, double[] b, double t) {
		return new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
	}

	private static int shade(int c, double k) {
		int r = Math.min(255, (int) ((c >> 16 & 255) * k)), g = Math.min(255, (int) ((c >> 8 & 255) * k)), b = Math.min(255, (int) ((c & 255) * k));
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	private static int rgb(double[] c) {
		int r = (int) Math.round(Math.max(0, Math.min(1, c[0])) * 255), g = (int) Math.round(Math.max(0, Math.min(1, c[1])) * 255),
				b = (int) Math.round(Math.max(0, Math.min(1, c[2])) * 255);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}
}
