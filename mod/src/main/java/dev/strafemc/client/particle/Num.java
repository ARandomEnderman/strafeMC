package dev.strafemc.client.particle;

import java.util.List;
import java.util.Map;

/**
 * Source 2 float input (CParticleFloatInput): literal, random, particle/collection age, particle attributes, control
 * point values, each run through its attribute mapping (remap / curve / ...). Port of VRF INumberProvider (MIT).
 */
abstract class Num {
	abstract float next(Particle p, PState s);

	float next(PState s) {
		return next(PState.DEFAULT_PARTICLE, s);
	}

	static final class Literal extends Num {
		final float v;

		Literal(float v) {
			this.v = v;
		}

		@Override
		float next(Particle p, PState s) {
			return v;
		}
	}

	static Num parse(Pd d, String key) {
		Object o = d.raw(key);
		if (o instanceof Number n) return new Literal(n.floatValue());
		if (!(o instanceof Map<?, ?>)) return null;
		Pd p = d.nested(o);
		String type = p.str("m_nType", null);
		if (type == null) return p.has("m_flLiteralValue") ? new Literal(p.f("m_flLiteralValue", 0)) : null;
		return switch (type) {
			case "PF_TYPE_INVALID" -> null;
			case "PF_TYPE_LITERAL" -> new Literal(p.f("m_flLiteralValue", 0));
			case "PF_TYPE_RANDOM_UNIFORM" -> new Random(p, false);
			case "PF_TYPE_RANDOM_BIASED" -> new Random(p, true);
			case "PF_TYPE_COLLECTION_AGE" -> mapped(p, (q, s) -> s.age);
			case "PF_TYPE_ENDCAP_AGE" -> mapped(p, (q, s) -> s.endCapAge());
			case "PF_TYPE_CONTROL_POINT_COMPONENT" -> {
				int cp = p.i("m_nControlPoint", 0), c = p.i("m_nVectorComponent", 0);
				yield mapped(p, (q, s) -> s.cp(cp).position.get(Math.max(0, Math.min(2, c))));
			}
			case "PF_TYPE_PARTICLE_DETAIL_LEVEL" -> new Literal(p.f("m_flLOD3", p.f("m_flLOD2", p.f("m_flLOD1", p.f("m_flLOD0", 0))))); // ultra
			case "PF_TYPE_PARTICLE_AGE" -> mapped(p, (q, s) -> q.age);
			case "PF_TYPE_PARTICLE_AGE_NORMALIZED" -> mapped(p, (q, s) -> q.normalizedAge());
			case "PF_TYPE_PARTICLE_FLOAT", "PF_TYPE_PARTICLE_INITIAL_FLOAT" -> {
				int f = p.field("m_nScalarAttribute", 0);
				yield mapped(p, (q, s) -> q.scalar(f));
			}
			case "PF_TYPE_PARTICLE_VECTOR_COMPONENT", "PF_TYPE_PARTICLE_INITIAL_VECTOR_COMPONENT" -> {
				int f = p.field("m_nVectorAttribute", 0), c = p.i("m_nVectorComponent", 0);
				yield mapped(p, (q, s) -> q.vectorComponent(f, c));
			}
			case "PF_TYPE_RENDERER_CAMERA_DISTANCE" -> {
				int cp = p.i("m_nControlPoint", 0);
				yield mapped(p, (q, s) -> s.cameraPosition.distance(s.cp(cp).position));
			}
			case "PF_TYPE_PARTICLE_SPEED" -> mapped(p, (q, s) -> q.speed());
			case "PF_TYPE_PARTICLE_NUMBER" -> mapped(p, (q, s) -> q.uniqueId);
			case "PF_TYPE_PARTICLE_NUMBER_NORMALIZED" -> mapped(p, (q, s) -> {
				long div = s.behaviorVersion() >= 12 ? Math.max(s.particleCount - 1, 1) : Math.max(s.particleCount, 1);
				return q.index / (float) div;
			});
			case "PF_TYPE_CONTROL_POINT_SPEED" -> {
				int cp = p.i("m_nControlPoint", 0);
				yield mapped(p, (q, s) -> s.cp(cp).velocity().length());
			}
			case "PF_TYPE_CONTROL_POINT_CHANGE_AGE" -> {
				int cp = p.i("m_nControlPoint", 0);
				yield mapped(p, (q, s) -> s.cp(cp).changeAge(s));
			}
			case "PF_TYPE_CONTROL_POINT_IS_SET" -> {
				int cp = p.i("m_nControlPoint", 0);
				yield mapped(p, (q, s) -> cp != -1 && s.cp(cp).changeTime != -1 ? 1 : 0);
			}
			case "PF_TYPE_CONTROL_POINT_DISTANCE" -> {
				int cp = p.i("m_nControlPoint", 0);
				yield mapped(p, (q, s) -> q.position.distance(s.cp(cp).position));
			}
			default -> new Literal(p.f("m_flLiteralValue", 0)); // unsupported input: its literal value (as VRF)
		};
	}

