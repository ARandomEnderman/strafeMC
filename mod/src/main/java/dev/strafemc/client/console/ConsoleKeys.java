package dev.strafemc.client.console;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permissions;
import org.lwjgl.glfw.GLFW;

/** Key bindings (Options > Controls > Key Binds > "strafeMC") and who may open the console. */
public final class ConsoleKeys {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("strafemc", "main"));
	public static KeyMapping CONSOLE;

	private ConsoleKeys() {}

	public static void register() {
		CONSOLE = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.strafemc.console", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_GRAVE_ACCENT, CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (CONSOLE.consumeClick()) {
				if (mc.screen != null || mc.player == null) continue;
				if (!allowed(mc)) {
					dev.strafemc.client.StrafeToasts.show("The CS2 console needs operator permission", 0xFFE04040);
					continue;
				}
				mc.setScreen(new ConsoleScreen());
			}
		});
	}

	/**
	 * Singleplayer: always (you own the server). Multiplayer (roadmap): only operators. The real gate for anything
	 * that changes the game lives on the Minecraft server (see dev.strafemc.server.Access); this only hides the UI.
	 */
	public static boolean allowed(Minecraft mc) {
		return true; // everyone may open it; the Minecraft server decides what each player may run (server.Access)
	}
}
