package dev.strafemc.client.particle;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/** Particle initializers used by the CS2 weapon effects. Ports of VRF's ValveResourceFormat/Particles/Initializers (MIT). */
final class Initializers {
	private Initializers() {}

	static PFunction create(String cls, Pd p) {
		return switch (cls) {
			case "C_INIT_InitFloat" -> new InitFloat(p);
			case "C_INIT_InitFloatCollection" -> new InitFloatCollection(p);
			case "C_INIT_InitVec" -> new InitVec(p);
			case "C_INIT_CreateWithinSphereTransform" -> new CreateWithinSphere(p);
			case "C_INIT_CreateWithinBox" -> new CreateWithinBox(p);
			case "C_INIT_RandomSequence" -> new RandomSequence(p);
			case "C_INIT_RandomColor" -> new RandomColor(p);
			case "C_INIT_VelocityRandom" -> new VelocityRandom(p);
			case "C_INIT_PositionOffset" -> new PositionOffset(p);
			case "C_INIT_GlobalScale" -> new GlobalScale(p);
			case "C_INIT_RemapScalarToVector" -> new RemapScalarToVector(p);
			case "C_INIT_InheritVelocity" -> new InheritVelocity(p);
			case "C_INIT_RingWave" -> new RingWave(p);
			case "C_INIT_MoveBetweenPoints" -> new MoveBetweenPoints(p);
			case "C_INIT_DistanceToCPInit" -> new DistanceToCPInit(p);
			default -> null;
		};
	}

	// ------------------------------------------------------------------ helpers

	static Vector3f transformPosition(PState s, int cp, Vector3f v) {
		return s.cp(cp).transform(true).transformPosition(new Vector3f(v));
	}

	static Vector3f transformDirection(PState s, int cp, Vector3f v) {
		return s.cp(cp).transform(true).transformDirection(new Vector3f(v));
	}

	/** RangeSampler: uniform per component, or an even (Halton) distribution when m_randomnessParameters asks for it. */
	static final class RangeSampler {
		final boolean even;
		final int seed;

		RangeSampler(Pd p) {
			Pd r = p.nested(p.raw("m_randomnessParameters"));
			even = r.b("m_bDistributeEvenly", false);
			seed = r.i("m_nSeed", 0);
		}

		Vector3f between(Particle q, PState s, Vector3f min, Vector3f max) {
			if (!even) return s.random.nextBetweenPerComponent(min, max);
			int start = seed < 0 ? seed + s.random.seed : seed;
			int index = (start + q.uniqueId) & 0x7FFFFFFF;
			Vector3f f = new Vector3f(radicalInverse(index, 2), radicalInverse(index, 3), radicalInverse(index, 5));
			return new Vector3f(max).sub(min).mul(f).add(min);
		}

		private static float radicalInverse(int index, int radix) {
			float inv = 1f / radix, digit = 1f, r = 0;
			do {
				digit *= inv;
				r += (index % radix) * digit;
				index /= radix;
			} while (index > 0);
			return r;
		}
	}

	static Vec negY(Vec v, boolean apply) {
		if (!apply) return v;
		return new Vec() {
			@Override
			Vector3f next(Particle q, PState s) {
				Vector3f r = v.next(q, s);
				r.y = -r.y;
				return r;
			}
		};
	}

	// ------------------------------------------------------------------ initializers

	static final class InitFloat extends PFunction.Initializer {
		final int field, method;
		final Num value, strength;

		InitFloat(Pd p) {
			super(p);
			field = p.field("m_nOutputField", Particle.RADIUS);
			value = p.num("m_InputValue", new Num.Literal(0));
			strength = p.num("m_InputStrength", new Num.Literal(1));
			method = Particle.setMethod(p.str("m_nSetMethod", null));
		}

		@Override
		long writes() {
			return mask(field);
		}

		@Override
		void init(Particle q, PCollection c, PState s) {
			float v = value.next(q, s);
			if (Particle.isAngle(field) && method != Particle.SET_SCALE_INITIAL && method != Particle.SET_SCALE_CURRENT) v = (float) Math.toRadians(v);
			float target = q.modifyScalarAtSpawn(c, field, v, method), cur = q.scalar(field);
			q.scalar(field, PMath.lerp(cur, target, strength.next(q, s)));
		}
	}

