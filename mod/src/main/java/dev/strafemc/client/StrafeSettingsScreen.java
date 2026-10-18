package dev.strafemc.client;

import dev.strafemc.StrafeMc;
import dev.strafemc.client.console.Binds;
import dev.strafemc.client.weapon.Crosshair;
import dev.strafemc.config.StrafeConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * strafeMC's settings, laid out like CS2's: tabs across the top (Game, Viewmodel, Crosshair, Audio, Binds, Setup &
 * Hosting), each a column of rows with the setting's name on the left and its control on the right, scrolling when
 * taller than the screen. The Crosshair tab is the crosshair editor; it and the Viewmodel tab have a live first-person preview.
 */
public final class StrafeSettingsScreen extends Screen {
	private static final String[] TABS = {"Game", "Viewmodel", "Crosshair", "Audio", "Binds", "Setup & Hosting"};
	/** The menu backdrop, shared by the other strafeMC screens. */
	public static final int BG = 0xF0121416;
	private static final int BAR = 0xFF0B0C0D, LINE = 0x30FFFFFF, MUTED = 0xFF9AA29E, ACCENT = 0xFFE0C060, TEXT = 0xFFFFFFFF;
	private static final int BAR_H = 30, ROW_H = 24, CTRL_W = 200;
	private static int lastTab;

	private final Screen parent;
	private int tab;
	/** Rows of the open tab: a label, an optional control, the y it sits at before scrolling. */
	private final List<Row> rows = new ArrayList<>();
	private final List<Button> tabButtons = new ArrayList<>();
	private double scroll;
	private int contentHeight, x0, x1, top, bottom, y;
	private EditBox code;

	private record Row(String label, AbstractWidget widget, int y, boolean header) {}

	public StrafeSettingsScreen(Screen parent) {
		this(parent, lastTab);
	}

	public StrafeSettingsScreen(Screen parent, int tab) {
		super(Component.translatable("strafemc.options.title"));
		this.parent = parent;
		this.tab = tab;
	}

	/** Opens on a given tab ("crosshair", ...). */
	public static StrafeSettingsScreen at(Screen parent, String tabName) {
		for (int i = 0; i < TABS.length; i++) if (TABS[i].toLowerCase().startsWith(tabName.toLowerCase())) return new StrafeSettingsScreen(parent, i);
		return new StrafeSettingsScreen(parent);
	}

