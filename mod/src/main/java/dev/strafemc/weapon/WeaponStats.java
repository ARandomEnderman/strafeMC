package dev.strafemc.weapon;

import dev.strafemc.data.WeaponData;

import java.util.List;
import java.util.Map;

/**
 * One CS2 weapon's numbers in one mode, read from the game's weapons.vdata: pairs are [normal, alt], the alt mode being
 * the silencer on (USP-S, M4A1-S: no tracers, the longer muzzle, less recoil) or, later, scoped.
 */
public record WeaponStats(String cls, int clip, int reserveMags, boolean reserveAsClips, double cycleTime, boolean fullAuto,
						  int damage, double rangeModifier, double range, double headshotMultiplier, double armorRatio,
						  double penetration, double deployDuration, double reloadLockout, int bullets,
						  double spread, double inaccuracyStand, double inaccuracyCrouch, double inaccuracyMove,
						  double inaccuracyJump, double inaccuracyLand, double inaccuracyLadder, double inaccuracyFire,
						  double recoveryTimeStand, double recoveryTimeCrouch, double maxSpeed, String shootSound, String nearlyEmptySound,
						  int mode, int tracerFrequency, boolean hasSilencer) {

	public static WeaponStats of(String cls) {
		return of(cls, 0);
	}

	public static WeaponStats of(String cls, int mode) {
		Map<String, Object> w = WeaponData.get(cls);
		return w == null ? null : of(cls, w, mode);
	}

	/** From a vdata entry (local, or the server's synced copy), normal mode. */
	public static WeaponStats of(String cls, Map<String, Object> w) {
		return of(cls, w, 0);
	}

	/** From a vdata entry in a mode (0 normal, 1 alt: silencer on). */
	public static WeaponStats of(String cls, Map<String, Object> w, int mode) {
		Object shoot = w.get("m_aShootSounds");
		boolean silencer = w.get("m_eSilencerType") != null && !"WEAPONSILENCER_NONE".equals(String.valueOf(w.get("m_eSilencerType")));
		// with the silencer on: the silenced shot (WEAPON_SOUND_SPECIAL1)
		String key = silencer && mode == 1 && shoot instanceof Map<?, ?> sm && sm.get("WEAPON_SOUND_SPECIAL1") != null ? "WEAPON_SOUND_SPECIAL1" : "WEAPON_SOUND_SINGLE";
		String sound = shoot instanceof Map<?, ?> m && m.get(key) != null ? m.get(key).toString() : null;
		String nearlyEmpty = shoot instanceof Map<?, ?> m && m.get("WEAPON_SOUND_NEARLYEMPTY") != null ? m.get("WEAPON_SOUND_NEARLYEMPTY").toString() : null;
		return new WeaponStats(cls, i(w, "m_iMaxClip1", mode), i(w, "m_nPrimaryReserveAmmoMax", mode), Boolean.TRUE.equals(w.get("m_bReserveAmmoAsClips")),
				d(w, "m_flCycleTime", mode), Boolean.TRUE.equals(w.get("m_bIsFullAuto")), i(w, "m_nDamage", mode), d(w, "m_flRangeModifier", mode),
				d(w, "m_flRange", mode), d(w, "m_flHeadshotMultiplier", mode), d(w, "m_flArmorRatio", mode), d(w, "m_flPenetration", mode),
				d(w, "m_flDeployDuration", mode), d(w, "m_flDisallowAttackAfterReloadStartDuration", mode), Math.max(1, i(w, "m_nNumBullets", mode)),
				d(w, "m_flSpread", mode), d(w, "m_flInaccuracyStand", mode), d(w, "m_flInaccuracyCrouch", mode), d(w, "m_flInaccuracyMove", mode),
				d(w, "m_flInaccuracyJump", mode), d(w, "m_flInaccuracyLand", mode), d(w, "m_flInaccuracyLadder", mode), d(w, "m_flInaccuracyFire", mode),
				d(w, "m_flRecoveryTimeStand", mode), d(w, "m_flRecoveryTimeCrouch", mode), d(w, "m_flMaxSpeed", mode), sound, nearlyEmpty,
				mode, i(w, "m_nTracerFrequency", mode), silencer);
	}

	/** Numbers may be a scalar or a [normal, alt] pair: the mode's value (the normal one if there's only one). */
	private static double d(Map<String, Object> w, String k, int mode) {
		Object v = w.get(k);
		if (v instanceof List<?> l && !l.isEmpty()) {
			Object x = l.get(Math.min(mode, l.size() - 1));
			if (x instanceof Number n) return n.doubleValue();
		}
		if (v instanceof Number n) return n.doubleValue();
		return 0;
	}

	private static int i(Map<String, Object> w, String k, int mode) {
		return (int) Math.round(d(w, k, mode));
	}
}
