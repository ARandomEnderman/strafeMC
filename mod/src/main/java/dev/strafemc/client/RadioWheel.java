package dev.strafemc.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.strafemc.data.RadioTable;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * CS2's chat wheel (radialradio): hold the radio key (Z, +radialradio) and the wheel opens over the game; you keep
 * moving while the mouse steers a cursor instead of the view. Point at one of the eight phrases and let go to send it
 * to your team (playerchatwheel); a quick tap with nothing picked pings instead (cl_radial_radio_tap_to_ping 1, within
 * cl_radial_menu_tap_duration 0.2 s). Middle mouse or the mouse wheel switches wheels 1-3 (the heading's mouse-button
 * icon); left click sends too. The cursor has to leave the dead zone (cl_radialmenu_deadzone_size 0.4 of the icon
 * radius) to pick; the last pick holds for cl_radial_coyote_time 0.15 s after it comes back. Ping key: middle mouse
 * (player_ping). Look from radialradio.vcss at 1080p: the screen dims (#00000099), a black middle disc (r 98),
 * eight wedges r 99-161 (#000000d9 fading out; the picked one with a light inner edge), icons 90 px at
 * cl_radial_menu_icon_radius 200, the picked phrase's name (26 px, caps) above the middle, the wheel's name under it.
 */
public final class RadioWheel {
	private static final int SLOTS = 8;
	private static final double ICON_R = 200, DEADZONE = 0.4, COYOTE = 0.15, TAP = 0.2;
	private static KeyMapping RADIO, PING;
	private static boolean open, keyHeld;
	private static int wheel;
	private static long openedAt, pickedAt;
	/** The virtual cursor, in 1080p pixels from the middle. */
	private static double cx, cy;
	private static int picked = -1;

	private RadioWheel() {}

	public static void register() {
		RADIO = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.strafemc.radio", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z,
				dev.strafemc.client.console.ConsoleKeys.CATEGORY));
		PING = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.strafemc.ping", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_MIDDLE,
				dev.strafemc.client.console.ConsoleKeys.CATEGORY));
		// before Minecraft handles its keys: a ping on the pick-block button doesn't also pick a block
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			boolean down = RADIO.isDown() && mc.screen == null && mc.player != null;
			if (down && !keyHeld && teamPlay()) press(0);
			else if (!down && keyHeld) release();
			keyHeld = down;
			while (PING.consumeClick()) {
				if (!teamPlay()) continue; // elsewhere the button stays Minecraft's (pick block on the same button by default)
				if (open) next();
				else if (mc.screen == null && mc.player != null) ping();
				if (PING.same(mc.options.keyPickItem)) while (mc.options.keyPickItem.consumeClick()) {}
			}
			if (open) {
				while (mc.options.keyAttack.consumeClick()) { // left click sends what's picked
					send(true);
					close();
				}
				if (mc.screen != null || mc.player == null) close();
			}
		});
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("strafemc", "radio_wheel"), (g, delta) -> draw(g));
	}

	/** In a game mode or on a game mode server: where the radio and pings go somewhere. */
	static boolean teamPlay() {
		var st = ModeHud.state();
		return ModeServerClient.on() || st != null && st.has("mode");
	}

	public static boolean open() {
		return open;
	}

	/** +radialradio / +radialradio2 / +radialradio3. */
	public static void press(int which) {
		if (open) return;
		open = true;
		wheel = which;
		openedAt = System.nanoTime();
		cx = cy = 0;
		picked = -1;
	}

	/** -radialradio: send the picked phrase, or ping on a quick tap. */
	public static void release() {
		if (!open) return;
		send(false);
		close();
	}

	private static void close() {
		open = false;
	}

	/** The middle mouse button or a wheel notch while open: the next wheel (1 -> 2 -> 3 -> 1). */
	public static void next() {
		if (!open) return;
		wheel = (wheel + 1) % RadioTable.WHEELS.length;
		picked = -1;
		var pl = Minecraft.getInstance().player;
		if (pl != null) dev.strafemc.client.sound.CsSounds.playOn("UIPanorama.generic_button_rollover", pl, 1.0, false);
	}

	/** Mouse movement while open moves the cursor (called from the turn instead of turning). */
	public static void mouse(double dx, double dy) {
		double max = ICON_R * 1.2;
		cx += dx * 0.5;
		cy += dy * 0.5;
		double len = Math.hypot(cx, cy);
		if (len > max) {
			cx *= max / len;
			cy *= max / len;
		}
	}

	private static void send(boolean click) {
		int slot = current();
		double held = (System.nanoTime() - openedAt) / 1e9;
		if (slot < 0) {
			if (!click && held <= TAP) ping();
			return;
		}
		RadioTable.Phrase ph = RadioTable.byText(RadioTable.WHEELS[wheel][slot]);
		if (ph != null && ClientPlayNetworking.canSend(dev.strafemc.net.StrafePayloads.Action.TYPE))
			ClientPlayNetworking.send(new dev.strafemc.net.StrafePayloads.Action("playerchatwheel", ph.radio()));
	}

	public static void ping() {
		if (ClientPlayNetworking.canSend(dev.strafemc.net.StrafePayloads.Action.TYPE))
			ClientPlayNetworking.send(new dev.strafemc.net.StrafePayloads.Action("player_ping", ""));
	}

	/** The slot the cursor points at (0 = top, clockwise), the last one within the coyote time, or -1. */
	private static int current() {
		long now = System.nanoTime();
		if (Math.hypot(cx, cy) >= DEADZONE * ICON_R) {
			double a = Math.atan2(cx, -cy); // 0 up, clockwise
			a = (a + Math.PI / SLOTS + 2 * Math.PI) % (2 * Math.PI);
			picked = (int) (a / (2 * Math.PI / SLOTS)) % SLOTS;
			pickedAt = now;
			return picked;
		}
		return picked >= 0 && (now - pickedAt) / 1e9 <= COYOTE ? picked : -1;
	}

	private static void draw(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (!open || mc.options.hideGui) return;
		double u = g.guiHeight() / 1080.0;
		int w = g.guiWidth(), h = g.guiHeight(), mx = w / 2, my = h / 2;
		g.fill(0, 0, w, h, 0x99000000);
		int sel = current();
		disc(g, mx, my, 98 * u, 0xBF000000);
		for (int i = 0; i < SLOTS; i++) wedge(g, mx, my, i, u, i == sel);
		var font = mc.font;
		String[] slots = RadioTable.WHEELS[wheel];
		for (int i = 0; i < SLOTS; i++) {
			RadioTable.Phrase ph = RadioTable.byText(slots[i]);
			double a = i * 2 * Math.PI / SLOTS;
			double ix = mx + Math.sin(a) * ICON_R * u, iy = my - Math.cos(a) * ICON_R * u;
			String rel = ph == null ? null : "gui/radio/" + iconName(ph.icon()) + ".png";
			int s = (int) Math.round(90 * u * (i == sel ? 1.0 : 0.9));
			int tint = i == sel ? 0xFFFFFFFF : 0xFFCCCCCC;
			if (rel != null && dev.strafemc.client.assets.RuntimePack.hasTexture(rel))
				g.blit(RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath("strafemc", "textures/" + rel), (int) Math.round(ix - s / 2.0),
						(int) Math.round(iy - s / 2.0), 0, 0, s, s, s, s, s, s, tint);
			else { // no CS2 icon yet: the phrase itself
				String t = ph == null ? "?" : font.plainSubstrByWidth(CsText.get(ph.text().substring(1), ph.text()), (int) (140 * u));
				g.text(font, t, (int) Math.round(ix - font.width(t) / 2.0), (int) Math.round(iy - 4), tint, true);
			}
		}
		// the picked phrase over the middle, the wheel's name and the switch button under it
		if (sel >= 0) {
			RadioTable.Phrase ph = RadioTable.byText(slots[sel]);
			if (ph != null) {
				String name = CsText.get(ph.text().substring(1), ph.text()).toUpperCase(java.util.Locale.ROOT);
				text(g, font, name, mx, my - 50 * u, 26 * u, 150 * u, 0xFFFFFFFF);
			}
		}
		String head = ("Chatwheel " + (wheel + 1)).toUpperCase(java.util.Locale.ROOT);
		double hw = text(g, font, head, mx - 18 * u, my + 60 * u, 24 * u, 400 * u, 0xFFFFFFFF);
		String mmb = "gui/radio/middle_mouse_button.png";
		if (dev.strafemc.client.assets.RuntimePack.hasTexture(mmb)) {
			int iw = (int) Math.round(24 * u), ih = (int) Math.round(20 * u);
			g.blit(RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath("strafemc", "textures/" + mmb), (int) Math.round(mx - 18 * u + hw / 2 + 12 * u),
					(int) Math.round(my + 60 * u - 12 * u + 4 * u), 0, 0, iw, ih, iw, ih, iw, ih, 0xFFFFFFFF);
		}
	}

	/** "icons/ui/chatwheel_gogogo" -> "chatwheel_gogogo" (the runtime pack's file name). */
	public static String iconName(String icon) {
		return icon.substring(icon.lastIndexOf('/') + 1);
	}

	/** Every icon the wheel can show (panorama/images/<icon>.vsvg), for the runtime pack. */
	public static java.util.Set<String> icons() {
		java.util.Set<String> out = new java.util.TreeSet<>();
		for (RadioTable.Phrase p : RadioTable.all()) out.add(p.icon());
		return out;
	}

	private static void disc(GuiGraphicsExtractor g, int cx, int cy, double r, int colour) {
		int ri = (int) Math.ceil(r);
		for (int y = -ri; y <= ri; y++) {
			int half = (int) Math.floor(Math.sqrt(Math.max(0, r * r - y * y)));
			if (half > 0) g.fill(cx - half, cy + y, cx + half, cy + y + 1, colour);
		}
	}

	/** One 45 deg wedge of the ring (r 99-161 at 1080p): black fading outwards; the picked one lit at its inner edge. */
	private static void wedge(GuiGraphicsExtractor g, int cx, int cy, int i, double u, boolean hot) {
		double r0 = 99 * u, r1 = 161 * u, gap = Math.toRadians(1.2);
		double a0 = (i - 0.5) * 2 * Math.PI / SLOTS + gap, a1 = (i + 0.5) * 2 * Math.PI / SLOTS - gap;
		int steps = Math.max(12, (int) (r1 * (a1 - a0) / 2));
		int rings = Math.max(6, (int) ((r1 - r0) / 2));
		for (int k = 0; k < rings; k++) {
			double f = (k + 0.5) / rings, r = r0 + (r1 - r0) * f;
			int colour;
			if (!hot) colour = (int) (0xD9 * (1 - f)) << 24;
			else if (f < 0.05) colour = 0xD9EEEEEE; // the picked wedge's light inner edge
			else if (f < 0.5) colour = (int) (0xFF + (0x80 - 0xFF) * (f - 0.05) / 0.45) << 24;
			else colour = (int) (0x80 * (1 - (f - 0.5) / 0.5)) << 24;
			int th = Math.max(1, (int) Math.ceil((r1 - r0) / rings));
			for (int s = 0; s <= steps; s++) {
				double a = a0 + (a1 - a0) * s / steps;
				int x = (int) Math.round(cx + Math.sin(a) * r), y = (int) Math.round(cy - Math.cos(a) * r);
				g.fill(x, y, x + th, y + th, colour);
			}
		}
	}

	/** Centred text at size px, shrunk to fit maxW (text-overflow: shrink); returns its width. */
	private static double text(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, String s, double x, double y, double size, double maxW,
			int colour) {
		float k = (float) (size / 9.0);
		double w = font.width(s) * k;
		if (w > maxW) {
			k *= (float) (maxW / w);
			w = maxW;
		}
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate((float) (x - w / 2), (float) (y - 4.5 * k));
		pose.scale(k, k);
		g.text(font, s, 0, 0, colour, true);
		pose.popMatrix();
		return w;
	}
}
