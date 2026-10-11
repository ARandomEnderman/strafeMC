package dev.strafemc.client.particle;

import org.joml.Vector3f;

/** More operators, initializers and forces used by CS2's impact effects. Ports of VRF's ValveResourceFormat/Particles (MIT). */
final class MoreFunctions {
	private MoreFunctions() {}

	static PFunction create(String kind, String cls, Pd p) {
		return switch (cls) {
			case "C_OP_Spin" -> new Spin(p, false);
			case "C_OP_SpinYaw" -> new Spin(p, true);
			case "C_OP_SpinUpdate" -> new SpinUpdate(p);
			case "C_OP_VelocityDecay" -> new VelocityDecay(p);
			case "C_OP_FadeInSimple" -> new FadeInSimple(p);
			case "C_OP_FadeIn" -> new FadeInRandom(p);
			case "C_OP_AlphaDecay" -> new AlphaDecay(p);
			case "C_OP_ClampScalar" -> new ClampScalar(p);
			case "C_OP_OscillateScalar" -> new OscillateScalar(p);
			case "C_OP_SetVec" -> new SetVec(p);
			case "C_OP_DistanceCull" -> new DistanceCull(p);
			case "C_INIT_RandomYawFlip" -> new RandomYawFlip(p);
			case "C_INIT_RandomSecondSequence" -> new RandomSecondSequence(p);
			case "C_INIT_NormalOffset" -> new NormalOffset(p);
			case "C_OP_LocalAccelerationForce" -> new LocalAcceleration(p);
			case "C_OP_RandomForce" -> new RandomForce(p);
			case "C_OP_TimeVaryingForce" -> new TimeVaryingForce(p);
			default -> null;
		};
	}

	/** Valve's parabolic sin(pi * x) (ParticleMath.SinPi). */
	static float sinPi(float x) {
		float phase = Math.abs(x) % 2f;
		boolean second = phase >= 1;
		float t = second ? phase - 1 : phase;
		float v = (4 - 4 * t) * t;
		return (x < 0) ^ second ? -v : v;
	}

	static final class Spin extends PFunction.Operator {
		final int rate, rateMin;
		final float stop;
		final boolean yaw;

