package dev.strafemc.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The strafeMC menu (key V): loadout, care package, achievements and settings on a wheel of CS2's own UI icons, the
 * middle saying what the one under the mouse does. Point at one (the whole slice around it counts, like CS2's wheels) and click, or hold the menu key, point and let go.
 */
public final class QuickMenuScreen extends Screen {
	private static final int GOLD = 0xFFEABE54;
	/** Wheel icons: texture name, CS2 panorama image (gui/menu/&lt;name&gt;.png, made by RuntimePack). */
	public static final String[][] ICONS = {{"loadout", "icons/ui/loadout"}, {"care_package", "icons/care_package"}, {"upgrader", "icons/ui/xpshop_tiles"},
			{"achievements", "icons/ui/trophy"}, {"settings", "icons/ui/settings"}};

	private record Entry(String icon, String label, String sub, Runnable action, boolean active) {}

	private java.util.List<Entry> entries = java.util.List.of();
	private int hovered = -1;
	/** When the screen opened: a held key let go after this long picks the slice under the mouse. */
	private final long opened = System.currentTimeMillis();
	/** The key that opened it has been let go (until then its repeats don't close it). */
	private boolean released;

	/** byKey: opened by the menu key (still held); false when coming back from one of its screens. */
	public QuickMenuScreen(boolean byKey) {
		super(Component.literal("strafeMC"));
		released = !byKey;
	}

	@Override
	protected void init() {
		entries = java.util.List.of(
				new Entry("loadout", "Loadout", "Gloves, knife and gun wraps", () -> {
					minecraft.setScreen(null);
					LoadoutKey.fromMenu = true; // its Done comes back here
					LoadoutKey.open();
				}, true),
				new Entry("care_package", "Care Package", !ClientGameplay.drops() ? "Off on this server" : CarePackageScreen.claimable() ? "Rewards waiting!"
						: "Free rewards for every rank", () -> minecraft.setScreen(new CarePackageScreen(this)), ClientGameplay.drops()),
				new Entry("upgrader", "Upgrader", "Stake skins for a better one", () -> minecraft.setScreen(new UpgraderScreen(this)), true),
				new Entry("achievements", "Achievements", "The strafeMC advancements", this::advancements, true),
				new Entry("settings", "Settings", "strafeMC settings", () -> minecraft.setScreen(new StrafeSettingsScreen(this)), true));
	}

	private int radius() {
		return Math.max(50, Math.min(110, Math.min(width, height) / 2 - 40));
	}

	/** Slice i's centre: the first at the top, then clockwise. */
	private double[] at(int i) {
		double a = -Math.PI / 2 + i * 2 * Math.PI / entries.size();
		return new double[] {width / 2.0 + Math.cos(a) * radius(), height / 2.0 + Math.sin(a) * radius()};
	}

	/** The slice under the mouse (by angle, outside the middle), or -1. */
	private int slice(double mx, double my) {
		double dx = mx - width / 2.0, dy = my - height / 2.0;
		if (dx * dx + dy * dy < 30 * 30 || entries.isEmpty()) return -1;
		double a = Math.atan2(dy, dx) + Math.PI / 2 + Math.PI / entries.size();
		a = (a % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI);
		return (int) (a / (2 * Math.PI / entries.size())) % entries.size();
	}

	private void pick(int i) {
		if (i < 0 || i >= entries.size() || !entries.get(i).active()) return;
		dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/submenu_leveloptions_select_01", 0.5f);
		entries.get(i).action().run();
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e, boolean doubleClick) {
		int i = slice(e.x(), e.y());
		if (i >= 0) {
			pick(i);
			return true;
		}
		return false;
	}

	/** Vanilla's advancement screen, opened on the strafeMC tab. */
	private void advancements() {
		var conn = minecraft.getConnection();
		if (conn == null) return;
		var adv = conn.getAdvancements();
		var root = adv.get(Identifier.fromNamespaceAndPath("strafemc", "root"));
		minecraft.setScreen(new net.minecraft.client.gui.screens.advancements.AdvancementsScreen(adv, this) {
			@Override
			public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
				return LoadoutKey.closesAll(this, e) || super.keyPressed(e); // the strafeMC menu key closes it all
			}
		});
		if (root != null) adv.setSelectedTab(root, true);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, 0xB0101317);
		var font = Minecraft.getInstance().font;
		int cx = width / 2, cy = height / 2, r = radius();
		hovered = slice(mx, my);
		RankBar.draw(g, cx + 14, 10, Math.min(220, width - 80)); // your CS2 rank, at the top
		// the ring: dots around, a darker disc in the middle
		double spin = System.nanoTime() / 1e9 * 0.15; // the dots turn slowly; the entries stay
		for (int k = 0; k < 120; k++) {
			double a = spin + k * Math.PI * 2 / 120;
			int x = (int) Math.round(cx + Math.cos(a) * r), y = (int) Math.round(cy + Math.sin(a) * r);
			g.fill(x, y, x + 1, y + 1, 0x60FFFFFF);
		}
		for (int i = 0; i < entries.size(); i++) {
			Entry e = entries.get(i);
			double[] p = at(i);
			int x = (int) p[0], y = (int) p[1], s = 22;
			boolean hot = i == hovered && e.active();
			g.fill(x - s, y - s, x + s, y + s, hot ? 0xE0303843 : 0xC01A1F26);
			if (hot) g.outline(x - s, y - s, 2 * s, 2 * s, GOLD);
			int color = !e.active() ? 0xFF606060 : hot ? GOLD : 0xFFFFFFFF;
			g.blit(RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath("strafemc", "textures/gui/menu/" + e.icon() + ".png"), x - 12, y - 16, 0, 0, 24, 24,
					64, 64, 64, 64, color);
			String label = font.plainSubstrByWidth(e.label(), 2 * s + 30);
			g.text(font, label, x - font.width(label) / 2, y + 10, color, true);
			if (e.icon().equals("care_package") && CarePackageScreen.claimable()) { // rewards waiting
				g.fill(x + s - 9, y - s + 1, x + s - 1, y - s + 9, GOLD);
				g.text(font, "!", x + s - 6, y - s + 1, 0xFF101317, false);
			}
		}
		// the middle: the title, or what the pointed-at entry does
		String title = hovered >= 0 ? entries.get(hovered).label() : "strafeMC";
		String sub = hovered >= 0 ? entries.get(hovered).sub() : "Point and click";
		g.text(font, title, cx - font.width(title) / 2, cy - 9, GOLD, true);
		for (var line : font.split(Component.literal(sub), Math.max(60, r - 30))) {
			g.text(font, line, cx - font.width(line) / 2, cy + 3, 0xFFB0B0B0, false);
			cy += 10;
		}
		super.extractRenderState(g, mx, my, delta);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
		if (LoadoutKey.matches(e)) { // pressing the menu key again closes it (not the opening press's repeats)
			if (released) onClose();
			return true;
		}
		return super.keyPressed(e);
	}

	/** The held menu key let go (after a moment): the slice under the mouse, like CS2's radial wheels. */
	@Override
	public boolean keyReleased(net.minecraft.client.input.KeyEvent e) {
		if (LoadoutKey.matches(e) && !released) {
			released = true;
			if (System.currentTimeMillis() - opened > 300 && hovered >= 0) pick(hovered); // held, pointed, let go
			return true;
		}
		return super.keyReleased(e);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
