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
	private static Path file;

	private StrafeSettings() {}

	public static boolean sourceMovement() {
		return sourceMovement;
	}

	public static void setSourceMovement(boolean on) {
		sourceMovement = on;
		save();
		GameplaySync.resendAll();
	}

	public static void load(MinecraftServer server) {
		file = server.getWorldPath(LevelResource.ROOT).resolve("strafemc_settings.json");
		sourceMovement = true;
		try {
			if (Files.exists(file)) {
				JsonObject o = GSON.fromJson(Files.readString(file), JsonObject.class);
				if (o != null && o.has("sourceMovement")) sourceMovement = o.get("sourceMovement").getAsBoolean();
			}
		} catch (Exception e) {
			StrafeMc.LOG.error("[strafemc] could not read " + file, e);
		}
	}

	private static void save() {
		if (file == null) return;
		JsonObject o = new JsonObject();
		o.addProperty("sourceMovement", sourceMovement);
		try {
			Files.writeString(file, GSON.toJson(o));
		} catch (Exception e) {
			StrafeMc.LOG.error("[strafemc] could not save " + file, e);
		}
	}

	public static void unload() {
		file = null;
		sourceMovement = true;
	}
}
