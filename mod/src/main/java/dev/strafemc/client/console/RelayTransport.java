package dev.strafemc.client.console;

import dev.strafemc.net.StrafePayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * Where CS2 console commands go. In your own singleplayer world (or with no world open): straight to your local CS2
 * server over netcon, as the host. On someone else's server: relayed through that Minecraft server, which checks
 * permissions and runs them on its own CS2 server (server.ConsoleRelay); the replies come back to this console.
 */
public final class RelayTransport implements ConsoleTransport {
	public static final RelayTransport INSTANCE = new RelayTransport();

	private RelayTransport() {}

	/** True when the CS2 server is someone else's: we're connected to a server that isn't our own integrated one. */
	public static boolean remote() {
		Minecraft mc = Minecraft.getInstance();
		return mc.getConnection() != null && mc.getSingleplayerServer() == null;
	}

	@Override
	public void send(String command) {
		if (!remote()) {
			NetconTransport.INSTANCE.send(command);
			return;
		}
		if (!ClientPlayNetworking.canSend(StrafePayloads.ConsoleCommand.TYPE)) {
			ConsoleLog.error("This server doesn't run strafeMC: no CS2 console here.");
			return;
		}
		ClientPlayNetworking.send(new StrafePayloads.ConsoleCommand(command));
	}

	@Override
	public boolean connected() {
		return remote() ? ClientPlayNetworking.canSend(StrafePayloads.ConsoleCommand.TYPE) : NetconTransport.INSTANCE.connected();
	}

	@Override
	public String describe() {
		return remote() ? "the server's CS2 (relayed)" : NetconTransport.INSTANCE.describe();
	}
}
