package dev.strafemc.client.particle;

import org.joml.Vector3f;

/**
 * Emitters, operators, pre-emission operators and force generators used by the CS2 weapon effects.
 * Ports of VRF's ValveResourceFormat/Particles (MIT).
 */
final class Operators {
	private Operators() {}

	static PFunction create(String kind, String cls, Pd p) {
		return switch (cls) {
			case "C_OP_InstantaneousEmitter" -> new InstantaneousEmitter(p);
			case "C_OP_ContinuousEmitter" -> new ContinuousEmitter(p);
			case "C_OP_BasicMovement" -> new BasicMovement(p);
			case "C_OP_Decay" -> new Decay(p);
			case "C_OP_FadeOut" -> new FadeOutRandom(p);
			case "C_OP_PositionLock" -> new PositionLock(p);
			case "C_OP_RampScalarLinearSimple" -> new RampScalarLinearSimple(p);
			case "C_OP_InterpolateRadius" -> new InterpolateRadius(p);
			case "C_OP_ColorInterpolate" -> new ColorInterpolate(p);
			case "C_OP_SetFloat" -> new SetFloat(p);
			case "C_OP_LerpScalar" -> new LerpScalar(p);
			case "C_OP_Cull" -> new Cull(p);
			case "C_OP_MaxVelocity" -> new MaxVelocity(p);
			case "C_OP_DistanceToTransform" -> new DistanceToTransform(p);
			case "C_OP_FadeAndKillForTracers" -> new FadeAndKill(p, true);
			case "C_OP_FadeAndKill" -> new FadeAndKill(p, false);
			case "C_OP_ChooseRandomChildrenInGroup" -> new ChooseRandomChildren(p);
			case "C_OP_StopAfterCPDuration", "C_OP_StopAfterDuration" -> new StopAfterDuration(p);
			case "C_OP_PerParticleForce" -> new PerParticleForce(p);
			case "C_OP_RotateVector" -> new RotateVector(p);
			case "C_OP_SetAttributeToScalarExpression" -> new ScalarExpression(p);
			case "C_OP_DistanceBetweenCPsToCP" -> new DistanceBetweenCPsToCP(p);
			default -> null;
		};
	}

	static float smoothstep(float a, float b, float x) {
		float t = PMath.saturate((x - a) / (b - a));
		return t * t * (3 - 2 * t);
	}

	// ------------------------------------------------------------------ emitter

	static final class InstantaneousEmitter extends PFunction.Emitter {
		final Num count, startTime;
		final int maxPerFrame;
		PSystem sys;
		boolean finished, resolved;
		int remaining;

		InstantaneousEmitter(Pd p) {
			super(p);
			count = p.num("m_nParticlesToEmit", new Num.Literal(100));
			startTime = p.num("m_flStartTime", new Num.Literal(0));
			maxPerFrame = p.i("m_nMaxEmittedPerFrame", -1);
		}

		@Override
		void onStart(PSystem s) {
			sys = s;
			finished = false;
			resolved = false;
			remaining = 0;
		}

		@Override
		void stop() {
			finished = true;
			sys = null;
		}

		@Override
		boolean finished() {
			return finished;
		}

		@Override
		void emit(float dt, PState s, float strength) {
			if (finished || sys == null) return;
			float elapsed = s.age - startAge, start = startTime.next(s);
			if (elapsed < start) return;
			if (!resolved) {
				resolved = true;
				remaining = (int) count.next(s);
			}
			int cap = maxPerFrame >= 0 ? maxPerFrame : Math.min(sys.maxParticles, 20000);
			int claimed = Math.min(remaining, cap);
			remaining -= claimed;
			int n = (int) (claimed * strength);
			for (int i = 0; i < n; i++) sys.emit(elapsed - start);
			if (remaining <= 0) finished = true;
		}
	}

	/** Emits m_flEmitRate particles per second from m_flStartTime for m_flEmissionDuration seconds (0 = until stopped). */
	static final class ContinuousEmitter extends PFunction.Emitter {
		final Num duration, startTime, rate;
		PSystem sys;
		boolean finished;
		float owed;

