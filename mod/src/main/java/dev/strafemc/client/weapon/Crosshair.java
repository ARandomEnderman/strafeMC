package dev.strafemc.client.weapon;

import dev.strafemc.config.StrafeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CS2's crosshair, drawn while you hold a CS weapon, from the same cl_crosshair* settings (and crosshair share codes,
 * apply_crosshair_code CSGO-...). Styles: 0 / 3 / 5 dynamic (the gap grows with the gun's inaccuracy), 1 / 4 static,
 * 2 classic split (the inner part stays, the outer part moves out with the spread up to the split distance). Sizes in
 * CS2's units: bar length and thickness in 480-high screen units (YRES), the gap from (4 + gap) half-units out.
 * FLAGGED: these scale formulas and the defaults are the commonly documented CS:GO / CS2 ones, not read from CS2's code.
 */
public final class Crosshair {
	/** Every setting and its default (CS2's, as documented). */
	public static final Map<String, Double> DEFAULTS = new LinkedHashMap<>();

	static {
		// CS2's crosshair settings as its menu has them now (settings_crosshair: 10 styles, sizes in pixels at 1080p).
		// FLAGGED: the defaults are ours; CS2's aren't in its files
		DEFAULTS.put("cl_crosshairstyle", 4.0);
		DEFAULTS.put("cl_crosshair_length", 10.0);
		DEFAULTS.put("cl_crosshair_thickness", 2.0);
		DEFAULTS.put("cl_crosshair_gap", 4.0);
		DEFAULTS.put("cl_crosshair_dynamic_spread_limit", 60.0);
		DEFAULTS.put("cl_crosshaircolor_r", 50.0);
		DEFAULTS.put("cl_crosshaircolor_g", 250.0);
		DEFAULTS.put("cl_crosshaircolor_b", 50.0);
		DEFAULTS.put("cl_crosshaircolor_a", 255.0);
		DEFAULTS.put("cl_crosshair_drawoutline", 1.0);
		DEFAULTS.put("cl_crosshairoutline_r", 0.0);
		DEFAULTS.put("cl_crosshairoutline_g", 0.0);
		DEFAULTS.put("cl_crosshairoutline_b", 0.0);
		DEFAULTS.put("cl_crosshairoutline_a", 255.0);
		DEFAULTS.put("cl_crosshairdot", 0.0);
		DEFAULTS.put("cl_crosshair_t", 0.0);
		DEFAULTS.put("cl_crosshair_recoil", 0.0);
		DEFAULTS.put("cl_crosshair_dynamic_splitdist", 7.0);
		DEFAULTS.put("cl_crosshair_dynamic_splitalpha_innermod", 1.0);
		DEFAULTS.put("cl_crosshair_dynamic_splitalpha_outermod", 0.5);
		DEFAULTS.put("cl_crosshair_dynamic_maxdist_splitratio", 0.35);
		DEFAULTS.put("crosshair", 1.0);
	}

	/** CS2's style names (GameUI_CrosshairStyle0-9). */
	public static final String[] STYLES = {"Dynamic Cross", "Dynamic Circle", "Dynamic Cross (Classic)", "Static Circle", "Static Cross",
			"Dynamic Cross (Legacy)", "Dot Only", "Dynamic Quadrant", "Static Square", "Static Quadrant"};

	private static double legacy;
	private static long lastNs;

	/** The held gun's m_flSpread (0 without one). */
	private static double heldSpread(Minecraft mc) {
		if (!(mc.player.getMainHandItem().getItem() instanceof dev.strafemc.weapon.GunItem g)) return 0;
		var w = dev.strafemc.client.ClientGameplay.stats(g.weaponClass, 0);
		return w != null ? w.spread() : 0;
	}

	/** CS2's colour presets 0-4 (red, green, yellow, blue, cyan); 5 is cl_crosshaircolor_r/g/b. */
	private static final int[][] PRESETS = {{250, 50, 50}, {50, 250, 50}, {250, 250, 50}, {50, 50, 250}, {50, 250, 250}};

	private Crosshair() {}

	public static double get(String name) {
		if (!migrated) migrate();
		Double v = StrafeConfig.crosshair.get(name);
		return v != null ? v : DEFAULTS.getOrDefault(name, 0.0);
	}

	private static boolean migrated;

	/**
	 * Settings saved under CS2's old names (cl_crosshairsize, cl_crosshairgap, cl_crosshairthickness, alpha, colour
	 * presets) turned into today's pixel settings once, so a crosshair made before keeps its look.
	 */
	private static synchronized void migrate() {
		if (migrated) return;
		migrated = true;
		Map<String, Double> c = StrafeConfig.crosshair;
		double y = 1080 / 480.0;
		boolean changed = false;
		if (c.containsKey("cl_crosshairsize")) {
			c.putIfAbsent("cl_crosshair_length", (double) Math.round(c.remove("cl_crosshairsize") * y));
			changed = true;
		}
		if (c.containsKey("cl_crosshairgap")) {
			c.putIfAbsent("cl_crosshair_gap", (double) Math.max(-10, Math.round((4 + c.remove("cl_crosshairgap")) * y / 2)));
			changed = true;
		}
		if (c.containsKey("cl_crosshairthickness")) {
			c.putIfAbsent("cl_crosshair_thickness", (double) Math.max(1, Math.round(c.remove("cl_crosshairthickness") * y)));
			changed = true;
		}
		Double use = c.remove("cl_crosshairusealpha"), alpha = c.remove("cl_crosshairalpha");
		if (use != null || alpha != null) {
			c.putIfAbsent("cl_crosshaircolor_a", use != null && use == 0 ? 255.0 : alpha != null ? alpha : 255.0);
			changed = true;
		}
		Double preset = c.remove("cl_crosshaircolor");
		if (preset != null && preset < 5) {
			int[] rgb = PRESETS[(int) Math.max(0, preset)];
			c.put("cl_crosshaircolor_r", (double) rgb[0]);
			c.put("cl_crosshaircolor_g", (double) rgb[1]);
			c.put("cl_crosshaircolor_b", (double) rgb[2]);
		}
		if (preset != null || c.remove("cl_crosshair_outlinethickness") != null) changed = true;
		if (changed) save();
	}

	/** Draws it (true), or false to leave Minecraft's crosshair. */
	public static boolean render(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !mc.options.getCameraType().isFirstPerson() || !(mc.player.getMainHandItem().getItem() instanceof dev.strafemc.weapon.GunItem))
			return false;
		if (get("crosshair") == 0) return true;
		draw(g, mc.getWindow().getWidth() / 2.0, mc.getWindow().getHeight() / 2.0, true);
		return true;
	}

	/** The editor's preview: centred at a GUI point, standing still (no spread, no recoil). */
	public static void preview(GuiGraphicsExtractor g, float guiX, float guiY) {
		double k = Minecraft.getInstance().getWindow().getGuiScale();
		draw(g, guiX * k, guiY * k, false);
	}

	/**
	 * Draws it centred at a screen pixel; live: with the gun's spread and recoil. Shapes by style: crosses (0, 2, 4, 5),
	 * circles (1, 3), a dot (6), quadrants (7, 9: a ring cut into four corner arcs), a square (8). FLAGGED: CS2 draws
	 * these in code that isn't in its files; the shapes follow the style names and which settings its menu shows for
	 * each, sizes as pixels at 1080p.
	 */
	private static void draw(GuiGraphicsExtractor g, double cx, double cy, boolean live) {
		Minecraft mc = Minecraft.getInstance();
		float k = (float) mc.getWindow().getGuiScale();
		int h = mc.getWindow().getHeight();
		double px = h / 1080.0, yres = h / 480.0;
		int style = Math.max(0, Math.min(9, (int) get("cl_crosshairstyle")));
		boolean dynamic = style == 0 || style == 1 || style == 2 || style == 5 || style == 7;
		int len = (int) Math.round(get("cl_crosshair_length") * px);
		int thick = Math.max(1, (int) Math.round(get("cl_crosshair_thickness") * px));
		double gap = get("cl_crosshair_gap") * px;
		// the dynamic opening: (inaccuracy + spread) x 320 / tan(half of 90 degrees) in 480-high units (FLAGGED: CS:GO's,
		// from memory), capped by the spread limit; the editor's preview pulses a made-up spread to show it
		double inacc = live ? GunClient.INSTANCE.inaccuracy() + heldSpread(mc) : 0.02 + 0.02 * Math.sin(System.nanoTime() / 1e9 * 2.5);
		double spread = dynamic ? Math.min(inacc * 320 * yres, get("cl_crosshair_dynamic_spread_limit") * px) : 0;
		if (style == 5) { // Legacy / shot feedback: opens at once, closes smoothly
			double dt = lastNs == 0 ? 0 : Math.min(0.1, (System.nanoTime() - lastNs) / 1e9);
			lastNs = System.nanoTime();
			legacy = spread > legacy ? spread : legacy + (spread - legacy) * Math.min(1, dt * 6);
			spread = legacy;
		}
		double fov = Math.toRadians(mc.options.fov().get()), pxPerTan = (h / 2.0) / Math.tan(fov / 2);
		if (live && get("cl_crosshair_recoil") != 0) { // where the bullets go: the full aim punch, not the camera's share
			double[] off = GunClient.INSTANCE.crosshairRecoil();
			cy += Math.tan(Math.toRadians(off[0])) * pxPerTan;
			cx += Math.tan(Math.toRadians(off[1])) * pxPerTan;
		}
		int[] rgb = {(int) get("cl_crosshaircolor_r"), (int) get("cl_crosshaircolor_g"), (int) get("cl_crosshaircolor_b")};
		int alpha = (int) get("cl_crosshaircolor_a");
		int ol = get("cl_crosshair_drawoutline") != 0 ? Math.max(1, (int) Math.round(px)) : 0;
		outline = (Math.min(255, (int) get("cl_crosshairoutline_a")) << 24) | ((int) get("cl_crosshairoutline_r") & 255) << 16
				| ((int) get("cl_crosshairoutline_g") & 255) << 8 | (int) get("cl_crosshairoutline_b") & 255;
		var pose = g.pose();
		pose.pushMatrix();
		pose.scale(1 / k, 1 / k); // real pixels: crisp at any GUI scale
		int x = (int) Math.round(cx), y = (int) Math.round(cy);
		boolean noTop = get("cl_crosshair_t") != 0;
		switch (style) {
			case 1, 3 -> ring(g, x, y, gap + spread, thick, 0, rgb, alpha, ol, false); // circles
			case 6 -> { // dot only
				int d = Math.max(2, thick);
				box(g, x - d / 2, y - d / 2, d, d, rgb, alpha, ol);
			}
			case 7 -> ring(g, x, y, gap + spread, Math.max(thick, len > 0 ? Math.min(len, thick * 2) : thick), 0.5, rgb, alpha, ol, noTop); // dynamic quadrant
			case 9 -> ring(g, x, y, gap, thick, get("cl_crosshair_dynamic_maxdist_splitratio"), rgb, alpha, ol, false); // static quadrant
			case 8 -> { // static square: a hollow square `gap` out
				int r = (int) Math.round(gap) + thick;
				box(g, x - r, y - r, 2 * r, thick, rgb, alpha, ol);
				box(g, x - r, y + r - thick, 2 * r, thick, rgb, alpha, ol);
				box(g, x - r, y - r + thick, thick, 2 * r - 2 * thick, rgb, alpha, ol);
				box(g, x + r - thick, y - r + thick, thick, 2 * r - 2 * thick, rgb, alpha, ol);
			}
			case 2 -> { // classic: the inner part stays, the outer moves out with the spread up to the split distance
				double ratio = get("cl_crosshair_dynamic_maxdist_splitratio");
				int inner = (int) Math.round(len * (1 - ratio)), outer = len - inner;
				int split = (int) Math.round(Math.min(spread, get("cl_crosshair_dynamic_splitdist") * px));
				int ia = (int) (alpha * get("cl_crosshair_dynamic_splitalpha_innermod")), oa = (int) (alpha * get("cl_crosshair_dynamic_splitalpha_outermod"));
				bars(g, x, y, (int) Math.round(gap), inner, thick, rgb, ia, ol, noTop);
				bars(g, x, y, (int) Math.round(gap) + inner + split, outer, thick, rgb, oa, ol, noTop);
			}
			default -> bars(g, x, y, (int) Math.round(gap + spread), len, thick, rgb, alpha, ol, noTop); // crosses
		}
		if (get("cl_crosshairdot") != 0 && style != 6) box(g, x - thick / 2, y - thick / 2, thick, thick, rgb, alpha, ol);
		pose.popMatrix();
	}

	private static int outline;

	/** The four bars (no top one in T style), from dist out, len long, thick wide. */
	private static void bars(GuiGraphicsExtractor g, int x, int y, int dist, int len, int thick, int[] rgb, int alpha, int ol, boolean noTop) {
		if (len <= 0) return;
		int half = thick / 2;
		box(g, x + dist, y - half, len, thick, rgb, alpha, ol); // right
		box(g, x - dist - len, y - half, len, thick, rgb, alpha, ol); // left
		box(g, x - half, y + dist, thick, len, rgb, alpha, ol); // bottom
		if (!noTop) box(g, x - half, y - dist - len, thick, len, rgb, alpha, ol); // top
	}

	/**
	 * A ring of radius r (inner edge) and width w, drawn row by row; cut > 0 leaves a gap of that fraction of each
	 * quarter around the up / down / left / right axes (the quadrants: four corner arcs). noTop drops the top one.
	 */
	private static void ring(GuiGraphicsExtractor g, int x, int y, double r, int w, double cut, int[] rgb, int alpha, int ol, boolean noTop) {
		double r0 = Math.max(0.5, r), r1 = r0 + w;
		int col = Math.min(255, alpha) << 24 | (rgb[0] & 255) << 16 | (rgb[1] & 255) << 8 | rgb[2] & 255;
		double halfCut = Math.toRadians(90 * Math.max(0, Math.min(1, cut)) / 2);
		for (int pass = ol > 0 ? 0 : 1; pass < 2; pass++) {
			double a = pass == 0 ? r0 - ol : r0, b = pass == 0 ? r1 + ol : r1;
			int c = pass == 0 ? outline : col;
			for (int dy = (int) Math.floor(-b); dy <= Math.ceil(b); dy++) {
				double yy = dy + 0.5, outer = b * b - yy * yy;
				if (outer < 0) continue;
				double xo = Math.sqrt(outer), xi = a * a - yy * yy > 0 ? Math.sqrt(a * a - yy * yy) : 0;
				for (int side = -1; side <= 1; side += 2) {
					int from = (int) Math.round(side < 0 ? -xo : xi), to = (int) Math.round(side < 0 ? -xi : xo);
					if (to <= from) continue;
					if (halfCut > 0 || noTop) { // cut away the parts near the axes, pixel by pixel
						for (int px = from; px < to; px++) {
							double ang = Math.atan2(Math.abs(yy), Math.abs(px + 0.5)); // 0 = horizontal, pi/2 = vertical
							boolean nearAxis = ang < halfCut || ang > Math.PI / 2 - halfCut;
							if (nearAxis || noTop && yy < 0 && ang > Math.PI / 4) continue;
							g.fill(x + px, y + dy, x + px + 1, y + dy + 1, c);
						}
					} else g.fill(x + from, y + dy, x + to, y + dy + 1, c);
				}
			}
		}
	}

	private static void box(GuiGraphicsExtractor g, int x, int y, int w, int h, int[] rgb, int alpha, int ol) {
		if (ol > 0) g.fill(x - ol, y - ol, x + w + ol, y + h + ol, outline);
		g.fill(x, y, x + w, y + h, Math.min(255, alpha) << 24 | (rgb[0] & 255) << 16 | (rgb[1] & 255) << 8 | rgb[2] & 255);
	}

	private static final String DICT = "ABCDEFGHJKLMNOPQRSTUVWXYZabcdefhijkmnopqrstuvwxyz23456789";

	/**
	 * A CS2 crosshair share code (CSGO-xxxxx-xxxxx-xxxxx-xxxxx-xxxxx: base 57, 18 bytes) into the cl_crosshair* settings;
	 * false if it isn't one. Field layout as the public share-code decoders read it (FLAGGED).
	 */
	public static boolean apply(String code) {
		String s = code.trim().replaceFirst("^CSGO", "").replace("-", "");
		if (s.length() != 25) return false;
		BigInteger n = BigInteger.ZERO, base = BigInteger.valueOf(57);
		for (int i = s.length() - 1; i >= 0; i--) {
			int d = DICT.indexOf(s.charAt(i));
			if (d < 0) return false;
			n = n.multiply(base).add(BigInteger.valueOf(d));
		}
		byte[] raw = n.toByteArray(), b = new byte[18];
		System.arraycopy(raw, Math.max(0, raw.length - 18), b, Math.max(0, 18 - raw.length), Math.min(18, raw.length));
		Map<String, Double> c = StrafeConfig.crosshair;
		// the code holds the old units (size / thickness in 480-high units, gap from 4 half-units); today's settings are
		// pixels at 1080p (FLAGGED: our conversion)
		double y = 1080 / 480.0;
		c.put("cl_crosshair_gap", Math.max(-10, Math.round((4 + b[2] / 10.0) * y / 2 * 10) / 10.0));
		c.put("cl_crosshaircolor_r", (double) (b[4] & 255));
		c.put("cl_crosshaircolor_g", (double) (b[5] & 255));
		c.put("cl_crosshaircolor_b", (double) (b[6] & 255));
		c.put("cl_crosshaircolor_a", (double) (b[7] & 255));
		c.put("cl_crosshair_dynamic_splitdist", (double) (b[8] & 0x7f));
		c.put("cl_crosshair_recoil", (double) ((b[8] >> 7) & 1));
		int preset = b[10] & 7;
		if (preset < 5) { // a preset colour: its RGB
			c.put("cl_crosshaircolor_r", (double) PRESETS[preset][0]);
			c.put("cl_crosshaircolor_g", (double) PRESETS[preset][1]);
			c.put("cl_crosshaircolor_b", (double) PRESETS[preset][2]);
		}
		c.put("cl_crosshair_drawoutline", (b[10] & 8) != 0 ? 1.0 : 0.0);
		c.put("cl_crosshair_dynamic_splitalpha_innermod", ((b[10] & 255) >> 4) / 10.0);
		c.put("cl_crosshair_dynamic_splitalpha_outermod", (b[11] & 0xf) / 10.0);
		c.put("cl_crosshair_dynamic_maxdist_splitratio", ((b[11] & 255) >> 4) / 10.0);
		c.put("cl_crosshair_thickness", (double) Math.max(1, Math.round((b[12] & 255) / 10.0 * y)));
		int flags = (b[13] & 255) >> 4;
		c.put("cl_crosshairdot", (flags & 1) != 0 ? 1.0 : 0.0);
		if ((flags & 4) == 0) c.put("cl_crosshaircolor_a", 255.0); // alpha off: opaque
		c.put("cl_crosshair_t", (flags & 8) != 0 ? 1.0 : 0.0);
		c.put("cl_crosshairstyle", (double) ((b[13] & 0xf) >> 1));
		c.put("cl_crosshair_length", (double) Math.round((b[14] & 255) / 10.0 * y));
		save();
		return true;
	}

	/** The current settings as a CS2 share code (the same layout apply reads; byte 0 is the sum of the rest). FLAGGED. */
	public static String export() {
		int[] b = new int[18];
		b[1] = 1;
		double y = 1080 / 480.0, oldGap = get("cl_crosshair_gap") * 2 / y - 4;
		b[2] = (int) Math.round(oldGap * 10) & 255;
		b[3] = 2; // outline thickness 1
		b[4] = (int) get("cl_crosshaircolor_r") & 255;
		b[5] = (int) get("cl_crosshaircolor_g") & 255;
		b[6] = (int) get("cl_crosshaircolor_b") & 255;
		b[7] = (int) get("cl_crosshaircolor_a") & 255;
		b[8] = ((int) get("cl_crosshair_dynamic_splitdist") & 0x7f) | (get("cl_crosshair_recoil") != 0 ? 0x80 : 0);
		b[9] = b[2]; // the fixed gap (weapon gap off): the same
		b[10] = 5 | (get("cl_crosshair_drawoutline") != 0 ? 8 : 0) // colour 5: its own RGB
				| ((int) Math.round(get("cl_crosshair_dynamic_splitalpha_innermod") * 10) & 0xf) << 4;
		b[11] = ((int) Math.round(get("cl_crosshair_dynamic_splitalpha_outermod") * 10) & 0xf)
				| ((int) Math.round(get("cl_crosshair_dynamic_maxdist_splitratio") * 10) & 0xf) << 4;
		b[12] = (int) Math.round(get("cl_crosshair_thickness") / y * 10) & 255;
		b[13] = (((int) get("cl_crosshairstyle") & 7) << 1) | (get("cl_crosshairdot") != 0 ? 0x10 : 0) | 0x40 | (get("cl_crosshair_t") != 0 ? 0x80 : 0);
		b[14] = (int) Math.round(get("cl_crosshair_length") / y * 10) & 255;
		int sum = 0;
		for (int i = 1; i < 18; i++) sum += b[i];
		b[0] = sum & 255;
		byte[] raw = new byte[19]; // a leading zero keeps it positive
		for (int i = 0; i < 18; i++) raw[i + 1] = (byte) b[i];
		BigInteger n = new BigInteger(raw), base = BigInteger.valueOf(57);
		StringBuilder out = new StringBuilder("CSGO");
		for (int i = 0; i < 25; i++) {
			if (i % 5 == 0) out.append('-');
			BigInteger[] qr = n.divideAndRemainder(base);
			out.append(DICT.charAt(qr[1].intValue()));
			n = qr[0];
		}
		return out.toString();
	}

	/** Saves the settings (a failed write only loses them on restart). */
	public static void save() {
		try {
			StrafeConfig.save();
		} catch (Exception e) {
			dev.strafemc.StrafeMc.LOG.warn("[strafemc] crosshair settings: {}", e.toString());
		}
	}
}
