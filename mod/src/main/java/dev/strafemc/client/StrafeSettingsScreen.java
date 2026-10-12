package dev.strafemc.client;

import dev.strafemc.StrafeMc;
import dev.strafemc.client.console.Binds;
import dev.strafemc.config.StrafeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** Options > "strafeMC...": personal settings every player can change (no operator needed), plus your console binds. */
public final class StrafeSettingsScreen extends OptionsSubScreen {
	private final Screen parent;

	public StrafeSettingsScreen(Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.translatable("strafemc.options.title"));
		this.parent = parent;
	}

	@Override
	protected void addOptions() {
		list.addSmall(
				bool("strafemc.options.scrolljump", StrafeConfig.scrollJump, v -> StrafeConfig.scrollJump = v),
				bool("strafemc.options.showspeed", StrafeConfig.showSpeed, v -> StrafeConfig.showSpeed = v),
				bool("strafemc.options.doubletapfly", StrafeConfig.doubleTapFly, v -> StrafeConfig.doubleTapFly = v));

		list.addHeader(Component.translatable("strafemc.options.setup"));
		list.addSmall(bool("strafemc.options.setup_ask", !StrafeConfig.setupNeverAsk, v -> StrafeConfig.setupNeverAsk = !v).createButton(options),
				Button.builder(Component.translatable("strafemc.options.setup_open"), b -> SetupScreen.open(this)).build());

		list.addHeader(Component.translatable("strafemc.options.binds"));
		var binds = Binds.all();
		if (binds.isEmpty()) {
			Button none = Button.builder(Component.translatable("strafemc.options.nobinds"), b -> {}).build();
			none.active = false;
			list.addSmall(java.util.List.of(none));
		}
		for (var e : binds.entrySet()) {
			Button label = Button.builder(Component.literal(e.getKey() + "  →  " + e.getValue()), b -> {}).build();
			label.active = false;
			Button remove = Button.builder(Component.translatable("strafemc.options.removebind"), b -> {
				Binds.unbind(e.getKey());
				minecraft.setScreen(new StrafeSettingsScreen(parent));
			}).build();
			list.addSmall(label, remove);
		}
	}

	private static OptionInstance<Boolean> bool(String key, boolean value, Consumer<Boolean> set) {
		return OptionInstance.createBoolean(key, value, v -> {
			set.accept(v);
			try {
				StrafeConfig.save();
			} catch (Exception e) {
				StrafeMc.LOG.warn("[strafemc] config save failed: {}", e.toString());
			}
		});
	}
}
