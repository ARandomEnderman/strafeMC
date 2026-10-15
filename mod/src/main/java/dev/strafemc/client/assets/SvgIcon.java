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
 * Minimal SVG rasterizer for CS2's Panorama HUD icons (VRF exports .vsvg as plain SVG).
 * Paths (M/L/H/V/C/S/Q/T/Z), rects, polygons, circles, ellipses and lines with transforms, opacity and linear gradients.
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
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		org.w3c.dom.Element root = parseXml(s);
		java.util.Map<String, org.w3c.dom.Element> ids = new java.util.HashMap<>();
		collectIds(root, ids);
		draw(g, root, t, 1, "#FFFFFF", null, scale, ids);
		g.dispose();
		return img;
	}

	private static org.w3c.dom.Element parseXml(String s) throws Exception {
		var f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
		f.setNamespaceAware(false);
		f.setValidating(false);
		f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); // Illustrator's DOCTYPE: never fetched
		f.setExpandEntityReferences(false);
		return f.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(s))).getDocumentElement();
	}

	private static void collectIds(org.w3c.dom.Element e, java.util.Map<String, org.w3c.dom.Element> ids) {
		if (!e.getAttribute("id").isEmpty()) ids.put(e.getAttribute("id"), e);
		for (org.w3c.dom.Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
			if (n instanceof org.w3c.dom.Element c) collectIds(c, ids);
	}

	/** An attribute, or the same property in the element's style="...". */
	private static String prop(org.w3c.dom.Element e, String name) {
		if (e.hasAttribute(name)) return e.getAttribute(name).trim();
		for (String decl : e.getAttribute("style").split(";")) {
			int c = decl.indexOf(':');
			if (c > 0 && decl.substring(0, c).trim().equals(name)) return decl.substring(c + 1).trim();
		}
		return null;
	}

	private static double num(org.w3c.dom.Element e, String name, double def) {
		String v = prop(e, name);
		if (v == null || v.isEmpty()) return def;
		try {
			return Double.parseDouble(v.replace("px", ""));
		} catch (NumberFormatException x) {
			return def;
		}
	}

	/**
	 * Draws an element and its children: paths, rects, polygons / polylines, circles, ellipses and lines, with their
	 * transforms, fill / stroke (inherited from groups; gradients by their stops), opacity and fill-rule. Symbols, defs,
	 * masks and clip paths aren't drawn (only through a use, which these icons don't have).
	 */
	private static void draw(Graphics2D g, org.w3c.dom.Element e, AffineTransform parent, double opacity, String fill, String stroke, double scale,
							 java.util.Map<String, org.w3c.dom.Element> ids) {
		String tag = e.getTagName();
		if (tag.equals("symbol") || tag.equals("defs") || tag.equals("mask") || tag.equals("clipPath") || tag.endsWith("Gradient")
				|| tag.equals("style") || tag.equals("title")) return;
		AffineTransform t = new AffineTransform(parent);
		String tr = e.getAttribute("transform");
		if (!tr.isEmpty()) t.concatenate(transform(tr));
		opacity *= num(e, "opacity", 1);
		String f = prop(e, "fill"), st = prop(e, "stroke");
		if (f != null) fill = f;
		if (st != null) stroke = st;
		java.awt.Shape shape = switch (tag) {
			case "path" -> {
				Path2D p = parse(e.getAttribute("d"));
				p.setWindingRule("evenodd".equals(prop(e, "fill-rule")) ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
				yield p;
			}
			case "rect" -> {
				double rx = num(e, "rx", num(e, "ry", 0)), ry = num(e, "ry", rx);
				double x = num(e, "x", 0), y = num(e, "y", 0), w = num(e, "width", 0), h = num(e, "height", 0);
				yield rx > 0 || ry > 0 ? new java.awt.geom.RoundRectangle2D.Double(x, y, w, h, rx * 2, ry * 2) : new java.awt.geom.Rectangle2D.Double(x, y, w, h);
			}
			case "polygon", "polyline" -> {
				Matcher m = TOKEN.matcher(e.getAttribute("points"));
				java.util.List<Double> v = new java.util.ArrayList<>();
				while (m.find()) v.add(Double.parseDouble(m.group()));
				Path2D.Double p = new Path2D.Double("evenodd".equals(prop(e, "fill-rule")) ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
				for (int i = 0; i + 1 < v.size(); i += 2) {
					if (i == 0) p.moveTo(v.get(i), v.get(i + 1));
					else p.lineTo(v.get(i), v.get(i + 1));
				}
				if (tag.equals("polygon")) p.closePath();
				yield p;
			}
			case "circle" -> {
				double r = num(e, "r", 0);
				yield new java.awt.geom.Ellipse2D.Double(num(e, "cx", 0) - r, num(e, "cy", 0) - r, 2 * r, 2 * r);
			}
			case "ellipse" -> {
				double rx = num(e, "rx", 0), ry = num(e, "ry", 0);
				yield new java.awt.geom.Ellipse2D.Double(num(e, "cx", 0) - rx, num(e, "cy", 0) - ry, 2 * rx, 2 * ry);
			}
			case "line" -> new java.awt.geom.Line2D.Double(num(e, "x1", 0), num(e, "y1", 0), num(e, "x2", 0), num(e, "y2", 0));
			default -> null;
		};
		if (shape != null) {
			java.awt.Shape s = t.createTransformedShape(shape);
			java.awt.Paint fp = tag.equals("line") || tag.equals("polyline") && fill == null ? null : paint(fill, opacity * num(e, "fill-opacity", 1), t, ids);
			if (fp != null) {
				g.setPaint(fp);
				g.fill(s);
			}
			java.awt.Paint sp = paint(stroke, opacity * num(e, "stroke-opacity", 1), t, ids);
			if (sp != null) {
				double sw = num(e, "stroke-width", 1) * scale * Math.sqrt(Math.abs(t.getDeterminant()) / (scale * scale));
				String cap = prop(e, "stroke-linecap"), join = prop(e, "stroke-linejoin");
				g.setStroke(new java.awt.BasicStroke((float) sw,
						"round".equals(cap) ? java.awt.BasicStroke.CAP_ROUND : "square".equals(cap) ? java.awt.BasicStroke.CAP_SQUARE : java.awt.BasicStroke.CAP_BUTT,
						"round".equals(join) ? java.awt.BasicStroke.JOIN_ROUND : "bevel".equals(join) ? java.awt.BasicStroke.JOIN_BEVEL : java.awt.BasicStroke.JOIN_MITER,
						(float) Math.max(1, num(e, "stroke-miterlimit", 4))));
				g.setPaint(sp);
				g.draw(s);
			}
		}
		for (org.w3c.dom.Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
			if (n instanceof org.w3c.dom.Element c) draw(g, c, t, opacity, fill, stroke, scale, ids);
	}

	/** A fill / stroke value as a paint: a colour, or a linear gradient by url(#id); null for none. */
	private static java.awt.Paint paint(String v, double opacity, AffineTransform t, java.util.Map<String, org.w3c.dom.Element> ids) {
		if (v == null) return null;
		v = v.trim();
		if (v.isEmpty() || v.equals("none")) return null;
		if (v.startsWith("url(")) {
			org.w3c.dom.Element gr = ids.get(v.replaceAll("^url\\(\\s*#|\\s*\\)$", ""));
			if (gr == null || !gr.getTagName().equals("linearGradient")) return null;
			java.util.List<Float> offs = new java.util.ArrayList<>();
			java.util.List<Color> cols = new java.util.ArrayList<>();
			for (org.w3c.dom.Node n = gr.getFirstChild(); n != null; n = n.getNextSibling())
				if (n instanceof org.w3c.dom.Element s && s.getTagName().equals("stop")) {
					float o = (float) Math.max(offs.isEmpty() ? 0 : offs.getLast() + 1e-4, Math.min(1, num(s, "offset", 0)));
					if (o > 1) break;
					Color c = colour(prop(s, "stop-color"), opacity * num(s, "stop-opacity", 1));
					offs.add(o);
					cols.add(c == null ? new Color(0, 0, 0, 0) : c);
				}
			if (cols.isEmpty()) return null;
			if (cols.size() == 1) return cols.getFirst();
			java.awt.geom.Point2D a = t.transform(new java.awt.geom.Point2D.Double(num(gr, "x1", 0), num(gr, "y1", 0)), null),
					b = t.transform(new java.awt.geom.Point2D.Double(num(gr, "x2", 1), num(gr, "y2", 0)), null);
			if (a.distance(b) < 1e-6) return cols.getLast();
			float[] fo = new float[offs.size()];
			for (int i = 0; i < fo.length; i++) fo[i] = offs.get(i);
			return new java.awt.LinearGradientPaint(a, b, fo, cols.toArray(Color[]::new));
		}
		return colour(v, opacity);
	}

	/** #rgb / #rrggbb / black / white with an opacity; other named colours as white (these icons are white). */
	private static Color colour(String v, double opacity) {
		if (v == null) return null;
		v = v.trim().toLowerCase(java.util.Locale.ROOT);
		int a = (int) Math.round(255 * Math.max(0, Math.min(1, opacity)));
		if (v.equals("none") || v.isEmpty()) return null;
		int rgb = 0xFFFFFF;
		if (v.equals("black")) rgb = 0;
		else if (v.startsWith("#") && v.length() == 7) rgb = Integer.parseInt(v.substring(1), 16);
		else if (v.startsWith("#") && v.length() == 4)
			rgb = Integer.parseInt("" + v.charAt(1) + v.charAt(1) + v.charAt(2) + v.charAt(2) + v.charAt(3) + v.charAt(3), 16);
		return new Color(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, a);
	}

	/** SVG transform list: matrix, translate, scale, rotate, skewX, skewY. */
	private static AffineTransform transform(String s) {
		AffineTransform t = new AffineTransform();
		Matcher m = Pattern.compile("(matrix|translate|scale|rotate|skewX|skewY)\\s*\\(([^)]*)\\)").matcher(s);
		while (m.find()) {
			Matcher nm = TOKEN.matcher(m.group(2));
			java.util.List<Double> v = new java.util.ArrayList<>();
			while (nm.find()) v.add(Double.parseDouble(nm.group()));
			double a0 = v.isEmpty() ? 0 : v.get(0);
			switch (m.group(1)) {
				case "matrix" -> {
					if (v.size() == 6) t.concatenate(new AffineTransform(v.get(0), v.get(1), v.get(2), v.get(3), v.get(4), v.get(5)));
				}
				case "translate" -> t.translate(a0, v.size() > 1 ? v.get(1) : 0);
				case "scale" -> t.scale(a0, v.size() > 1 ? v.get(1) : a0);
				case "rotate" -> {
					if (v.size() >= 3) t.rotate(Math.toRadians(a0), v.get(1), v.get(2));
					else t.rotate(Math.toRadians(a0));
				}
				case "skewX" -> t.shear(Math.tan(Math.toRadians(a0)), 0);
				case "skewY" -> t.shear(0, Math.tan(Math.toRadians(a0)));
				default -> {}
			}
		}
		return t;
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
