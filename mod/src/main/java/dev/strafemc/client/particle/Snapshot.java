package dev.strafemc.client.particle;

import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * A control point snapshot: a list of points with per-point attributes (position, normal, radius, alpha, scratch...),
 * keyed by ParticleField id. Games build them at runtime (the inferno's fire outline and fill points) and attach them to
 * a control point; C_INIT_InitFromCPSnapshot / C_OP_SetFromCPSnapshot / C_OP_MaintainEmitter read them.
 */
public final class Snapshot {
	public final int count;
	private final Map<Integer, Vector3f[]> vectors = new HashMap<>();
	private final Map<Integer, float[]> scalars = new HashMap<>();

	public Snapshot(int count) {
		this.count = count;
	}

	public Vector3f[] vectors(int field) {
		return vectors.computeIfAbsent(field, k -> {
			Vector3f[] v = new Vector3f[count];
			for (int i = 0; i < count; i++) v[i] = new Vector3f();
			return v;
		});
	}

	public float[] scalars(int field) {
		return scalars.computeIfAbsent(field, k -> new float[count]);
	}

	boolean hasVector(int field) {
		return vectors.containsKey(field);
	}

	/** Attribute `field` of point i as a vector (scalars spread over x, y, z); zero when the snapshot lacks it. */
	Vector3f vector(int field, int i) {
		Vector3f[] v = vectors.get(field);
		if (v != null) return new Vector3f(v[i]);
		float[] f = scalars.get(field);
		return f != null ? new Vector3f(f[i]) : new Vector3f();
	}

	/** Attribute `field` of point i as a scalar (a vector's x); zero when the snapshot lacks it. */
	float scalar(int field, int i) {
		float[] f = scalars.get(field);
		if (f != null) return f[i];
		Vector3f[] v = vectors.get(field);
		return v != null ? v[i].x : 0;
	}
}
