package dev.strafemc.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * strafeMC's notices: small panels in the bottom left, the newest at the bottom, each sliding in, staying a few
 * seconds and fading out; a coloured edge says what kind (gold: news, red: can't do that, green: got something). The
 * same text again while it's still up restarts it instead of stacking.
 */
public final class StrafeToasts {
	private static final long SHOW_MS = 4000, IN_MS = 150, OUT_MS = 400;
	private static final int MAX = 4, WIDTH = 190;

	private record Toast(String text, int color, long at) {}

	private static final List<Toast> UP = new ArrayList<>();

	private StrafeToasts() {}

	public static void show(String text, int color) {
		Minecraft.getInstance().execute(() -> {
			UP.removeIf(t -> t.text().equals(text));
			UP.add(new Toast(text, color, System.currentTimeMillis()));
			while (UP.size() > MAX) UP.remove(0);
		});
	}

	public static void register() {
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(net.minecraft.resources.Identifier.fromNamespaceAndPath("strafemc", "toasts"),
				(g, delta) -> draw(g));
	}

	private static void draw(GuiGraphicsExtractor g) {
		if (UP.isEmpty()) return;
		long now = System.currentTimeMillis();
		UP.removeIf(t -> now - t.at() > SHOW_MS + OUT_MS);
		var font = Minecraft.getInstance().font;
		int y = g.guiHeight() - 6;
		for (int i = UP.size() - 1; i >= 0; i--) { // newest at the bottom
			Toast t = UP.get(i);
			long age = now - t.at();
			var lines = font.split(Component.literal(t.text()), WIDTH - 14);
			int h = 8 + lines.size() * 10;
			float in = Math.min(1, age / (float) IN_MS), alpha = age < SHOW_MS ? 1 : Math.max(0, 1 - (age - SHOW_MS) / (float) OUT_MS);
			int x = 6 - Math.round((1 - in) * (WIDTH + 10)), a = Math.round(alpha * 255);
			if (a <= 8) continue;
			y -= h;
			g.fill(x, y, x + WIDTH, y + h, (Math.round(alpha * 0xE0) << 24) | 0x101317);
			g.fill(x, y, x + 2, y + h, (a << 24) | (t.color() & 0xFFFFFF));
			int ty = y + 4;
			for (var line : lines) {
				g.text(font, line, x + 8, ty, (a << 24) | 0xFFFFFF, false);
				ty += 10;
			}
			y -= 4;
		}
	}
}