	interface Src {
		float get(Particle p, PState s);
	}

	private static Num mapped(Pd p, Src src) {
		Mapping m = new Mapping(p);
		return new Num() {
			@Override
			float next(Particle q, PState s) {
				return m.apply(src.get(q, s));
			}
		};
	}

	static final class Random extends Num {
		private static final int SIGN_FLIP_OFFSET = 37;
		final float min, max, biasParam;
		final boolean varying, biased, signFlip;
		final String biasType;
		final int offset;

		Random(Pd p, boolean biased) {
			min = p.f("m_flRandomMin", 0);
			max = p.f("m_flRandomMax", 1);
			signFlip = p.b("m_bHasRandomSignFlip", false);
			varying = "PF_RANDOM_MODE_VARYING".equals(p.str("m_nRandomMode", "PF_RANDOM_MODE_CONSTANT"));
			this.biased = biased;
			biasParam = biased ? p.f("m_flBiasParameter", 0) : 0;
			biasType = p.str("m_nBiasType", "PF_BIAS_TYPE_STANDARD");
			offset = varying ? 0 : p.nextOrdinal();
		}

		@Override
		float next(Particle q, PState s) {
			float r = varying ? s.random.next() : s.random.forParticle(q.id, offset);
			if (biased) r = PMath.biasFromParameter(r, biasParam, biasType);
			float v = min + (max - min) * r;
			if (signFlip) {
				float sign = varying ? s.random.next() : s.random.forParticle(q.id, offset + SIGN_FLIP_OFFSET);
				if (sign < 0.5f) v = -v;
			}
			return v;
		}
	}

	/** AttributeMapping: how an input value becomes the output (direct, mult, remap, curve, ...). */
	static final class Mapping {
		final String type;
		final boolean looped;
		float mult, in0, in1, out0, out1, notchMin, notchMax, notchOut, notchIn, biasParam, compare;
		String biasType = "PF_BIAS_TYPE_STANDARD", roundType = "PF_ROUND_TYPE_NEAREST";
		Curve curve;

		Mapping(Pd p) {
			type = p.str("m_nMapType", "PF_MAP_TYPE_DIRECT");
			looped = "PF_INPUT_MODE_LOOPED".equals(p.str("m_nInputMode", "PF_INPUT_MODE_CLAMPED"));
			switch (type) {
				case "PF_MAP_TYPE_MULT" -> mult = p.f("m_flMultFactor", 0);
				case "PF_MAP_TYPE_REMAP", "PF_MAP_TYPE_REMAP_BIASED" -> {
					in0 = p.f("m_flInput0", 0);
					in1 = p.f("m_flInput1", 0);
					out0 = p.f("m_flOutput0", 0);
					out1 = p.f("m_flOutput1", 0);
					if (type.equals("PF_MAP_TYPE_REMAP") && in0 > in1) {
						float t = in0;
						in0 = in1;
						in1 = t;
						t = out0;
						out0 = out1;
						out1 = t;
					}
					biasType = p.str("m_nBiasType", biasType);
					biasParam = p.f("m_flBiasParameter", 0);
				}
				case "PF_MAP_TYPE_CURVE" -> curve = new Curve(p.nested(p.raw("m_Curve")), looped);
				case "PF_MAP_TYPE_NOTCHED" -> {
					notchMin = p.f("m_flNotchedRangeMin", 0);
					notchMax = p.f("m_flNotchedRangeMax", 0);
					notchOut = p.f("m_flNotchedOutputOutside", 0);
					notchIn = p.f("m_flNotchedOutputInside", 0);
				}
				case "PF_MAP_TYPE_ROUND" -> roundType = p.str("m_nRoundType", roundType);
				case "PF_MAP_TYPE_MIN", "PF_MAP_TYPE_MAX", "PF_MAP_TYPE_MOD" -> compare = p.f("m_flCompareValue", 0);
				default -> {
				}
			}
		}

