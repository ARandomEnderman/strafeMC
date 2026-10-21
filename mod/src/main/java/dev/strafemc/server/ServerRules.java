package dev.strafemc.server;

import dev.strafemc.StrafeMc;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * What a server offers besides the game itself, in strafemc-server.properties (next to server.properties on a dedicated
 * server, in the world folder otherwise). gamemode-server=true sets the server up as a dedicated game mode server:
 * everyone plays with the skins and buy menu loadout they picked in strafeMC's main menu, and the in-game loadout,
 * levels, care packages and CS2 loot in chests, the shop, the upgrader and trade ups are off. Without it each of those
 * is its own switch below. Operators switch it with strafemc_gamemode_server (console) or /strafemode server on|off.
 */
public final class ServerRules {
	public static final String FILE = "strafemc-server.properties";

	/** key, comment, default; in the file's order. */
	private record Key(String key, String comment, boolean def) {}

	private static final Key[] KEYS = {
			new Key("gamemode-server", "Dedicated game mode server: menu loadouts for everyone; loadout items, levels, drops, shop, upgrader and trade ups off (overrides the switches below)", false),
			new Key("menu-loadout", "Players use the skins and buy menu loadout from strafeMC's main menu instead of loadout items", false),
			new Key("levels", "CS2 XP and ranks for kills", true),
			new Key("drops", "Care packages for rank ups, and CS2 cases, keys, capsules, vouchers and skins in structure chests", true),
			new Key("shop", "The shop (buying and selling skins and cases)", true),
			new Key("upgrader", "The upgrader", true),
			new Key("tradeup", "Trade up contracts", true)};

	private static final Map<String, Boolean> VALUES = new java.util.concurrent.ConcurrentHashMap<>();
	private static Path file;

	private ServerRules() {}

	static {
		reset();
	}

	private static void reset() {
		for (Key k : KEYS) VALUES.put(k.key, k.def);
	}

	private static boolean get(String key) {
		return Boolean.TRUE.equals(VALUES.get(key));
	}

	public static boolean modeServer() {
		return get("gamemode-server");
	}

	/** Players' main-menu skins are what they wear (Loadout.worn), and the in-game loadout is closed. */
	public static boolean menuLoadout() {
		return modeServer() || get("menu-loadout");
	}

	public static boolean levels() {
		return !modeServer() && get("levels");
	}

	public static boolean drops() {
		return !modeServer() && get("drops");
	}

	public static boolean shop() {
		return !modeServer() && get("shop");
	}

	public static boolean upgrader() {
		return !modeServer() && get("upgrader");
	}

	public static boolean tradeup() {
		return !modeServer() && get("tradeup");
	}

	/** For the gameplay sync: every switch as it applies now. */
	public static Map<String, Boolean> effective() {
		Map<String, Boolean> m = new LinkedHashMap<>();
		m.put("modeServer", modeServer());
		m.put("menuLoadout", menuLoadout());
		m.put("levels", levels());
		m.put("shop", shop());
		m.put("upgrader", upgrader());
		m.put("tradeup", tradeup());
		return m;
	}

	/** Why this player can't use one of the switched features now ("shop", "upgrader", ...), or null when they can. */
	public static String refusal(String feature) {
		return switch (feature) {
			case "loadout" -> menuLoadout() ? "Your loadout comes from strafeMC's main menu on this server." : null;
			case "levels" -> levels() ? null : "Levels are off on this server.";
			case "shop" -> shop() ? null : "The shop is off on this server.";
			case "upgrader" -> upgrader() ? null : "The upgrader is off on this server.";
			case "tradeup" -> tradeup() ? null : "Trade ups are off on this server.";
			default -> null;
		};
	}

	public static void setModeServer(boolean on) {
		VALUES.put("gamemode-server", on);
		save();
		GameplaySync.resendAll();
		dev.strafemc.weapon.MenuLoadout.refreshAll();
	}

	public static void register() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(ServerRules::load);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			file = null;
			reset();
		});
	}

	public static void load(MinecraftServer server) {
		file = server.isDedicatedServer() ? Path.of(FILE).toAbsolutePath() : server.getWorldPath(LevelResource.ROOT).resolve(FILE);
		reset();
		if (Files.exists(file)) {
			Properties p = new Properties();
			try (var in = Files.newBufferedReader(file)) {
				p.load(in);
				for (Key k : KEYS) {
					String v = p.getProperty(k.key);
					if (v != null) VALUES.put(k.key, Boolean.parseBoolean(v.trim()));
				}
			} catch (Exception e) {
				StrafeMc.LOG.error("[strafemc] could not read " + file, e);
			}
		}
		save(); // writes the file with every key (and its comment) the first time, and keys added since
		StrafeMc.LOG.info("[strafemc] server rules from {}: {}", file, effective());
	}

	private static void save() {
		if (file == null) return;
		StringBuilder sb = new StringBuilder("# strafeMC server settings (true / false). Changes here apply after a restart; /strafemode server on|off right away.\n");
		for (Key k : KEYS) sb.append("\n# ").append(k.comment).append(" (default ").append(k.def).append(")\n").append(k.key).append('=').append(get(k.key)).append('\n');
		try {
			Files.writeString(file, sb.toString());
		} catch (Exception e) {
			StrafeMc.LOG.error("[strafemc] could not save " + file, e);
		}
	}
}
