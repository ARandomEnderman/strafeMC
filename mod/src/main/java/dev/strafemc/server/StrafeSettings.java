package dev.strafemc.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.strafemc.StrafeMc;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server-wide strafeMC settings, saved per world (<world>/strafemc_settings.json) and sent to every player in the
 * gameplay sync. Only operators change them (ConsoleActions). Source movement is one of these: on a server, everyone
 * plays with the same movement, and the server (fall damage, stair ramps) relies on it instead of the client's word.
 */
public final class StrafeSettings {
	private static final Gson GSON = new Gson();
	private static volatile boolean sourceMovement = true;
	/** Gun and knife hits push targets back like Minecraft hits (off = CS2: no knockback, the hit slows instead). */
	private static volatile boolean mcKnockback = false;
	/** On: every player turns Source movement on/off for themselves (PERSONAL); off: operators decide for everyone. */
	private static volatile boolean playerChoice = false;
	private static final java.util.Map<java.util.UUID, Boolean> PERSONAL = new java.util.concurrent.ConcurrentHashMap<>();
	/** Lag compensation (rewind shots to what the shooter saw) and how far back it may go (CS2's sv_maxunlag is 0.2 s). */
	private static volatile boolean lagComp = true;
	private static volatile int maxUnlagMs = 300;
	private static Path file;

	private StrafeSettings() {}

	public static boolean sourceMovement() {
		return sourceMovement;
	}

	/** This player's Source movement: their own choice while players may choose, else the server's setting. */
	public static boolean sourceMovement(net.minecraft.world.entity.player.Player p) {
		if (!playerChoice || p == null) return sourceMovement;
		Boolean b = PERSONAL.get(p.getUUID());
		return b != null ? b : sourceMovement;
	}

	public static boolean playerChoice() {
		return playerChoice;
	}

	public static void setPlayerChoice(boolean on) {
		playerChoice = on;
		save();
		GameplaySync.resendAll();
	}

	public static void setPersonal(java.util.UUID id, boolean on) {
		PERSONAL.put(id, on);
		save();
		GameplaySync.resendAll();
	}

	/** Who chose what (sent in the gameplay sync; each client picks its own). */
	public static java.util.Map<String, Boolean> personal() {
		java.util.Map<String, Boolean> m = new java.util.TreeMap<>();
		PERSONAL.forEach((k, v) -> m.put(k.toString(), v));
		return m;
	}

	public static void setSourceMovement(boolean on) {
		sourceMovement = on;
		save();
		GameplaySync.resendAll();
	}

	public static boolean mcKnockback() {
		return mcKnockback;
	}

	public static void setMcKnockback(boolean on) {
		mcKnockback = on;
		save();
	}

	public static boolean lagComp() {
		return lagComp;
	}

	public static void setLagComp(boolean on) {
		lagComp = on;
		save();
	}

	public static int maxUnlagMs() {
		return maxUnlagMs;
	}

	public static void setMaxUnlagMs(int ms) {
		maxUnlagMs = Math.max(0, Math.min(1000, ms));
		save();
	}

	public static void load(MinecraftServer server) {
		file = server.getWorldPath(LevelResource.ROOT).resolve("strafemc_settings.json");
		sourceMovement = true;
		mcKnockback = false;
		lagComp = true;
		maxUnlagMs = 300;
		playerChoice = false;
		PERSONAL.clear();
		try {
			if (Files.exists(file)) {
				JsonObject o = GSON.fromJson(Files.readString(file), JsonObject.class);
				if (o != null && o.has("sourceMovement")) sourceMovement = o.get("sourceMovement").getAsBoolean();
				if (o != null && o.has("mcKnockback")) mcKnockback = o.get("mcKnockback").getAsBoolean();
				if (o != null && o.has("lagComp")) lagComp = o.get("lagComp").getAsBoolean();
				if (o != null && o.has("maxUnlagMs")) maxUnlagMs = o.get("maxUnlagMs").getAsInt();
				if (o != null && o.has("playerChoice")) playerChoice = o.get("playerChoice").getAsBoolean();
				if (o != null && o.has("personal"))
					for (var e : o.getAsJsonObject("personal").entrySet()) PERSONAL.put(java.util.UUID.fromString(e.getKey()), e.getValue().getAsBoolean());
			}
		} catch (Exception e) {
			StrafeMc.LOG.error("[strafemc] could not read " + file, e);
		}
	}

	private static void save() {
		if (file == null) return;
		JsonObject o = new JsonObject();
		o.addProperty("sourceMovement", sourceMovement);
		o.addProperty("mcKnockback", mcKnockback);
		o.addProperty("lagComp", lagComp);
		o.addProperty("maxUnlagMs", maxUnlagMs);
		o.addProperty("playerChoice", playerChoice);
		JsonObject pers = new JsonObject();
		PERSONAL.forEach((k, v) -> pers.addProperty(k.toString(), v));
		o.add("personal", pers);
		try {
			Files.writeString(file, GSON.toJson(o));
		} catch (Exception e) {
			StrafeMc.LOG.error("[strafemc] could not save " + file, e);
		}
	}

	public static void unload() {
		file = null;
		sourceMovement = true;
		mcKnockback = false;
		playerChoice = false;
		PERSONAL.clear();
	}
}
