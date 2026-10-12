package dev.strafemc.server;

import dev.strafemc.StrafeMc;
import dev.strafemc.config.StrafeConfig;
import net.minecraft.server.level.ServerPlayer;

/**
 * CS2 fall damage, measured in the real engine (bot dropped from known heights, 2026-10-11, build 2000930):
 * damage = 0.2415 * (impact speed - 590 u/s), fatal at ~1004 u/s; scaled by sv_falldamage_scale.
 * Fits every clean sample within 1.5 HP (10/26/37/74/94 HP at 633/693/748/894/980 u/s). Converted to MC HP with hpScale.
 */
public final class CsFallDamage {
	public static final double SAFE_SPEED = 590.0;
	public static final double DAMAGE_PER_UNIT = 0.2415;

	private CsFallDamage() {}

	public static double csDamage(double impactSpeed, double scale) {
		return Math.max(0, (impactSpeed - SAFE_SPEED) * DAMAGE_PER_UNIT) * scale;
	}

	/** Server side. Multiplayer TODO: validate the reported speed against the server's own view of the fall. */
	public static void apply(ServerPlayer p, double impactSpeed, double scale) {
		if (!StrafeSettings.sourceMovement()) return;
		double v = Math.min(impactSpeed, 3500), s = Math.max(0, Math.min(scale, 10));
		double cs = csDamage(v, s);
		if (cs <= 0) return;
		float mc = (float) (cs * StrafeConfig.hpScale);
		p.hurtServer(p.level(), p.damageSources().fall(), mc);
		// taking damage marks the player for a motion resync, which the client would apply as knockback on top of its
		// own movement (it launched bunny-hoppers upward); fall damage never pushes anyone
		p.hurtMarked = false;
		StrafeMc.LOG.info("[strafemc] CS2 fall damage {}: impact {} u/s -> {} CS HP -> {} MC HP", p.getScoreboardName(),
				String.format("%.0f", v), String.format("%.1f", cs), String.format("%.2f", mc));
	}
}
