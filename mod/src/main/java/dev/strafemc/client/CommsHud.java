package dev.strafemc.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.strafemc.data.RadioTable;
import dev.strafemc.net.StrafePayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Team comms as CS2 shows them (mode/Comms sends them): a chat wheel message is the sender's name and the phrase in
 * chat (CS2's Game_radio " %s4%s1: %s2"), their agent's voice line for it on the radio, and the radio icon over their
 * head (radio_voice); a ping is the playerping arrow in the pinger's colour where they looked (on the player they
 * looked at, following them), seen through walls, with UI.PlayerPing / UI.PlayerPingUrgent there and a ring flashing
 * out from it. One ping per player: a new one replaces it. Pings fade near the crosshair (cl_ping_fade_deadzone 60 to
 * cl_ping_fade_distance 300, read as 1080p pixels from the middle: FLAGGED). FLAGGED ours: the arrow is a fixed 32 px
 * screen marker (CS2's is a sprite in the world), the radio icon shows for RADIO_ICON s, phrases a voice has no line for
 * play PanoramaUI.Chatwheel.Alert.
 */
public final class CommsHud {
	private static final int CT = 0xFF1ED2FF, T = 0xFFFFDD23;
	/** CS2's cl_teammate_color_1-5. */
	private static final int[] COLOURS = {0xFF88CEF5, 0xFF009E80, 0xFFF1E441, 0xFFE6802A, 0xFFBD2C96};
	private static final double RADIO_ICON = 2.0, FLASH = 0.5, FADE_IN = 60, FADE_OUT = 300;
	private static final Random RNG = new Random();

	private record Ping(int owner, double x, double y, double z, int on, int colour, boolean urgent, long at, double life) {}

	private static final Map<Integer, Ping> PINGS = new HashMap<>();
	/** Who's talking on the radio: entity id -> until (System.nanoTime). */
	private static final Map<Integer, Long> TALKING = new HashMap<>();

	private CommsHud() {}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(StrafePayloads.Comms.TYPE, (msg, ctx) -> ctx.client().execute(() -> receive(msg.json())));
		ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> {
			PINGS.clear();
			TALKING.clear();
		});
		HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CROSSHAIR,
				Identifier.fromNamespaceAndPath("strafemc", "comms"), (g, delta) -> draw(g));
	}

	private static void receive(String json) {
		JsonObject o;
		try {
			o = JsonParser.parseString(json).getAsJsonObject();
		} catch (Exception e) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		String k = o.get("k").getAsString();
		int colour = o.has("c") ? COLOURS[Math.floorMod(o.get("c").getAsInt(), COLOURS.length)] : o.get("t") != null && o.get("t").getAsBoolean() ? T : CT;
		switch (k) {
			case "cooldown" -> StrafeToasts.show(CsText.get("Chatwheel_ping_on_cooldown", "Cannot ping. Ping is on cooldown."), 0xFFE04040);
			case "radio" -> {
				String text = o.get("text").getAsString();
				String line = CsText.get(text.substring(1), text);
				// CS2's radio line in team chat: the name in the sender's colour, then the phrase
				mc.gui.getChat().addClientSystemMessage(Component.literal(o.get("name").getAsString()).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(colour & 0xFFFFFF)))
						.append(Component.literal(": " + line).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF)))));
				var lines = RadioTable.lines(o.get("voice").getAsString(), o.get("radio").getAsString());
				String ev = lines.isEmpty() ? "PanoramaUI.Chatwheel.Alert" : lines.get(RNG.nextInt(lines.size()));
				dev.strafemc.client.sound.CsSounds.playOn(ev, mc.player, 1.0, false); // the radio: heard as your own, not from them
				TALKING.put(o.get("id").getAsInt(), System.nanoTime() + (long) (RADIO_ICON * 1e9));
			}
			case "ping" -> {
				Ping p = new Ping(o.get("id").getAsInt(), o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble(), o.get("on").getAsInt(),
						colour, o.get("urgent").getAsBoolean(), System.nanoTime(), o.get("life").getAsDouble());
				PINGS.put(p.owner(), p);
				dev.strafemc.client.sound.CsSounds.play(p.urgent() ? "UI.PlayerPingUrgent" : "UI.PlayerPing", p.x(), p.y(), p.z(), 1.0);
			}
			default -> {}
		}
	}

	private static void draw(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.options.hideGui || (PINGS.isEmpty() && TALKING.isEmpty())) return;
		long now = System.nanoTime();
		var cam = mc.gameRenderer.getMainCamera();
		Matrix4f m = cam.getViewRotationProjectionMatrix(new Matrix4f());
		Vec3 eye = cam.position();
		double u = g.guiHeight() / 1080.0;
		PINGS.values().removeIf(p -> (now - p.at()) / 1e9 > p.life());
		for (Ping p : PINGS.values()) {
			Vec3 at = new Vec3(p.x(), p.y(), p.z());
			if (p.on() >= 0) { // on a player: their head, wherever they go
				Entity e = mc.level.getEntity(p.on());
				if (e != null) at = new Vec3(e.getX(), e.getY() + e.getBbHeight(), e.getZ());
			}
			double[] s = screen(m, at.subtract(eye), g);
			if (s == null) continue;
			double age = (now - p.at()) / 1e9;
			double fade = Math.min(1, Math.min(age / 0.15, (p.life() - age) / 0.5)); // in quickly, out over the last half second
			double d = Math.hypot(s[0] - g.guiWidth() / 2.0, s[1] - g.guiHeight() / 2.0) / u;
			fade *= 0.35 + 0.65 * Math.max(0, Math.min(1, (d - FADE_IN) / (FADE_OUT - FADE_IN)));
			int alpha = (int) (255 * Math.max(0, fade));
			if (age < FLASH) { // the ring flashing out from the spot
				double f = age / FLASH;
				int rs = (int) Math.round((16 + 48 * f) * u);
				blit(g, "gui/ping/ring.png", s[0] - rs / 2.0, s[1] - rs / 2.0, rs, rs, a(p.colour(), (int) (alpha * (1 - f))));
			}
			int size = (int) Math.round(32 * u * (p.urgent() ? 1.25 : 1));
			blit(g, "gui/ping/arrow_outline.png", s[0] - size / 2.0, s[1] - size, size, size, a(0xFF000000, alpha));
			blit(g, "gui/ping/arrow.png", s[0] - size / 2.0, s[1] - size, size, size, a(p.colour(), alpha));
		}
		TALKING.values().removeIf(t -> t < now);
		for (var en : TALKING.entrySet()) {
			Entity e = mc.level.getEntity(en.getKey());
			if (e == null || e == mc.player || !e.isAlive()) continue;
			double[] s = screen(m, new Vec3(e.getX(), e.getY() + e.getBbHeight() + 0.5, e.getZ()).subtract(eye), g);
			if (s == null) continue;
			int size = (int) Math.round(28 * u);
			blit(g, "gui/ping/radio.png", s[0] - size / 2.0, s[1] - size / 2.0, size, size, 0xFFFFFFFF);
		}
	}

	/** A spot relative to the camera on the GUI, or null when it's behind. */
	private static double[] screen(Matrix4f m, Vec3 rel, GuiGraphicsExtractor g) {
		Vector4f v = new Vector4f((float) rel.x, (float) rel.y, (float) rel.z, 1).mul(m);
		if (v.w <= 0.05f) return null;
		return new double[] {(v.x / v.w + 1) / 2 * g.guiWidth(), (1 - v.y / v.w) / 2 * g.guiHeight()};
	}

	private static void blit(GuiGraphicsExtractor g, String rel, double x, double y, int w, int h, int colour) {
		if (!dev.strafemc.client.assets.RuntimePack.hasTexture(rel)) {
			if (rel.endsWith("arrow.png")) g.fill((int) x + w / 4, (int) y + h / 4, (int) x + w * 3 / 4, (int) y + h * 3 / 4, colour);
			return;
		}
		g.blit(RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath("strafemc", "textures/" + rel), (int) Math.round(x), (int) Math.round(y), 0, 0, w, h, w, h,
				w, h, colour);
	}

	private static int a(int argb, int alpha) {
		return (((argb >>> 24) * alpha / 255) << 24) | (argb & 0xFFFFFF);
	}
}
