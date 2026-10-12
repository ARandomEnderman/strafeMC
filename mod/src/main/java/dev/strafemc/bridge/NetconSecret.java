package dev.strafemc.bridge;

import dev.strafemc.StrafeMc;
import dev.strafemc.config.StrafeConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The password for the CS2 server's net console. CS2 listens for it on every network interface, so strafeMC always
 * starts its server with a random password and sends it first on every connection. It is made once, kept in the
 * strafeMC folder on this PC, and never sent to players: their commands go through the Minecraft server's permission
 * checks (server.ConsoleRelay), which then uses it.
 */
public final class NetconSecret {
	private static final Path FILE = StrafeConfig.RUNTIME_DIR.resolve("netcon-password.txt");
	private static String value;

	private NetconSecret() {}

	public static synchronized String get() {
		if (value != null) return value;
		try {
			if (Files.isRegularFile(FILE)) {
				String s = Files.readString(FILE).trim();
				if (s.matches("[0-9a-f]{32}")) return value = s;
			}
			byte[] b = new byte[16];
			new SecureRandom().nextBytes(b);
			value = HexFormat.of().formatHex(b);
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, value);
		} catch (Exception e) {
			StrafeMc.LOG.warn("[strafemc] netcon password not saved ({}); using one for this session", e.toString());
			if (value == null) {
				byte[] b = new byte[16];
				new SecureRandom().nextBytes(b);
				value = HexFormat.of().formatHex(b);
			}
		}
		return value;
	}

	/** The first line to send on a new net console connection. Harmless on a server started without a password. */
	public static String passLine() {
		return "PASS " + get() + "\n";
	}
}