	static final class InitFloatCollection extends PFunction.Initializer {
		final int field;
		final Num value;

		InitFloatCollection(Pd p) {
			super(p);
			field = p.field("m_nOutputField", Particle.RADIUS);
			value = p.num("m_InputValue", new Num.Literal(0));
		}

		@Override
		long writes() {
			return mask(field);
		}

		@Override
		void init(Particle q, PCollection c, PState s) {
			q.scalar(field, value.next(s));
		}
	}

	static final class InitVec extends PFunction.Initializer {
		final int field;
		final Vec value;
		final boolean writePrev;

		InitVec(Pd p) {
			super(p);
			field = p.field("m_nOutputField", Particle.COLOR);
			value = p.vecInput("m_InputValue", Vec.literal(new Vector3f()));
			writePrev = p.b("m_bWritePreviousPosition", false);
		}

		@Override
		long writes() {
			return writePrev && field == Particle.POSITION ? mask(Particle.POSITION) | mask(Particle.POSITION_PREVIOUS) : mask(field);
		}

		@Override
		void init(Particle q, PCollection c, PState s) {
			q.vector(field, value.next(q, s));
		}
	}

	static final class CreateWithinSphere extends PFunction.Initializer {
		final Xform transform;
		final Num radiusMin, radiusMax, speedMin, speedMax;
		final Vec localSpeedMin, localSpeedMax, distanceBias;
		final Vector3f distanceBiasAbs;
		final float speedRandExp;
		final boolean localCoords;
		final int outField, velField;

		CreateWithinSphere(Pd p) {
			super(p);
			transform = p.transform("m_TransformInput", Xform.CP0);
			radiusMin = p.num("m_fRadiusMin", new Num.Literal(0));
			radiusMax = p.num("m_fRadiusMax", new Num.Literal(0));
			speedMin = p.num("m_fSpeedMin", new Num.Literal(0));
			speedMax = p.num("m_fSpeedMax", new Num.Literal(0));
			speedRandExp = Math.max(-255, Math.min(255, p.f("m_fSpeedRandExp", 1)));
			// Vpcf61ToVpcf62: before format 62 the local-space speed's Y axis was mirrored
			localSpeedMin = negY(p.vecInput("m_LocalCoordinateSystemSpeedMin", Vec.literal(new Vector3f())), p.format > 0 && p.format < 62);
			localSpeedMax = negY(p.vecInput("m_LocalCoordinateSystemSpeedMax", Vec.literal(new Vector3f())), p.format > 0 && p.format < 62);
			distanceBias = p.vecInput("m_vecDistanceBias", Vec.literal(new Vector3f(1)));
			distanceBiasAbs = p.vec("m_vecDistanceBiasAbs", new Vector3f());
			localCoords = p.b("m_bLocalCoords", false);
			outField = p.field("m_nFieldOutput", Particle.POSITION);
			velField = p.field("m_nFieldVelocity", Particle.POSITION_PREVIOUS);
		}

		@Override
		long writes() {
			return outField == Particle.POSITION ? mask(Particle.POSITION) | mask(Particle.POSITION_PREVIOUS) | mask(velField) : mask(outField) | mask(velField);
		}

		@Override
		void init(Particle q, PCollection c, PState s) {
			Matrix4f m = transform.matrix(s);
			Vector3f pos = m.getTranslation(new Vector3f());
			float[] rf = new float[1];
			Vector3f rv = s.random.nextInUnitBall(rf);
			if (distanceBiasAbs.x != 0) rv.x = Math.abs(rv.x);
			if (distanceBiasAbs.y != 0) rv.y = Math.abs(rv.y);
			if (distanceBiasAbs.z != 0) rv.z = Math.abs(rv.z);
			Vector3f dir = PMath.safeNormalize(rv.mul(distanceBias.next(q, s)));
			float rmin = radiusMin.next(q, s), rmax = radiusMax.next(q, s);
			float dist = (rmax - rmin) * rf[0] + rmin;
			float smin = speedMin.next(q, s), smax = speedMax.next(q, s);
			float speed = (float) Math.pow(s.random.next(), speedRandExp) * (smax - smin) + smin;
			Vector3f local = s.random.nextBetweenPerComponent(localSpeedMin.next(q, s), localSpeedMax.next(q, s));
			Vector3f offset = new Vector3f(dir).mul(dist);
			Vector3f world = localCoords ? m.transformDirection(offset).add(pos) : new Vector3f(pos).add(offset);
			q.vector(outField, world);
			Vector3f vdir = localCoords ? m.transformDirection(new Vector3f(dir)) : dir;
			q.velocity.set(vdir.mul(speed).add(m.transformDirection(local)));
		}
	}

