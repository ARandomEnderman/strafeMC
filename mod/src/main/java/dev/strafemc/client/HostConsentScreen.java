package dev.strafemc.client;

import dev.strafemc.config.StrafeConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Asked once, when opening the first singleplayer world on a PC that can host a CS2 server: run CS2's own server
 * alongside your worlds for its real settings, commands and bots? The answer is saved; Options > strafeMC changes it.
 */
public final class HostConsentScreen extends Screen {
	private static final String[] TEXT = {
			"§lRun a CS2 server with your worlds?",
			"",
			"strafeMC can start Counter-Strike 2's own dedicated server from your CS2 folder while you play your own worlds. Then the console has CS2's real settings and commands (sv_airaccelerate, bots and more), like on a CS2 server. Without it, CS2's default settings are used.",
			"",
			"It runs in insecure mode (no VAC), as a LAN server, never joins Valve's matchmaking, changes nothing in your CS2 folder, and stops when Minecraft closes.",
			"",
			"§eNote:§r while it runs, other people on your local network (same Wi-Fi or LAN) could reach its console and run CS2 commands on it. Details in Options > strafeMC > About hosting.",
	};

	public HostConsentScreen() {
		super(Component.literal("Run a CS2 server?"));
	}

	@Override
	protected void init() {
		int cx = width / 2, y = height - 32;
		addRenderableWidget(Button.builder(Component.literal("§aYes, I understand"), b -> answer(true)).bounds(cx - 155, y, 150, 20).build());
		addRenderableWidget(Button.builder(Component.literal("No thanks"), b -> answer(false)).bounds(cx + 5, y, 150, 20).build());
	}

	private void answer(boolean yes) {
		StrafeConfig.hostAsked = true;
		StrafeConfig.hostServer = yes;
		try {
			StrafeConfig.save();
		} catch (Exception ignored) {
		}
		onClose();
		if (yes) dev.strafemc.client.console.ServerLauncher.start();
	}

	@Override
	public void onClose() {
		minecraft.setScreen(null);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
		super.extractRenderState(g, mx, my, partial);
		int w = Math.min(380, width - 32), x = (width - w) / 2, y = 20;
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
