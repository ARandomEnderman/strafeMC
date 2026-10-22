package dev.strafemc.server;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.strafemc.StrafeMc;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * strafeMC operators: players who may use the CS2 console and strafeMC's op-only commands without being Minecraft
 * operators. Minecraft ops manage the list with /strafeop and /strafedeop. Saved per world/server in strafemc_ops.json.
 */
public final class StrafeOps {
	private static final Gson GSON = new Gson();
	/** uuid -> last known name (the name is only for reading the file). */
	private static final Map<UUID, String> OPS = new ConcurrentHashMap<>();
	private static Path file;

	private StrafeOps() {}

	public static boolean contains(ServerPlayer p) {
		return OPS.containsKey(p.getUUID());
	}

	public static void load(MinecraftServer server) {
		OPS.clear();
		file = server.getWorldPath(LevelResource.ROOT).resolve("strafemc_ops.json");
		try {
			if (Files.exists(file)) {
				Map<String, String> m = GSON.fromJson(Files.readString(file), new TypeToken<Map<String, String>>() {}.getType());
				if (m != null) m.forEach((k, v) -> OPS.put(UUID.fromString(k), v));
			}
		} catch (Exception e) {
			StrafeMc.LOG.error("[strafemc] could not read " + file, e);
		}
	}

	public static void unload() {
		OPS.clear();
		file = null;
	}

	private static void save() {
		if (file == null) return;
		Map<String, String> m = new LinkedHashMap<>();
		OPS.forEach((k, v) -> m.put(k.toString(), v));
		try {
			Files.writeString(file, GSON.toJson(m));
		} catch (Exception e) {
			StrafeMc.LOG.error("[strafemc] could not save " + file, e);
		}
	}

	public static void register() {
		net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> {
			dispatcher.register(Commands.literal("strafeop")
					.requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
					.then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
						ServerPlayer t = EntityArgument.getPlayer(ctx, "player");
						if (OPS.put(t.getUUID(), t.getScoreboardName()) != null) {
							ctx.getSource().sendFailure(Component.literal(t.getScoreboardName() + " is already a strafeMC op"));
							return 0;
						}
						save();
						ctx.getSource().sendSuccess(() -> Component.literal("Made " + t.getScoreboardName() + " a strafeMC op"), true);
						dev.strafemc.server.Toasts.info(t, "You are now a strafeMC op: you can use the CS2 console.");
						return 1;
					})));
			dispatcher.register(Commands.literal("strafedeop")
					.requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
					.then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
						ServerPlayer t = EntityArgument.getPlayer(ctx, "player");
						if (OPS.remove(t.getUUID()) == null) {
							ctx.getSource().sendFailure(Component.literal(t.getScoreboardName() + " is not a strafeMC op"));
							return 0;
						}
						save();
						ctx.getSource().sendSuccess(() -> Component.literal("Removed " + t.getScoreboardName() + " from the strafeMC ops"), true);
						dev.strafemc.server.Toasts.info(t, "You are no longer a strafeMC op.");
						return 1;
					})));
		});
	}
}