	static final class CreateWithinBox extends PFunction.Initializer {
		final Vec min, max;
		final int cp;
		final RangeSampler sampler;

		CreateWithinBox(Pd p) {
			super(p);
			min = p.vecInput("m_vecMin", Vec.literal(new Vector3f()));
			max = p.vecInput("m_vecMax", Vec.literal(new Vector3f()));
			cp = p.i("m_nControlPointNumber", 0);
			sampler = new RangeSampler(p);
		}

		@Override
		long writes() {
			return mask(Particle.POSITION) | mask(Particle.POSITION_PREVIOUS);
		}

		@Override
		void init(Particle q, PCollection c, PState s) {
			q.position.set(sampler.between(q, s, min.next(q, s), max.next(q, s)).add(s.cp(cp).position));
		}
	}

	static final class RandomSequence extends PFunction.Initializer {
		int seqMin, seqMax, current, cursor;
		final int initialCursor;
		final boolean shuffle, linear;
		final int[] wSeq, list;
		final float[] wWeight;
		float total;

		RandomSequence(Pd p) {
			super(p);
			seqMin = p.i("m_nSequenceMin", 0);
			seqMax = p.i("m_nSequenceMax", 0);
			shuffle = p.b("m_bShuffle", false);
			linear = p.b("m_bLinear", false);
			List<Map<String, Object>> entries = p.array("m_WeightedList");
			wSeq = new int[entries.size()];
			wWeight = new float[entries.size()];
			if (!entries.isEmpty()) {
				seqMin = Integer.MAX_VALUE;
				seqMax = 0;
				for (int i = 0; i < wSeq.length; i++) {
					Pd e = p.nested(entries.get(i));
					wSeq[i] = e.i("m_nSequence", 0);
					wWeight[i] = e.f("m_flRelativeWeight", 1);
					seqMin = Math.min(seqMin, wSeq[i]);
					seqMax = Math.max(seqMax, wSeq[i]);
					if (wWeight[i] > 0) total += wWeight[i];
				}
				initialCursor = shuffle || linear ? seqMin : 0;
				list = new int[0];
			} else {
				initialCursor = 0;
				if (shuffle || linear) {
					list = new int[Math.max(seqMax - seqMin + 1, 1)];
					for (int i = 0; i < list.length; i++) list[i] = seqMin + i;
				} else list = new int[0];
			}
			cursor = initialCursor;
			current = list.length;
		}

		@Override
		void reset() {
			current = list.length;
			cursor = initialCursor;
		}

		@Override
		long writes() {
			return mask(Particle.SEQUENCE_NUMBER);
		}

		@Override
		void init(Particle q, PCollection c, PState s) {
			if (wSeq.length > 0) {
				int seq = 0;
				if (total > 0) {
					float pick = linear ? cursor : total * s.random.next();
					for (int i = 0; i < wSeq.length; i++) {
						if (wWeight[i] <= 0) continue;
						pick -= wWeight[i];
						seq = wSeq[i];
						if (pick < 0) break;
					}
				}
				if (linear && ++cursor >= Math.ceil(total)) cursor = 0;
				q.sequence = seq;
			} else if (shuffle || linear) {
				if (current >= list.length) {
					if (shuffle)
						for (int i = list.length - 1; i > 0; i--) {
							int j = Math.min((int) (s.random.next() * (i + 1)), i);
							int t = list[i];
							list[i] = list[j];
							list[j] = t;
						}
					current = 0;
				}
				q.sequence = list[current++];
			} else {
				float r = s.random.next();
				q.sequence = seqMax > seqMin ? Math.min(seqMin + (int) (r * (seqMax - seqMin + 1)), seqMax) : seqMin;
			}
		}
	}

	static final class RandomColor extends PFunction.Initializer {
		final Vector3f min, max;
		final int field;