		float apply(float v) {
			switch (type) {
				case "PF_MAP_TYPE_MULT":
					return v * mult;
				case "PF_MAP_TYPE_REMAP": {
					float x = wrap(v);
					if (in0 == in1) return x >= in1 ? out1 : out0;
					return clampOut(PMath.remapRange(x, in0, in1, out0, out1));
				}
				case "PF_MAP_TYPE_REMAP_BIASED": {
					float t = Math.min(PMath.remap(wrap(v), in0, in1), 1f);
					return clampOut(PMath.lerp(out0, out1, PMath.biasFromParameter(t, biasParam, biasType)));
				}
				case "PF_MAP_TYPE_CURVE":
					return curve.eval(v);
				case "PF_MAP_TYPE_NOTCHED":
					return v >= notchMin && v <= notchMax ? notchIn : notchOut;
				case "PF_MAP_TYPE_ROUND":
					return switch (roundType) {
						case "PF_ROUND_TYPE_FLOOR" -> (float) Math.floor(v);
						case "PF_ROUND_TYPE_CEIL" -> (float) Math.ceil(v);
						default -> Math.round(v);
					};
				case "PF_MAP_TYPE_MIN":
					return Math.min(v, compare);
				case "PF_MAP_TYPE_MAX":
					return Math.max(v, compare);
				case "PF_MAP_TYPE_MOD":
					return compare == 0 ? v : v % compare;
				default:
					return v;
			}
		}

		private float wrap(float v) {
			if (!looped || in1 == 0) return v;
			float t = v / in1;
			return in1 * (t - (float) Math.floor(t));
		}

		private float clampOut(float v) {
			return Math.min(Math.max(v, Math.min(out0, out1)), Math.max(out0, out1));
		}
	}

	/** Piecewise curve (VRF evaluates segments linearly). */
	static final class Curve {
		final float[] xs, ys;
		final float dMinX, dMaxX, dMinY, dMaxY;
		final boolean looped;

		Curve(Pd c, boolean looped) {
			this.looped = looped;
			float[] mn = c.floats("m_vDomainMins"), mx = c.floats("m_vDomainMaxs");
			dMinX = mn.length > 0 ? mn[0] : 0;
			dMinY = mn.length > 1 ? mn[1] : 0;
			dMaxX = mx.length > 0 ? mx[0] : 0;
			dMaxY = mx.length > 1 ? mx[1] : 0;
			List<Map<String, Object>> sp = c.array("m_spline");
			int n = Math.min(sp.size(), c.array("m_tangents").size());
			xs = new float[n];
			ys = new float[n];
			for (int i = 0; i < n; i++) {
				Pd q = c.nested(sp.get(i));
				xs[i] = q.f("x", 0);
				ys[i] = q.f("y", 0);
			}
		}

		float eval(float v) {
			if (xs.length < 2) return 0;
			v = looped ? PMath.wrap(v, dMinX, dMaxX) : Math.min(Math.max(v, dMinX), dMaxX);
			if (v <= xs[0]) return clampY(ys[0]);
			if (v >= xs[xs.length - 1]) return clampY(ys[ys.length - 1]);
			for (int i = 0; i < xs.length - 1; i++)
				if (v >= xs[i] && v <= xs[i + 1]) return clampY(ys[i] + (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]) * (v - xs[i]));
			return clampY(ys[ys.length - 1]);
		}

		private float clampY(float y) {
			return Math.min(Math.max(y, dMinY), dMaxY);
		}
	}
}
