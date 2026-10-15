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

	/** Deletes what the setup made, in the background (it's large), and clears the paths it found. */
	private static void resetSetup() {
		StrafeConfig.vrfPath = ""; // the CS2 folder stays (it picks which install the setup and the CS2 server use)
		StrafeConfig.setupNeverAsk = false;
		try {
			StrafeConfig.save();
		} catch (Exception ignored) {
		}
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
					dev.strafemc.StrafeMc.LOG.warn("[strafemc] setup reset: {}", e.toString());
				}
			}
			dev.strafemc.StrafeMc.LOG.info("[strafemc] setup reset: done, restart Minecraft");
			net.minecraft.client.Minecraft.getInstance().execute(() -> {
				var p = net.minecraft.client.Minecraft.getInstance().player;
				if (p != null) p.sendOverlayMessage(Component.literal("Setup reset. Restart Minecraft to run it again."));
			});
		}, "strafemc-setup-reset");
		t.setDaemon(true);
		t.start();
	}

	@Override
	protected void addOptions() {
		list.addSmall(
				bool("strafemc.options.scrolljump", StrafeConfig.scrollJump, v -> StrafeConfig.scrollJump = v),
				bool("strafemc.options.showspeed", StrafeConfig.showSpeed, v -> StrafeConfig.showSpeed = v),
				bool("strafemc.options.doubletapfly", StrafeConfig.doubleTapFly, v -> StrafeConfig.doubleTapFly = v));

		list.addSmall(Button.builder(Component.literal("Crosshair editor"), b -> minecraft.setScreen(new CrosshairScreen(this))).build(),
				Button.builder(Component.literal("Loadout"), b -> LoadoutKey.open()).build());

		list.addHeader(Component.translatable("strafemc.options.setup"));
		list.addSmall(bool("strafemc.options.setup_ask", !StrafeConfig.setupNeverAsk, v -> StrafeConfig.setupNeverAsk = !v).createButton(options),
				Button.builder(Component.translatable("strafemc.options.setup_open"), b -> SetupScreen.open(this)).build());
		list.addSmall(Button.builder(Component.literal("Reset setup (test from scratch)"), b -> minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(
				yes -> {
					if (yes) resetSetup();
					minecraft.setScreen(this);
				},
				Component.literal("Reset the first-time setup?"),
				Component.literal("Deletes everything the setup made (the converted CS2 files, about 10 GB, the generated resource pack and the "
						+ "downloaded converter). Your CS2 install isn't touched and stays selected. Restart Minecraft "
						+ "afterwards: the setup starts again like on a new PC.")))).build(), null);

		list.addHeader(Component.translatable("strafemc.options.hosting"));
		list.addSmall(bool("strafemc.options.hostserver", StrafeConfig.hostServer, v -> StrafeConfig.hostServer = v).createButton(options),
				Button.builder(Component.translatable("strafemc.options.hosting_about"), b -> minecraft.setScreen(new HostingInfoScreen(this))).build());

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