		RandomColor(Pd p) {
			super(p);
			min = p.color24("m_ColorMin", new Vector3f(1));
			max = p.color24("m_ColorMax", new Vector3f(1));
			field = p.field("m_nFieldOutput", Particle.COLOR);
			// m_flTintPerc (scene light tint) needs scene lighting; CS2 weapon effects use 0
		}

		@Override
		long writes() {
			return mask(field);
		}

		@Override
		void init(Particle q, PCollection c, PState s) {
			float r = s.random.next();
			q.vector(field, new Vector3f(max).sub(min).mul(r).add(min));
		}
	}

	static final class VelocityRandom extends PFunction.Initializer {
		final Vec vMin, vMax;
		final Num sMin, sMax;
		final int cp;
		final boolean ignoreDt;
		final RangeSampler sampler;

		VelocityRandom(Pd p) {
			super(p);
			vMin = p.vecInput("m_LocalCoordinateSystemSpeedMin", Vec.literal(new Vector3f()));
			vMax = p.vecInput("m_LocalCoordinateSystemSpeedMax", Vec.literal(new Vector3f()));
			sMin = p.num("m_fSpeedMin", new Num.Literal(0));
			sMax = p.num("m_fSpeedMax", new Num.Literal(0));
			cp = p.i("m_nControlPointNumber", 0);
			ignoreDt = p.b("m_bIgnoreDT", false);
			sampler = new RangeSampler(p);
		}

		@Override
		long writes() {
			return mask(Particle.POSITION_PREVIOUS);
		}

		@Override
		void init(Particle q, PCollection c, PState s) {
			Vector3f a = vMin.next(q, s), b = vMax.next(q, s), v = new Vector3f();
			if (a.lengthSquared() > 0 || b.lengthSquared() > 0) v = transformDirection(s, cp, sampler.between(q, s, a, b));
			float mn = sMin.next(q, s), mx = sMax.next(q, s);
			if (mn != 0 || mx != 0) v.add(s.random.nextBetweenPerComponent(new Vector3f(mn), new Vector3f(mx)));
			if (ignoreDt && s.system != null && s.system.frameTime() > 0) v.div(s.system.frameTime());
			q.velocity.add(v);
		}
	}

	static final class PositionOffset extends PFunction.Initializer {
		final Vec min, max;
		final Xform transform;
		final boolean local, proportional;
		final RangeSampler sampler;

		PositionOffset(Pd p) {
			super(p);
			min = p.vecInput("m_OffsetMin", Vec.literal(new Vector3f()));
			max = p.vecInput("m_OffsetMax", Vec.literal(new Vector3f()));
			transform = p.transform("m_TransformInput", Xform.CP0);
			local = p.b("m_bLocalCoords", false);
			proportional = p.b("m_bProportional", false);
			sampler = new RangeSampler(p);
		}

		@Override
		long writes() {
			return mask(Particle.POSITION) | mask(Particle.POSITION_PREVIOUS);
		}

		@Override
		void init(Particle q, PCollection c, PState s) {
			Vector3f o = sampler.between(q, s, min.next(q, s), max.next(q, s));
			if (proportional) o.mul(q.radius);
			if (local) o = transform.matrix(s).transformDirection(o);
			q.position.add(o);
		}
	}

	static final class GlobalScale extends PFunction.Initializer {
		final float scale;
		final int scaleCp, cp;
		final boolean radius, position, velocity;

		GlobalScale(Pd p) {
			super(p);
			scale = p.f("m_flScale", 1);
			scaleCp = p.i("m_nScaleControlPointNumber", -1);
			cp = p.i("m_nControlPointNumber", 0);
			radius = p.b("m_bScaleRadius", true);
			position = p.b("m_bScalePosition", true);
			velocity = p.b("m_bScaleVelocity", true);
		}

		@Override
		long writes() {
			return mask(Particle.POSITION) | mask(Particle.POSITION_PREVIOUS) | mask(Particle.RADIUS) | mask(Particle.HITBOX_OFFSET);
		}

		@Override
		void init(Particle q, PCollection c, PState s) {
			float f = scale;
			if (scaleCp >= 0) {
				float k = s.cp(scaleCp).position.x;
				if (k == 0) return;
				f *= k;
			}
			if (f == 1) return;
			if (radius) q.radius *= f;
			if (position) {
				Vector3f o = s.cp(cp).position;
				q.position.sub(o).mul(f).add(o);
			}
			if (velocity) q.velocity.mul(f);
		}
	}