	@Override
	protected void init() {
		rows.clear();
		tabButtons.clear();
		lastTab = tab;
		// the tab bar: CS2's row of section names
		int tw = Math.min(110, (width - 20) / TABS.length), tx = (width - tw * TABS.length) / 2;
		for (int i = 0; i < TABS.length; i++) {
			final int t = i;
			tabButtons.add(addRenderableWidget(Button.builder(Component.literal(TABS[i].toUpperCase()), x -> {
				tab = t;
				scroll = 0;
				rebuildWidgets();
			}).bounds(tx + i * tw, 5, tw, 20).build()));
		}
		boolean xhair = tab == 1 || tab == 2; // these two have the preview beside them
		int contentW = Math.min(xhair ? 420 : 520, width - 40);
		x0 = xhair ? Math.max(20, width / 2 - contentW + 40) : (width - contentW) / 2;
		x1 = x0 + contentW;
		top = BAR_H + 10;
		bottom = height - 34;
		y = 0;
		switch (tab) {
			case 0 -> game();
			case 1 -> viewmodel();
			case 2 -> crosshair();
			case 3 -> audio();
			case 4 -> binds();
			default -> setup();
		}
		contentHeight = y;
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 26, 200, 20).build());
		layout();
	}

	// ---------------------------------------------------------------- tabs

	private void game() {
		header("Movement");
		toggle("Scroll to jump", () -> StrafeConfig.scrollJump, v -> StrafeConfig.scrollJump = v);
		toggle("Double-tap to fly", () -> StrafeConfig.doubleTapFly, v -> StrafeConfig.doubleTapFly = v);
		header("HUD");
		toggle("Show speed", () -> StrafeConfig.showSpeed, v -> StrafeConfig.showSpeed = v);
		toggle("Speed meter", () -> StrafeConfig.showSpeedMeter, v -> StrafeConfig.showSpeedMeter = v);
		toggle("Jump stats", () -> StrafeConfig.jumpStats, v -> StrafeConfig.jumpStats = v);
		toggle("Velocity graph", () -> StrafeConfig.velGraph, v -> StrafeConfig.velGraph = v);
	}

	private void viewmodel() {
		header("Agent");
		button("Agents", "Choose...", () -> minecraft.setScreen(new AgentScreen(this)));
		header("Viewmodel");
		toggle("Right-handed", () -> StrafeConfig.rightHand, v -> StrafeConfig.rightHand = v);
		slider("Field of view", 60, 68, 1, () -> StrafeConfig.viewmodelFov, v -> StrafeConfig.viewmodelFov = v);
		slider("Offset X", -5, 5, 0.1, () -> StrafeConfig.viewmodelOffsetX, v -> StrafeConfig.viewmodelOffsetX = v);
		slider("Offset Y", -5, 5, 0.1, () -> StrafeConfig.viewmodelOffsetY, v -> StrafeConfig.viewmodelOffsetY = v);
		slider("Offset Z", -5, 5, 0.1, () -> StrafeConfig.viewmodelOffsetZ, v -> StrafeConfig.viewmodelOffsetZ = v);
		slider("Sway", 0, 5, 0.1, () -> StrafeConfig.viewmodelSwayScale, v -> StrafeConfig.viewmodelSwayScale = v);
		header("Performance");
		toggle("GPU animation", () -> StrafeConfig.gpuSkinning, v -> StrafeConfig.gpuSkinning = v);
	}

	private void crosshair() {
		header("Style");
		xslider("Style", "cl_crosshairstyle", 0, 9, 1);
		xslider("Length", "cl_crosshair_length", 0, 60, 1);
		xslider("Thickness", "cl_crosshair_thickness", 0, 32, 1);
		xslider("Gap", "cl_crosshair_gap", -10, 64, 1);
		xtoggle("Centre dot", "cl_crosshairdot");
		xtoggle("T style", "cl_crosshair_t");
		xtoggle("Follow recoil", "cl_crosshair_recoil");
		header("Colour");
		xslider("Red", "cl_crosshaircolor_r", 0, 255, 1);
		xslider("Green", "cl_crosshaircolor_g", 0, 255, 1);
		xslider("Blue", "cl_crosshaircolor_b", 0, 255, 1);
		xslider("Alpha", "cl_crosshaircolor_a", 0, 255, 1);
		xtoggle("Outline", "cl_crosshair_drawoutline");
		xslider("Outline red", "cl_crosshairoutline_r", 0, 255, 1);
		xslider("Outline green", "cl_crosshairoutline_g", 0, 255, 1);
		xslider("Outline blue", "cl_crosshairoutline_b", 0, 255, 1);
		xslider("Outline alpha", "cl_crosshairoutline_a", 0, 255, 1);
		header("Dynamic");
		xslider("Spread limit", "cl_crosshair_dynamic_spread_limit", 0, 255, 1);
		xslider("Split distance", "cl_crosshair_dynamic_splitdist", 0, 127, 1);
		xslider("Inner split alpha", "cl_crosshair_dynamic_splitalpha_innermod", 0, 1, 0.05);
		xslider("Outer split alpha", "cl_crosshair_dynamic_splitalpha_outermod", 0.3, 1, 0.05);
		xslider("Split ratio", "cl_crosshair_dynamic_maxdist_splitratio", 0, 1, 0.05);
		header("Crosshair code");
		code = new EditBox(font, 0, 0, CTRL_W, 18, Component.literal("Share code"));
		code.setMaxLength(40);
		code.setHint(Component.literal("CSGO-xxxxx-xxxxx-..."));
		row("Code", code);
		button("Import code", "Import", () -> {
			if (Crosshair.apply(code.getValue())) rebuildWidgets();
			else code.setValue("");
		});
		button("Copy code", "Copy", () -> {
			String c = Crosshair.export();
			minecraft.keyboardHandler.setClipboard(c);
			code.setValue(c);
		});
		button("Reset crosshair", "Reset", () -> {
			StrafeConfig.crosshair.clear();
			Crosshair.save();
			rebuildWidgets();
		});
	}

	private void audio() {
		header("Volume");
		slider("Footsteps", 0, 1, 0.05, () -> StrafeConfig.movementVolume, v -> StrafeConfig.movementVolume = v);
		slider("Landing", 0, 1, 0.05, () -> StrafeConfig.landVolume, v -> StrafeConfig.landVolume = v);
	}

	private void binds() {
		header("Binds");
		var all = Binds.all();
		if (all.isEmpty()) text("No binds yet.");
		for (var e : all.entrySet())
			button(e.getKey() + "  ->  " + e.getValue(), "Remove", () -> {
				Binds.unbind(e.getKey());
				rebuildWidgets();
			});
	}

	private void setup() {
		header("First-time setup");
		toggle("Ask on start", () -> !StrafeConfig.setupNeverAsk, v -> StrafeConfig.setupNeverAsk = !v);
		button("Setup", "Open", () -> SetupScreen.open(this));
		button("Reset setup", "Reset", () -> minecraft.setScreen(new ConfirmScreen(yes -> {
			if (yes) resetSetup();
			minecraft.setScreen(this);
		}, Component.literal("Reset the first-time setup?"), Component.literal("Deletes the converted CS2 files (about 10 GB). Restart Minecraft to run the setup again."))));
		header("Hosting");
		toggle("Host CS2 server", () -> StrafeConfig.hostServer, v -> StrafeConfig.hostServer = v);
		button("About hosting", "Open", () -> minecraft.setScreen(new HostingInfoScreen(this)));
	}

	// ---------------------------------------------------------------- rows

	private void header(String s) {
		if (y > 0) y += 6;
		rows.add(new Row(s, null, y, true));
		y += 18;
	}

	private void text(String s) {
		rows.add(new Row(s, null, y, false));
		y += ROW_H;
	}

	private void row(String label, AbstractWidget w) {
		addRenderableWidget(w);
		rows.add(new Row(label, w, y, false));
		y += ROW_H;
	}

	private void toggle(String label, BooleanSupplier get, Consumer<Boolean> set) {
		row(label, Button.builder(onOff(get.getAsBoolean()), b -> {
			set.accept(!get.getAsBoolean());
			save();
			b.setMessage(onOff(get.getAsBoolean()));
		}).bounds(0, 0, CTRL_W, 20).build());
	}

	private void button(String label, String action, Runnable run) {
		row(label, Button.builder(Component.literal(action), b -> run.run()).bounds(0, 0, CTRL_W, 20).build());
	}

	private void slider(String label, double min, double max, double step, DoubleSupplier get, DoubleConsumer set) {
		row(label, new Slider(min, max, step, get, v -> {
			set.accept(v);
			save();
		}, null));
	}

	private void xslider(String label, String cvar, double min, double max, double step) {
		row(label, new Slider(min, max, step, () -> Crosshair.get(cvar), v -> {
			StrafeConfig.crosshair.put(cvar, v);
			Crosshair.save();
		}, cvar.equals("cl_crosshairstyle") ? Crosshair.STYLES : null));
	}

	private void xtoggle(String label, String cvar) {
		toggle(label, () -> Crosshair.get(cvar) != 0, v -> StrafeConfig.crosshair.put(cvar, v ? 1.0 : 0.0));
	}

	private static Component onOff(boolean on) {
		return Component.literal(on ? "On" : "Off");
	}

	private static void save() {
		try {
			StrafeConfig.save();
		} catch (Exception e) {
			StrafeMc.LOG.warn("[strafemc] config save failed: {}", e.toString());
		}
	}

	/** Puts each row's control at its scrolled place; ones outside the content area are hidden. */
	private void layout() {
		int maxScroll = Math.max(0, contentHeight - (bottom - top));
		scroll = Math.max(0, Math.min(maxScroll, scroll));
		for (Row r : rows) {
			if (r.widget == null) continue;
			int yy = top + r.y - (int) scroll;
			r.widget.setX(x1 - CTRL_W);
			r.widget.setY(yy + (ROW_H - r.widget.getHeight()) / 2 - 1);
			r.widget.visible = yy >= top - 2 && yy + ROW_H <= bottom + 2;
		}
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (super.mouseScrolled(mx, my, sx, sy)) return true; // a slider under the mouse
		scroll -= sy * ROW_H;
		layout();
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, BG);
		g.fill(0, 0, width, BAR_H, BAR);
		g.fill(0, BAR_H, width, BAR_H + 1, LINE);
		g.enableScissor(0, top, width, bottom);
		for (Row r : rows) {
			int yy = top + r.y - (int) scroll;
			if (yy + ROW_H < top || yy > bottom) continue;
			if (r.header) {
				g.text(font, r.label.toUpperCase(), x0, yy + 4, ACCENT, false);
				g.fill(x0, yy + 15, x1, yy + 16, LINE);
			} else {
				g.text(font, r.label, x0 + 4, yy + 7, r.widget == null ? MUTED : TEXT, false);
				g.fill(x0, yy + ROW_H - 1, x1, yy + ROW_H, 0x14FFFFFF);
			}
		}
		g.disableScissor();
		super.extractRenderState(g, mx, my, delta);
		// the open tab: an accent line under it
		Button t = tabButtons.get(tab);
		g.fill(t.getX() + 4, t.getY() + t.getHeight() + 1, t.getX() + t.getWidth() - 4, t.getY() + t.getHeight() + 3, ACCENT);
		if (tab == 1 || tab == 2) { // the viewmodel and crosshair, live, beside their settings (a 16:9 view)
			int px0 = x1 + 20, px1 = width - 20, py0 = top, py1 = Math.min(bottom, top + (px1 - px0) * 9 / 16);
			if (px1 - px0 > 60) {
				g.fill(px0, py0, px1, py1, 0xFF5A7894);
				g.fill(px0, py0 + (py1 - py0) * 2 / 3, px1, py1, 0xFF6B5A44);
				LoadoutPreview.drawFirstPerson(g, px0, py0, px1, py1, previewWeapon());
				Crosshair.preview(g, (px0 + px1) / 2f, (py0 + py1) / 2f);
				g.text(font, "Preview", px0 + 4, py0 + 4, 0xFFFFFFFF, true);
			}
		}
	}

	/** The preview's weapon: the gun or knife in your hand, else an AK-47. */
	private String previewWeapon() {
		var p = minecraft.player;
		if (p != null && p.getMainHandItem().getItem() instanceof dev.strafemc.weapon.GunItem gi && !(gi instanceof dev.strafemc.weapon.GrenadeItem)
				&& !(gi instanceof dev.strafemc.weapon.HealthshotItem)) return gi.weaponClass;
		return "weapon_ak47";
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
		if (LoadoutKey.closesAll(this, e)) return true; // the strafeMC menu key closes every menu screen
		return super.keyPressed(e);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	/** A slider over [min, max] in steps, showing its value (or a name from names). */
	private final class Slider extends AbstractSliderButton {
		private final double min, max, step;
		private final DoubleConsumer set;
		private final String[] names;
		/** What 0 shows as, or null for the number. */
		String zero;

		Slider(double min, double max, double step, DoubleSupplier get, DoubleConsumer set, String[] names) {
			super(0, 0, CTRL_W, 20, Component.empty(), Math.max(0, Math.min(1, (get.getAsDouble() - min) / (max - min))));
			this.min = min;
			this.max = max;
			this.step = step;
			this.set = set;
			this.names = names;
			updateMessage();
		}

		private double v() {
			return Math.round((min + value * (max - min)) / step) * step;
		}

		@Override
		public void updateMessage() {
			double v = v();
			String s = zero != null && v == 0 ? zero : names != null ? names[(int) Math.max(0, Math.min(names.length - 1, Math.round(v)))]
					: step >= 1 ? String.valueOf(Math.round(v)) : String.format(java.util.Locale.ROOT, "%.2f", v);
			setMessage(Component.literal(s));
		}

		@Override
		protected void applyValue() {
			set.accept(v());
		}
	}

	/** Deletes what the setup made, in the background (it's large), and clears the converter path. */
	private static void resetSetup() {
		StrafeConfig.vrfPath = ""; // the CS2 folder stays (it picks which install the setup and the CS2 server use)
		StrafeConfig.setupNeverAsk = false;
		save();
		Thread t = new Thread(() -> {
			for (java.nio.file.Path dir : new java.nio.file.Path[] {StrafeConfig.cacheDir(), dev.strafemc.client.assets.RuntimePack.DIR,
					StrafeConfig.RUNTIME_DIR.resolve("tools")}) {
				if (!java.nio.file.Files.exists(dir)) continue;
				try (var walk = java.nio.file.Files.walk(dir)) {
					walk.sorted(java.util.Comparator.reverseOrder()).forEach(f -> {
						try {
							java.nio.file.Files.deleteIfExists(f);
						} catch (Exception ignored) { // in use until the restart: the setup overwrites it anyway
						}
					});
				} catch (Exception e) {
					StrafeMc.LOG.warn("[strafemc] setup reset: {}", e.toString());
				}
			}
			StrafeMc.LOG.info("[strafemc] setup reset: done, restart Minecraft");
			net.minecraft.client.Minecraft.getInstance().execute(() -> {
				var p = net.minecraft.client.Minecraft.getInstance().player;
				if (p != null) StrafeToasts.show("Setup reset. Restart Minecraft to run it again.", 0xFFEABE54);
			});
		}, "strafemc-setup-reset");
		t.setDaemon(true);
		t.start();
	}
}