		ContinuousEmitter(Pd p) {
			super(p);
			duration = p.num("m_flEmissionDuration", new Num.Literal(0));
			startTime = p.num("m_flStartTime", new Num.Literal(0));
			rate = p.num("m_flEmitRate", new Num.Literal(100));
		}

		@Override
		void onStart(PSystem s) {
			sys = s;
			finished = false;
			owed = 0;
		}

		@Override
		void stop() {
			finished = true;
			sys = null;
		}

		@Override
		boolean finished() {
			return finished;
		}

		@Override
		void emit(float dt, PState s, float strength) {
			if (finished || sys == null) return;
			float elapsed = s.age - startAge, start = startTime.next(s), dur = duration.next(s);
			if (elapsed < start) return;
			float from = Math.max(start, elapsed - dt), to = dur > 0 ? Math.min(elapsed, start + dur) : elapsed;
			if (to > from) owed += rate.next(s) * (to - from) * strength;
			int n = (int) owed;
			owed -= n;
			for (int i = 0; i < n; i++) sys.emit(dt * (n - i) / (n + 1f)); // spread over this frame
			if (dur > 0 && elapsed >= start + dur) finished = true;
		}
	}

	// ------------------------------------------------------------------ operators

	static final class BasicMovement extends PFunction.Operator {
		final Vec gravity;
		final Num drag;
		PSystem sys;

