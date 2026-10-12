package dev.strafemc.server;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.strafemc.StrafeMc;
import dev.strafemc.bridge.Cs2Bridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Each world keeps its own CS2 cvar settings (<world>/strafemc_cvars.json: every cvar that differs from the CS2
 * server's defaults). When a world opens, the CS2 server goes back to its defaults and then gets that world's values,
 * so worlds never inherit each other's settings. Saved when the world closes and every minute (crash safety).
 */
public final class WorldCvars {
	private static final Gson GSON = new Gson();
	private static Path file;
	/** Values to apply once the CS2 server is reachable (null = already applied). */
	private static Map<String, String> pending;
	private static int tick, connectedTicks, verifyAt, tries;
	/** The world's values just applied, re-checked once the server has settled. */
	private static Map<String, String> applied;

	private WorldCvars() {}

	public static void load(MinecraftServer server) {
		file = server.getWorldPath(LevelResource.ROOT).resolve("strafemc_cvars.json");
		Map<String, String> saved = new TreeMap<>();
		try {
			if (Files.exists(file)) {
				Map<String, String> m = GSON.fromJson(Files.readString(file), new TypeToken<Map<String, String>>() {}.getType());
				if (m != null) saved.putAll(m);
			}
		} catch (Exception e) {
			StrafeMc.LOG.error("[strafemc] could not read " + file, e);
		}
		pending = saved;
	}

	/** Server tick: applies the world's cvars once CS2 is up; saves every minute. */
	public static void tick(MinecraftServer server) {
		if (file == null) return;
		// wait until CS2 has been up for 5 s: a server still loading its map runs its game-mode config afterwards, which
		// resets settings like sv_infinite_ammo; then check 5 s later and apply again what didn't stick (twice at most)
		connectedTicks = Cs2Bridge.INSTANCE.connected() ? connectedTicks + 1 : 0;
		if (pending != null && connectedTicks >= 100 && !Cs2Bridge.INSTANCE.defaults().isEmpty()) {
			applied = new TreeMap<>(pending);
			apply();
			verifyAt = tick + 100;
			tries = 0;
		} else if (applied != null && tick >= verifyAt && connectedTicks > 0) {
			Map<String, String> again = new TreeMap<>();
			applied.forEach((k, v) -> {
				if (Cs2Bridge.INSTANCE.reported(k) && Cs2Bridge.INSTANCE.settable(k) && !v.equals(Cs2Bridge.INSTANCE.cvar(k))) again.put(k, v);
			});
			if (again.isEmpty() || ++tries > 2) applied = null;
			else {
				StrafeMc.LOG.info("[strafemc] world cvars didn't stick, applying again: {}", again.keySet());
				again.forEach((k, v) -> ConsoleRelay.exec(k + " \"" + v.replace("\"", "") + "\""));
				verifyAt = tick + 100;
			}
		}
		if (++tick % 1200 == 0) save();
	}

	private static void apply() {
		Map<String, String> want = new TreeMap<>(Cs2Bridge.INSTANCE.defaults());
		want.putAll(pending);
		int changed = 0;
		for (var e : want.entrySet()) {
			String now = Cs2Bridge.INSTANCE.cvar(e.getKey());
			if (e.getValue().equals(now) || !Cs2Bridge.INSTANCE.settable(e.getKey())) continue;
			if (ConsoleRelay.exec(e.getKey() + " \"" + e.getValue().replace("\"", "") + "\"")) changed++;
		}
		StrafeMc.LOG.info("[strafemc] world cvars applied: {} saved, {} changed on the CS2 server", pending.size(), changed);
		pending = null;
	}

	/** Saves every cvar that differs from the CS2 server's defaults (nothing until this world's cvars were applied). */
	public static void save() {
		if (file == null || pending != null) return;
		Map<String, String> defaults = Cs2Bridge.INSTANCE.defaults();
		if (defaults.isEmpty()) return;
		Map<String, String> out = new TreeMap<>();
		Cs2Bridge.INSTANCE.cvars().forEach((k, v) -> {
			if (defaults.containsKey(k) && !v.equals(defaults.get(k))) out.put(k, v);
		});
		try {
			Files.writeString(file, GSON.toJson(out));
		} catch (Exception e) {
			StrafeMc.LOG.error("[strafemc] could not save " + file, e);
		}
	}

	public static void unload() {
		save();
		file = null;
		pending = null;
		applied = null;
		connectedTicks = 0;
	}
}
