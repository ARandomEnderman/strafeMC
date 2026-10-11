package dev.strafemc.client.console;

import dev.strafemc.StrafeMc;
import dev.strafemc.config.StrafeConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loopback-only connection to CS2's net console (-netconport). Everything the server prints streams back here,
 * so the console also shows the live server log. Also learns every cvar/command from `cvarlist` for autocomplete.
 */
public final class NetconTransport implements ConsoleTransport {
	public static final NetconTransport INSTANCE = new NetconTransport();
	private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");
	private static final Pattern CVARLIST = Pattern.compile("^(\\S+)\\s+:\\s+(.*?)\\s+:\\s+(.*?)\\s+:\\s*(.*)$");

	/** name -> "value | flags | description" for autocomplete. */
	public final Map<String, String[]> known = new ConcurrentSkipListMap<>();

	private Socket socket;
	private OutputStream out;
	private volatile boolean connected;
	private long lastAttempt;
	private volatile long captureUntil;
	/** Server output is shown only shortly after one of our own commands (unless showServerLog), so the log spam stays hidden. */
	private volatile long replyUntil;
	private static final long REPLY_WINDOW_MS = 1500;
	/** CS2 server noise that is never a reply to a command: frame-spike reports + their timing tables, Steam relay retries. */
	private static final Pattern NOISE = Pattern.compile(
			"^(\\d+\\.\\d+ (Long frame|UNEXPECTED LONG FRAME)|Framerate spike report|FrameTotal:|\\s*excl ms|\\s*-{4,}|\\s*\\d+\\.\\d+ms\\s+\\d+\\.\\d+%|\\s*$)"
			+ "|SDR RelayNetworkStatus|SDR network config|Cert request for|AuthStatus|Failed to connect to Steam");

	private NetconTransport() {}

	@Override
	public boolean connected() {
		return connected;
	}

	@Override
	public String describe() {
		return "CS2 netcon 127.0.0.1:" + StrafeConfig.netconPort + (connected ? " (connected)" : " (not connected)");
	}

	public synchronized void ensureConnected() {
		if (connected || System.currentTimeMillis() - lastAttempt < 2000) return;
		lastAttempt = System.currentTimeMillis();
		try {
			Socket s = new Socket();
			s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), StrafeConfig.netconPort), 1000);
			socket = s;
			out = s.getOutputStream();
			connected = true;
			Thread t = new Thread(() -> readLoop(s), "strafemc-netcon");
			t.setDaemon(true);
			t.start();
			ConsoleLog.add("Connected to " + describe(), ConsoleLog.OK);
			StrafeMc.LOG.info("[strafemc] netcon connected");
			if (known.isEmpty()) learnCommands();
		} catch (IOException e) {
			connected = false;
			ConsoleLog.add("CS2 server not reachable on netcon port " + StrafeConfig.netconPort + " (start it with launch-cs2.bat -Metamod)", ConsoleLog.WARN);
		}
	}

	/** Runs `cvarlist` and swallows its output into {@link #known} instead of the scrollback. */
	public void learnCommands() {
		captureUntil = System.currentTimeMillis() + 6000;
		raw("cvarlist");
	}

	@Override
	public void send(String command) {
		ensureConnected();
		if (!connected) return;
		replyUntil = System.currentTimeMillis() + REPLY_WINDOW_MS;
		raw(command);
	}

	private synchronized void raw(String command) {
		try {
			out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
			out.flush();
		} catch (IOException e) {
			drop("send failed: " + e.getMessage());
		}
	}

	private void readLoop(Socket s) {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				line = ANSI.matcher(line).replaceAll("");
				if (System.currentTimeMillis() < captureUntil) {
					Matcher m = CVARLIST.matcher(line);
					if (m.matches()) {
						known.put(m.group(1), new String[] {m.group(2).trim(), m.group(3).trim(), m.group(4).trim()});
						continue;
					}
					if (line.equals("cvar list") || line.startsWith("-----")) continue;
					if (line.matches("^\\s*\\d+ total convars.*")) {
						captureUntil = 0;
						StrafeMc.LOG.info("[strafemc] learned {} CS2 commands/cvars for autocomplete", known.size());
						continue;
					}
				}
				if (!dev.strafemc.config.StrafeConfig.showServerLog && (System.currentTimeMillis() > replyUntil || NOISE.matcher(line).find())) continue;
				int color = line.contains("Unknown command") || line.toLowerCase().contains("error") ? ConsoleLog.ERROR : ConsoleLog.WHITE;
				ConsoleLog.add(line, color);
			}
		} catch (IOException e) {
			// fall through
		}
		drop("connection closed");
	}

	private synchronized void drop(String why) {
		if (!connected) return;
		connected = false;
		try {
			socket.close();
		} catch (IOException ignored) {
		}
		ConsoleLog.add("Lost CS2 netcon: " + why, ConsoleLog.WARN);
	}

	public Map<String, String[]> knownSnapshot() {
		return new TreeMap<>(known);
	}
}
