package dev.strafemc.weapon;

/**
 * One reload in animation time, with CS2's silent reload:
 * while the reload key has been held since the reload started (m_bReloadHeldSinceStart) and the clip is inside its
 * WPN_RELOAD_SILENT section (m_bInSilentReloadSection), holding for 0.2 s (m_flStealthHoldStartTime) makes the weapon
 * stealthy (m_bStealthy) and drops m_flWeaponActionPlaybackRate to 0.5. Letting go, or leaving the section, clears
 * stealthy and sets the rate to 0.99; a released key can't make the same reload stealthy again.
 * Every reload timer (ammo, lockout, foley, viewmodel) runs on this animation time, so the rate rescales them all.
 * Shared by the MC server (authoritative ammo) and the client (viewmodel, local sounds).
 */
public final class ReloadTimeline {
	public static final double STEALTH_HOLD_TIME = 0.2, STEALTH_RATE = 0.5, RELEASED_RATE = 0.99;

	/** End of WPN_RELOAD_SILENT in clip seconds. */
	public final double silentEnd;
	public double animTime, rate = 1;
	public boolean heldSinceStart, stealthy;
	private double holdStart = -1;

	public ReloadTimeline(double silentEnd, boolean keyHeldAtStart) {
		this.silentEnd = silentEnd;
		this.heldSinceStart = keyHeldAtStart;
	}

	/** Advances by dt wall seconds at the current rate, then updates the silent-reload state (the server's per-tick order). */
	public void advance(double wallNow, double dt, boolean keyHeld) {
		animTime += dt * rate;
		if (!keyHeld) heldSinceStart = false;
		if (heldSinceStart && animTime < silentEnd) {
			if (holdStart < 0) holdStart = wallNow;
			if (wallNow - holdStart >= STEALTH_HOLD_TIME) {
				rate = STEALTH_RATE;
				stealthy = true;
			}
		} else {
			holdStart = -1;
			if (rate != 1 && rate != RELEASED_RATE) rate = RELEASED_RATE;
			stealthy = false;
		}
	}
}
