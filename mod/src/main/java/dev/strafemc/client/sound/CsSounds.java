package dev.strafemc.client.sound;

import dev.strafemc.StrafeMc;
import dev.strafemc.config.StrafeConfig;
import net.minecraft.client.Minecraft;

/** Plays a CS2 sound event (files, volume, pitch, distance curve from the game's .vsndevts) at a world position. */
public final class CsSounds {
	private CsSounds() {}

	/** Position in blocks. Returns false if the event doesn't exist. */
	public static boolean play(String event, double x, double y, double z, double gain) {
		return play(event, x, y, z, gain, false);
	}

	/**
	 * suppressed: the source is stealthy (CS2 silent reload, m_bStealthy). Events with suppression_enable then play at
	 * suppression_volume_multiplier and their distance curve shrinks by suppression_falloff_multiplier.
	 */
	public static boolean play(String event, double x, double y, double z, double gain, boolean suppressed) {
		return play(event, x, y, z, gain, suppressed, null);
	}

	/** A sound that moves with an entity (weapon sounds come from the gun, wherever its holder goes). */
	public static boolean playOn(String event, net.minecraft.world.entity.Entity e, double gain, boolean suppressed) {
		// the local player's own weapon sounds play at the listener (centred, like CS2's own-weapon sounds); following the
		// player in world space (0.12.1) sounded wrong and fixed positions went one-sided while strafing
		if (e == net.minecraft.client.Minecraft.getInstance().player) return playOwn(event, gain, suppressed);
		return play(event, e.getX(), e.getEyeY(), e.getZ(), gain, suppressed, null);
	}

	/** A CS2 sound file played at the listener (UI sounds: "sounds/ui/panorama/case_unlock_01"); converted on first use. */
	public static void playFile(String vsndPath, float volume) {
		if (!CsAudio.ready() || dev.strafemc.client.Placeholders.active()) return;
		net.minecraft.client.Minecraft.getInstance().getSoundManager().play(CsSoundInstance.own(CsAudio.id(vsndPath), volume, 1f));
	}

	/** Own animation foley (draw/inspect clip sounds) still playing, so an interrupted animation can cut it. */
	private static final java.util.List<CsSoundInstance> ANIM = new java.util.ArrayList<>();

	/** The local player's animation-clip sound (draw, inspect): like playOn for the own player, but stoppable. */
	public static void playAnim(String event) {
		CsSoundInstance i = own(event, 1.0, false);
		if (i == null) return;
		ANIM.removeIf(x -> !net.minecraft.client.Minecraft.getInstance().getSoundManager().isActive(x));
		ANIM.add(i);
	}

	/** Stops the own animation foley (the animation it belonged to was interrupted). */
	public static void stopAnim() {
		for (CsSoundInstance i : ANIM) net.minecraft.client.Minecraft.getInstance().getSoundManager().stop(i);
		ANIM.clear();
	}

	private static boolean playOwn(String event, double gain, boolean suppressed) {
		if (dev.strafemc.client.Placeholders.active()) {
			var p = net.minecraft.client.Minecraft.getInstance().player;
			return p != null && dev.strafemc.client.Placeholders.sound(event, p.getX(), p.getEyeY(), p.getZ(), gain);
		}
		own(event, gain, suppressed);
		return CsSoundEvents.get(event) != null || !CsAudio.ready();
	}

	private static CsSoundInstance own(String event, double gain, boolean suppressed) {
		return own(event, gain, suppressed, 0);
	}

	private static CsSoundInstance own(String event, double gain, boolean suppressed, int depth) {
		if (!CsAudio.ready()) return null;
		CsSoundEvents.Event e = CsSoundEvents.get(event);
		if (e == null) return null;
		CsSoundInstance first = null;
		if (depth < 4) for (String child : e.children()) {
			CsSoundInstance c = own(child, gain, suppressed, depth + 1);
			if (first == null) first = c;
		}
		boolean sup = suppressed && e.suppression();
		float vol = (float) (e.volume() * e.volumeAt(0) * gain * (sup ? e.suppressionVolume() : 1));
		String file = e.pickFile();
		if (vol <= 0.001f || file == null) return first;
		CsSoundInstance i = CsSoundInstance.own(CsAudio.id(file), Math.min(1f, vol), (float) e.randomPitch());
		net.minecraft.client.Minecraft.getInstance().getSoundManager().play(i);
		return i;
	}

	public static boolean play(String event, double x, double y, double z, double gain, boolean suppressed,
							   net.minecraft.world.entity.Entity follow) {
		if (dev.strafemc.client.Placeholders.active()) return dev.strafemc.client.Placeholders.sound(event, x, y, z, gain);
		return play(event, x, y, z, gain, suppressed, follow, 0);
	}

	private static boolean play(String event, double x, double y, double z, double gain, boolean suppressed,
								net.minecraft.world.entity.Entity follow, int depth) {
		if (!CsAudio.ready()) return true;
		CsSoundEvents.Event e = CsSoundEvents.get(event);
		if (e == null) {
			if (StrafeConfig.debug) StrafeMc.LOG.info("[strafemc] unknown CS2 sound event {}", event);
			return false;
		}
		if (depth < 4) for (String child : e.children()) play(child, x, y, z, gain, suppressed, follow, depth + 1);
		var mc = Minecraft.getInstance();
		double u = StrafeConfig.unitsPerBlock;
		var cam = mc.gameRenderer.getMainCamera().position();
		double sy = y + e.offsetUp() / u;
		double dist = Math.sqrt(sq(cam.x - x) + sq(cam.y - sy) + sq(cam.z - z)) * u;
		boolean sup = suppressed && e.suppression();
		double falloff = sup ? Math.max(1e-3, e.suppressionFalloff()) : 1;
		float vol = (float) (e.volume() * e.volumeAt(dist / falloff) * gain * (sup ? e.suppressionVolume() : 1));
		String file = e.pickFile();
		if (vol <= 0.001f || file == null) return true;
		mc.getSoundManager().play(new CsSoundInstance(CsAudio.id(file), Math.min(1f, vol), (float) e.randomPitch(), x, sy, z, follow,
				sy - y));
		return true;
	}

	private static double sq(double v) {
		return v * v;
	}
}
