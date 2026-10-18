package dev.strafemc.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** The spray key (G by default; CS2's T is Minecraft's chat): held, it shows the graffiti wheel (SprayWheelScreen). */
public final class SprayKey {
	private static KeyMapping KEY;

	private SprayKey() {}

	public static void register() {
		KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.strafemc.spray", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G,
				dev.strafemc.client.console.ConsoleKeys.CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (KEY.consumeClick()) if (mc.screen == null && mc.player != null) mc.setScreen(new SprayWheelScreen());
		});
	}

	/** The key's name as bound ("G"). */
	static String name() {
		return KEY == null ? "G" : KEY.getTranslatedKeyMessage().getString();
	}

	static boolean matches(net.minecraft.client.input.KeyEvent e) {
		return KEY != null && KEY.matches(e);
	}
}
