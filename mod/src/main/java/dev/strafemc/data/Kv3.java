package dev.strafemc.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for KV3 text as written by ValveResourceFormat ("-d" dumps of .vdata_c etc).
 * Values: Map (object), List (array), String, Long, Double, Boolean, null.
 * Typed strings like resource_name:"x" become plain Strings.
 */
public final class Kv3 {
	private final String s;
	private int i;

	private Kv3(String s) {
		this.s = s;
	}

	public static Object parse(String text) {
		Kv3 p = new Kv3(text);
		p.ws();
		return p.value();
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> parseObject(String text) {
		return (Map<String, Object>) parse(text);
	}

	private void ws() {
		while (i < s.length()) {
			char c = s.charAt(i);
			if (Character.isWhitespace(c)) i++;
			else if (s.startsWith("//", i)) i = skipTo("\n", i + 2, 1);
			else if (s.startsWith("/*", i)) i = skipTo("*/", i + 2, 2);
			else if (s.startsWith("<!--", i)) i = skipTo("-->", i + 4, 3);
			else break;
		}
	}

	private int skipTo(String end, int from, int len) {
		int e = s.indexOf(end, from);
		return e < 0 ? s.length() : e + len;
	}

	private Object value() {
		ws();
		char c = s.charAt(i);
		if (c == '{') {
			i++;
			Map<String, Object> m = new LinkedHashMap<>();
			while (true) {
				ws();
				if (s.charAt(i) == '}') {
					i++;
					return m;
				}
				String k = s.charAt(i) == '"' ? string() : bare();
				ws();
				if (s.charAt(i) != '=') throw err("expected '='");
				i++;
				m.put(k, value());
				ws();
				if (i < s.length() && s.charAt(i) == ',') i++;
			}
		}
		if (c == '[') {
			i++;
			List<Object> a = new ArrayList<>();
			while (true) {
				ws();
				if (s.charAt(i) == ']') {
					i++;
					return a;
				}
				a.add(value());
				ws();
				if (s.charAt(i) == ',') i++;
			}
		}
		if (c == '"') return string();
		String t = bare();
		if (t.endsWith(":")) { // typed string: resource_name:"..."
			ws();
			if (i < s.length() && s.charAt(i) == '"') return string();
		}
		switch (t) {
			case "true": return Boolean.TRUE;
			case "false": return Boolean.FALSE;
			case "null": return null;
		}
		try {
			if (t.matches("-?\\d+")) return Long.parseLong(t);
			return Double.parseDouble(t);
		} catch (NumberFormatException e) {
			return t;
		}
	}

	private String bare() {
		int st = i;
		while (i < s.length()) {
			char c = s.charAt(i);
			if (Character.isWhitespace(c) || "{}[]=,\"".indexOf(c) >= 0) break;
			i++;
		}
		if (st == i) throw err("unexpected character");
		return s.substring(st, i);
	}

	private String string() {
		if (s.startsWith("\"\"\"", i)) {
			int e = s.indexOf("\"\"\"", i + 3);
			String r = s.substring(i + 3, e);
			i = e + 3;
			return r;
		}
		StringBuilder b = new StringBuilder();
		i++;
		while (true) {
			char c = s.charAt(i++);
			if (c == '"') return b.toString();
			if (c == '\\') {
				char n = s.charAt(i++);
				b.append(switch (n) {
					case 'n' -> '\n';
					case 't' -> '\t';
					default -> n;
				});
			} else b.append(c);
		}
	}

	private IllegalStateException err(String m) {
		int a = Math.max(0, i - 40), b = Math.min(s.length(), i + 40);
		return new IllegalStateException("KV3 parse error at " + i + ": " + m + " near '" + s.substring(a, b).replace('\n', ' ') + "'");
	}
}
