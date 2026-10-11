package dev.strafemc.client.particle;

import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/**
 * Reader over one KV3 object of a .vpcf definition with Source 2 defaults (ParticleDefinitionParser in VRF, MIT):
 * literals, enums, particle fields and the float/vector/transform input blocks.
 */
final class Pd {
	final Map<String, Object> data;
	private final int[] ordinal; // shared per system: constant-random inputs each get their own slot
	final int behaviorVersion;
	/** .vpcf file format version (vpcfNN); VRF's upgrade steps that matter are applied where the fields are read. */
	final int format;

	Pd(Map<String, Object> data, int[] ordinal, int behaviorVersion, int format) {
		this.data = data == null ? Map.of() : data;
		this.ordinal = ordinal;
		this.behaviorVersion = behaviorVersion;
		this.format = format;
	}

	Pd(Map<String, Object> data, int behaviorVersion) {
		this(data, new int[1], behaviorVersion, 0);
	}

	Pd(Map<String, Object> data, int behaviorVersion, int format) {
		this(data, new int[1], behaviorVersion, format);
	}

	@SuppressWarnings("unchecked")
	Pd nested(Object o) {
		return new Pd(o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(), ordinal, behaviorVersion, format);
	}

	int nextOrdinal() {
		return ordinal[0]++;
	}

	boolean has(String k) {
		return data.containsKey(k) && data.get(k) != null;
	}

	Object raw(String k) {
		return data.get(k);
	}

	String str(String k, String def) {
		Object o = data.get(k);
		return o == null ? def : o.toString();
	}

	float f(String k, float def) {
		Object o = data.get(k);
		if (o instanceof Number n) return n.floatValue();
		if (o instanceof Map<?, ?>) { // authored as a float input: use its value for this (literal) read
			Num n = num(k, null);
			return n == null ? def : n.next(PState.DEFAULT);
		}
		if (o instanceof String s) {
			try {
				return Float.parseFloat(s);
			} catch (NumberFormatException e) {
				return def;
			}
		}
		return def;
	}

	int i(String k, int def) {
		Object o = data.get(k);
		if (o instanceof Number n) return n.intValue();
		if (o instanceof Map<?, ?>) return (int) f(k, def);
		return def;
	}

	boolean b(String k, boolean def) {
		Object o = data.get(k);
		if (o instanceof Boolean bo) return bo;
		if (o instanceof Number n) return n.intValue() != 0;
		return def;
	}

	/** ParticleField ids are stored as ints. */
	int field(String k, int def) {
		return i(k, def);
	}

	float[] floats(String k) {
		Object o = data.get(k);
		if (!(o instanceof List<?> l)) return new float[0];
		float[] r = new float[l.size()];
		for (int j = 0; j < r.length; j++) r[j] = l.get(j) instanceof Number n ? n.floatValue() : 0;
		return r;
	}

	Vector3f vec(String k, Vector3f def) {
		Object o = data.get(k);
		if (o instanceof String s) { // "0.7 0.5 0.25"
			String[] p = s.trim().split("\\s+");
			if (p.length >= 3) return new Vector3f(Float.parseFloat(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2]));
		}
		if (o instanceof List<?>) {
			float[] f = floats(k);
			if (f.length >= 3) return new Vector3f(f[0], f[1], f[2]);
		}
		return def == null ? null : new Vector3f(def);
	}

	/** 0-255 color array to 0-1. */
	Vector3f color24(String k, Vector3f def) {
		if (!has(k)) return def;
		float[] f = floats(k);
		return new Vector3f(f.length > 0 ? f[0] : 0, f.length > 1 ? f[1] : 0, f.length > 2 ? f[2] : 0).div(255f);
	}

	@SuppressWarnings("unchecked")
	List<Map<String, Object>> array(String k) {
		Object o = data.get(k);
		return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
	}

	Num num(String k, Num def) {
		if (!has(k)) return def;
		Num n = Num.parse(this, k);
		return n == null ? def : n;
	}

	Vec vecInput(String k, Vec def) {
		if (!has(k)) return def;
		return Vec.parse(this, k);
	}

	Xform transform(String k, Xform def) {
		if (!data.containsKey(k)) return def;
		return Xform.parse(this, k);
	}
}