		Spin(Pd p, boolean yaw) {
			super(p);
			this.yaw = yaw;
			rate = p.i("m_nSpinRateDegrees", 0);
			rateMin = p.i("m_nSpinRateMinDegrees", 0);
			stop = p.f("m_fSpinRateStopTime", 0);
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				float r;
				if (stop == 0) r = rate * strength;
				else {
					float stopAge = q.lifetime * stop, rem = stopAge != 0 ? Math.max(0, 1 - q.age / stopAge) : 0, dec = rate * strength * rem;
					r = rate >= 0 ? Math.max(dec, rateMin) : Math.min(dec, rateMin);
				}
				float delta = (float) Math.toRadians(r) * (float) (Math.PI * 2) * dt;
				if (yaw) q.rotation.x += delta;
				else q.rotation.z += delta;
			}
		}
	}

	static final class SpinUpdate extends PFunction.Operator {
		SpinUpdate(Pd p) {
			super(p);
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				q.rotation.add(new Vector3f(q.rotationSpeed).mul(Math.min(dt, q.age) * strength));
			}
		}
	}

	static final class VelocityDecay extends PFunction.Operator {
		final float min;

		VelocityDecay(Pd p) {
			super(p);
			min = p.f("m_flMinVelocity", 1);
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			for (int i = 0; i < c.count; i++) if (c.current[i].speed() <= min) c.current[i].kill();
		}
	}

	static final class FadeInSimple extends PFunction.Operator {
		final float time;
		final int field;

		FadeInSimple(Pd p) {
			super(p);
			time = p.f("m_flFadeInTime", 0.25f);
			field = p.field("m_nFieldOutput", Particle.ALPHA);
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				float t = q.normalizedAge();
				if (t <= time) q.scalar(field, t / time * c.initial[q.index].alpha);
			}
		}
	}

	static final class FadeInRandom extends PFunction.Operator {
		final float min, max, exp;
		final boolean proportional;

		FadeInRandom(Pd p) {
			super(p);
			min = p.f("m_flFadeInTimeMin", 0.25f);
			max = p.f("m_flFadeInTimeMax", 0.25f);
			exp = p.f("m_flFadeInTimeExp", 1);
			proportional = p.b("m_bProportional", true);
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				float fade = min == max ? min : (float) Math.pow(s.random.forParticle(q.id, 0), exp) * (max - min) + min;
				float t = proportional ? q.normalizedAge() : q.age;
				if (t < fade) q.alpha = Operators.smoothstep(0, fade, t) * c.initial[q.index].alpha;
			}
		}
	}

	static final class AlphaDecay extends PFunction.Operator {
		final float min;

		AlphaDecay(Pd p) {
			super(p);
			min = p.f("m_flMinAlpha", 0);
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			for (int i = 0; i < c.count; i++) if (c.current[i].alpha <= min) c.current[i].kill();
		}
	}

	static final class ClampScalar extends PFunction.Operator {
		final int field;
		final Num min, max;

		ClampScalar(Pd p) {
			super(p);
			field = p.field("m_nFieldOutput", Particle.RADIUS);
			min = p.num("m_flOutputMin", new Num.Literal(0));
			max = p.num("m_flOutputMax", new Num.Literal(1));
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				float cur = q.scalar(field), v = Math.min(max.next(q, s), Math.max(cur, min.next(q, s)));
				q.scalar(field, PMath.lerp(cur, v, strength));
			}
		}
	}

	static final class OscillateScalar extends PFunction.Operator {
		final int field;
		final float rateMin, rateMax, freqMin, freqMax, startMin, startMax, endMin, endMax, mult, add;
		final boolean proportional, proportionalWindow, clamp;

		OscillateScalar(Pd p) {
			super(p);
			field = p.field("m_nField", Particle.ALPHA);
			rateMin = p.f("m_RateMin", 0);
			rateMax = p.f("m_RateMax", 0);
			freqMin = p.f("m_FrequencyMin", 1);
			freqMax = p.f("m_FrequencyMax", 1);
			startMin = p.f("m_flStartTime_min", 0);
			startMax = p.f("m_flStartTime_max", 0);
			endMin = p.f("m_flEndTime_min", 1);
			endMax = p.f("m_flEndTime_max", 1);
			mult = p.f("m_flOscMult", 2);
			add = p.f("m_flOscAdd", 0.5f);
			proportional = p.b("m_bProportional", true);
			proportionalWindow = p.b("m_bProportionalOp", true);
			clamp = field == Particle.ALPHA || field == Particle.ALPHA_ALTERNATE;
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			float phase = mult * s.age + add, step = strength * dt;
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				if (q.lifetime <= 0) continue;
				float wt = proportionalWindow ? q.normalizedAge() : q.age;
				float st = s.random.forParticleBetween(q.id, 11, startMin, startMax), et = s.random.forParticleBetween(q.id, 12, endMin, endMax);
				if (wt < st || wt >= et) continue;
				float f = s.random.forParticleBetween(q.id, 0, freqMin, freqMax), r = s.random.forParticleBetween(q.id, 1, rateMin, rateMax);
				float d = sinPi(proportional ? q.normalizedAge() * f * mult + add : f * phase);
				float v = q.scalar(field) + d * r * step;
				q.scalar(field, clamp ? PMath.saturate(v) : v);
			}
		}
	}

	static final class SetVec extends PFunction.Operator {
		final int field, method;
		final Vec value;
		final Num lerp;
		final boolean normalized;

		SetVec(Pd p) {
			super(p);
			field = p.field("m_nOutputField", Particle.COLOR);
			value = p.vecInput("m_InputValue", Vec.literal(new Vector3f()));
			method = Particle.setMethod(p.str("m_nSetMethod", null));
			lerp = p.num("m_Lerp", new Num.Literal(1));
			normalized = p.b("m_bNormalizedOutput", false);
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				Vector3f v = value.next(q, s);
				float l = PMath.saturate(lerp.next(q, s) * strength);
				Vector3f cur = q.modifyVector(c, field, v, method), init = q.vector(field);
				v = init.lerp(cur, l);
				if (normalized && v.lengthSquared() > 0) v = PMath.safeNormalize(v);
				q.vector(field, v);
			}
		}
	}

	static final class DistanceCull extends PFunction.Operator {
		final int cp;
		final Num distance;
		final Vector3f offset;
		final boolean inside;

		DistanceCull(Pd p) {
			super(p);
			cp = p.i("m_nControlPoint", 0);
			offset = p.vec("m_vecPointOffset", new Vector3f());
			distance = p.num("m_flDistance", new Num.Literal(0));
			inside = p.b("m_bCullInside", false);
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			float r = distance.next(s);
			Vector3f o = new Vector3f(s.cp(cp).position).add(offset);
			for (int i = 0; i < c.count; i++) {
				float edge = o.distance(c.current[i].position) - r;
				if (inside ? edge < 0 : edge > 0) c.current[i].kill();
			}
		}
	}

	static final class RandomYawFlip extends PFunction.Initializer {
		final float percent;

		RandomYawFlip(Pd p) {
			super(p);
			percent = p.f("m_flPercent", 0.5f);
		}

		@Override
		long writes() {
			return mask(Particle.YAW);
		}

		@Override
		void init(Particle q, PCollection c, PState s) {
			if (s.random.next() < percent) q.rotation.x += (float) Math.PI;
		}
	}

	static final class RandomSecondSequence extends PFunction.Initializer {
		final int min, max;

		RandomSecondSequence(Pd p) {
			super(p);
			min = p.i("m_nSequenceMin", 0);
			max = p.i("m_nSequenceMax", 0);
		}

		@Override
		long writes() {
			return mask(Particle.SECOND_SEQUENCE);
		}

		@Override
		void init(Particle q, PCollection c, PState s) {
			float r = s.random.next();
			q.secondSequence = max > min ? Math.min(min + (int) (r * (max - min + 1)), max) : min;
		}
	}

	static final class NormalOffset extends PFunction.Initializer {
		final Vector3f min, max;
		final int cp;
		final boolean local, normalize;

		NormalOffset(Pd p) {
			super(p);
			min = p.vec("m_OffsetMin", new Vector3f());
			max = p.vec("m_OffsetMax", new Vector3f());
			cp = p.i("m_nControlPointNumber", 0);
			local = p.b("m_bLocalCoords", false);
			normalize = p.b("m_bNormalize", false);
		}

		@Override
		long writes() {
			return mask(Particle.CREATION_TIME) | mask(Particle.NORMAL);
		}

		@Override
		void init(Particle q, PCollection c, PState s) {
			Vector3f o = s.random.nextBetweenPerComponent(min, max);
			if (local) o = Initializers.transformDirection(s, cp, o);
			Vector3f n = q.vector(Particle.NORMAL).add(o);
			if (normalize && n.lengthSquared() > 0) n = PMath.safeNormalize(n);
			q.vector(Particle.NORMAL, n);
		}
	}

	static final class LocalAcceleration extends PFunction.Force {
		final int cp, scaleCp;
		final Vec accel;

		LocalAcceleration(Pd p) {
			super(p);
			cp = p.i("m_nCP", 0);
			scaleCp = p.i("m_nScaleCP", -1);
			accel = p.vecInput("m_vecAccel", Vec.literal(new Vector3f()));
		}

		@Override
		void addForces(PCollection c, PState s, float strength) {
			Vector3f f = accel.next(s);
			if (cp >= 0) {
				if (scaleCp >= 0) f.mul(s.cp(scaleCp).position);
				f = Initializers.transformDirection(s, cp, f);
			}
			for (int i = 0; i < c.count; i++) c.current[i].forceAccumulator.add(f);
		}
	}

	static final class RandomForce extends PFunction.Force {
		final Vector3f min, max;

		RandomForce(Pd p) {
			super(p);
			min = p.vec("m_MinForce", new Vector3f());
			max = p.vec("m_MaxForce", new Vector3f());
		}

		@Override
		void addForces(PCollection c, PState s, float strength) {
			for (int i = 0; i < c.count; i++) c.current[i].forceAccumulator.add(s.random.nextBetweenPerComponent(min, max).mul(strength));
		}
	}

	static final class TimeVaryingForce extends PFunction.Force {
		final float start, end;
		final Vector3f from, to;

		TimeVaryingForce(Pd p) {
			super(p);
			start = p.f("m_flStartLerpTime", 0);
			end = p.f("m_flEndLerpTime", 10);
			from = p.vec("m_StartingForce", new Vector3f());
			to = p.vec("m_EndingForce", new Vector3f());
		}

		@Override
		void addForces(PCollection c, PState s, float strength) {
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				float t = end != start ? PMath.saturate((q.age - start) / (end - start)) : q.age > end ? 1 : 0;
				q.forceAccumulator.add(new Vector3f(from).lerp(to, t).mul(strength));
			}
		}
	}
}
