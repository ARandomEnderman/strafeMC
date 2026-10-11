package dev.strafemc.client.console;

/**
 * Where console commands for the CS2 server go. Today: straight to the local CS2 process over loopback netcon.
 * Multiplayer (roadmap): a relay through the Minecraft server, which checks operator permission and owns the CS2 process.
 */
public interface ConsoleTransport {
	/** Sends one command line to the CS2 server; output arrives asynchronously in {@link ConsoleLog}. */
	void send(String command);

	boolean connected();

	String describe();
}
