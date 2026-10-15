package dev.strafemc.client;

import dev.strafemc.client.weapon.Crosshair;
import dev.strafemc.config.StrafeConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The crosshair editor (from the strafeMC settings and the console's crosshair_editor): every cl_crosshair* setting as
 * a slider or switch, a box for CS2 share codes, and the crosshair drawn live in the preview at the top.
 */
public class CrosshairScreen extends Screen {
	private record Setting(String cvar, String label, double min, double max, double step, boolean toggle) {}

	/** The settings in three columns: the crosshair's shape, its colour, how it moves. */
	private static final String[] SECTIONS = {"Shape", "Colour", "Movement"};
	private static final List<List<Setting>> COLUMNS = List.of(
			List.of(new Setting("cl_crosshairstyle", "Style", 0, 5, 1, false),
					new Setting("cl_crosshairsize", "Length", 0, 20, 0.5, false),
					new Setting("cl_crosshairthickness", "Thickness", 0, 6, 0.1, false),
					new Setting("cl_crosshairgap", "Gap", -10, 10, 0.5, false),
					new Setting("cl_crosshairdot", "Centre dot", 0, 1, 1, true),
					new Setting("cl_crosshair_t", "T style", 0, 1, 1, true),
					new Setting("cl_crosshair_drawoutline", "Outline", 0, 1, 1, true),
					new Setting("cl_crosshair_outlinethickness", "Outline thickness", 0, 3, 0.5, false)),
			List.of(new Setting("cl_crosshaircolor", "Colour", 0, 5, 1, false),
					new Setting("cl_crosshaircolor_r", "Red", 0, 255, 1, false),
					new Setting("cl_crosshaircolor_g", "Green", 0, 255, 1, false),
					new Setting("cl_crosshaircolor_b", "Blue", 0, 255, 1, false),
					new Setting("cl_crosshairusealpha", "Transparency", 0, 1, 1, true),
					new Setting("cl_crosshairalpha", "Alpha", 0, 255, 1, false)),
			List.of(new Setting("cl_crosshair_recoil", "Follow recoil", 0, 1, 1, true),
					new Setting("cl_crosshair_dynamic_splitdist", "Split distance", 0, 20, 1, false),
					new Setting("cl_crosshair_dynamic_splitalpha_innermod", "Inner alpha", 0, 1, 0.1, false),
					new Setting("cl_crosshair_dynamic_splitalpha_outermod", "Outer alpha", 0.3, 1, 0.1, false),
					new Setting("cl_crosshair_dynamic_maxdist_splitratio", "Split ratio", 0, 1, 0.05, false)));
	private static final String[] STYLES = {"Default", "Default static", "Classic", "Classic dynamic", "Classic static", "Legacy"};
	private static final String[] COLOURS = {"Red", "Green", "Yellow", "Blue", "Cyan", "Custom"};
	private int[] headerX = new int[3];
	private int headerY;

	private final Screen parent;
	private EditBox code;
	private int previewBottom;

	public CrosshairScreen(Screen parent) {
		super(Component.literal("Crosshair"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		previewBottom = Math.min(100, height / 4);
		int colW = Math.min(150, (width - 40) / 3), gap = 8, x0 = (width - 3 * colW - 2 * gap) / 2;
		headerY = previewBottom + 6;
		int top = headerY + 12, rows = 0;
		for (int c = 0; c < 3; c++) {
			int x = x0 + c * (colW + gap);
			headerX[c] = x;
			List<Setting> col = COLUMNS.get(c);
			rows = Math.max(rows, col.size());
			for (int i = 0; i < col.size(); i++) {
				Setting s = col.get(i);
				int y = top + i * 22;
				if (s.toggle) addRenderableWidget(Button.builder(label(s), b -> {
					set(s, Crosshair.get(s.cvar) != 0 ? 0 : 1);
					b.setMessage(label(s));
				}).bounds(x, y, colW, 20).build());
				else addRenderableWidget(new Slider(x, y, colW, s));
			}
		}
		// share codes: paste and import, or copy yours out
		int total = 3 * colW + 2 * gap, by = top + rows * 22 + 8;
		code = addRenderableWidget(new EditBox(font, x0, by, total - 2 * 84, 20, Component.literal("Share code")));
		code.setMaxLength(40);
		code.setHint(Component.literal("Paste a CSGO-... code to import"));
		addRenderableWidget(Button.builder(Component.literal("Import"), b -> {
			if (Crosshair.apply(code.getValue())) rebuildWidgets();
			else code.setValue("");
		}).bounds(x0 + total - 2 * 84 + 4, by, 80, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Copy my code"), b -> {
			String c = Crosshair.export();
			minecraft.keyboardHandler.setClipboard(c);
			code.setValue(c);
		}).bounds(x0 + total - 80, by, 80, 20).build());
		int bottom = Math.min(height - 24, by + 28);
		addRenderableWidget(Button.builder(Component.literal("Reset to default"), b -> {
			StrafeConfig.crosshair.clear();
			Crosshair.save();
			rebuildWidgets();
		}).bounds(width / 2 - 154, bottom, 150, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 + 4, bottom, 150, 20).build());
	}

	private static Component label(Setting s) {
		return Component.literal(s.label + ": " + (Crosshair.get(s.cvar) != 0 ? "On" : "Off"));
	}

	private static void set(Setting s, double v) {
		StrafeConfig.crosshair.put(s.cvar, v);
		Crosshair.save();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		// the preview: a sky-ish strip with the crosshair at its middle, at the game's real pixel size
		g.fill(0, 0, width, previewBottom, 0xFF5A7894);
		g.fill(0, previewBottom * 2 / 3, width, previewBottom, 0xFF6B5A44);
		Crosshair.preview(g, width / 2f, previewBottom / 2f);
		g.text(font, "Crosshair preview", 6, 6, 0xFFFFFFFF, true);
		for (int c = 0; c < 3; c++) g.text(font, SECTIONS[c], headerX[c], headerY, 0xFFE0C060, true);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	private class Slider extends AbstractSliderButton {
		private final Setting s;

		Slider(int x, int y, int w, Setting s) {
			super(x, y, w, 20, Component.empty(), (Crosshair.get(s.cvar) - s.min) / (s.max - s.min));
			this.s = s;
			updateMessage();
		}

		private double v() {
			double raw = s.min + value * (s.max - s.min);
			return Math.round(raw / s.step) * s.step;
		}

		@Override
		protected void updateMessage() {
			double v = v();
			String shown = s.step >= 1 ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.2f", v);
			if (s.cvar.equals("cl_crosshairstyle")) shown = STYLES[(int) Math.max(0, Math.min(5, v))];
			if (s.cvar.equals("cl_crosshaircolor")) shown = COLOURS[(int) Math.max(0, Math.min(5, v))];
			setMessage(Component.literal(s.label + ": " + shown));
		}

		@Override
		protected void applyValue() {
			set(s, v());
		}
	}
}
