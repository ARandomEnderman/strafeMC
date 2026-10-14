package dev.strafemc.setup;

import dev.strafemc.client.viewmodel.GloveSkinBaker;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A skin wrap's picture: a piece of the skin's own pattern texture (not a render of the weapon), coloured the way the
 * skin's paint style uses it, cut into a shape that tells gun wraps (a sheet with a folded corner) from knife wraps (a
 * blade). Paint styles (the material's F_PAINT_STYLE): 0 solid colours, 1 hydrographic, 2 spray paint, 3 anodized,
 * 4 anodized multicolour, 5 anodized airbrush, 6 custom paint, 7 patina, 8 gunsmith. Styles 1, 2, 4 and 5 use the
 * pattern's channels as a mask for the four colours; 6 to 8 carry their colours in the texture; 0 and 3 are colours only.
 * FLAGGED: our reading of the styles, made for a recognisable icon, not the in-game look.
 */
public final class WrapIcons {
	public static final int SIZE = 32;

	private WrapIcons() {}

	/** The icon, or null if the skin's material can't be read. pattern: the converted g_tPattern image (null if none). */
	public static BufferedImage make(GloveSkinBaker.Params p, BufferedImage pattern, boolean knife) {
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
		return shape(swatch, knife);
	}

	/** The plain wrap item pictures (no skin): grey, same shapes. */
	public static BufferedImage base(boolean knife) {
		BufferedImage s = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < SIZE; y++)
			for (int x = 0; x < SIZE; x++) {
				int v = 110 + ((x / 4 + y / 4) % 2) * 25;
				s.setRGB(x, y, 0xFF000000 | v << 16 | v << 8 | v);
			}
		return shape(s, knife);
	}

	/** Gun wrap: a square sheet with a dark edge and its top right corner folded over. Knife wrap: a diagonal blade with a handle. */
	private static BufferedImage shape(BufferedImage swatch, boolean knife) {
		BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < SIZE; y++)
			for (int x = 0; x < SIZE; x++) {
				int c = swatch.getRGB(x, y);
				if (!knife) {
					boolean inside = x >= 3 && x <= 28 && y >= 3 && y <= 28;
					if (!inside) continue;
					int fold = (x - 20) - (y - 3); // folded corner: the triangle above the line from (20,3) to (28,11)
					if (fold > 0) continue;
					if (fold == 0 || fold == -1 && x >= 20) c = 0xFF1A1A1A;
					else if (x >= 20 && y <= 11 && fold > -9 && (28 - x) + (y - 3) < 9) c = shade(c, 1.35); // the fold's back
					else if (x == 3 || x == 28 || y == 3 || y == 28) c = 0xFF1A1A1A;
					out.setRGB(x, y, c);
				} else {
					// blade along the diagonal from (8,23) to (28,3), 5 px each side; a dark handle below it
					double u = (x - 8 + (23 - y)) / 2.0, v = (x - 8 - (23 - y)) / 2.0; // along / across the blade
					boolean blade = u >= 0 && u <= 20 && Math.abs(v) <= 5 - Math.max(0, u - 14) * 0.8;
					boolean handle = u < 0 && u >= -8 && Math.abs(v) <= 2.5;
					if (handle) out.setRGB(x, y, 0xFF2A2420);
					else if (blade) {
						boolean edge = Math.abs(v) > 5 - Math.max(0, u - 14) * 0.8 - 1.1 || u > 19;
						out.setRGB(x, y, edge ? 0xFF1A1A1A : c);
					}
				}
			}
		return out;
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
