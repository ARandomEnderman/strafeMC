package dev.strafemc.bridge;

import java.util.Map;

/**
 * CS2's default values for the settings strafeMC uses, for when no CS2 server has reported any (a fresh install, a
 * server without a CS2 server next to it). A live CS2 server's values, or the last ones it reported, always win.
 */
public final class Cs2Defaults {
	private Cs2Defaults() {}

	public static final Map<String, String> VALUES = Map.ofEntries(
			Map.entry("sv_accelerate", "5.5"), Map.entry("sv_airaccelerate", "12"), Map.entry("sv_air_max_wishspeed", "30"),
			Map.entry("sv_friction", "5.2"), Map.entry("sv_stopspeed", "80"), Map.entry("sv_maxspeed", "320"),
			Map.entry("sv_gravity", "800"), Map.entry("sv_jump_impulse", "301.99338"), Map.entry("sv_stepsize", "18"),
			Map.entry("sv_standable_normal", "0.7"), Map.entry("sv_walkable_normal", "0.7"), Map.entry("sv_maxvelocity", "3500"),
			Map.entry("sv_bounce", "0"), Map.entry("sv_staminamax", "80"), Map.entry("sv_staminajumpcost", "0.08"),
			Map.entry("sv_staminalandcost", "0.05"), Map.entry("sv_staminarecoveryrate", "60"), Map.entry("sv_timebetweenducks", "0.4"),
			Map.entry("sv_autobunnyhopping", "0"), Map.entry("sv_enablebunnyhopping", "0"), Map.entry("sv_accelerate_use_weapon_speed", "1"),
			Map.entry("sv_wateraccelerate", "10"), Map.entry("sv_waterfriction", "1"), Map.entry("sv_ladder_scale_speed", "0.78"),
			Map.entry("sv_ladder_angle", "-0.707"), Map.entry("sv_ladder_dampen", "0.2"), Map.entry("weapon_accuracy_nospread", "0"),
			Map.entry("mp_damage_headshot_only", "0"), Map.entry("host_timescale", "1"), Map.entry("sv_cheats", "0"),
			Map.entry("sv_bhop_time_window", "0.0078125"), Map.entry("sv_step_move_vel_min", "64"), Map.entry("sv_jump_spam_penalty_time", "0.015625"),
			Map.entry("sv_legacy_jump", "0"), Map.entry("sv_jump_precision_enable", "1"), Map.entry("sv_noclipspeed", "1200"),
			Map.entry("sv_noclipaccelerate", "5"), Map.entry("sv_noclipfriction", "4"), Map.entry("sv_noclipspeedscaleonshift", "0.5"),
			Map.entry("footstep_audible_threshold", "0.55"), Map.entry("sv_min_jump_landing_sound", "260"), Map.entry("sv_footsteps", "1"),
			Map.entry("sv_falldamage_scale", "1"), Map.entry("sv_subtick_movement_view_angles", "1"), Map.entry("sv_infinite_ammo", "0"));
}