		BasicMovement(Pd p) {
			super(p);
			gravity = p.vecInput("m_Gravity", Vec.literal(new Vector3f()));
			drag = p.num("m_fDrag", new Num.Literal(0));
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			if (s.system != null)
				for (PFunction.Force f : s.system.forces) {
					float fs = f.runStrength(s);
					if (fs > 0) f.addForces(c, s, fs);
				}
			float dt2 = dt * dt;
			Vector3f g = gravity.next(s).mul(dt2);
			float d = Math.max(0, Math.min(0.9999999f, drag.next(s)));
			float dragFactor = (float) Math.exp(Math.log(1 - d) / (1.0 / 30.0) * dt);
			if (c.previousFrameTime > 0) dragFactor *= dt / c.previousFrameTime;
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				Vector3f forces = new Vector3f(q.forceAccumulator).mul(dt2).add(g);
				Vector3f step = forces.mul(q.forceScale).add(new Vector3f(q.position).sub(q.positionPrevious).mul(dragFactor));
				q.velocity.set(step).div(dt);
				q.forceAccumulator.zero();
				q.positionPrevious.set(q.position);
				q.position.add(step);
			}
		}
	}

	static final class Decay extends PFunction.Operator {
		Decay(Pd p) {
			super(p);
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			for (int i = 0; i < c.count; i++) if (c.current[i].age > c.current[i].lifetime) c.current[i].kill();
		}
	}

	/** C_OP_FadeOut (CGeneralRandomFade + ease in/out). */
	static final class FadeOutRandom extends PFunction.Operator {
		final float min, max, exp;
		final boolean proportional, ease;

		FadeOutRandom(Pd p) {
			super(p);
			min = p.f("m_flFadeOutTimeMin", 0.25f);
			max = p.f("m_flFadeOutTimeMax", 0.25f);
			exp = p.f("m_flFadeOutTimeExp", 1);
			proportional = p.b("m_bProportional", true);
			ease = p.b("m_bEaseInAndOut", true);
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				float fade = min == max ? min : (float) Math.pow(s.random.forParticle(q.id, 0), exp) * (max - min) + min;
				float left = proportional ? 1 - q.normalizedAge() : q.lifetime - q.age;
				if (left < fade) {
					float e = PMath.saturate(1 - left / fade);
					if (ease) e = smoothstep(0, 1, e);
					q.alpha = (1 - e) * c.initial[q.index].alpha;
				}
			}
		}
	}

	static final class PositionLock extends PFunction.Operator {
		final Xform transform;
		final float startMin, startMax, startExp, endMin, endMax, endExp, range, jump, prevScale;
		final Num rangeBias;
		final boolean lockRot;
		final Vec scale;
		final int out, outPrev;
		Vector3f prevPos;
		org.joml.Matrix4f prevMatrix = new org.joml.Matrix4f();

		PositionLock(Pd p) {
			super(p);
			transform = p.transform("m_TransformInput", Xform.CP0);
			startMin = p.f("m_flStartTime_min", 1);
			startMax = p.f("m_flStartTime_max", 1);
			startExp = Math.max(-255, Math.min(255, p.f("m_flStartTime_exp", 1)));
			endMin = p.f("m_flEndTime_min", 1);
			endMax = p.f("m_flEndTime_max", 1);
			endExp = Math.max(-255, Math.min(255, p.f("m_flEndTime_exp", 1)));
			range = p.f("m_flRange", 0);
			rangeBias = p.num("m_flRangeBias", new Num.Literal(0.2f));
			float j = p.f("m_flJumpThreshold", 512);
			jump = j * j;
			prevScale = p.f("m_flPrevPosScale", 1);
			lockRot = p.b("m_bLockRot", false);
			scale = p.vecInput("m_vecScale", Vec.literal(new Vector3f(1)));
			out = p.field("m_nFieldOutput", Particle.POSITION);
			outPrev = p.field("m_nFieldOutputPrev", Particle.POSITION_PREVIOUS);
		}

		@Override
		void reset() {
			prevPos = null;
			prevMatrix = new org.joml.Matrix4f();
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			org.joml.Matrix4f m = transform.matrix(s);
			Vector3f pos = m.getTranslation(new Vector3f());
			Vector3f sc = scale.next(s);
			Vector3f lockedNormal = transform.orientation(s);
			if (prevPos == null) {
				prevPos = new Vector3f(pos);
				prevMatrix = new org.joml.Matrix4f(m);
			}
			Vector3f delta = new Vector3f(pos).sub(prevPos);
			org.joml.Matrix4f rotLock = new org.joml.Matrix4f();
			if (lockRot) rotLock = new org.joml.Matrix4f(m).mul(new org.joml.Matrix4f(prevMatrix).invert());
			prevPos.set(pos);
			prevMatrix.set(m);
			boolean instantJump = jump != 0 && delta.lengthSquared() > jump;
			if (delta.lengthSquared() == 0 && !lockRot) return;
			boolean always = startMin >= 1;
			float bias = rangeBias.next(s);
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				float timeFade = 1;
				if (!always) {
					float st = (float) Math.pow(s.random.next(), startExp) * (startMax - startMin) + startMin;
					float et = (float) Math.pow(s.random.next(), endExp) * (endMax - endMin) + endMin;
					timeFade = q.normalizedAge() <= st ? 1 : 1 - PMath.saturate(PMath.remap(q.normalizedAge(), st, et));
					if (instantJump) timeFade = 1;
				}
				float lock = strength * timeFade;
				if (lock <= 0) continue;
				Vector3f cur = q.vector(out);
				float creation = dt > 0 ? Math.min(q.age, dt) / dt : 1;
				Vector3f d = new Vector3f(delta).mul(sc).mul(creation * lock);
				float rotStrength = always ? strength : timeFade;
				if (range > 0) {
					float dist = pos.distance(new Vector3f(cur).add(d));
					float fade = PMath.bias(PMath.saturate(dist / range), bias);
					d.mul(1 - fade);
					rotStrength = 1 - fade * rotStrength;
				}
				if (lockRot) {
					Vector3f prev = q.vector(outPrev);
					Vector3f rp = rotLock.transformPosition(new Vector3f(cur)), rpp = rotLock.transformPosition(new Vector3f(prev));
					float rs = rotStrength * creation;
					q.vector(outPrev, new Vector3f(prev).add(rpp.sub(prev).mul(rs * prevScale)));
					q.vector(out, new Vector3f(cur).add(rp.sub(cur).mul(rs)));
					if (lockedNormal != null) q.normal.set(lockedNormal);
				} else {
					q.vector(outPrev, q.vector(outPrev).add(new Vector3f(d).mul(prevScale)));
					q.vector(out, cur.add(d));
				}
			}
		}
	}

	static final class RampScalarLinearSimple extends PFunction.Operator {
		final float rate, start, end;
		final int field;

		RampScalarLinearSimple(Pd p) {
			super(p);
			rate = p.f("m_Rate", 0);
			start = p.f("m_flStartTime", 0);
			end = p.f("m_flEndTime", 1);
			field = p.field("m_nField", Particle.RADIUS);
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				float t = q.normalizedAge();
				if (t > start && t < end) q.scalar(field, q.scalar(field) + rate * dt * strength);
			}
		}
	}

	static final class InterpolateRadius extends PFunction.Operator {
		final float start, end, bias;
		final Num startScale, endScale;
		final boolean ease;

		InterpolateRadius(Pd p) {
			super(p);
			start = p.f("m_flStartTime", 0);
			end = p.f("m_flEndTime", 1);
			startScale = p.num("m_flStartScale", new Num.Literal(1));
			endScale = p.num("m_flEndScale", new Num.Literal(1));
			ease = p.b("m_bEaseInAndOut", false);
			float b = p.f("m_flBias", 0.5f);
			bias = b == 0 ? 0.5f : b;
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			if (end <= start) return;
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				if (q.lifetime <= 0) continue;
				float t = q.normalizedAge(), ff = dt / q.lifetime;
				float windowEnd = q.lifetime * (end - start) <= dt ? start + ff : end;
				if (t >= start && t <= ff + windowEnd) {
					float a = startScale.next(q, s), b = endScale.next(q, s);
					float ts = PMath.remap(t, start, end);
					if (ease) ts = ts * ts * (3 - 2 * ts);
					else if (bias != 0.5f) ts = PMath.bias(ts, bias);
					float r = Math.max(Math.min(a, b), Math.min(Math.max(a, b), PMath.lerp(a, b, ts)));
					q.radius = c.initial[q.index].radius * r;
				}
			}
		}
	}

	static final class ColorInterpolate extends PFunction.Operator {
		final Vector3f fade;
		final float start, end;
		final int field;
		final boolean ease;

		ColorInterpolate(Pd p) {
			super(p);
			fade = p.color24("m_ColorFade", new Vector3f(1));
			start = p.f("m_flFadeStartTime", 0);
			end = p.f("m_flFadeEndTime", 1);
			field = p.field("m_nFieldOutput", Particle.COLOR);
			ease = p.b("m_bEaseInOut", true);
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				float t = q.normalizedAge();
				if (t >= start && t <= end) {
					float k = PMath.remap(t, start, end);
					if (ease) k = smoothstep(0, 1, k);
					Vector3f init = c.initial[q.index].vector(Particle.COLOR);
					Vector3f faded = new Vector3f(init).lerp(fade, k);
					q.vector(field, new Vector3f(init).lerp(faded, strength));
				}
			}
		}
	}

	static final class SetFloat extends PFunction.Operator {
		final int field, method;
		final Num value, lerp;

		SetFloat(Pd p) {
			super(p);
			field = p.field("m_nOutputField", Particle.RADIUS);
			value = p.num("m_InputValue", new Num.Literal(0));
			method = Particle.setMethod(p.str("m_nSetMethod", null));
			lerp = p.num("m_Lerp", new Num.Literal(1));
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				float v = value.next(q, s), l = PMath.saturate(lerp.next(q, s));
				if (Particle.isAngle(field) && method != Particle.SET_SCALE_INITIAL && method != Particle.SET_SCALE_CURRENT) v = (float) Math.toRadians(v);
				float target = q.modifyScalar(c, field, v, method), cur = q.scalar(field);
				float blended = PMath.lerp(cur, target, l);
				if (field == Particle.ALPHA || field == Particle.ALPHA_ALTERNATE) blended = PMath.saturate(blended);
				else if (field == Particle.RADIUS || field == Particle.TRAIL_LENGTH) blended = Math.max(0, blended);
				float base = method == Particle.SET_SCALE_INITIAL || method == Particle.SET_ADD_INITIAL ? c.initial[q.index].scalar(field) : cur;
				q.scalar(field, PMath.lerp(base, blended, strength));
			}
		}
	}

	static final class LerpScalar extends PFunction.Operator {
		final int field;
		final Num output;
		final float start, end;

		LerpScalar(Pd p) {
			super(p);
			field = p.field("m_nFieldOutput", Particle.RADIUS);
			output = p.num("m_flOutput", new Num.Literal(1));
			start = p.f("m_flStartTime", 0);
			end = p.f("m_flEndTime", 1);
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				float w = PMath.saturate(PMath.remap(q.normalizedAge(), start, end));
				float v = PMath.lerp(c.initial[q.index].scalar(field), output.next(q, s), w);
				q.scalar(field, PMath.lerp(q.scalar(field), v, strength));
			}
		}
	}

	static final class Cull extends PFunction.Operator {
		final float perc, start, end, exp;

		Cull(Pd p) {
			super(p);
			perc = p.f("m_flCullPerc", 0.5f);
			start = p.f("m_flCullStart", 0);
			end = p.f("m_flCullEnd", 1);
			exp = p.f("m_flCullExp", 1);
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			for (int i = 0; i < c.count; i++) {
				float chance = s.random.next();
				float t = (float) Math.pow(s.random.next(), exp) * (end - start) + start;
				if (chance < perc && c.current[i].normalizedAge() >= t) c.current[i].kill();
			}
		}
	}

	static final class MaxVelocity extends PFunction.Operator {
		final Num max, min;

		MaxVelocity(Pd p) {
			super(p);
			max = p.num("m_flMaxVelocity", new Num.Literal(0));
			min = p.num("m_flMinVelocity", new Num.Literal(0));
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				float mx = max.next(q, s), mn = min.next(q, s);
				Vector3f orig = new Vector3f(q.velocity);
				float sp = orig.length();
				Vector3f nv;
				if (sp > mx) nv = new Vector3f(orig).mul(mx / sp);
				else if (sp > 0 && sp < mn) nv = new Vector3f(orig).mul(mn / sp);
				else continue;
				q.velocity.set(orig.lerp(nv, strength));
				q.positionPrevious.set(q.position).sub(new Vector3f(q.velocity).mul(dt));
			}
		}
	}

	static final class DistanceToTransform extends PFunction.Operator {
		final int field, method;
		final Num inMin, inMax, outMin, outMax;
		final Xform start;
		final boolean activeRange, additive;
		final Vec scale;

		DistanceToTransform(Pd p) {
			super(p);
			field = p.field("m_nFieldOutput", Particle.RADIUS);
			inMin = p.num("m_flInputMin", new Num.Literal(0));
			inMax = p.num("m_flInputMax", new Num.Literal(128));
			outMin = p.num("m_flOutputMin", new Num.Literal(0));
			outMax = p.num("m_flOutputMax", new Num.Literal(1));
			start = p.transform("m_TransformStart", Xform.CP0);
			method = Particle.setMethod(p.str("m_nSetMethod", null));
			activeRange = p.b("m_bActiveRange", false);
			additive = p.b("m_bAdditive", false);
			scale = p.vecInput("m_vecComponentScale", Vec.literal(new Vector3f(1)));
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			Vector3f origin = start.matrix(s).getTranslation(new Vector3f());
			boolean clamp = field == Particle.ALPHA || field == Particle.ALPHA_ALTERNATE;
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				float a = inMin.next(q, s), b = inMax.next(q, s), oa = outMin.next(q, s), ob = outMax.next(q, s);
				Vector3f d = new Vector3f(q.position).sub(origin).mul(scale.next(q, s));
				float d2 = d.lengthSquared(), a2 = a * a, b2 = b * b;
				boolean pass = !activeRange || (d2 <= b2 && a2 <= d2);
				if (!pass) continue;
				if (clamp) {
					oa = PMath.saturate(oa);
					ob = PMath.saturate(ob);
				}
				float div = b2 == a2 ? 1 : b2 - a2;
				float v = PMath.lerp(oa, ob, PMath.saturate((d2 - a2) / div));
				float fin = q.modifyScalar(c, field, v, method), cur = q.scalar(field);
				float delta = (fin - cur) * strength;
				q.scalar(field, additive ? cur + (cur + delta) : cur + delta);
			}
		}
	}

	static final class FadeAndKill extends PFunction.Operator {
		final float inStart, inEnd, outStart, outEnd, startAlpha, endAlpha;
		final boolean tracers;

		FadeAndKill(Pd p, boolean tracers) {
			super(p);
			this.tracers = tracers;
			inStart = p.f("m_flStartFadeInTime", 0);
			inEnd = p.f("m_flEndFadeInTime", 0.5f);
			outStart = p.f("m_flStartFadeOutTime", 0.5f);
			outEnd = p.f("m_flEndFadeOutTime", 1);
			startAlpha = p.f("m_flStartAlpha", 1);
			endAlpha = p.f("m_flEndAlpha", 0);
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				boolean expired = tracers ? q.lifetime <= q.age : q.lifetime < q.age;
				if (expired) {
					if (!tracers) {
						q.kill();
						continue;
					}
					float ageAtStart = q.age - dt;
					if (ageAtStart >= q.lifetime || dt <= 0) {
						q.kill();
						continue;
					}
					float lived = (q.lifetime - ageAtStart) / dt;
					q.position.set(new Vector3f(q.positionPrevious).lerp(q.position, lived));
					q.trailLength *= lived;
					continue;
				}
				float t = q.normalizedAge(), init = c.initial[q.index].alpha, alpha = q.alpha;
				if (t >= inStart && t < inEnd) alpha = init * PMath.lerp(startAlpha, 1, smoothstep(inStart, inEnd, t));
				if (t >= outStart && t < outEnd) alpha = init * PMath.lerp(1, endAlpha, smoothstep(outStart, outEnd, t));
				q.alpha = tracers ? alpha : PMath.lerp(q.alpha, alpha, strength);
			}
		}
	}

	static final class RotateVector extends PFunction.Operator {
		final int field;
		final Vector3f axisMin, axisMax;
		final float rateMin, rateMax;
		final Num scale;
		final boolean normalize;

		RotateVector(Pd p) {
			super(p);
			field = p.field("m_nFieldOutput", Particle.NORMAL);
			axisMin = p.vec("m_vecRotAxisMin", new Vector3f(0, 0, 1));
			axisMax = p.vec("m_vecRotAxisMax", new Vector3f(0, 0, 1));
			rateMin = p.f("m_flRotRateMin", 180);
			rateMax = p.f("m_flRotRateMax", 180);
			scale = p.num("m_flScale", new Num.Literal(1));
			normalize = p.b("m_bNormalize", true);
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				float r = s.random.forParticle(q.id, 0);
				Vector3f axis = new Vector3f(axisMin).lerp(axisMax, r);
				if (axis.lengthSquared() == 0) continue;
				axis.normalize();
				float rate = (float) Math.toRadians(PMath.lerp(rateMin, rateMax, r));
				Vector3f cur = q.vector(field);
				Vector3f rot = new org.joml.Matrix3f().rotation(rate * scale.next(q, s) * dt, axis).transform(new Vector3f(cur));
				if (normalize) rot = PMath.safeNormalize(rot);
				q.vector(field, cur.lerp(rot, strength));
			}
		}
	}

	static final class ScalarExpression extends PFunction.Operator {
		final int field, method;
		final Num in1, in2;
		final String expr;
		final Num.Mapping remap;

		ScalarExpression(Pd p) {
			super(p);
			field = p.field("m_nOutputField", Particle.RADIUS);
			in1 = p.num("m_flInput1", new Num.Literal(1));
			in2 = p.num("m_flInput2", new Num.Literal(0));
			expr = p.str("m_nExpression", "SCALAR_EXPRESSION_ADD");
			method = Particle.setMethod(p.str("m_nSetMethod", null));
			remap = p.raw("m_flOutputRemap") instanceof java.util.Map<?, ?> m ? new Num.Mapping(p.nested(m)) : null;
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				float a = in1.next(q, s), b = in2.next(q, s);
				float out = switch (expr) {
					case "SCALAR_EXPRESSION_SUBTRACT" -> a - b;
					case "SCALAR_EXPRESSION_MUL" -> a * b;
					case "SCALAR_EXPRESSION_DIVIDE" -> b == 0 ? 0 : a / b;
					case "SCALAR_EXPRESSION_INPUT_1" -> a;
					case "SCALAR_EXPRESSION_MIN" -> Math.min(a, b);
					case "SCALAR_EXPRESSION_MAX" -> Math.max(a, b);
					case "SCALAR_EXPRESSION_MOD" -> a % b;
					case "SCALAR_EXPRESSION_EQUAL" -> a == b ? 1 : 0;
					case "SCALAR_EXPRESSION_GT" -> a > b ? 1 : 0;
					case "SCALAR_EXPRESSION_LT" -> a < b ? 1 : 0;
					case "SCALAR_EXPRESSION_UNINITIALIZED" -> 0;
					default -> a + b;
				};
				if (!expr.equals("SCALAR_EXPRESSION_EQUAL") && !expr.equals("SCALAR_EXPRESSION_GT") && !expr.equals("SCALAR_EXPRESSION_LT")) out *= strength;
				if (remap != null) out = remap.apply(out);
				q.scalar(field, q.modifyScalar(c, field, out, method));
			}
		}
	}

	// ------------------------------------------------------------------ pre-emission

	static final class DistanceBetweenCPsToCP extends PFunction.PreEmission {
		final float inMin, inMax, outMin, outMax;
		final int start, end, outCp, outField;

		DistanceBetweenCPsToCP(Pd p) {
			super(p);
			inMin = p.f("m_flInputMin", 0);
			inMax = p.f("m_flInputMax", 128);
			outMin = p.f("m_flOutputMin", 0);
			outMax = p.f("m_flOutputMax", 1);
			start = p.i("m_nStartCP", 0);
			end = p.i("m_nEndCP", 1);
			outCp = p.i("m_nOutputCP", 2);
			outField = p.i("m_nOutputCPField", 0);
		}

		@Override
		void operate(PSystem sys, PState s, float dt) {
			float d = s.cp(start).position.distance(s.cp(end).position);
			float v = outMin + (outMax - outMin) * PMath.saturate(PMath.remap(d, inMin, inMax));
			s.cp(outCp).position.setComponent(Math.max(0, Math.min(2, outField)), v);
		}
	}

	static final class ChooseRandomChildren extends PFunction.PreEmission {
		final int group;
		final Num number;

		ChooseRandomChildren(Pd p) {
			super(p);
			group = p.i("m_nChildGroupID", 1);
			number = p.num("m_flNumberOfChildren", new Num.Literal(1));
		}

		@Override
		void operate(PSystem sys, PState s, float dt) {
			sys.chooseRandomChildrenInGroup(group, (int) number.next(s));
		}
	}

	static final class StopAfterDuration extends PFunction.PreEmission {
		final Num duration;
		boolean stopped;

		StopAfterDuration(Pd p) {
			super(p);
			duration = p.num("m_flDuration", new Num.Literal(1));
		}

		@Override
		void reset() {
			stopped = false;
		}

		@Override
		void operate(PSystem sys, PState s, float dt) {
			if (stopped || s.inEndCap) return;
			if (s.age > duration.next(s)) {
				stopped = true;
				sys.stop();
			}
		}
	}

	// ------------------------------------------------------------------ forces

	static final class PerParticleForce extends PFunction.Force {
		final Num scale;
		final Vec force;
		final int cp;

		PerParticleForce(Pd p) {
			super(p);
			scale = p.num("m_flForceScale", new Num.Literal(1));
			force = p.vecInput("m_vForce", Vec.literal(new Vector3f()));
			cp = p.i("m_nCP", -1);
		}

		@Override
		void addForces(PCollection c, PState s, float strength) {
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				Vector3f v = force.next(q, s);
				if (cp >= 0) v = Initializers.transformDirection(s, cp, v);
				q.forceAccumulator.add(v.mul(scale.next(q, s) * strength));
			}
		}
	}
}
