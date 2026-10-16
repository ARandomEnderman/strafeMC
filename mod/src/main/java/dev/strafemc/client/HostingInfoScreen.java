package dev.strafemc.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Options > strafeMC > About hosting: what "Host a CS2 server" does and what it opens on the network. */
public final class HostingInfoScreen extends Screen {
	private static final String[] TEXT = {
			"§lHost a CS2 server (advanced)",
			"",
			"When you open your own world, strafeMC starts Counter-Strike 2's dedicated server from your CS2 folder, so CS2's real settings and commands (bots, cvars) work in the console. Nothing in your CS2 folder is changed: it only uses launch options.",
			"",
			"It always runs in insecure mode (no VAC), as a LAN server, and never joins Valve's matchmaking. strafeMC checks this every few seconds and stops the server otherwise. It stops when Minecraft closes. Windows only for now.",
			"",
			"§eYour network:§r the server's main console (netcon, port 29015) is protected by a random password that only strafeMC knows; operators and strafeops use it through the Minecraft server. But CS2 also opens a second console (VConsole, port 29000) on every network interface, with no password, and it can't be turned off. Anyone on your local network (same Wi-Fi or LAN) could connect to it and run CS2 console commands on this server.",
			"",
			"§7Advanced users can block that with a Windows Firewall rule that blocks incoming connections to cs2.exe from other computers. That rule also applies to your normal CS2 game (only incoming connections).",
	};
	private final Screen parent;

	public HostingInfoScreen(Screen parent) {
		super(Component.literal("About hosting"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(width / 2 - 75, height - 28, 150, 20).build());
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	/** The settings menu's dark backdrop instead of the blurred panorama / world: text stays readable. */
	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float partial) {
		g.fill(0, 0, width, height, StrafeSettingsScreen.BG);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
		super.extractRenderState(g, mx, my, partial);
		int w = Math.min(380, width - 32), x = (width - w) / 2, y = 14;
		for (String l : TEXT) {
			if (l.isEmpty()) {
				y += 6;
				continue;
			}
			for (var line : font.split(Component.literal(l), w)) {
				g.text(font, line, x, y, 0xFFE0E0E0, false);
				y += 10;
			}
		}
	}
}
