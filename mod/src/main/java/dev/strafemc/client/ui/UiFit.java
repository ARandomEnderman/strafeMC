package dev.strafemc.client.ui;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * strafeMC's menus are laid out for at least a given number of GUI pixels. When the player's GUI scale would make the
 * screen smaller than that (e.g. 1280x720 at auto scale = 427x240), the next smaller whole GUI scale is used while the
 * menu is open, the way Minecraft's auto scale picks one: text stays pixel-sharp and mouse input needs no remapping.
 * The scale goes back as soon as the menu closes (MinecraftUiFitMixin).
 */
public final class UiFit {
	/** The usual minimum for a full-screen strafeMC menu (a 1280x720 window at GUI scale 2, 1080p at 3). */
	public static final int MIN_W = 640, MIN_H = 360;

	/** A menu that needs at least minWidth x minHeight GUI pixels. */
	public interface Sized {
		default int minWidth() {
			return MIN_W;
		}

		default int minHeight() {
			return MIN_H;
		}
	}

	private UiFit() {}

	/** The GUI scale to use with this screen open: the chosen one, lowered until the screen gets its minimum size. */
	public static int scale(int scale, Screen screen) {
		if (!(screen instanceof Sized s)) return scale;
		Window w = Minecraft.getInstance().getWindow();
		while (scale > 1 && (ceilDiv(w.getWidth(), scale) < s.minWidth() || ceilDiv(w.getHeight(), scale) < s.minHeight())) scale--;
		return scale;
	}

	/** The GUI scale the player's options give, without any fitting. */
	public static int chosen(Minecraft mc) {
		return mc.getWindow().calculateScale(mc.options.guiScale().get(), mc.isEnforceUnicode());
	}

	private static int ceilDiv(int a, int b) {
		return (a + b - 1) / b;
	}
}
