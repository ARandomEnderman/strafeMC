package dev.strafemc.server;

import dev.strafemc.net.StrafePayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * strafeMC's own notices (bottom left of the screen, client StrafeToasts) instead of the action bar or chat: "Not enough
 * balance", a spray's cooldown, a rank up. A player without the mod gets the action bar.
 */
public final class Toasts {
	/** Kinds: their accent colour. */
	public static final int INFO = 0xFFEABE54, WARN = 0xFFE04040, GOOD = 0xFF55FF55;

	private Toasts() {}

	public static void send(ServerPlayer p, String text, int color) {
		if (ServerPlayNetworking.canSend(p, StrafePayloads.Toast.TYPE)) ServerPlayNetworking.send(p, new StrafePayloads.Toast(text, color));
		else p.sendOverlayMessage(Component.literal(text));
	}

	public static void warn(ServerPlayer p, String text) {
		send(p, text, WARN);
	}

	public static void info(ServerPlayer p, String text) {
		send(p, text, INFO);
	}
}
