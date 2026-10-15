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

	private static final List<Setting> SETTINGS = List.of(
			new Setting("cl_crosshairstyle", "Style", 0, 5, 1, false),
			new Setting("cl_crosshairsize", "Length", 0, 20, 0.5, false),
			new Setting("cl_crosshairthickness", "Thickness", 0, 6, 0.1, false),
			new Setting("cl_crosshairgap", "Gap", -10, 10, 0.5, false),
			new Setting("cl_crosshaircolor", "Colour (5 = custom)", 0, 5, 1, false),
			new Setting("cl_crosshaircolor_r", "Red", 0, 255, 1, false),
			new Setting("cl_crosshaircolor_g", "Green", 0, 255, 1, false),
			new Setting("cl_crosshaircolor_b", "Blue", 0, 255, 1, false),
			new Setting("cl_crosshairalpha", "Alpha", 0, 255, 1, false),
			new Setting("cl_crosshairusealpha", "Use alpha", 0, 1, 1, true),
			new Setting("cl_crosshairdot", "Centre dot", 0, 1, 1, true),
			new Setting("cl_crosshair_drawoutline", "Outline", 0, 1, 1, true),
			new Setting("cl_crosshair_outlinethickness", "Outline thickness", 0, 3, 0.5, false),
			new Setting("cl_crosshair_t", "T style", 0, 1, 1, true),
			new Setting("cl_crosshair_recoil", "Follow recoil", 0, 1, 1, true),
			new Setting("cl_crosshair_dynamic_splitdist", "Split distance", 0, 20, 1, false),
			new Setting("cl_crosshair_dynamic_splitalpha_innermod", "Inner split alpha", 0, 1, 0.1, false),
			new Setting("cl_crosshair_dynamic_splitalpha_outermod", "Outer split alpha", 0.3, 1, 0.1, false),
			new Setting("cl_crosshair_dynamic_maxdist_splitratio", "Split size ratio", 0, 1, 0.05, false));

	private final Screen parent;
	private EditBox code;
	private int previewBottom;

	public CrosshairScreen(Screen parent) {
		super(Component.literal("Crosshair"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		previewBottom = Math.min(110, height / 3);
		int colW = 150, gap = 6, cols = Math.max(1, Math.min(3, (width - 20) / (colW + gap)));
		int x0 = (width - cols * colW - (cols - 1) * gap) / 2, y = previewBottom + 8;
		for (int i = 0; i < SETTINGS.size(); i++) {
			Setting s = SETTINGS.get(i);
			int x = x0 + (i % cols) * (colW + gap), yy = y + (i / cols) * 24;
			if (s.toggle) addRenderableWidget(Button.builder(label(s), b -> {
				set(s, Crosshair.get(s.cvar) != 0 ? 0 : 1);
				b.setMessage(label(s));
			}).bounds(x, yy, colW, 20).build());
			else addRenderableWidget(new Slider(x, yy, colW, s));
		}
		int rows = (SETTINGS.size() + cols - 1) / cols, by = y + rows * 24 + 6;
		code = addRenderableWidget(new EditBox(font, x0, by, Math.min(width - 2 * x0, cols * colW + (cols - 1) * gap) - 160, 20, Component.literal("Share code")));
		code.setMaxLength(40);
		code.setHint(Component.literal("CSGO-xxxxx-xxxxx-xxxxx-xxxxx-xxxxx"));
		addRenderableWidget(Button.builder(Component.literal("Apply code"), b -> {
			if (Crosshair.apply(code.getValue())) rebuildWidgets();
			else code.setValue("");
		}).bounds(code.getX() + code.getWidth() + 4, by, 76, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Reset"), b -> {
			StrafeConfig.crosshair.clear();
			Crosshair.save();
			rebuildWidgets();
		}).bounds(code.getX() + code.getWidth() + 84, by, 76, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 75, Math.min(height - 26, by + 28), 150, 20).build());
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
		g.text(font, "Crosshair", 6, 6, 0xFFFFFFFF, true);
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
			setMessage(Component.literal(s.label + ": " + (s.step >= 1 ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.2f", v))));
		}

		@Override
		protected void applyValue() {
			set(s, v());
		}
	}
}
