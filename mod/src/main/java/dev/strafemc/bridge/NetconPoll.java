package dev.strafemc.bridge;

import dev.strafemc.config.StrafeConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the CS2 server's settings and player counts over its net console, for a plain CS2 server without the bridge
 * plugin (what players host). Asks for every watched setting by name ("sv_gravity" answers "sv_gravity = 800") and
 * for "status" now and then (bot / human counts). Only used while the plugin is silent.
 */
final class NetconPoll {
	/** The settings strafeMC reads (same list the bridge plugin watches). */
	static final Set<String> WATCHED = new LinkedHashSet<>(Cs2Defaults.VALUES.keySet());

	static {
		WATCHED.add("weapon_recoil_scale");
		WATCHED.add("weapon_recoil_decay_coefficient");
	}

	private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");
	private static final Pattern VALUE = Pattern.compile("^([a-z0-9_]+) = (.*?)\\s*$");
	private static final Pattern PLAYERS = Pattern.compile("^players\\s*:\\s*(\\d+) humans?, (\\d+) bots?");

	private final BiConsumer<String, String> onCvar;
	private final BiConsumer<Integer, Integer> onPlayers;
	private Socket socket;
	private OutputStream out;
	private volatile long lastReplyMs;
	private int round;

	NetconPoll(BiConsumer<String, String> onCvar, BiConsumer<Integer, Integer> onPlayers) {
		this.onCvar = onCvar;
		this.onPlayers = onPlayers;
	}

	boolean alive() {
		return System.currentTimeMillis() - lastReplyMs < 5000;
	}

	/** One poll round (called about every second by the bridge heartbeat while the plugin is silent). */
	void poll() {
		if (!connect()) return;
		StringBuilder b = new StringBuilder();
		if (round++ % 2 == 0) for (String n : WATCHED) b.append(n).append('\n');
		if (round % 10 == 1) b.append("status\n");
		if (b.isEmpty()) return;
		try {
			out.write(b.toString().getBytes(StandardCharsets.UTF_8));
			out.flush();
		} catch (IOException e) {
			close();
		}
	}

	void close() {
		try {
			if (socket != null) socket.close();
		} catch (IOException ignored) {
		}
		socket = null;
	}

	private boolean connect() {
		if (socket != null && !socket.isClosed()) return true;
		try {
			Socket s = new Socket();
			s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), StrafeConfig.netconPort), 500);
			s.getOutputStream().write(NetconSecret.passLine().getBytes(StandardCharsets.UTF_8));
			socket = s;
			out = s.getOutputStream();
			Thread t = new Thread(() -> read(s), "strafemc-netcon-poll");
			t.setDaemon(true);
			t.start();
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	private void read(Socket s) {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				line = ANSI.matcher(line).replaceAll("").trim();
				Matcher m = VALUE.matcher(line);
				if (m.matches() && WATCHED.contains(m.group(1))) {
					lastReplyMs = System.currentTimeMillis();
					onCvar.accept(m.group(1), m.group(2));
					continue;
				}
				m = PLAYERS.matcher(line);
				if (m.find()) {
					lastReplyMs = System.currentTimeMillis();
					onPlayers.accept(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
				}
			}
		} catch (IOException ignored) {
		}
		if (socket == s) socket = null;
	}
}
