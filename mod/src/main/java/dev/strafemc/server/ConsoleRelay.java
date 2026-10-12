package dev.strafemc.server;

import dev.strafemc.StrafeMc;
import dev.strafemc.bridge.Cs2Bridge;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.net.StrafePayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CS2 console commands from players, run on the Minecraft server's own CS2 server (loopback netcon), with permission
 * checks. Non-ops may only read a cvar's value (answered from the bridge, never forwarded). Everything else is op-only
 * (Access.isOperator: ops, or the singleplayer owner). The CS2 output that follows a command goes back to whoever sent it.
 */
public final class ConsoleRelay {
	private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]"), NAME = Pattern.compile("[A-Za-z0-9_+\\-.]+");
	/** CS2 server noise that is never a reply to a command (same filter as the client's netcon view). */
	private static final Pattern NOISE = Pattern.compile(
			"^(\\d+\\.\\d+ (Long frame|UNEXPECTED LONG FRAME)|Framerate spike report|FrameTotal:|\\s*excl ms|\\s*-{4,}|\\s*\\d+\\.\\d+ms\\s+\\d+\\.\\d+%|\\s*$)"
			+ "|SDR RelayNetworkStatus|SDR network config|Cert request for|AuthStatus|Failed to connect to Steam");
	private static final long REPLY_WINDOW_MS = 1500;

	private static MinecraftServer server;
	private static Socket socket;
	private static OutputStream out;
	/** Who gets CS2's output right now, and until when. */
	private static volatile UUID replyTo;
	private static volatile long replyUntil;

	private ConsoleRelay() {}

	public static void handle(ServerPlayer p, String line) {
		server = p.level().getServer();
		String cmd = line.trim();
		if (cmd.isEmpty() || cmd.length() > 512 || cmd.contains("\n") || cmd.contains("\r")) return;
		String[] tok = cmd.split("\\s+");
		boolean op = Access.isOperator(p);
		StrafeMc.LOG.info("[strafemc] console relay from {} (op={}): {}", p.getScoreboardName(), op, cmd);
		if (!op) {
			// reading a cvar is allowed for everyone: answered from the server's bridge values, never sent to CS2
			String v = tok.length == 1 && NAME.matcher(tok[0]).matches() ? Cs2Bridge.INSTANCE.cvar(tok[0].toLowerCase()) : null;
			if (v != null) reply(p, tok[0].toLowerCase() + " = " + v, 0);
			else reply(p, "Only operators can run CS2 commands on this server (you can read a cvar by typing just its name).", 2);
			return;
		}
		if (cmd.contains(";")) { // one command per relay message: no smuggling a second one past the log line
			reply(p, "Send one command at a time.", 2);
			return;
		}
		if (!connect()) {
			reply(p, "This server's CS2 server isn't running (netcon port " + StrafeConfig.netconPort + ").", 2);
			return;
		}
		replyTo = p.getUUID();
		replyUntil = System.currentTimeMillis() + REPLY_WINDOW_MS;
		try {
			synchronized (ConsoleRelay.class) {
				out.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
				out.flush();
			}
		} catch (IOException e) {
			drop();
			reply(p, "Sending to CS2 failed: " + e.getMessage(), 2);
		}
	}

	private static synchronized boolean connect() {
		if (socket != null && socket.isConnected() && !socket.isClosed()) return true;
		try {
			Socket s = new Socket();
			s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), StrafeConfig.netconPort), 1000);
			socket = s;
			out = s.getOutputStream();
			Thread t = new Thread(() -> readLoop(s), "strafemc-relay-netcon");
			t.setDaemon(true);
			t.start();
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	private static synchronized void drop() {
		try {
			if (socket != null) socket.close();
		} catch (IOException ignored) {
		}
		socket = null;
	}

	private static void readLoop(Socket s) {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				String clean = ANSI.matcher(line).replaceAll("");
				if (NOISE.matcher(clean).find() || System.currentTimeMillis() > replyUntil) continue;
				UUID to = replyTo;
				MinecraftServer srv = server;
				if (to == null || srv == null) continue;
				srv.execute(() -> {
					ServerPlayer p = srv.getPlayerList().getPlayer(to);
					if (p != null) reply(p, clean, 0);
				});
			}
		} catch (IOException ignored) {
		}
		drop();
	}

	/** kind: 0 = output, 1 = ok, 2 = error (ConsoleLog colours). */
	private static void reply(ServerPlayer p, String text, int kind) {
		if (ServerPlayNetworking.canSend(p, StrafePayloads.ConsoleReply.TYPE)) ServerPlayNetworking.send(p, new StrafePayloads.ConsoleReply(text, kind));
	}
}
