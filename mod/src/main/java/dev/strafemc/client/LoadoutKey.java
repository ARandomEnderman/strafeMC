package dev.strafemc.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** The loadout key (U by default) and the strafeMC menu key (the ISO "<" key by default: loadout, care package, achievements, settings). */
public final class LoadoutKey {
	private static KeyMapping KEY, MENU;

	private LoadoutKey() {}

	public static void register() {
		KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.strafemc.loadout", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U,
				dev.strafemc.client.console.ConsoleKeys.CATEGORY));
		// "<" on ISO keyboards (left of Z): GLFW's second non-US key on Windows
		MENU = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.strafemc.menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_WORLD_2,
				dev.strafemc.client.console.ConsoleKeys.CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (KEY.consumeClick()) if (mc.screen == null && mc.player != null) open();
			while (MENU.consumeClick()) if (mc.screen == null && mc.player != null) mc.setScreen(new QuickMenuScreen());
		});
	}

	static boolean matches(net.minecraft.client.input.KeyEvent e) {
		return MENU != null && MENU.matches(e);
	}

	/** The loadout itself: the server opens its menu. */
	public static void open() {
		if (ClientPlayNetworking.canSend(dev.strafemc.net.StrafePayloads.Action.TYPE))
			ClientPlayNetworking.send(new dev.strafemc.net.StrafePayloads.Action("loadout", ""));
	}
}
