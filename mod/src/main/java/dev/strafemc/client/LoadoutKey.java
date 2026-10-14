package dev.strafemc.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** Opens the loadout (key U by default): the server opens the menu. */
public final class LoadoutKey {
	private static KeyMapping KEY;

	private LoadoutKey() {}

	public static void register() {
		KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.strafemc.loadout", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U,
				dev.strafemc.client.console.ConsoleKeys.CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (KEY.consumeClick()) if (mc.screen == null && mc.player != null) open();
		});
	}

	public static void open() {
		if (ClientPlayNetworking.canSend(dev.strafemc.net.StrafePayloads.Action.TYPE))
			ClientPlayNetworking.send(new dev.strafemc.net.StrafePayloads.Action("loadout", ""));
	}
}
