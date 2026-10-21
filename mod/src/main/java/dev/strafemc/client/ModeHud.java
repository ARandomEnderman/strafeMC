package dev.strafemc.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.strafemc.net.StrafePayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * The running game mode's HUD (strafeGM), top centre: Arms Race shows the clock, your level and weapon with the kills
 * to the next one, the next weapon and the leader; the winner when the match is over.
 */
public final class ModeHud {
	private static final int GOLD = 0xFFEABE54, WHITE = 0xFFFFFFFF, GREY = 0xFFB0B0B0;
	private static volatile JsonObject state = new JsonObject();

	private ModeHud() {}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(StrafePayloads.ModeState.TYPE, (msg, ctx) -> {
			try {
				state = JsonParser.parseString(msg.json()).getAsJsonObject();
			} catch (Exception e) {
				state = new JsonObject();
			}
		});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> state = new JsonObject());
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, Identifier.fromNamespaceAndPath("strafemc", "game_mode"), (g, delta) -> {
			Minecraft mc = Minecraft.getInstance();
			JsonObject s = state;
			if (mc.player == null || mc.options.hideGui || !s.has("mode") || !"armsrace".equals(s.get("mode").getAsString())) return;
			var font = mc.font;
			int cx = g.guiWidth() / 2, y = 4;
			String phase = s.get("phase").getAsString();
			int time = s.get("time").getAsInt();
			String clock = time / 60 + ":" + String.format("%02d", time % 60);
			if (phase.equals("over")) {
				String w = s.get("winner").getAsString() + " won the Arms Race";
				g.text(font, w, cx - font.width(w) / 2, y, GOLD, true);
				String next = "Next match in " + time + " s";
				g.text(font, next, cx - font.width(next) / 2, y + 11, GREY, true);
				return;
			}
			String top = (phase.equals("warmup") ? "Warmup " : "Arms Race ") + clock;
			g.text(font, top, cx - font.width(top) / 2, y, phase.equals("warmup") ? GREY : WHITE, true);
			String lv = "Level " + s.get("level").getAsInt() + "/" + s.get("levels").getAsInt() + "  " + name(s.get("weapon").getAsString())
					+ "  (" + s.get("kills").getAsInt() + "/" + s.get("perLevel").getAsInt() + ")";
			g.text(font, lv, cx - font.width(lv) / 2, y + 11, GOLD, true);
			String next = s.get("next").getAsString();
			String leader = s.get("leader").getAsString();
			String line = (next.isEmpty() ? "" : "Next: " + name(next)) + (leader.isEmpty() ? "" : (next.isEmpty() ? "" : "   ")
					+ "Leader: " + leader + " (" + s.get("leaderLevel").getAsInt() + ")");
			if (!line.isEmpty()) g.text(font, line, cx - font.width(line) / 2, y + 22, GREY, true);
			double immune = s.get("immune").getAsDouble();
			if (immune > 0) {
				String im = "Protected";
				g.text(font, im, cx - font.width(im) / 2, y + 33, 0xFF7FD0FF, true);
			}
		});
	}

	/** CS2's weapon name ("weapon_knifegg": the golden knife). */
	private static String name(String cls) {
		if (cls.equals("weapon_knifegg")) return "Golden Knife";
		return dev.strafemc.weapon.SkinWraps.WEAPON_NAMES.getOrDefault(cls, cls.replace("weapon_", ""));
	}
}
