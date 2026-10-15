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
		DEFAULTS.put("cl_crosshairstyle", 4.0);
		DEFAULTS.put("cl_crosshairsize", 5.0);
		DEFAULTS.put("cl_crosshairthickness", 0.5);
		DEFAULTS.put("cl_crosshairgap", 1.0);
		DEFAULTS.put("cl_crosshaircolor", 1.0);
		DEFAULTS.put("cl_crosshaircolor_r", 50.0);
		DEFAULTS.put("cl_crosshaircolor_g", 250.0);
		DEFAULTS.put("cl_crosshaircolor_b", 50.0);
		DEFAULTS.put("cl_crosshairalpha", 200.0);
		DEFAULTS.put("cl_crosshairusealpha", 1.0);
		DEFAULTS.put("cl_crosshairdot", 0.0);
		DEFAULTS.put("cl_crosshair_drawoutline", 1.0);
		DEFAULTS.put("cl_crosshair_outlinethickness", 1.0);
		DEFAULTS.put("cl_crosshair_t", 0.0);
		DEFAULTS.put("cl_crosshair_recoil", 0.0);
		DEFAULTS.put("cl_crosshair_dynamic_splitdist", 7.0);
		DEFAULTS.put("cl_crosshair_dynamic_splitalpha_innermod", 1.0);
		DEFAULTS.put("cl_crosshair_dynamic_splitalpha_outermod", 0.5);
		DEFAULTS.put("cl_crosshair_dynamic_maxdist_splitratio", 0.35);
		DEFAULTS.put("crosshair", 1.0);
	}

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
		Double v = StrafeConfig.crosshair.get(name);
		return v != null ? v : DEFAULTS.getOrDefault(name, 0.0);
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

	/** Draws it centred at a screen pixel; live: with the gun's spread and recoil. */
	private static void draw(GuiGraphicsExtractor g, double cx, double cy, boolean live) {
		Minecraft mc = Minecraft.getInstance();
		float k = (float) mc.getWindow().getGuiScale();
		int h = mc.getWindow().getHeight();
		double yres = h / 480.0;
		int style = (int) get("cl_crosshairstyle");
		boolean dynamic = style == 0 || style == 2 || style == 3 || style == 5;
		// 0 / 1 "Default": CS2's own look (length, thickness and gap fixed; colour and alpha still yours)
		boolean defaultLook = style == 0 || style == 1;
		int len = (int) Math.round((defaultLook ? DEFAULTS.get("cl_crosshairsize") : get("cl_crosshairsize")) * yres);
		int thick = Math.max(1, (int) Math.round((defaultLook ? DEFAULTS.get("cl_crosshairthickness") : get("cl_crosshairthickness")) * yres));
		double gapPx = (4 + (defaultLook ? DEFAULTS.get("cl_crosshairgap") : get("cl_crosshairgap"))) * yres / 2;
		// the gun's inaccuracy as pixels on screen (the spread cone at the current field of view)
		double fov = Math.toRadians(mc.options.fov().get());
		double pxPerTan = (h / 2.0) / Math.tan(fov / 2);
		// the dynamic gap as CS:GO drew it: (inaccuracy + spread) x 320 / tan(half the 90 degree FOV), in 480-high units
		// (FLAGGED: from memory of CS:GO's crosshair code); the editor's preview pulses a made-up spread to show it
		double inacc = live ? GunClient.INSTANCE.inaccuracy() + heldSpread(mc) : 0.02 + 0.02 * Math.sin(System.nanoTime() / 1e9 * 2.5);
		double spreadPx = dynamic ? inacc * 320 * yres : 0;
		if (style == 5) { // Legacy: opens at once, closes smoothly
			double dt = lastNs == 0 ? 0 : Math.min(0.1, (System.nanoTime() - lastNs) / 1e9);
			lastNs = System.nanoTime();
			legacy = spreadPx > legacy ? spreadPx : legacy + (spreadPx - legacy) * Math.min(1, dt * 6);
			spreadPx = legacy;
		}
		// recoil: the crosshair shows where the bullets go (the full aim punch, not the camera's share)
		if (live && get("cl_crosshair_recoil") != 0) {
			double[] off = GunClient.INSTANCE.crosshairRecoil();
			cy += Math.tan(Math.toRadians(off[0])) * pxPerTan;
			cx += Math.tan(Math.toRadians(off[1])) * pxPerTan;
		}
		int colourIdx = (int) get("cl_crosshaircolor");
		int[] rgb = colourIdx >= 0 && colourIdx < 5 ? PRESETS[colourIdx]
				: new int[] {(int) get("cl_crosshaircolor_r"), (int) get("cl_crosshaircolor_g"), (int) get("cl_crosshaircolor_b")};
		int alpha = get("cl_crosshairusealpha") != 0 ? (int) get("cl_crosshairalpha") : 255;
		boolean outline = get("cl_crosshair_drawoutline") != 0;
		int ol = outline ? Math.max(1, (int) Math.round(get("cl_crosshair_outlinethickness") * h / 1080.0)) : 0;
		var pose = g.pose();
		pose.pushMatrix();
		pose.scale(1 / k, 1 / k); // real pixels: crisp at any GUI scale
		int x = (int) Math.round(cx), y = (int) Math.round(cy);
		if (style == 2) { // classic split: the inner part stays, the outer moves out with the spread (up to the split distance)
			double ratio = get("cl_crosshair_dynamic_maxdist_splitratio");
			int inner = (int) Math.round(len * (1 - ratio)), outer = len - inner;
			int split = (int) Math.round(Math.min(spreadPx, get("cl_crosshair_dynamic_splitdist") * yres));
			int ia = (int) (alpha * get("cl_crosshair_dynamic_splitalpha_innermod")), oa = (int) (alpha * get("cl_crosshair_dynamic_splitalpha_outermod"));
			bars(g, x, y, (int) Math.round(gapPx), inner, thick, rgb, ia, ol);
			bars(g, x, y, (int) Math.round(gapPx) + inner + split, outer, thick, rgb, oa, ol);
		} else bars(g, x, y, (int) Math.round(gapPx + spreadPx), len, thick, rgb, alpha, ol);
		if (get("cl_crosshairdot") != 0) box(g, x - thick / 2, y - thick / 2, thick, thick, rgb, alpha, ol);
		pose.popMatrix();
	}

	/** The four bars (no top one with cl_crosshair_t), from dist out, len long, thick wide. */
	private static void bars(GuiGraphicsExtractor g, int x, int y, int dist, int len, int thick, int[] rgb, int alpha, int ol) {
		if (len <= 0) return;
		int half = thick / 2;
		box(g, x + dist, y - half, len, thick, rgb, alpha, ol); // right
		box(g, x - dist - len, y - half, len, thick, rgb, alpha, ol); // left
		box(g, x - half, y + dist, thick, len, rgb, alpha, ol); // bottom
		if (get("cl_crosshair_t") == 0) box(g, x - half, y - dist - len, thick, len, rgb, alpha, ol); // top
	}

	private static void box(GuiGraphicsExtractor g, int x, int y, int w, int h, int[] rgb, int alpha, int ol) {
		if (ol > 0) g.fill(x - ol, y - ol, x + w + ol, y + h + ol, Math.min(255, alpha) << 24);
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
		c.put("cl_crosshairgap", b[2] / 10.0);
		c.put("cl_crosshair_outlinethickness", (b[3] & 255) / 2.0);
		c.put("cl_crosshaircolor_r", (double) (b[4] & 255));
		c.put("cl_crosshaircolor_g", (double) (b[5] & 255));
		c.put("cl_crosshaircolor_b", (double) (b[6] & 255));
		c.put("cl_crosshairalpha", (double) (b[7] & 255));
		c.put("cl_crosshair_dynamic_splitdist", (double) (b[8] & 0x7f));
		c.put("cl_crosshair_recoil", (double) ((b[8] >> 7) & 1));
		c.put("cl_crosshaircolor", (double) (b[10] & 7));
		c.put("cl_crosshair_drawoutline", (b[10] & 8) != 0 ? 1.0 : 0.0);
		c.put("cl_crosshair_dynamic_splitalpha_innermod", ((b[10] & 255) >> 4) / 10.0);
		c.put("cl_crosshair_dynamic_splitalpha_outermod", (b[11] & 0xf) / 10.0);
		c.put("cl_crosshair_dynamic_maxdist_splitratio", ((b[11] & 255) >> 4) / 10.0);
		c.put("cl_crosshairthickness", (b[12] & 255) / 10.0);
		int flags = (b[13] & 255) >> 4;
		c.put("cl_crosshairdot", (flags & 1) != 0 ? 1.0 : 0.0);
		c.put("cl_crosshairusealpha", (flags & 4) != 0 ? 1.0 : 0.0);
		c.put("cl_crosshair_t", (flags & 8) != 0 ? 1.0 : 0.0);
		c.put("cl_crosshairstyle", (double) ((b[13] & 0xf) >> 1));
		c.put("cl_crosshairsize", (b[14] & 255) / 10.0);
		save();
		return true;
	}

	/** The current settings as a CS2 share code (the same layout apply reads; byte 0 is the sum of the rest). FLAGGED. */
	public static String export() {
		int[] b = new int[18];
		b[1] = 1;
		b[2] = (int) Math.round(get("cl_crosshairgap") * 10) & 255;
		b[3] = (int) Math.round(get("cl_crosshair_outlinethickness") * 2) & 255;
		b[4] = (int) get("cl_crosshaircolor_r") & 255;
		b[5] = (int) get("cl_crosshaircolor_g") & 255;
		b[6] = (int) get("cl_crosshaircolor_b") & 255;
		b[7] = (int) get("cl_crosshairalpha") & 255;
		b[8] = ((int) get("cl_crosshair_dynamic_splitdist") & 0x7f) | (get("cl_crosshair_recoil") != 0 ? 0x80 : 0);
		b[9] = (int) Math.round(get("cl_crosshairgap") * 10) & 255; // the fixed gap (weapon gap off): the same
		b[10] = ((int) get("cl_crosshaircolor") & 7) | (get("cl_crosshair_drawoutline") != 0 ? 8 : 0)
				| ((int) Math.round(get("cl_crosshair_dynamic_splitalpha_innermod") * 10) & 0xf) << 4;
		b[11] = ((int) Math.round(get("cl_crosshair_dynamic_splitalpha_outermod") * 10) & 0xf)
				| ((int) Math.round(get("cl_crosshair_dynamic_maxdist_splitratio") * 10) & 0xf) << 4;
		b[12] = (int) Math.round(get("cl_crosshairthickness") * 10) & 255;
		b[13] = (((int) get("cl_crosshairstyle") & 7) << 1) | (get("cl_crosshairdot") != 0 ? 0x10 : 0) | (get("cl_crosshairusealpha") != 0 ? 0x40 : 0)
				| (get("cl_crosshair_t") != 0 ? 0x80 : 0);
		b[14] = (int) Math.round(get("cl_crosshairsize") * 10) & 255;
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
