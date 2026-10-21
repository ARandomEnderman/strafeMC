package dev.strafemc.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Picking a side in a team game mode (CS2's team select: Counter-Terrorists, Terrorists or auto-select), with how many
 * are on each. Sends CS2's jointeam; the server balances (mp_limitteams) and says no to a full side.
 */
public final class TeamSelectScreen extends Screen {
	private static final int CT = 0xFFB5D4EE, T = 0xFFEAD18A;

	public TeamSelectScreen() {
		super(Component.literal("Choose a team"));
	}

	@Override
	protected void init() {
		int w = 150, h = 60, gap = 12, cx = width / 2, y = height / 2 - h / 2;
		addRenderableWidget(Button.builder(Component.literal("Counter-Terrorists"), b -> pick("ct")).bounds(cx - w - gap / 2, y, w, h).build());
		addRenderableWidget(Button.builder(Component.literal("Terrorists"), b -> pick("t")).bounds(cx + gap / 2, y, w, h).build());
		addRenderableWidget(Button.builder(Component.literal("Auto-select"), b -> pick("auto")).bounds(cx - 75, y + h + gap, 150, 20).build());
	}

	private void pick(String side) {
		if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(dev.strafemc.net.StrafePayloads.Action.TYPE))
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.strafemc.net.StrafePayloads.Action("jointeam", side));
		if (!side.equals("auto")) {
			dev.strafemc.config.StrafeConfig.team = side;
			try {
				dev.strafemc.config.StrafeConfig.save();
			} catch (Exception ignored) {
			}
		}
		onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, 0xB0000000);
		super.extractRenderState(g, mx, my, delta);
		var font = minecraft.font;
		String title = "Choose a team";
		g.text(font, title, width / 2 - font.width(title) / 2, height / 2 - 60, 0xFFFFFFFF, true);
		int[] n = ModeHud.teamCounts();
		String ct = n[0] + " player" + (n[0] == 1 ? "" : "s"), t = n[1] + " player" + (n[1] == 1 ? "" : "s");
		g.text(font, ct, width / 2 - 6 - 75 - font.width(ct) / 2, height / 2 + 34, CT, true);
		g.text(font, t, width / 2 + 6 + 75 - font.width(t) / 2, height / 2 + 34, T, true);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
