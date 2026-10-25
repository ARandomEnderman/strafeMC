package dev.strafemc.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * CS2's alert bar (panorama hudalerts) in the round modes, with the server's alert (RoundMode.alert): WARMUP m:ss,
 * WARMUP ENDING, WAITING FOR PLAYERS, FINAL ROUND, MATCH POINT, LAST ROUND OF FIRST HALF, in CS2's own words. Centred
 * 120 px from the top, 18 px white text padded 8 / 24 px, 2 px side bars in the team's colour; it opens sideways over
 * 0.25 s with a white flash fading over 0.15 s. FLAGGED ours: a dark backing (CS2 blurs the world behind it).
 */
public final class AlertBar {
	private static final int CT = 0xFF6F9CE6, T = 0xFFEABE54;
	private static String shownKey = "";
	private static long shownAt;

	private AlertBar() {}

	public static void register() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("strafemc", "alert_bar"), (g, delta) -> {
			Minecraft mc = Minecraft.getInstance();
			var s = ModeHud.state();
			if (mc.player == null || mc.options.hideGui || s == null || !s.has("alert")) {
				shownKey = "";
				return;
			}
			String key = s.get("alert").getAsString(), arg = s.has("alertArg") ? s.get("alertArg").getAsString() : "";
			if (!key.equals(shownKey)) { // a new alert opens again (the same one ticking its clock doesn't)
				shownKey = key;
				shownAt = System.currentTimeMillis();
			}
			String text = CsText.get(key, key.replace("SFUI_Notice_Alert_", "").replace('_', ' ').toUpperCase(java.util.Locale.ROOT))
					.replace("%s1", arg).replaceAll("<[^>]*>", "");
			double u = g.guiHeight() / 1080.0;
			float t = (System.currentTimeMillis() - shownAt) / 1000f;
			float open = Math.min(1, t / 0.25f);
			open = open * open; // ease-in, as CS2's transition
			var font = mc.font;
			float k = (float) (18 * u / 9.0);
			int tw = (int) Math.round(font.width(text) * k), w = (int) Math.round((tw + 48 * u) * open), h = (int) Math.round(18 * u + 16 * u);
			if (w < 2) return;
			int cx = g.guiWidth() / 2, y = (int) Math.round(120 * u), x0 = cx - w / 2;
			int side = dev.strafemc.weapon.CsTeam.isT(mc.player) ? T : CT;
			g.fill(x0, y, x0 + w, y + h, 0x90000000);
			float flash = Math.max(0, 1 - Math.max(0, t - 0.25f) / 0.15f);
			if (flash > 0) g.fill(x0, y, x0 + w, y + h, ((int) (255 * flash) << 24) | 0xFFFFFF);
			int b = Math.max(1, (int) Math.round(2 * u));
			g.fill(x0, y, x0 + b, y + h, side);
			g.fill(x0 + w - b, y, x0 + w, y + h, side);
			if (open >= 1) {
				var pose = g.pose();
				pose.pushMatrix();
				pose.translate(cx - tw / 2f, (float) (y + 8 * u + 1));
				pose.scale(k, k);
				g.text(font, text, 0, 0, 0xFFFFFFFF, true);
				pose.popMatrix();
			}
		});
	}
}
