package dev.strafemc.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The strafeMC menu (key U): one place for the loadout, the care package, the buy menu, the advancement tab and the
 * settings. Big tiles in a row, CS2-dark; the care package tile is marked while it has rewards to claim.
 */
public final class QuickMenuScreen extends Screen {
	private static final int GOLD = 0xFFEABE54;

	private record Entry(String label, String sub, Runnable action, boolean active) {}

	private java.util.List<Entry> entries = java.util.List.of();

	public QuickMenuScreen() {
		super(Component.literal("strafeMC"));
	}

	@Override
	protected void init() {
		boolean buy = ClientGameplay.buyMode() != 0;
		entries = java.util.List.of(
				new Entry("Loadout", "Gloves, knife and gun wraps", () -> {
					minecraft.setScreen(null);
					LoadoutKey.open();
				}, true),
				new Entry("Care Package", "Free rewards every hour", () -> minecraft.setScreen(new CarePackageScreen(this)), true),
				new Entry("Buy Menu", buy ? "Guns, grenades and gear" : "Off on this server", () -> BuyMenuScreen.open(minecraft), buy),
				new Entry("Achievements", "The strafeMC advancement tab", this::advancements, true),
				new Entry("Settings", "strafeMC settings", () -> minecraft.setScreen(new StrafeSettingsScreen(this)), true));
		int n = entries.size(), w = tileW(), x0 = width / 2 - (n * (w + 6) - 6) / 2, y = height / 2 - 20;
		for (int i = 0; i < n; i++) {
			Entry e = entries.get(i);
			Button b = addRenderableWidget(Button.builder(Component.literal(e.label()), btn -> e.action().run()).bounds(x0 + i * (w + 6), y, w, 40).build());
			b.active = e.active();
		}
		addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(width / 2 - 50, height - 30, 100, 20).build());
	}

	private int tileW() {
		return Math.max(64, Math.min(110, (width - 20) / Math.max(1, entries.size()) - 6));
	}

	/** Vanilla's advancement screen, opened on the strafeMC tab. */
	private void advancements() {
		var conn = minecraft.getConnection();
		if (conn == null) return;
		var adv = conn.getAdvancements();
		var root = adv.get(net.minecraft.resources.Identifier.fromNamespaceAndPath("strafemc", "root"));
		minecraft.setScreen(new net.minecraft.client.gui.screens.advancements.AdvancementsScreen(adv, this));
		if (root != null) adv.setSelectedTab(root, true);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, StrafeSettingsScreen.BG);
		var font = Minecraft.getInstance().font;
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(width / 2f, height / 2f - 60);
		pose.scale(2f, 2f);
		g.text(font, "strafeMC", -font.width("strafeMC") / 2, 0, GOLD, true);
		pose.popMatrix();
		super.extractRenderState(g, mx, my, delta);
		int n = entries.size(), w = tileW(), x0 = width / 2 - (n * (w + 6) - 6) / 2, y = height / 2 - 20;
		for (int i = 0; i < n; i++) {
			Entry e = entries.get(i);
			int x = x0 + i * (w + 6);
			String sub = font.plainSubstrByWidth(e.sub(), w + 4);
			g.text(font, sub, x + w / 2 - font.width(sub) / 2, y + 46, 0xFF909090, false);
			if (e.label().equals("Care Package") && CarePackageScreen.claimable()) { // rewards waiting
				g.fill(x + w - 10, y + 2, x + w - 2, y + 10, GOLD);
				g.text(font, "!", x + w - 7, y + 2, 0xFF101317, false);
			}
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
		if (LoadoutKey.matches(e)) { // the menu key closes it again
			onClose();
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
