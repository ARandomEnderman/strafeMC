package dev.strafemc.weapon;

import java.util.Set;

/**
 * The weapons.vdata fields that decide gameplay. The server syncs exactly these to clients (server.GameplaySync); a
 * client merges them over its own local entry, so every other field (sounds, particles, models) stays cosmetic.
 *
 * ADD A FIELD HERE whenever gameplay code starts reading a new one. Otherwise clients silently use their local value
 * for it. The build checks this (gradle task checkGameplayKeys): every "m_..." field named in the gameplay classes
 * must be listed here, or under COSMETIC.
 */
public final class GameplayKeys {
	public static final Set<String> SYNCED = Set.of("m_iMaxClip1", "m_nPrimaryReserveAmmoMax", "m_bReserveAmmoAsClips", "m_flCycleTime",
			"m_bIsFullAuto", "m_nDamage", "m_flRangeModifier", "m_flRange", "m_flHeadshotMultiplier", "m_flArmorRatio", "m_flPenetration",
			"m_flDeployDuration", "m_flDisallowAttackAfterReloadStartDuration", "m_nNumBullets", "m_flSpread", "m_flInaccuracyStand",
			"m_flInaccuracyCrouch", "m_flInaccuracyMove", "m_flInaccuracyJump", "m_flInaccuracyLand", "m_flInaccuracyLadder",
			"m_flInaccuracyFire", "m_flRecoveryTimeStand", "m_flRecoveryTimeCrouch", "m_flRecoveryTimeStandFinal",
			"m_flRecoveryTimeCrouchFinal", "m_nRecoveryTransitionStartBullet", "m_nRecoveryTransitionEndBullet", "m_flMaxSpeed",
			"m_flRecoilAngle", "m_flRecoilAngleVariance", "m_flRecoilMagnitude", "m_flRecoilMagnitudeVariance", "m_nRecoilSeed", "m_flFlinchVelocityModifierLarge", "m_flFlinchVelocityModifierSmall", "m_flThrowVelocity", "m_eSilencerType",
			"m_nZoomLevels", "m_nZoomFOV1", "m_nZoomFOV2", "m_flZoomTime0", "m_flZoomTime1", "m_flZoomTime2", "m_bUnzoomsAfterShot",
			"m_bHasBurstMode", "m_flCycleTimeWhenInBurstMode", "m_flTimeBetweenBurstShots", "m_bIsRevolver", "m_bReloadsSingleShells");

	/** Fields the gameplay classes mention that are only looks or sound (read from each player's own files). */
	public static final Set<String> COSMETIC = Set.of("m_aShootSounds", "m_nTracerFrequency", "m_bHideViewModelWhenZoomed", "m_flInaccuracyAltSoundThreshold");

	private GameplayKeys() {}
}
