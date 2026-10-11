package dev.strafemc.weapon;

import dev.strafemc.data.WeaponData;

import java.util.List;
import java.util.Map;

/** One CS2 weapon's numbers, read from the game's weapons.vdata (index [0] = normal mode, [1] = alt/scoped). */
public record WeaponStats(String cls, int clip, int reserveMags, boolean reserveAsClips, double cycleTime, boolean fullAuto,
						  int damage, double rangeModifier, double range, double headshotMultiplier, double armorRatio,
						  double penetration, double deployDuration, double reloadLockout, int bullets,
						  double spread, double inaccuracyStand, double inaccuracyCrouch, double inaccuracyMove,
						  double inaccuracyJump, double inaccuracyLand, double inaccuracyLadder, double inaccuracyFire,
						  double recoveryTimeStand, double recoveryTimeCrouch, double maxSpeed, String shootSound, String nearlyEmptySound) {

	public static WeaponStats of(String cls) {
		Map<String, Object> w = WeaponData.get(cls);
		if (w == null) return null;
		Object shoot = w.get("m_aShootSounds");
		String sound = shoot instanceof Map<?, ?> m && m.get("WEAPON_SOUND_SINGLE") != null ? m.get("WEAPON_SOUND_SINGLE").toString() : null;
		String nearlyEmpty = shoot instanceof Map<?, ?> m && m.get("WEAPON_SOUND_NEARLYEMPTY") != null ? m.get("WEAPON_SOUND_NEARLYEMPTY").toString() : null;
		return new WeaponStats(cls, i(w, "m_iMaxClip1"), i(w, "m_nPrimaryReserveAmmoMax"), Boolean.TRUE.equals(w.get("m_bReserveAmmoAsClips")),
				d(w, "m_flCycleTime"), Boolean.TRUE.equals(w.get("m_bIsFullAuto")), i(w, "m_nDamage"), d(w, "m_flRangeModifier"),
				d(w, "m_flRange"), d(w, "m_flHeadshotMultiplier"), d(w, "m_flArmorRatio"), d(w, "m_flPenetration"),
				d(w, "m_flDeployDuration"), d(w, "m_flDisallowAttackAfterReloadStartDuration"), Math.max(1, i(w, "m_nNumBullets")),
				d(w, "m_flSpread"), d(w, "m_flInaccuracyStand"), d(w, "m_flInaccuracyCrouch"), d(w, "m_flInaccuracyMove"),
				d(w, "m_flInaccuracyJump"), d(w, "m_flInaccuracyLand"), d(w, "m_flInaccuracyLadder"), d(w, "m_flInaccuracyFire"),
				d(w, "m_flRecoveryTimeStand"), d(w, "m_flRecoveryTimeCrouch"), d(w, "m_flMaxSpeed"), sound, nearlyEmpty);
	}

	/** Numbers may be a scalar or a [normal, alt] pair; we use the normal-mode value. */
	private static double d(Map<String, Object> w, String k) {
		Object v = w.get(k);
		if (v instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Number n) return n.doubleValue();
		if (v instanceof Number n) return n.doubleValue();
		return 0;
	}

	private static int i(Map<String, Object> w, String k) {
		return (int) Math.round(d(w, k));
	}
}
