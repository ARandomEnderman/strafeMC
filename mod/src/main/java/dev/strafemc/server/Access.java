package dev.strafemc.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/** Who may use the CS2 console / cheat commands: the singleplayer owner or an op (gamemaster level). */
public final class Access {
	private Access() {}

	/** sv_cheats on this server's CS2 server. Unlocks cheat commands for operators only; never for anyone else. */
	public static boolean cheats() {
		String v = dev.strafemc.bridge.Cs2Bridge.INSTANCE.cvar("sv_cheats");
		return "1".equals(v) || "true".equals(v);
	}

	public static boolean isOperator(ServerPlayer p) {
		var server = p.level().getServer();
		return (server != null && server.isSingleplayerOwner(p.nameAndId()))
				|| p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}
}
