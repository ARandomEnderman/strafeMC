package dev.strafemc.client;

import dev.strafemc.net.StrafePayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.Entity;

/**
 * CS2's death camera: when you're killed the view stays where it was (no turning to the killer, no looking around, no
 * walking) for the death animation, the travel and the freeze time (the server's spec_freeze_deathanim_time,
 * spec_freeze_traveltime and spec_freeze_time; the game modes set CS2's gamemode configs' spec_freeze_time:
 * competitive 2.0, casual 3.0, deathmatch / arms race 2; else CS2's defaults 0.8 / 0.3 / 3.0) before the death screen
 * shows. CS2's death panel stays up the whole time you're dead.
 * <p>
 * The view's colour (CS2's client, the death camera's post-processing weights, t = seconds since the death): the red
 * tint (death_cam_phase1) at clamp(4t), in over a quarter second; the fade to black (death_cam_phase2) at clamp(2t)
 * when it was a headshot (m_bKilledByHeadshot: black half a second after), else clamp(2 (t - (spec_freeze_time -
 * 0.5))): black over the freeze time's last half second.
 */
public final class DeathCam {
	/** A spec_freeze_ cvar (seconds, 0-10) as the server has it, else CS2's default. */
	private static double cvar(String name, double def) {
		try {
			String v = ClientGameplay.cvar(name);
			return v == null ? def : Math.max(0, Math.min(10, Double.parseDouble(v)));
		} catch (NumberFormatException e) {
			return def;
		}
	}

	/** How long the camera holds: death animation + travel + freeze time. */
	private static double length() {
		return cvar("spec_freeze_deathanim_time", 0.8) + cvar("spec_freeze_traveltime", 0.3) + cvar("spec_freeze_time", 3.0);
	}

	private static long diedAt;
	/** The death screen held back (null: Minecraft makes it when it's let through); holding: the camera is on. */
	private static Screen pending;
	private static boolean holding, released;
	/** The killfeed said the shot that killed us was a headshot (CS2's m_bKilledByHeadshot). */
	private static boolean headshot;
	/** The server's death panel details (who, with what skin, their health, damage both ways). */
	private static StrafePayloads.DeathInfo info;

	private DeathCam() {}

	/** A killfeed line: whether the shot that killed us was a headshot (the fade to black comes sooner then). */
	public static void notice(StrafePayloads.KillNotice m) {
		var p = Minecraft.getInstance().player;
		if (p == null || m.victimId() != p.getId()) return;
		headshot = (m.flags() & dev.strafemc.weapon.KillFeed.HEADSHOT) != 0;
	}

	public static void info(StrafePayloads.DeathInfo m) {
		Minecraft.getInstance().execute(() -> info = m);
	}

	public static void register() {
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(net.minecraft.resources.Identifier.fromNamespaceAndPath("strafemc", "death_panel"),
				(g, delta) -> panel(g));
		DeathFade.init();
	}

	/** After the world is drawn, before the HUD, HUD shown or not (GameRendererLevelEffectsMixin): the death camera's colour. */
	public static void afterLevel() {
		fade();
	}

	/** The death camera's colour now: the red tint and the fade to black, as CS2 weighs them. */
	private static void fade() {
		var p = Minecraft.getInstance().player;
		if (!holding && !(released && p != null && p.isDeadOrDying())) return; // black stays under the death screen until the respawn
		double t = (System.nanoTime() - diedAt) / 1e9;
		double tint = clamp(4 * t), black = headshot ? clamp(2 * t) : clamp(2 * (t - (cvar("spec_freeze_time", 3.0) - 0.5)));
		float keep = (float) (1 - black), gb = (float) (1 - tint * (1 - 0.58));
		DeathFade.draw(keep, gb * keep, gb * keep);
	}

	private static double clamp(double v) {
		return Math.max(0, Math.min(1, v));
	}

