package dev.strafemc.client.assets;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal SVG path rasterizer for CS2's Panorama HUD icons (VRF exports .vsvg as plain SVG with path data).
 * Supports M/L/H/V/C/S/Q/T/Z (absolute and relative) and fill-rule; fills in white so the HUD can tint it.
 */
public final class SvgIcon {
	private static final Pattern VIEWBOX = Pattern.compile("viewBox=\"([-0-9.\\s]+)\"");
	private static final Pattern PATH = Pattern.compile("<path([^>]*)>");
	private static final Pattern ATTR_D = Pattern.compile("\\sd=\"([^\"]*)\"");
	private static final Pattern TOKEN = Pattern.compile("[MmLlHhVvCcSsQqTtZzAa]|-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");

	private SvgIcon() {}

	public static BufferedImage rasterize(Path svg, int size) throws Exception {
		return rasterize(svg, size, false);
	}

	/** heightOnly: the image is `size` high and as wide as the SVG's aspect needs (wide weapon silhouettes). */
	public static BufferedImage rasterize(Path svg, int size, boolean heightOnly) throws Exception {
		String s = Files.readString(svg);
		double vx = 0, vy = 0, vw = 32, vh = 32;
		Matcher vb = VIEWBOX.matcher(s);
		if (vb.find()) {
			String[] p = vb.group(1).trim().split("\\s+");
			vx = Double.parseDouble(p[0]);
			vy = Double.parseDouble(p[1]);
			vw = Double.parseDouble(p[2]);
			vh = Double.parseDouble(p[3]);
		}
		int w = heightOnly ? Math.max(1, (int) Math.round(size * vw / vh)) : size;
		BufferedImage img = new BufferedImage(w, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		double scale = heightOnly ? size / vh : size / Math.max(vw, vh);
		AffineTransform t = new AffineTransform();
		t.translate((w - vw * scale) / 2, (size - vh * scale) / 2);
		t.scale(scale, scale);
		t.translate(-vx, -vy);
		g.setColor(Color.WHITE);
		Matcher pm = PATH.matcher(s);
		while (pm.find()) {
			String attrs = pm.group(1);
			Matcher d = ATTR_D.matcher(attrs);
			if (!d.find()) continue;
			Path2D path = parse(d.group(1));
			path.setWindingRule(attrs.contains("evenodd") ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
			g.fill(t.createTransformedShape(path));
		}
		g.dispose();
		return img;
	}

	/**
	 * A coloured SVG drawn additively, as Panorama's "additive" panels draw CS2's HUD art (the kill cards): fills and
	 * strokes in their own colours, clipped by the SVG's mask, then turned into white whose alpha is how bright it was
	 * (black adds nothing). Height `size`, width by the aspect.
	 */
	public static BufferedImage rasterizeAdditive(Path svg, int size) throws Exception {
		String s = Files.readString(svg);
		double vx = 0, vy = 0, vw = 32, vh = 32;
		Matcher vb = VIEWBOX.matcher(s);
		if (vb.find()) {
			String[] p = vb.group(1).trim().split("\s+");
			vx = Double.parseDouble(p[0]);
			vy = Double.parseDouble(p[1]);
			vw = Double.parseDouble(p[2]);
			vh = Double.parseDouble(p[3]);
		}
		int w = Math.max(1, (int) Math.round(size * vw / vh));
		double scale = size / vh;
		AffineTransform t = new AffineTransform();
		t.translate((w - vw * scale) / 2, 0);
		t.scale(scale, scale);
		t.translate(-vx, -vy);
		BufferedImage img = new BufferedImage(w, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		// the mask's shapes clip everything drawn after it
		java.awt.geom.Area clip = null;
		int maskEnd = s.indexOf("</mask>");
		if (maskEnd > 0) {
			Matcher mm = PATH.matcher(s.substring(s.indexOf("<mask"), maskEnd));
			while (mm.find()) {
				Matcher d = ATTR_D.matcher(mm.group(1));
				if (!d.find()) continue;
				java.awt.geom.Area a = new java.awt.geom.Area(t.createTransformedShape(parse(d.group(1))));
				if (clip == null) clip = a;
				else clip.add(a);
			}
		}
		if (clip != null) g.setClip(clip);
		Matcher pm = PATH.matcher(maskEnd > 0 ? s.substring(maskEnd) : s);
		while (pm.find()) {
			String attrs = pm.group(1);
			Matcher d = ATTR_D.matcher(attrs);
			if (!d.find()) continue;
			Path2D path = parse(d.group(1));
			path.setWindingRule(attrs.contains("evenodd") ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
			java.awt.Shape shape = t.createTransformedShape(path);
			Color fill = colour(attrs, "fill"), stroke = colour(attrs, "stroke");
			if (fill != null) {
				g.setColor(fill);
				g.fill(shape);
			}
			if (stroke != null) {
				Matcher sw = Pattern.compile("stroke-width=\"([0-9.]+)\"").matcher(attrs);
				g.setStroke(new java.awt.BasicStroke((float) ((sw.find() ? Double.parseDouble(sw.group(1)) : 1) * scale)));
				g.setColor(stroke);
				g.draw(shape);
			}
		}
		g.dispose();
		for (int y = 0; y < size; y++)
			for (int x = 0; x < w; x++) {
				int c = img.getRGB(x, y), a = c >>> 24;
				int lum = ((c >> 16 & 255) * 2126 + (c >> 8 & 255) * 7152 + (c & 255) * 722) / 10000;
				img.setRGB(x, y, (a * lum / 255) << 24 | 0xFFFFFF);
			}
		return img;
	}

	/** An attribute's colour (none / black / white / #rrggbb), or null for none or missing. */
	private static Color colour(String attrs, String name) {
		Matcher m = Pattern.compile("\s" + name + "=\"([^\"]*)\"").matcher(attrs);
		if (!m.find()) return null;
		String v = m.group(1).trim().toLowerCase(java.util.Locale.ROOT);
		return switch (v) {
			case "none", "" -> null;
			case "black" -> Color.BLACK;
			case "white" -> Color.WHITE;
			default -> v.startsWith("#") && v.length() == 7 ? new Color(Integer.parseInt(v.substring(1), 16)) : Color.WHITE;
		};
	}

	static Path2D parse(String d) {
		Path2D.Double p = new Path2D.Double();
		Matcher m = TOKEN.matcher(d);
		java.util.List<String> tok = new java.util.ArrayList<>();
		while (m.find()) tok.add(m.group());
		double x = 0, y = 0, sx = 0, sy = 0, cx = 0, cy = 0; // current, subpath start, last control point
		char cmd = 'M', prev = ' ';
		int i = 0;
		while (i < tok.size()) {
			String t = tok.get(i);
			if (Character.isLetter(t.charAt(0))) {
				cmd = t.charAt(0);
				i++;
				if (cmd == 'Z' || cmd == 'z') {
					p.closePath();
					x = sx;
					y = sy;
					prev = cmd;
					continue;
				}
			}
			boolean rel = Character.isLowerCase(cmd);
			double ox = rel ? x : 0, oy = rel ? y : 0;
			switch (Character.toUpperCase(cmd)) {
				case 'M' -> {
					x = ox + num(tok, i++);
					y = oy + num(tok, i++);
					p.moveTo(x, y);
					sx = x;
					sy = y;
					cmd = rel ? 'l' : 'L'; // further pairs are implicit lineTo
				}
				case 'L' -> {
					x = ox + num(tok, i++);
					y = oy + num(tok, i++);
					p.lineTo(x, y);
				}
				case 'H' -> {
					x = ox + num(tok, i++);
					p.lineTo(x, y);
				}
				case 'V' -> {
					y = oy + num(tok, i++);
					p.lineTo(x, y);
				}
				case 'C' -> {
					double x1 = ox + num(tok, i++), y1 = oy + num(tok, i++);
					cx = ox + num(tok, i++);
					cy = oy + num(tok, i++);
					x = ox + num(tok, i++);
					y = oy + num(tok, i++);
					p.curveTo(x1, y1, cx, cy, x, y);
				}
				case 'S' -> {
					boolean refl = "CcSs".indexOf(prev) >= 0;
					double x1 = refl ? 2 * x - cx : x, y1 = refl ? 2 * y - cy : y;
					cx = ox + num(tok, i++);
					cy = oy + num(tok, i++);
					x = ox + num(tok, i++);
					y = oy + num(tok, i++);
					p.curveTo(x1, y1, cx, cy, x, y);
				}
				case 'Q' -> {
					cx = ox + num(tok, i++);
					cy = oy + num(tok, i++);
					x = ox + num(tok, i++);
					y = oy + num(tok, i++);
					p.quadTo(cx, cy, x, y);
				}
				case 'T' -> {
					boolean refl = "QqTt".indexOf(prev) >= 0;
					cx = refl ? 2 * x - cx : x;
					cy = refl ? 2 * y - cy : y;
					x = ox + num(tok, i++);
					y = oy + num(tok, i++);
					p.quadTo(cx, cy, x, y);
				}
				case 'A' -> { // arcs are rare in these icons: approximate with a line to the end point
					i += 5;
					x = ox + num(tok, i++);
					y = oy + num(tok, i++);
					p.lineTo(x, y);
				}
				default -> i++;
			}
			prev = cmd;
		}
		return p;
	}

	private static double num(java.util.List<String> t, int i) {
		return i < t.size() ? Double.parseDouble(t.get(i)) : 0;
	}
}
