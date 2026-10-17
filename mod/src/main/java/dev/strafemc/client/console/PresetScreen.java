package dev.strafemc.client.console;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Saved movement presets: save the current CS2 movement cvars under a name, load or delete saved ones. */
public final class PresetScreen extends Screen {
	private final Screen parent;
	private EditBox name;
	private int scroll;

	public PresetScreen(Screen parent) {
		super(Component.literal("Movement presets"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int cx = width / 2, y = 30;
		name = new EditBox(font, cx - 150, y, 196, 20, Component.literal("preset name"));
		name.setHint(Component.literal("preset name"));
		name.setMaxLength(32);
		addRenderableWidget(name);
		addRenderableWidget(Button.builder(Component.literal("Save current"), b -> {
			String n = name.getValue().trim();
			if (!n.isEmpty() && Presets.save(n)) rebuildWidgets();
		}).bounds(cx + 50, y, 100, 20).build());
		y += 28;
		addRenderableWidget(Button.builder(Component.literal("Load CS2 defaults"), b -> {
			ConsoleCommands.execute("strafemc_reset_movement");
		}).bounds(cx - 150, y, 300, 20).build());
		y += 28;
		var names = Presets.names();
		int rows = Math.max(1, (height - y - 40) / 24);
		scroll = Math.max(0, Math.min(scroll, Math.max(0, names.size() - rows)));
		for (int i = scroll; i < Math.min(names.size(), scroll + rows); i++) {
			String n = names.get(i);
			Button label = Button.builder(Component.literal(n), b -> {}).bounds(cx - 150, y, 150, 20).build();
			label.active = false;
			addRenderableWidget(label);
			addRenderableWidget(Button.builder(Component.literal("Load"), b -> ConsoleCommands.execute("strafemc_preset_load " + n))
					.bounds(cx + 4, y, 70, 20).build());
			addRenderableWidget(Button.builder(Component.literal("§cDelete"), b -> {
				Presets.delete(n);
				rebuildWidgets();
			}).bounds(cx + 80, y, 70, 20).build());
			y += 24;
		}
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 26, 200, 20).build());
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		scroll = Math.max(0, scroll - (int) Math.signum(sy));
		rebuildWidgets();
		return true;
	}

	/** The settings menu's dark backdrop instead of the blurred panorama / world: text stays readable. */
	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float partial) {
		g.fill(0, 0, width, height, dev.strafemc.client.StrafeSettingsScreen.BG);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
		super.extractRenderState(g, mx, my, partial);
		g.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
		if (Presets.names().isEmpty()) g.centeredText(font, "No saved presets yet", width / 2, 94, 0xFF9A9A9A);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
