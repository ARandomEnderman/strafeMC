package dev.strafemc.bridge;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.strafemc.StrafeMc;
import dev.strafemc.config.StrafeConfig;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loopback UDP link to the Cs2McBridge plugin inside the CS2 dedicated server (127.0.0.1 only).
 * Keeps a live copy of the server's cvars; the plugin pushes "cvar <name> <value>" on every change.
 * The last known values are cached on disk so movement still uses real game values when CS2 is not running.
 */
public final class Cs2Bridge {
	public static final Cs2Bridge INSTANCE = new Cs2Bridge();
	private static final Gson GSON = new Gson();
	private static final Path CACHE = StrafeConfig.RUNTIME_DIR.resolve("cvars-cache.json");
	/** Values seen right after a fresh CS2 server start = the game's defaults (used by strafemc_reset_movement). */
	private static final Path DEFAULTS = StrafeConfig.RUNTIME_DIR.resolve("cvars-defaults.json");
	private volatile Map<String, String> defaults = Map.of();
	private volatile int bots = -1, humans = -1;

	/** CS2 server population (from the plugin heartbeat), -1 if unknown. */
	public int bots() {
		return bots;
	}

	public int humans() {
		return humans;
	}

	private final Map<String, String> cvars = new ConcurrentHashMap<>();
	private volatile long lastRxMs;
	private volatile boolean dirty;
	private volatile int version;
	private DatagramSocket sock;
	private InetSocketAddress server;
	private boolean wasConnected;
	/** Set by the client to show a popup when the CS2 server connects / disconnects. */
	public static volatile java.util.function.Consumer<Boolean> onConnectionChange = c -> {};

	private Cs2Bridge() {}

	public void start() {
		loadCache();
		try {
			sock = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
			server = new InetSocketAddress(InetAddress.getLoopbackAddress(), StrafeConfig.bridgePort);
		} catch (IOException e) {
			StrafeMc.LOG.error("[strafemc] bridge socket failed", e);
			return;
		}
		Thread rx = new Thread(this::receiveLoop, "strafemc-bridge-rx");
		rx.setDaemon(true);
		rx.start();
		Thread hb = new Thread(this::heartbeatLoop, "strafemc-bridge-hb");
		hb.setDaemon(true);
		hb.start();
		StrafeMc.LOG.info("[strafemc] bridge client on {} -> {}", sock.getLocalSocketAddress(), server);
	}

	public boolean connected() {
		return System.currentTimeMillis() - lastRxMs < 3000;
	}

	public int version() {
		return version;
	}

	public boolean hasCvars() {
		return !cvars.isEmpty();
	}

	/** Raw cvar string, or null if the server never reported it. */
	public String cvar(String name) {
		return cvars.get(name);
	}

	public void send(String msg) {
		if (sock == null) return;
		byte[] b = msg.getBytes(StandardCharsets.UTF_8);
		try {
			sock.send(new DatagramPacket(b, b.length, server));
		} catch (IOException e) {
			if (StrafeConfig.debug) StrafeMc.LOG.debug("[strafemc] bridge send failed: {}", e.toString());
		}
	}

