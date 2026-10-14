package dev.strafemc.weapon;

import dev.strafemc.data.AnimClip;

/**
 * CS2's shell-by-shell reload (m_bReloadsSingleShells: Nova, XM1014, Sawed-Off), laid out by the reload clip's own marks:
 * WPN_RELOAD_INTRO, then the WPN_RELOAD_LOOP part once per shell (each shell going in at WPN_RELOAD_ADD_AMMO), then
 * WPN_RELOAD_OUTRO. Reload time t (the ReloadTimeline's animation time) maps to a time in the clip. Shared by the server
 * (ammo) and the client (viewmodel, foley).
 */
public final class ShellReload {
	public final double loopStart, loopEnd, addAt, outroStart, outroEnd;
	/** Shells this reload loads (fewer when the ammo runs out on the way). */
	public int shells;

	private ShellReload(double loopStart, double loopEnd, double addAt, double outroStart, double outroEnd, int shells) {
		this.loopStart = loopStart;
		this.loopEnd = loopEnd;
		this.addAt = addAt;
		this.outroStart = outroStart;
		this.outroEnd = outroEnd;
		this.shells = shells;
	}

	/** From the reload clip's marks, or null if it has no loop part. */
	public static ShellReload of(AnimClip c, int shells) {
		if (c == null) return null;
		double ls = -1, le = -1, add = -1, os = -1, oe = -1;
		for (AnimClip.Event e : c.events())
			switch (e.name()) {
				case "WPN_RELOAD_LOOP" -> {
					ls = e.time();
					le = e.time() + e.duration();
				}
				case "WPN_RELOAD_ADD_AMMO" -> add = e.time();
				case "WPN_RELOAD_OUTRO" -> {
					os = e.time();
					oe = Math.min(c.duration(), e.time() + e.duration());
				}
				default -> {
				}
			}
		if (ls < 0 || le <= ls) return null;
		if (add < ls || add > le) add = (ls + le) / 2;
		if (os < 0) {
			os = le;
			oe = c.duration();
		}
		return new ShellReload(ls, le, add, os, Math.max(os, oe), Math.max(1, shells));
	}

	private double loopLen() {
		return loopEnd - loopStart;
	}

	/** The clip time at reload time t. */
	public double clipTime(double t) {
		if (t < loopStart) return t;
		double u = t - loopStart, l = loopLen();
		if (u < shells * l) return loopStart + u % l;
		return Math.min(outroEnd, outroStart + (u - shells * l));
	}

	/** Reload time at which shell k (from 0) goes in. */
	public double shellAt(int k) {
		return loopStart + k * loopLen() + (addAt - loopStart);
	}

	/** Reload time the reload is over. */
	public double end() {
		return loopStart + shells * loopLen() + (outroEnd - outroStart);
	}

	/** Reload times at which a clip event at clip time e happens: once before the loop, once per shell in it, once in the outro. */
	public java.util.List<Double> times(double e) {
		java.util.List<Double> out = new java.util.ArrayList<>();
		if (e < loopStart) out.add(e);
		else if (e < loopEnd) for (int k = 0; k < shells; k++) out.add(e + k * loopLen());
		else if (e >= outroStart && e <= outroEnd) out.add(loopStart + shells * loopLen() + (e - outroStart));
		return out;
	}
}
