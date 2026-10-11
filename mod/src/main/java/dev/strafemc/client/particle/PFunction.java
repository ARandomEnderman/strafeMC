package dev.strafemc.client.particle;

/**
 * Base of every particle function (emitter, initializer, operator, force, pre-emission): operator strength with its
 * fade in/out window and end-cap phase. Port of VRF ParticleFunction (MIT).
 */
abstract class PFunction {
	private final Num strength;
	private final String endCapState;
	final float fadeInStart, fadeInEnd, fadeOutStart, fadeOutEnd, oscillatePeriod;
	private final boolean normalizeToStopTime, unityCurve, fastPath;
	private final float timeOffsetMin, timeOffsetMax, timeScaleMin, timeScaleMax;
	private final int timeOffsetSeed, timeScaleSeed;

	PFunction(Pd p) {
		strength = p.num("m_flOpStrength", new Num.Literal(1));
		endCapState = p.str("m_nOpEndCapState", "PARTICLE_ENDCAP_ALWAYS_ON");
		fadeInStart = p.f("m_flOpStartFadeInTime", 0);
		fadeInEnd = p.f("m_flOpEndFadeInTime", 0);
		fadeOutStart = p.f("m_flOpStartFadeOutTime", 0);
		fadeOutEnd = p.f("m_flOpEndFadeOutTime", 0);
		oscillatePeriod = p.f("m_flOpFadeOscillatePeriod", 0);
		timeOffsetMin = p.f("m_flOpTimeOffsetMin", 0);
		timeOffsetMax = p.f("m_flOpTimeOffsetMax", 0);
		timeScaleMin = p.f("m_flOpTimeScaleMin", 1);
		timeScaleMax = p.f("m_flOpTimeScaleMax", 1);
		normalizeToStopTime = p.b("m_bNormalizeToStopTime", false);
		timeOffsetSeed = p.i("m_nOpTimeOffsetSeed", 0);
		timeScaleSeed = p.i("m_nOpTimeScaleSeed", 0);
		unityCurve = fadeInStart == 0 && fadeInEnd == 0 && fadeOutStart == 0 && fadeOutEnd == 0
				&& (timeOffsetSeed == 0 || (timeOffsetMin == 0 && timeOffsetMax == 0))
				&& (timeScaleSeed == 0 || (timeScaleMin == 1 && timeScaleMax == 1));
		fastPath = unityCurve && endCapState.equals("PARTICLE_ENDCAP_ALWAYS_ON") && strength instanceof Num.Literal l && l.v == 1f;
	}

	boolean runsInCurrentPhase(PState s) {
		return endCapState.equals("PARTICLE_ENDCAP_ALWAYS_ON") || s.inEndCap == endCapState.equals("PARTICLE_ENDCAP_ENDCAP_ON");
	}

	float runStrength(PState s) {
		if (fastPath) return 1f;
		if (!runsInCurrentPhase(s)) return 0f;
		float op = strength.next(s);
		if (unityCurve || op <= 0) return op;
		return Math.max(0, op) * fadeInOut(functionTime(s));
	}

	private float functionTime(PState s) {
		if (oscillatePeriod > 0) return s.age / oscillatePeriod % 1f;
		float t = Math.max(0, s.age - s.restartAge);
		if (normalizeToStopTime && endCapState.equals("PARTICLE_ENDCAP_ENDCAP_ON")) t = Math.max(0, t - s.endCapStartAge);
		if (timeOffsetSeed != 0) t = Math.max(0, t + PRandom.forSampleBetween(s.random.seed + timeOffsetSeed, timeOffsetMin, timeOffsetMax));
		if (timeScaleSeed != 0 && t > fadeInStart) {
			float scale = 1f / Math.max(0.0001f, PRandom.forSampleBetween(s.random.seed + timeScaleSeed, timeScaleMin, timeScaleMax));
			t = fadeInStart + scale * (t - fadeInStart);
		}
		return t;
	}

	private float fadeInOut(float time) {
		float inS = fadeInStart, inE = fadeInEnd, outS = fadeOutStart, outE = fadeOutEnd;
		if (inS > time) return 0;
		if (outE > 0 && outE < time) return 0;
		inE = Math.max(inE, inS);
		outS = Math.max(outS, inE);
		outE = Math.max(outE, outS);
		float st = 1;
		if (inE > time && inE > inS) st = Math.min(st, flerp(0, 1, inS, inE, time));
		if (time > outS && outE > outS) st = Math.min(st, flerp(0, 1, outE, outS, time));
		return st;
	}

	static float flerp(float f1, float f2, float i1, float i2, float x) {
		return f1 + (f2 - f1) * (x - i1) / (i2 - i1);
	}

	void reset() {}

	/** Emitters: create particles over time through the system's emit callback. */
	abstract static class Emitter extends PFunction {
		float startAge;

		Emitter(Pd p) {
			super(p);
		}

		void start(PSystem sys, PState s) {
			startAge = s.age;
			onStart(sys);
		}

		abstract void onStart(PSystem sys);

		abstract void stop();

		abstract void emit(float dt, PState s, float strength);

		abstract boolean finished();
	}

	/** Initializers: set attributes of a newly created particle. */
	abstract static class Initializer extends PFunction {
		int definitionIndex = -1;

		Initializer(Pd p) {
			super(p);
		}

		abstract void init(Particle p, PCollection c, PState s);

		/** ParticleField bit mask this initializer writes (first-writer-wins rule below behavior version 6). */
		abstract long writes();

		static long mask(int field) {
			return 1L << field;
		}
	}

	/** Operators: update live particles every step. */
	abstract static class Operator extends PFunction {
		Operator(Pd p) {
			super(p);
		}

		abstract void operate(PCollection c, float dt, PState s, float strength);
	}

	/** Force generators: run by C_OP_BasicMovement, accumulate into Particle.forceAccumulator. */
	abstract static class Force extends PFunction {
		Force(Pd p) {
			super(p);
		}

		abstract void addForces(PCollection c, PState s, float strength);
	}

	/** Pre-emission operators: system-level work before emitters (child selection, stop after duration, ...). */
	abstract static class PreEmission extends PFunction {
		boolean hasRun;
		final boolean runOnce;

		PreEmission(Pd p) {
			super(p);
			runOnce = p.b("m_bRunOnce", false);
		}

		abstract void operate(PSystem sys, PState s, float dt);
	}
}