	/**
	 * CS2's death panel (huddeathpanel): top centre, 410 px wide at 1080p; the killer's face (players) with their health
	 * bar under it, their name, "killed you with their {weapon}" (the skin's name), damage taken and given, and the
	 * weapon's picture on the right. Shown while you're dead.
	 */
	private static void panel(net.minecraft.client.gui.GuiGraphicsExtractor g) {
		var mc = Minecraft.getInstance();
		StrafePayloads.DeathInfo m = info;
		if (m == null || mc.player == null || !mc.player.isDeadOrDying()) return;
		var font = mc.font;
		float u = g.guiHeight() / 1080f * 2; // our GUI units are 2 screen px at the usual scale
		int w = Math.round(410 * u / 2), pic = Math.round(128 * u / 2), x = g.guiWidth() / 2 - (w + pic) / 2;
		int y = Math.max(Math.round(60 * u / 2), ModeHud.topBottom + 6); // under the strafeGM HUD's top
		int av = Math.round(80 * u / 2), tx = x + 12 + av;
		// the text beside the face first: its height decides where the damage rows go (a long skin name wraps)
		var lines = m.self() ? java.util.List.<net.minecraft.util.FormattedCharSequence>of()
				: font.split(net.minecraft.network.chat.Component.literal("killed you with their " + m.weapon()), w - av - 18);
		int textBottom = y + 22 + lines.size() * 10 + (m.self() ? 0 : 12);
		int dy = Math.max(y + 14 + av, textBottom + 4), h = dy + 22 - y;
		g.fill(x, y, x + w + pic, y + h, 0xAA000000);
		g.fill(x + 6, y + 6, x + 6 + av, y + 6 + av, 0x80000000);
		var skin = skinOf(mc, m);
		if (skin != null) net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(g, skin, x + 8, y + 8, av - 4);
		// their health bar under the face (CS2's #dee5c8)
		g.fill(x + 6, y + 8 + av, x + 6 + av, y + 11 + av, 0xFF2A2F36);
		g.fill(x + 6, y + 8 + av, x + 6 + Math.round(av * Math.min(1, m.hp() / 100f)), y + 11 + av, 0xFFDEE5C8);
		g.text(font, m.self() ? dev.strafemc.client.CsText.get("DeathPanel_KilledSelf", "YOU KILLED YOURSELF") : m.killer(), tx, y + 10, 0xFFCCCCCC, true);
		if (!m.self()) {
			int ty = y + 22;
			for (var line : lines) {
				g.text(font, line, tx, ty, 0xFFFFFFFF, true);
				ty += 10;
			}
			g.text(font, m.hp() + " HP", tx, ty + 2, 0xFFDEE5C8, false);
		}
		g.text(font, "\u00a77Damage taken:  \u00a7f" + m.taken() + " \u00a77in \u00a7f" + m.hitsTaken() + (m.hitsTaken() == 1 ? " hit" : " hits"), x + 8, dy, -1, false);
		g.text(font, "\u00a77Damage given:  \u00a7f" + m.given() + " \u00a77in \u00a7f" + m.hitsGiven() + (m.hitsGiven() == 1 ? " hit" : " hits"), x + 8, dy + 11, -1, false);
		// the weapon's picture (in the killer's skin)
		net.minecraft.resources.Identifier img = m.cls().isEmpty() ? null : m.kit().isEmpty() ? CaseImages.image("panorama/images/econ/weapons/base_weapons/" + m.cls() + "_png")
				: CaseImages.get(new dev.strafemc.weapon.Cases.Entry(m.kit(), m.cls()));
		if (img != null) {
			int ph = pic * 3 / 4;
			g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, img, x + w, y + (h - ph) / 2, 0, 0, pic, ph, pic, ph);
		}
	}

	/** The killer's skin for the face: their entity's when it's here (players, bots), else their player list entry's. */
	private static net.minecraft.world.entity.player.PlayerSkin skinOf(Minecraft mc, StrafePayloads.DeathInfo m) {
		Entity k = mc.level == null ? null : mc.level.getEntity(m.killerId());
		if (k instanceof net.minecraft.client.entity.ClientAvatarEntity a) return a.getSkin();
		var info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(m.killer());
		return info == null ? null : info.getSkin();
	}



	/** The death screen asked to open: hold it while the camera runs. True if it was held. */
	public static boolean hold(Screen deathScreen) {
		var mc = Minecraft.getInstance();
		if (released || mc.level == null || mc.player == null) return false;
		if (!holding) {
			holding = true;
			pending = deathScreen;
			diedAt = System.nanoTime();
		}
		return true;
	}

	/** Dead: the view can't be turned (MouseHandlerZoomMixin) and you can't walk (MovementController). */
	public static boolean locked() {
		var p = Minecraft.getInstance().player;
		return holding || p != null && p.isDeadOrDying();
	}

	/** Every frame: starts with the death itself; after its time, lets the death screen through. */
	public static void frame(Minecraft mc) {
		if (!holding && !released && mc.player != null && mc.level != null && mc.player.isDeadOrDying()) hold(null);
		if (mc.player != null && mc.player.isAlive()) { // alive again (respawned, or a mode's respawn cut it short)
			holding = false;
			pending = null;
			released = false;
			info = null;
			headshot = false;
			return;
		}
		if (!holding) return;
		if ((System.nanoTime() - diedAt) / 1e9 >= length()) {
			Screen s = pending;
			pending = null;
			holding = false;
			released = true;
			mc.execute(() -> mc.setScreen(s)); // not from inside the frame being drawn (null: Minecraft makes the death screen)
		}
	}
}
