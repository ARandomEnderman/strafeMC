package dev.strafemc.client.console;

import dev.strafemc.StrafeMc;
import dev.strafemc.bridge.Cs2Bridge;
import dev.strafemc.bridge.NetconSecret;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.setup.Cs2Finder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Starts / stops the local CS2 server from inside Minecraft, only when the player asks (console command, quick-menu
 * button, or the advanced "Host a CS2 server" option when opening their own world). Two ways:
 * a launcher script set up on this PC (launcherPath), which runs in its own window with all of its own checks; or
 * "Host a CS2 server" (advanced, Options > strafeMC): CS2's own dedicated server, started from the player's CS2 folder
 * with launch options only (nothing in the CS2 folder is changed): insecure, LAN-only, and its net console protected
 * by a random password. A watchdog checks it really runs insecure (and stops it otherwise); it stops when Minecraft closes.
 */
public final class ServerLauncher {
	private static final int GAME_PORT = 27015;
	private static volatile Process hosted;

	private ServerLauncher() {}

	private static boolean scriptConfigured() {
		return !StrafeConfig.launcherPath.isBlank() && Files.isRegularFile(Path.of(StrafeConfig.launcherPath));
	}

	/** True when this PC can start a CS2 server: a launcher script, or hosting turned on with a CS2 folder. */
	public static boolean configured() {
		return scriptConfigured() || StrafeConfig.hostServer && executable() != null;
	}

	private static Path executable() {
		if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return null; // Windows only for now
		Path cs2 = Cs2Finder.find(StrafeConfig.cs2Path);
		if (cs2 == null) return null;
		Path exe = cs2.resolve("game").resolve("bin").resolve("win64").resolve("cs2.exe");
		return Files.isRegularFile(exe) ? exe : null;
	}

	public static void start() {
		if (!configured()) {
			ConsoleLog.add("No CS2 server is set up on this PC. Hosting one is an advanced option in Options > strafeMC. CS2's default settings are used.",
					ConsoleLog.WARN);
			return;
		}
		if (Cs2Bridge.INSTANCE.connected() || hosted != null && hosted.isAlive()) {
			ConsoleLog.add("The CS2 server is already running.", ConsoleLog.OK);
			return;
		}
		if (scriptConfigured()) startScript();
		else startHosted();
	}

	private static void startScript() {
		Path bat = Path.of(StrafeConfig.launcherPath);
		try {
			// a separate visible window, so the safety monitor's output stays readable and the server outlives this call
			new ProcessBuilder("cmd.exe", "/c", "start", "\"strafeMC - CS2 server\"", "cmd.exe", "/k", bat.toString(), "-Metamod",
					"-NetconPassword", NetconSecret.get()).directory(bat.getParent().toFile()).start();
			ConsoleLog.add("Starting the CS2 server via " + bat.getFileName() + ". It connects in ~10 s.", ConsoleLog.OK);
			StrafeMc.LOG.info("[strafemc] user started CS2 server via {}", bat);
		} catch (Exception e) {
			ConsoleLog.error("Could not start the launcher: " + e.getMessage());
		}
	}

	private static void startHosted() {
		Path exe = executable();
		List<String> cmd = List.of(exe.toString(), "-dedicated", "-insecure", "-port", String.valueOf(GAME_PORT),
				"-netconport", String.valueOf(StrafeConfig.netconPort), "-netconpassword", NetconSecret.get(), "-maxplayers", "16",
				"+ip", "127.0.0.1", "+sv_lan", "1", "+sv_hibernate_when_empty", "0", "+bot_quota", "0", "+map", "de_dust2");
		try {
			Path log = StrafeConfig.RUNTIME_DIR.resolve("cs2-server.log");
			Process p = new ProcessBuilder(cmd).directory(exe.getParent().toFile()).redirectErrorStream(true)
					.redirectOutput(log.toFile()).start();
			hosted = p;
			StrafeMc.LOG.info("[strafemc] hosting CS2 server: {} (pid {}, output in {})", exe, p.pid(), log);
			ConsoleLog.add("Starting your CS2 server (insecure, LAN only). It connects in ~20 s.", ConsoleLog.OK);
			ConsoleLog.add("Note: other people on your network can reach this CS2 server's console. Read more in Options > strafeMC > About hosting.",
					ConsoleLog.WARN);
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				if (p.isAlive()) p.destroyForcibly();
			}, "strafemc-cs2-stop"));
			Thread w = new Thread(() -> watchdog(p), "strafemc-cs2-watchdog");
			w.setDaemon(true);
			w.start();
		} catch (Exception e) {
			ConsoleLog.error("Could not start CS2: " + e.getMessage());
		}
	}

	/** Every 5 s: the server must report "insecure" and no Steam identity, confirmed within 2 minutes, or it is stopped. */
	private static void watchdog(Process p) {
		long deadline = System.currentTimeMillis() + 120_000;
		boolean confirmed = false;
		while (p.isAlive()) {
			String st = status();
			if (st != null && st.contains("version")) {
				String ver = line(st, "version"), sid = line(st, "steamid");
				if (!ver.contains("insecure") || !sid.isEmpty() && !sid.contains("[I:1:0]")) {
					stopHosted("it isn't running in insecure mode (" + ver.trim() + ")");
					return;
				}
				if (!confirmed) StrafeMc.LOG.info("[strafemc] hosted CS2 server confirmed insecure: {}", ver.trim());
				confirmed = true;
			} else if (!confirmed && System.currentTimeMillis() > deadline) {
				stopHosted("insecure mode couldn't be confirmed within 2 minutes");
				return;
			}
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				return;
			}
		}
		StrafeMc.LOG.info("[strafemc] hosted CS2 server exited ({})", p.exitValue());
	}

	private static void stopHosted(String why) {
		Process p = hosted;
		if (p != null) p.destroyForcibly();
		StrafeMc.LOG.error("[strafemc] stopped the hosted CS2 server: {}", why);
		ConsoleLog.error("Stopped your CS2 server: " + why + ".");
	}

	private static String line(String text, String key) {
		for (String l : text.split("\n")) if (l.trim().startsWith(key)) return l;
		return "";
	}

	/** The server's "status" output over the net console, or null if it isn't reachable yet. */
	private static String status() {
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), StrafeConfig.netconPort), 1000);
			s.setSoTimeout(1500);
			OutputStream out = s.getOutputStream();
			out.write((NetconSecret.passLine() + "status\n").getBytes(StandardCharsets.UTF_8));
			out.flush();
			StringBuilder b = new StringBuilder();
			BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
			long end = System.currentTimeMillis() + 1500;
			String l;
			while (System.currentTimeMillis() < end && (l = r.readLine()) != null) {
				b.append(l).append('\n');
				if (l.startsWith("#end")) break;
			}
			return b.toString();
		} catch (Exception e) {
			return null;
		}
	}

	public static void stop() {
		if (!NetconTransport.INSTANCE.connected() && !Cs2Bridge.INSTANCE.connected() && (hosted == null || !hosted.isAlive())) {
			ConsoleLog.add("The CS2 server isn't running.", ConsoleLog.WARN);
			return;
		}
		ConsoleCommands.quiet("quit");
		ConsoleLog.add("Asked the CS2 server to quit.", ConsoleLog.OK);
	}
}