	private void receiveLoop() {
		byte[] buf = new byte[65535];
		while (true) {
			try {
				DatagramPacket p = new DatagramPacket(buf, buf.length);
				sock.receive(p);
				if (!p.getAddress().isLoopbackAddress()) continue;
				lastRxMs = System.currentTimeMillis();
				handle(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
			} catch (IOException e) {
				StrafeMc.LOG.warn("[strafemc] bridge receive error: {}", e.toString());
			}
		}
	}

	private void handle(String msg) {
		int sp = msg.indexOf(' ');
		String verb = sp < 0 ? msg : msg.substring(0, sp);
		String arg = sp < 0 ? "" : msg.substring(sp + 1);
		switch (verb) {
			case "cvars" -> {
				Map<String, String> m = GSON.fromJson(arg, new TypeToken<Map<String, String>>() {}.getType());
				int changed = 0;
				for (var e : m.entrySet()) {
					if (e.getValue().startsWith("<")) continue; // "<missing>" etc.
					if (!e.getValue().equals(cvars.put(e.getKey(), e.getValue()))) changed++;
				}
				if (changed > 0) bump("snapshot (" + changed + " changed)");
			}
			case "defaults" -> {
				Map<String, String> m = GSON.fromJson(arg, new TypeToken<Map<String, String>>() {}.getType());
				m.values().removeIf(v -> v.startsWith("<"));
				if (!m.equals(defaults)) saveDefaults(m);
			}
			case "cvar" -> {
				int s2 = arg.indexOf(' ');
				if (s2 > 0) {
					String n = arg.substring(0, s2), v = arg.substring(s2 + 1);
					cvars.put(n, v);
					bump(n + " = " + v);
				}
			}
			case "pong" -> {
				String[] t = arg.split(" ");
				if (t.length >= 5) {
					bots = Integer.parseInt(t[3]);
					humans = Integer.parseInt(t[4]);
				}
			}
			case "subbed" -> {}
			default -> StrafeMc.LOG.info("[strafemc] bridge: {}", msg.length() > 300 ? msg.substring(0, 300) + "..." : msg);
		}
	}

	private void bump(String what) {
		version++;
		dirty = true;
		StrafeMc.LOG.info("[strafemc] cvar update: {}", what);
	}

	private void heartbeatLoop() {
		int n = 0;
		while (true) {
			try {
				boolean c = connected();
				if (c != wasConnected) {
					StrafeMc.LOG.info(c ? "[strafemc] bridge CONNECTED to CS2 server" : "[strafemc] bridge lost CS2 server (using cached cvars)");
					wasConnected = c;
					onConnectionChange.accept(c);
				}
				send("sub");
				if (!c || n % 10 == 0) send("cvars");
				if (!c || n % 30 == 0) send("defaults");
				send("ping " + n++);
				if (dirty) {
					dirty = false;
					saveCache();
				}
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	public Map<String, String> defaults() {
		return defaults;
	}

	/** Defaults come from the plugin, which snapshots its watched cvars while the server boots. */
	private void saveDefaults(Map<String, String> m) {
		try {
			defaults = new HashMap<>(m);
			Files.writeString(DEFAULTS, GSON.toJson(defaults));
			StrafeMc.LOG.info("[strafemc] recorded {} CS2 default cvar values to {}", m.size(), DEFAULTS);
		} catch (IOException e) {
			StrafeMc.LOG.warn("[strafemc] could not write defaults: {}", e.toString());
		}
	}

	private void loadCache() {
		try {
			if (Files.exists(DEFAULTS)) defaults = GSON.fromJson(Files.readString(DEFAULTS), new TypeToken<Map<String, String>>() {}.getType());
		} catch (Exception e) {
			StrafeMc.LOG.warn("[strafemc] defaults unreadable: {}", e.toString());
		}
		try {
			if (Files.exists(CACHE)) {
				Map<String, String> m = GSON.fromJson(Files.readString(CACHE), new TypeToken<Map<String, String>>() {}.getType());
				if (m != null) cvars.putAll(m);
				StrafeMc.LOG.info("[strafemc] loaded {} cached cvars from {}", cvars.size(), CACHE);
			} else {
				StrafeMc.LOG.warn("[strafemc] no cvar cache yet; start CS2 via launch-cs2.bat -Metamod once so real values can be read");
			}
		} catch (Exception e) {
			StrafeMc.LOG.error("[strafemc] cvar cache unreadable", e);
		}
	}

	private void saveCache() {
		try {
			Files.createDirectories(CACHE.getParent());
			Files.writeString(CACHE, GSON.toJson(new HashMap<>(cvars)));
		} catch (IOException e) {
			StrafeMc.LOG.warn("[strafemc] cvar cache write failed: {}", e.toString());
		}
	}
}
