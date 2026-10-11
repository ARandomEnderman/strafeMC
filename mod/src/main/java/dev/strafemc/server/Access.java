package dev.strafemc.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/** Who may use the CS2 console / cheat commands: the singleplayer owner or an op (gamemaster level). */
public final class Access {
	private Access() {}

	public static boolean isOperator(ServerPlayer p) {
		var server = p.level().getServer();
		return (server != null && server.isSingleplayerOwner(p.nameAndId()))
				|| p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}
}
