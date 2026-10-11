package dev.strafemc.client.console;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Scrollback for the in-game console: CS2 server output (netcon) plus local messages. */
public final class ConsoleLog {
	public static final int WHITE = 0xFFD8D8D8, GRAY = 0xFF9A9A9A, ECHO = 0xFFB4C8FF, ERROR = 0xFFFF7070, OK = 0xFF8CE68C, WARN = 0xFFFFC864;
	private static final int MAX = 5000;
	private static final ArrayDeque<Line> LINES = new ArrayDeque<>();
	private static volatile int version;

	public record Line(String text, int color) {}

	private ConsoleLog() {}

	public static synchronized void add(String text, int color) {
		for (String t : text.split("\r?\n", -1)) {
			if (t.isEmpty() && text.length() > 0 && !text.equals("\n")) continue;
			LINES.addLast(new Line(t, color));
			if (LINES.size() > MAX) LINES.removeFirst();
		}
		version++;
	}

	public static void info(String t) {
		add(t, WHITE);
	}

	public static void error(String t) {
		add(t, ERROR);
	}

	public static synchronized List<Line> snapshot() {
		return new ArrayList<>(LINES);
	}

	public static synchronized void clear() {
		LINES.clear();
		version++;
	}

	public static int version() {
		return version;
	}
}
