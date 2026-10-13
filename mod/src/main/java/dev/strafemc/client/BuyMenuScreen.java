package dev.strafemc.client;

import dev.strafemc.client.console.ConsoleCommands;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The buy menu (B): CS2's gear in its groups, without money. The server checks whether you may buy here and applies
 * CS2's grenade limits (weapon.BuyMenu).
 */
public final class BuyMenuScreen extends Screen {
	private static final String[][] GROUPS = {
			{"Equipment", "kevlar:Kevlar Vest", "assaultsuit:Kevlar + Helmet", "healthshot:Medi-Shot"},
			{"Grenades", "hegrenade:HE Grenade", "flashbang:Flashbang", "smokegrenade:Smoke Grenade", "molotov:Molotov", "incgrenade:Incendiary Grenade",
					"decoy:Decoy Grenade"},
			{"Rifles", "ak47:AK-47"},
			{"Knife", "knife:Falchion Knife"}};

	public BuyMenuScreen() {
		super(Component.literal("Buy menu"));
	}

	@Override
	protected void init() {
		int colW = 120, gap = 10, total = GROUPS.length * colW + (GROUPS.length - 1) * gap;
		int x0 = (width - total) / 2, y0 = height / 2 - 70;
		for (int c = 0; c < GROUPS.length; c++) {
			int x = x0 + c * (colW + gap);
			for (int i = 1; i < GROUPS[c].length; i++) {
				String[] kv = GROUPS[c][i].split(":", 2);
				addRenderableWidget(Button.builder(Component.literal(kv[1]), b -> ConsoleCommands.buy(kv[0]))
						.bounds(x, y0 + 16 + (i - 1) * 22, colW, 20).build());
			}
		}
		addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(width / 2 - 40, y0 + 16 + 7 * 22, 80, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		int colW = 120, gap = 10, total = GROUPS.length * colW + (GROUPS.length - 1) * gap;
		int x0 = (width - total) / 2, y0 = height / 2 - 70;
		g.text(font, title, (width - font.width(title)) / 2, y0 - 18, 0xFFFFFFFF, true);
		for (int c = 0; c < GROUPS.length; c++) g.text(font, GROUPS[c][0], x0 + c * (colW + gap), y0 + 4, 0xFFEABE54, true);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
