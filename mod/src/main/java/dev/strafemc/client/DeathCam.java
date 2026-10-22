package dev.strafemc.client;

import dev.strafemc.net.StrafePayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * CS2's death camera (freezecam): when you're killed, the view stays on your own death for spec_freeze_deathanim_time,
 * turns to your killer over spec_freeze_traveltime and holds on them for spec_freeze_time before the death screen shows.
 * All three are the server's cvars (the game modes set CS2's gamemode configs' spec_freeze_time: competitive 2.0,
 * casual 3.0, deathmatch / arms race 2), else CS2's defaults 0.8 / 0.3 / 3.0. A headshot death turns faster (half the
 * travel time). FLAGGED ours.
 */
public final class DeathCam {
	/** How long the camera holds on the killer: the server's spec_freeze_time. */
	private static double hold() {
		return cvar("spec_freeze_time", 3.0);
	}

	/** A spec_freeze_ cvar (seconds, 0-10) as the server has it, else CS2's default. */
	private static double cvar(String name, double def) {
		try {
			String v = ClientGameplay.cvar(name);
			return v == null ? def : Math.max(0, Math.min(10, Double.parseDouble(v)));
		} catch (NumberFormatException e) {
			return def;
		}
	}
	/** The death screen comes before the server says who killed us (the killfeed line, the death panel): wait this long for it. */
	private static final double WAIT_KILLER = 0.75;
	private static int killer = -1;
	private static boolean headshot;
	private static long diedAt;
	/** Seconds after the death when the killer was known (-1: not yet). */
	private static double killerSince = -1;
	/** The death screen held back (null: Minecraft makes it when it's let through); holding: the camera is on. */
	private static Screen pending;
	private static boolean holding;
	private static float fromYaw, fromPitch;
	private static boolean released;
	/** The server's death panel details (who, with what skin, their health, damage both ways). */
	private static StrafePayloads.DeathInfo info;

	private DeathCam() {}

	/** A killfeed line: remember who killed us (it arrives around our death). */
	public static void notice(StrafePayloads.KillNotice m) {
		var p = Minecraft.getInstance().player;
		if (p == null || m.victimId() != p.getId() || m.attackerId() == p.getId()) return;
		killer = m.attackerId();
		headshot = (m.flags() & dev.strafemc.weapon.KillFeed.HEADSHOT) != 0;
	}

	public static void info(StrafePayloads.DeathInfo m) {
		Minecraft.getInstance().execute(() -> {
			info = m;
			if (killer < 0 && !m.self()) killer = m.killerId(); // killed by something the killfeed doesn't show (a zombie)
		});
	}

	public static void register() {
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(net.minecraft.resources.Identifier.fromNamespaceAndPath("strafemc", "death_panel"),
				(g, delta) -> panel(g));
	}