	static final class RemapScalarToVector extends PFunction.Initializer {
		final int in, out, cp, method;
		final float inMin, inMax, start, end, bias;
		final Vector3f outMin, outMax;
		final boolean local;

		RemapScalarToVector(Pd p) {
			super(p);
			in = p.field("m_nFieldInput", Particle.CREATION_TIME);
			out = p.field("m_nFieldOutput", Particle.POSITION);
			inMin = p.f("m_flInputMin", 0);
			inMax = p.f("m_flInputMax", 1);
			outMin = p.vec("m_vecOutputMin", new Vector3f());
			outMax = p.vec("m_vecOutputMax", new Vector3f(1));
			start = p.f("m_flStartTime", -1);
			end = p.f("m_flEndTime", -1);
			method = Particle.setMethod(p.str("m_nSetMethod", null));
			cp = p.i("m_nControlPointNumber", 0);
			local = p.b("m_bLocalCoords", true);
			bias = p.f("m_flRemapBias", 0.5f);
		}

		@Override
		long writes() {
			return mask(out) | mask(Particle.POSITION_PREVIOUS);
		}

		@Override
		void init(Particle q, PCollection c, PState s) {
			if (q.vectorRef(out) == null) return;
			if (start >= 0 && end >= 0 && (q.creationTime < start || q.creationTime > end)) return;
			float t = PMath.saturate(PMath.remap(q.scalar(in), inMin, inMax));
			if (bias != 0.5f && bias > 0) t = (float) Math.pow(t, Math.log(bias) / Math.log(0.5));
			Vector3f v = new Vector3f(outMin).lerp(outMax, t);
			if (local) {
				if (out == Particle.POSITION || out == Particle.POSITION_PREVIOUS || out == Particle.HITBOX_OFFSET) v = transformPosition(s, cp, v);
				else if (out == Particle.NORMAL || out == Particle.SCRATCH_VECTOR || out == Particle.SCRATCH_VECTOR2) v = transformDirection(s, cp, v);
			}
			q.vector(out, q.modifyVectorAtSpawn(c, out, v, method));
		}
	}

	static final class InheritVelocity extends PFunction.Initializer {
		final int cp;
		final float scale;

		InheritVelocity(Pd p) {
			super(p);
			cp = p.i("m_nControlPointNumber", 0);
			scale = p.f("m_flVelocityScale", 1);
		}

		@Override
		long writes() {
			return mask(Particle.POSITION_PREVIOUS);
		}

		@Override
		void init(Particle q, PCollection c, PState s) {
			ControlPoint k = s.cp(cp);
			if (new Vector3f(k.position).sub(k.positionPrevious).length() >= 100) return;
			q.velocity.add(k.velocity().mul(scale));
		}
	}

	static final class RingWave extends PFunction.Initializer {
		final boolean even, xyOnly;
		final Num radius, thickness, perOrbit, speedMin, speedMax;
		final Xform transform;
		int orbitCount;

		RingWave(Pd p) {
			super(p);
			even = p.b("m_bEvenDistribution", false);
			perOrbit = p.num("m_flParticlesPerOrbit", new Num.Literal(-1));
			radius = p.num("m_flInitialRadius", new Num.Literal(0));
			thickness = p.num("m_flThickness", new Num.Literal(0));
			speedMin = p.num("m_flInitialSpeedMin", new Num.Literal(0));
			speedMax = p.num("m_flInitialSpeedMax", new Num.Literal(0));
			transform = p.transform("m_TransformInput", Xform.CP0);
			xyOnly = p.b("m_bXYVelocityOnly", false);
		}

		@Override
		void reset() {
			orbitCount = 0;
		}

		@Override
		long writes() {
			return mask(Particle.POSITION) | mask(Particle.POSITION_PREVIOUS);
		}

