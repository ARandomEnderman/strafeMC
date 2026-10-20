package dev.strafemc.client.particle;

import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/** Source 2 vector input (CParticleVecInput). Port of VRF IVectorProvider (MIT). */
abstract class Vec {
	abstract Vector3f next(Particle p, PState s);

	Vector3f next(PState s) {
		return next(PState.DEFAULT_PARTICLE, s);
	}

	static Vec literal(Vector3f v) {
		Vector3f c = new Vector3f(v);
		return new Vec() {
			@Override
			Vector3f next(Particle p, PState s) {
				return new Vector3f(c);
			}
		};
	}

	static Vec parse(Pd d, String key) {
		Object o = d.raw(key);
		if (o instanceof Map<?, ?> m && m.containsKey("m_nType")) {
			Pd p = d.nested(o);
			String type = p.str("m_nType", "");
			switch (type) {
				case "PVEC_TYPE_LITERAL":
					return literal(p.vec("m_vLiteralValue", new Vector3f()));
				case "PVEC_TYPE_LITERAL_COLOR":
					return literal(p.vec("m_LiteralColor", new Vector3f()).div(255f));
				case "PVEC_TYPE_PARTICLE_VECTOR":
				case "PVEC_TYPE_PARTICLE_INITIAL_VECTOR": {
					// the particle's own vector attribute, scaled (m_nControlPoint, written into every input block, isn't used)
					int field = p.field("m_nVectorAttribute", 0);
					Vector3f scale = p.vec("m_vVectorAttributeScale", new Vector3f(1));
					return new Vec() {
						@Override
						Vector3f next(Particle q, PState s) {
							return new Vector3f(scale).mul(q.vector(field));
						}
					};
				}
				case "PVEC_TYPE_PARTICLE_VELOCITY":
					return new Vec() {
						@Override
						Vector3f next(Particle q, PState s) {
							return new Vector3f(q.velocity);
						}
					};
				case "PVEC_TYPE_CP_VALUE": {
					int cp = p.i("m_nControlPoint", 0);
					Vector3f sc = p.vec("m_vCPValueScale", new Vector3f(1));
					return new Vec() {
						@Override
						Vector3f next(Particle q, PState s) {
							return new Vector3f(s.cp(cp).position).mul(sc);
						}
					};
				}
				case "PVEC_TYPE_CP_DELTA": {
					int cp = p.i("m_nControlPoint", 0), dcp = p.i("m_nDeltaControlPoint", 0);
					return new Vec() {
						@Override
						Vector3f next(Particle q, PState s) {
							return new Vector3f(s.cp(cp).position).sub(s.cp(dcp).position);
						}
					};
				}
				case "PVEC_TYPE_CP_RELATIVE_POSITION": {
					int cp = p.i("m_nControlPoint", 0);
					Vector3f rel = p.vec("m_vCPRelativePosition", new Vector3f());
					return new Vec() {
						@Override
						Vector3f next(Particle q, PState s) {
							return new Vector3f(s.cp(cp).position).add(rel);
						}
					};
				}
				case "PVEC_TYPE_CP_RELATIVE_DIR": {
					int cp = p.i("m_nControlPoint", 0);
					Vector3f rel = p.vec("m_vCPRelativeDir", new Vector3f());
					return new Vec() {
						@Override
						Vector3f next(Particle q, PState s) {
							Vector3f o2 = s.cp(cp).orientation;
							if (o2.lengthSquared() == 0 || rel.lengthSquared() == 0) return new Vector3f();
							return new Vector3f(o2).sub(rel);
						}
					};
				}
				case "PVEC_TYPE_FLOAT_COMPONENTS": {
					Num x = p.num("m_FloatComponentX", new Num.Literal(0)), y = p.num("m_FloatComponentY", new Num.Literal(0)),
							z = p.num("m_FloatComponentZ", new Num.Literal(0));
					return new Vec() {
						@Override
						Vector3f next(Particle q, PState s) {
							return new Vector3f(x.next(q, s), y.next(q, s), z.next(q, s));
						}
					};
				}
				case "PVEC_TYPE_FLOAT_INTERP_CLAMPED":
				case "PVEC_TYPE_FLOAT_INTERP_OPEN": {
					boolean clamp = type.endsWith("CLAMPED");
					Num in = p.num("m_FloatInterp", new Num.Literal(0));
					float i0 = p.f("m_flInterpInput0", 0), i1 = p.f("m_flInterpInput1", 0);
					Vector3f o0 = p.vec("m_vInterpOutput0", new Vector3f()), o1 = p.vec("m_vInterpOutput1", new Vector3f());
					return new Vec() {
						@Override
						Vector3f next(Particle q, PState s) {
							float t = PMath.remap(in.next(q, s), i0, i1);
							if (clamp) t = PMath.saturate(t);
							return new Vector3f(o0).lerp(o1, t);
						}
					};
				}
				case "PVEC_TYPE_FLOAT_INTERP_GRADIENT": {
					Num in = p.num("m_FloatInterp", new Num.Literal(0));
					float i0 = p.f("m_flInterpInput0", 0), i1 = p.f("m_flInterpInput1", 0);
					List<Map<String, Object>> stops = p.nested(p.raw("m_Gradient")).array("m_Stops");
					float[] pos = new float[stops.size()];
					Vector3f[] col = new Vector3f[stops.size()];
					for (int i = 0; i < pos.length; i++) {
						Pd st = p.nested(stops.get(i));
						pos[i] = st.f("m_flPosition", 0);
						col[i] = st.color24("m_Color", new Vector3f());
					}
					return new Vec() {
						@Override
						Vector3f next(Particle q, PState s) {
							if (pos.length == 0) return new Vector3f();
							float g = PMath.remap(in.next(q, s), i0, i1);
							if (g <= pos[0]) return new Vector3f(col[0]);
							if (g >= pos[pos.length - 1]) return new Vector3f(col[pos.length - 1]);
							for (int i = 0; i < pos.length - 1; i++)
								if (g >= pos[i] && g <= pos[i + 1]) return new Vector3f(col[i]).lerp(col[i + 1], PMath.remap(g, pos[i], pos[i + 1]));
							return new Vector3f(col[pos.length - 1]);
						}
					};
				}
				case "PVEC_TYPE_RANDOM_UNIFORM":
				case "PVEC_TYPE_RANDOM_UNIFORM_OFFSET": {
					Vector3f mn = p.vec("m_vRandomMin", new Vector3f()), mx = p.vec("m_vRandomMax", new Vector3f());
					boolean offset = type.endsWith("OFFSET");
					int field = p.field("m_nVectorAttribute", 0);
					Vector3f scale = p.vec("m_vVectorAttributeScale", new Vector3f(1));
					return new Vec() {
						@Override
						Vector3f next(Particle q, PState s) {
							Vector3f r = mn.equals(mx) ? new Vector3f(mn) : s.random.nextBetweenPerComponent(mn, mx);
							return offset ? r.add(new Vector3f(scale).mul(q.vector(field))) : r;
						}
					};
				}
				default:
					return literal(p.vec("m_vLiteralValue", new Vector3f()));
			}
		}
		if (o instanceof List<?> || o instanceof String) return literal(d.vec(key, new Vector3f()));
		return literal(new Vector3f());
	}
}