	/**
	 * CS2's death panel (huddeathpanel): top centre, 410 px wide at 1080p; the killer's face (players) with their health
	 * bar under it, their name, "killed you with their {weapon}" (the skin's name), damage taken and given, and the
	 * weapon's picture on the right. Shown while the death camera holds.
	 */
	private static void panel(net.minecraft.client.gui.GuiGraphicsExtractor g) {
		var mc = Minecraft.getInstance();
		StrafePayloads.DeathInfo m = info;
		if (!holding || m == null || mc.player == null) return;
		var font = mc.font;
		float u = g.guiHeight() / 1080f * 2; // our GUI units are 2 screen px at the usual scale
		int w = Math.round(410 * u / 2), pic = Math.round(128 * u / 2), h = Math.round(150 * u / 2), x = g.guiWidth() / 2 - (w + pic) / 2;
		int y = Math.max(Math.round(60 * u / 2), ModeHud.topBottom + 6); // under the strafeGM HUD's top
		g.fill(x, y, x + w + pic, y + h, 0xAA000000);
		int av = Math.round(80 * u / 2);
		g.fill(x + 6, y + 6, x + 6 + av, y + 6 + av, 0x80000000);
		Entity k = mc.level == null ? null : mc.level.getEntity(m.killerId());
		if (k instanceof net.minecraft.client.player.AbstractClientPlayer cp)
			net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(g, cp.getSkin(), x + 8, y + 8, av - 4);
		// their health bar under the face (CS2's #dee5c8)
		g.fill(x + 6, y + 8 + av, x + 6 + av, y + 11 + av, 0xFF2A2F36);
		g.fill(x + 6, y + 8 + av, x + 6 + Math.round(av * Math.min(1, m.hp() / 100f)), y + 11 + av, 0xFFDEE5C8);
		int tx = x + 12 + av;
		g.text(font, m.self() ? dev.strafemc.client.CsText.get("DeathPanel_KilledSelf", "YOU KILLED YOURSELF") : m.killer(), tx, y + 10, 0xFFCCCCCC, true);
		if (!m.self()) {
			String desc = "killed you with their " + m.weapon();
			int ty = y + 22;
			for (var line : font.split(net.minecraft.network.chat.Component.literal(desc), w - av - 18)) {
				g.text(font, line, tx, ty, 0xFFFFFFFF, true);
				ty += 10;
			}
			g.text(font, m.hp() + " HP", tx, ty + 2, 0xFFDEE5C8, false);
		}
		int dy = y + 14 + av;
		g.text(font, "§7Damage taken:  §f" + m.taken() + " §7in §f" + m.hitsTaken() + (m.hitsTaken() == 1 ? " hit" : " hits"), x + 8, dy, -1, false);
		g.text(font, "§7Damage given:  §f" + m.given() + " §7in §f" + m.hitsGiven() + (m.hitsGiven() == 1 ? " hit" : " hits"), x + 8, dy + 11, -1, false);
		// the weapon's picture (in the killer's skin)
		net.minecraft.resources.Identifier img = m.cls().isEmpty() ? null : m.kit().isEmpty() ? CaseImages.image("panorama/images/econ/weapons/base_weapons/" + m.cls() + "_png")
				: CaseImages.get(new dev.strafemc.weapon.Cases.Entry(m.kit(), m.cls()));
		if (img != null) {
			int ph = pic * 3 / 4;
			g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, img, x + w, y + (h - ph) / 2, 0, 0, pic, ph, pic, ph);
		}
	}

	/** The death screen asked to open: hold it while the camera looks at the killer. True if it was held. */
	public static boolean hold(Screen deathScreen) {
		var mc = Minecraft.getInstance();
		if (released || mc.level == null || mc.player == null || killer >= 0 && mc.level.getEntity(killer) == null) return false;
		if (!holding) {
			holding = true;
			pending = deathScreen;
			diedAt = System.nanoTime();
			fromYaw = mc.player.getYRot();
			fromPitch = mc.player.getXRot();
		}
		return true;
	}

	/** The camera is the death cam's: no mouse look (MouseHandlerZoomMixin). */
	public static boolean locked() {
		return holding;
	}

	/** Every frame while dead: turn toward the killer; then let the death screen through. */
	public static void frame(Minecraft mc) {
		// it starts with the death itself, not only when Minecraft asks for the death screen
		if (!holding && !released && mc.player != null && mc.level != null && mc.player.isDeadOrDying()) hold(null);
		if (holding && mc.player != null && mc.player.isAlive()) { // respawned before the camera was done (a mode's respawn)
			holding = false;
			pending = null;
			killerSince = -1;
			info = null;
		}
		if (!holding) {
			if (mc.player != null && mc.player.isAlive()) { // alive again: ready for the next death
				killer = -1;
				released = false;
			}
			return;
		}
		double t = (System.nanoTime() - diedAt) / 1e9;
		if (killer < 0 && t < WAIT_KILLER) return; // the server hasn't said who yet
		// the camera's turn starts once we know who, and not before it has watched the death (spec_freeze_deathanim_time)
		if (killer >= 0 && killerSince < 0) killerSince = Math.max(t, cvar("spec_freeze_deathanim_time", 0.8));
		double since = killerSince < 0 || t < killerSince ? 0 : t - killerSince;
		Entity k = mc.level == null || killer < 0 ? null : mc.level.getEntity(killer);
		if (k != null && mc.player != null) {
			Vec3 eye = mc.player.getEyePosition(), to = k.getEyePosition().subtract(eye);
			float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z)), pitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
			double travel = Math.max(0.01, (headshot ? 0.5 : 1) * cvar("spec_freeze_traveltime", 0.3)), f = Math.min(1, since / travel);
			f = 1 - Math.pow(1 - f, 3);
			float dy = net.minecraft.util.Mth.wrapDegrees(yaw - fromYaw);
			mc.player.setYRot(fromYaw + (float) (dy * f));
			mc.player.setXRot(fromPitch + (float) ((pitch - fromPitch) * f));
			mc.player.yRotO = mc.player.getYRot();
			mc.player.xRotO = mc.player.getXRot();
		}
		if (killerSince >= 0 && t >= killerSince && since >= cvar("spec_freeze_traveltime", 0.3) + hold() || k == null && t >= WAIT_KILLER) {
			Screen s = pending;
			pending = null;
			holding = false;
			killerSince = -1;
			released = true;
			info = null;
			mc.execute(() -> mc.setScreen(s)); // not from inside the frame being drawn (null: Minecraft makes the death screen)
		}
	}
}
