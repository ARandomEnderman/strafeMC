package dev.strafemc.client.viewmodel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * A CS2 viewmodel animation clip exported by VRF (glTF: the viewmodel skeleton + the weapon skeleton, TRS tracks per bone).
 * {@link #pose(float)} gives every bone's clip-space (model) matrix at a time.
 */
public final class VmClip {
	private record Track(float[] times, float[] values, int comps) {}

	public final String name;
	public final float duration;
	private final String[] names;
	private final int[] parent;
	private final float[][] restT, restR, restS;
	private final Track[] tT, tR, tS;
	private final Map<String, Integer> index = new HashMap<>();

	public VmClip(String name, Glb g) {
		this(name, g, 0);
	}

	/** The file's animation number anim (a file with several clips: CS2's third-person player clips). */
	public VmClip(String name, Glb g, int anim) {
		this.name = name;
		var nodes = g.arr("nodes");
		int n = nodes.size();
		names = new String[n];
		parent = new int[n];
		restT = new float[n][];
		restR = new float[n][];
		restS = new float[n][];
		tT = new Track[n];
		tR = new Track[n];
		tS = new Track[n];
		java.util.Arrays.fill(parent, -1);
		for (int i = 0; i < n; i++) {
			JsonObject o = nodes.get(i).getAsJsonObject();
			names[i] = o.has("name") ? o.get("name").getAsString() : "";
			index.putIfAbsent(names[i], i);
			restT[i] = vec(o, "translation", 0, 0, 0);
			restR[i] = vec(o, "rotation", 0, 0, 0, 1);
			restS[i] = vec(o, "scale", 1, 1, 1);
			if (o.has("children")) for (JsonElement c : o.getAsJsonArray("children")) parent[c.getAsInt()] = i;
		}
		float dur = 0;
		if (g.arr("animations").size() > anim) {
			JsonObject an = g.obj("animations", anim);
			var samplers = an.getAsJsonArray("samplers");
			for (JsonElement ce : an.getAsJsonArray("channels")) {
				JsonObject c = ce.getAsJsonObject(), t = c.getAsJsonObject("target");
				int node = t.get("node").getAsInt();
				JsonObject s = samplers.get(c.get("sampler").getAsInt()).getAsJsonObject();
				float[] times = g.floats(s.get("input").getAsInt());
				float[] vals = g.floats(s.get("output").getAsInt());
				for (float tm : times) dur = Math.max(dur, tm);
				switch (t.get("path").getAsString()) {
					case "translation" -> tT[node] = new Track(times, vals, 3);
					case "rotation" -> tR[node] = new Track(times, vals, 4);
					case "scale" -> tS[node] = new Track(times, vals, 3);
				}
			}
		}
		duration = dur;
	}

	private static float[] vec(JsonObject o, String k, float... def) {
		if (!o.has(k)) return def;
		var a = o.getAsJsonArray(k);
		float[] v = new float[a.size()];
		for (int i = 0; i < v.length; i++) v[i] = a.get(i).getAsFloat();
		return v;
	}

	public Integer node(String boneName) {
		return index.get(boneName);
	}

	/** Node i's parent node (-1: a root). */
	public int parent(int i) {
		return parent[i];
	}

	public int size() {
		return names.length;
	}

	/** Model-space matrix of every clip node at time t (seconds, clamped). */
	public Matrix4f[] pose(float t) {
		int n = names.length;
		Matrix4f[] world = new Matrix4f[n];
		for (int i = 0; i < n; i++) world[i] = null;
		for (int i = 0; i < n; i++) resolve(i, t, world);
		return world;
	}

	/**
	 * Every bone's local translation (3), rotation (4) and scale (3) by name at time t, crossfaded from a snapshot
	 * (w = this clip's weight; from null or w >= 1: this clip alone). A snapshot of what's on screen is the start of the
	 * next crossfade, so any chain of animations blends, however fast they follow each other.
	 */
	public Map<String, float[]> snapshot(float t, Map<String, float[]> from, float w) {
		Map<String, float[]> out = new HashMap<>();
		for (int i = 0; i < names.length; i++) out.put(names[i], local(i, t, from, w));
		if (from != null) // bones only the old pose has (another weapon's) keep their last place
			for (var e : from.entrySet()) out.putIfAbsent(e.getKey(), e.getValue());
		return out;
	}

	/** Crossfade (CS2 graph transitions) from a snapshot into this clip at t, w = this clip's weight (0..1). */
	public Matrix4f[] pose(float t, Map<String, float[]> from, float w) {
		if (from == null || w >= 1) return pose(t);
		int n = names.length;
		Matrix4f[] world = new Matrix4f[n];
		for (int i = 0; i < n; i++) resolveBlend(i, t, from, w, world);
		return world;
	}

	/** One bone's local T, R, S (10 floats) at t, blended from the snapshot's (rotations the short way). */
	private float[] local(int i, float t, Map<String, float[]> from, float w) {
		float[] T = sample(tT[i], t, restT[i]), R = sample(tR[i], t, restR[i]), S = sample(tS[i], t, restS[i]);
		float[] f = from == null || w >= 1 ? null : from.get(names[i]);
		if (f != null) {
			float dot = f[3] * R[0] + f[4] * R[1] + f[5] * R[2] + f[6] * R[3];
			float sign = dot < 0 ? -1 : 1;
			T = new float[] {f[0] + (T[0] - f[0]) * w, f[1] + (T[1] - f[1]) * w, f[2] + (T[2] - f[2]) * w};
			S = new float[] {f[7] + (S[0] - f[7]) * w, f[8] + (S[1] - f[8]) * w, f[9] + (S[2] - f[9]) * w};
			float[] r = new float[4];
			for (int k = 0; k < 4; k++) r[k] = f[3 + k] + (sign * R[k] - f[3 + k]) * w;
			float len = (float) Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2] + r[3] * r[3]);
			if (len > 1e-6f) for (int k = 0; k < 4; k++) r[k] /= len;
			R = r;
		}
		return new float[] {T[0], T[1], T[2], R[0], R[1], R[2], R[3], S[0], S[1], S[2]};
	}

	private Matrix4f resolveBlend(int i, float t, Map<String, float[]> from, float w, Matrix4f[] world) {
		if (world[i] != null) return world[i];
		float[] l = local(i, t, from, w);
		Matrix4f local = new Matrix4f().translationRotateScale(new Vector3f(l[0], l[1], l[2]), new Quaternionf(l[3], l[4], l[5], l[6]).normalize(),
				new Vector3f(l[7], l[8], l[9]));
		Matrix4f m = parent[i] >= 0 ? new Matrix4f(resolveBlend(parent[i], t, from, w, world)).mul(local) : local;
		world[i] = m;
		return m;
	}

	private Matrix4f resolve(int i, float t, Matrix4f[] world) {
		if (world[i] != null) return world[i];
		float[] T = sample(tT[i], t, restT[i]), R = sample(tR[i], t, restR[i]), S = sample(tS[i], t, restS[i]);
		Matrix4f local = new Matrix4f().translationRotateScale(new Vector3f(T[0], T[1], T[2]),
				new Quaternionf(R[0], R[1], R[2], R[3]).normalize(), new Vector3f(S[0], S[1], S[2]));
		Matrix4f m = parent[i] >= 0 ? new Matrix4f(resolve(parent[i], t, world)).mul(local) : local;
		world[i] = m;
		return m;
	}

	private static float[] sample(Track tr, float t, float[] rest) {
		if (tr == null) return rest;
		float[] ts = tr.times;
		int c = tr.comps;
		if (ts.length == 1 || t <= ts[0]) return slice(tr.values, 0, c);
		if (t >= ts[ts.length - 1]) return slice(tr.values, ts.length - 1, c);
		int k = 1;
		while (k < ts.length && ts[k] < t) k++;
		float a = (t - ts[k - 1]) / Math.max(1e-6f, ts[k] - ts[k - 1]);
		float[] p = slice(tr.values, k - 1, c), q = slice(tr.values, k, c);
		if (c == 4) { // shortest-path nlerp for rotations
			float dot = p[0] * q[0] + p[1] * q[1] + p[2] * q[2] + p[3] * q[3];
			if (dot < 0) for (int j = 0; j < 4; j++) q[j] = -q[j];
		}
		float[] o = new float[c];
		for (int j = 0; j < c; j++) o[j] = p[j] + (q[j] - p[j]) * a;
		return o;
	}

	private static float[] slice(float[] v, int k, int c) {
		float[] o = new float[c];
		System.arraycopy(v, k * c, o, 0, c);
		return o;
	}
}