		@Override
		void init(Particle q, PCollection c, PState s) {
			float th = thickness.next(q, s), po = perOrbit.next(q, s);
			Vector3f off = s.random.nextInUnitBall(null).mul(th);
			float r = radius.next(q, s);
			float angle;
			if (even) {
				float per = po == -1 ? c.count : po;
				angle = ++orbitCount * (float) (Math.PI * 2 / Math.max(1, per));
			} else angle = s.random.nextBetween(0, (float) (Math.PI * 2));
			Matrix4f m = transform.matrix(s);
			Vector3f local = new Vector3f((float) Math.cos(angle), (float) Math.sin(angle), 0).mul(r).add(off);
			q.position.set(m.transformPosition(local));
			Vector3f out = PMath.safeNormalize(new Vector3f(q.position).sub(m.getTranslation(new Vector3f())));
			if (xyOnly) out.z = 0;
			q.velocity.add(out.mul(s.random.nextBetween(speedMin.next(q, s), speedMax.next(q, s))));
		}
	}

	static final class MoveBetweenPoints extends PFunction.Initializer {
		final Num speedMin, speedMax, endSpread, startOffset, endOffset;
		final int endCp;

		MoveBetweenPoints(Pd p) {
			super(p);
			speedMin = p.num("m_flSpeedMin", new Num.Literal(1));
			speedMax = p.num("m_flSpeedMax", new Num.Literal(1));
			endSpread = p.num("m_flEndSpread", new Num.Literal(0));
			startOffset = p.num("m_flStartOffset", new Num.Literal(0));
			endOffset = p.num("m_flEndOffset", new Num.Literal(0));
			endCp = p.i("m_nEndControlPointNumber", 1);
		}

		@Override
		long writes() {
			return mask(Particle.POSITION) | mask(Particle.POSITION_PREVIOUS) | mask(Particle.LIFE_DURATION);
		}

		@Override
		void init(Particle q, PCollection c, PState s) {
			Vector3f end = new Vector3f(s.cp(endCp).position);
			float spread = endSpread.next(q, s);
			if (spread > 0) end.add(s.random.nextInUnitBall(null).mul(spread));
			Vector3f delta = new Vector3f(end).sub(q.position);
			float len = delta.length();
			float speed = s.random.nextBetween(speedMin.next(q, s), speedMax.next(q, s));
			if (len <= 0 || speed <= 0) return;
			Vector3f dir = delta.div(len);
			float off = Math.min(len, startOffset.next(q, s));
			q.position.add(new Vector3f(dir).mul(off));
			len = Math.max(0, len - off - endOffset.next(q, s));
			q.lifetime = len / speed;
			q.velocity.set(dir).mul(speed);
		}
	}

	static final class DistanceToCPInit extends PFunction.Initializer {
		final int field, cp, method;
		final Num inMin, inMax, outMin, outMax;
		final boolean activeRange, hasBias;
		final Vector3f scale;
		final float bias;

		DistanceToCPInit(Pd p) {
			super(p);
			field = p.field("m_nFieldOutput", Particle.RADIUS);
			inMin = p.num("m_flInputMin", new Num.Literal(0));
			inMax = p.num("m_flInputMax", new Num.Literal(128));
			outMin = p.num("m_flOutputMin", new Num.Literal(0));
			outMax = p.num("m_flOutputMax", new Num.Literal(1));
			cp = Math.max(0, Math.min(63, p.i("m_nStartCP", 0)));
			method = Particle.setMethod(p.str("m_nSetMethod", null));
			activeRange = p.b("m_bActiveRange", false);
			scale = p.vec("m_vecDistanceScale", new Vector3f(1));
			bias = p.f("m_flRemapBias", 0.5f);
			hasBias = bias != 0.5f;
		}

		@Override
		long writes() {
			return mask(field);
		}

		@Override
		void init(Particle q, PCollection c, PState s) {
			float a = inMin.next(q, s), b = inMax.next(q, s), oa = outMin.next(q, s), ob = outMax.next(q, s);
			if (field == Particle.ALPHA || field == Particle.ALPHA_ALTERNATE) {
				oa = PMath.saturate(oa);
				ob = PMath.saturate(ob);
			}
			float d = new Vector3f(s.cp(cp).position).sub(q.position).mul(scale).length();
			if (activeRange && (d < a || d > b)) return;
			float v = oa + (ob - oa) * PMath.saturate(PMath.remap(d, a, b));
			if (hasBias) v = PMath.bias(PMath.saturate(v), bias);
			q.scalar(field, q.modifyScalarAtSpawn(c, field, v, method));
		}
	}
}
