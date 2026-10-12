package dev.strafemc.client.console;

import dev.strafemc.StrafeMc;
import dev.strafemc.bridge.Cs2Bridge;
import dev.strafemc.config.StrafeConfig;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Starts / stops the local CS2 server from inside Minecraft (singleplayer). Starting always goes through the user's
 * launch-cs2.bat, so every safety check (copy path, firewall rules, Steam closed, insecure mode, socket watch) still applies.
 * Only ever runs when the user asks (console command or quick-menu button).
 */
public final class ServerLauncher {
	private ServerLauncher() {}

	/** A local CS2 server is an optional extra for hosts: only offered when a launcher is set up. */
	public static boolean configured() {
		return !StrafeConfig.launcherPath.isBlank() && Files.isRegularFile(Path.of(StrafeConfig.launcherPath));
	}

	public static void start() {
		if (!configured()) {
			ConsoleLog.add("No local CS2 server is set up on this PC (optional, for hosts). CS2's default settings are used.", ConsoleLog.WARN);
			return;
		}
		if (Cs2Bridge.INSTANCE.connected()) {
			ConsoleLog.add("The CS2 server is already running.", ConsoleLog.OK);
			return;
		}
		Path bat = Path.of(StrafeConfig.launcherPath);
		if (!Files.isRegularFile(bat)) {
			ConsoleLog.error("Launcher not found: " + bat + " (set launcherPath in the strafeMC config)");
			return;
		}
		try {
			// a separate visible window, so the safety monitor's output stays readable and the server outlives this call
			new ProcessBuilder("cmd.exe", "/c", "start", "\"strafeMC - CS2 server\"", "cmd.exe", "/k", bat.toString(), "-Metamod")
					.directory(bat.getParent().toFile()).start();
			ConsoleLog.add("Starting the CS2 server via " + bat.getFileName() + " (Steam must be closed). It connects in ~10 s.", ConsoleLog.OK);
			StrafeMc.LOG.info("[strafemc] user started CS2 server via {}", bat);
		} catch (Exception e) {
			ConsoleLog.error("Could not start the launcher: " + e.getMessage());
		}
	}

	public static void stop() {
		if (!NetconTransport.INSTANCE.connected() && !Cs2Bridge.INSTANCE.connected()) {
			ConsoleLog.add("The CS2 server isn't running.", ConsoleLog.WARN);
			return;
		}
		ConsoleCommands.quiet("quit");
		ConsoleLog.add("Asked the CS2 server to quit.", ConsoleLog.OK);
	}
}
