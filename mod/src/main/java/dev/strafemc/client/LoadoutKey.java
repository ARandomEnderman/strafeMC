package dev.strafemc.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** The loadout key (U by default) and the strafeMC menu key (V by default: loadout, care package, achievements, settings). */
public final class LoadoutKey {
	private static KeyMapping KEY, MENU;
	/** The loadout was opened from the strafeMC menu: closing it goes back there. */
	static boolean fromMenu;

	private LoadoutKey() {}

	public static void register() {
		KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.strafemc.loadout", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U,
				dev.strafemc.client.console.ConsoleKeys.CATEGORY));
		MENU = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.strafemc.menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V,
				dev.strafemc.client.console.ConsoleKeys.CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (KEY.consumeClick()) if (mc.screen == null && mc.player != null) {
				fromMenu = false;
				open();
			}
			while (MENU.consumeClick()) if (mc.screen == null && mc.player != null) mc.setScreen(new QuickMenuScreen(true));
		});
	}

	static boolean matches(net.minecraft.client.input.KeyEvent e) {
		return MENU != null && MENU.matches(e);
	}

	/**
	 * The menu key pressed in one of the strafeMC menu's screens: closes it all (back to the game). Not while typing in a
	 * text box. True if it did.
	 */
	static boolean closesAll(net.minecraft.client.gui.screens.Screen s, net.minecraft.client.input.KeyEvent e) {
		if (!matches(e) || s.getFocused() instanceof net.minecraft.client.gui.components.EditBox) return false;
		fromMenu = false;
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
		mc.setScreen(null);
		return true;
	}

	/** The loadout itself: the server opens its menu. */
	public static void open() {
		if (ClientPlayNetworking.canSend(dev.strafemc.net.StrafePayloads.Action.TYPE))
			ClientPlayNetworking.send(new dev.strafemc.net.StrafePayloads.Action("loadout", ""));
	}
}
