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
		if (g.arr("animations").size() > 0) {
			JsonObject an = g.obj("animations", 0);
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

	/** Model-space matrix of every clip node at time t (seconds, clamped). */
	public Matrix4f[] pose(float t) {
		int n = names.length;
		Matrix4f[] world = new Matrix4f[n];
		for (int i = 0; i < n; i++) world[i] = null;
		for (int i = 0; i < n; i++) resolve(i, t, world);
		return world;
	}

	/**
	 * Crossfade (CS2 graph transitions): every node's local translation / rotation / scale blended from another clip
	 * at its own time (bones matched by name) into this clip, w = this clip's weight (0..1). Rotations take the short way.
	 */
	public Matrix4f[] pose(float t, VmClip from, float fromT, float w) {
		if (from == null || w >= 1) return pose(t);
		int n = names.length;
		Matrix4f[] world = new Matrix4f[n];
		for (int i = 0; i < n; i++) resolveBlend(i, t, from, fromT, w, world);
		return world;
	}

	private Matrix4f resolveBlend(int i, float t, VmClip from, float fromT, float w, Matrix4f[] world) {
		if (world[i] != null) return world[i];
		float[] T = sample(tT[i], t, restT[i]), R = sample(tR[i], t, restR[i]), S = sample(tS[i], t, restS[i]);
		Integer j = from.index.get(names[i]);
		if (j != null) {
			float[] T0 = sample(from.tT[j], fromT, from.restT[j]), R0 = sample(from.tR[j], fromT, from.restR[j]), S0 = sample(from.tS[j], fromT, from.restS[j]);
			float dot = R0[0] * R[0] + R0[1] * R[1] + R0[2] * R[2] + R0[3] * R[3];
			float sign = dot < 0 ? -1 : 1;
			for (int k = 0; k < 3; k++) {
				T[k] = T0[k] + (T[k] - T0[k]) * w;
				S[k] = S0[k] + (S[k] - S0[k]) * w;
			}
			float[] r = new float[4];
			for (int k = 0; k < 4; k++) r[k] = R0[k] + (sign * R[k] - R0[k]) * w;
			R = r;
		}
		Matrix4f local = new Matrix4f().translationRotateScale(new Vector3f(T[0], T[1], T[2]),
				new Quaternionf(R[0], R[1], R[2], R[3]).normalize(), new Vector3f(S[0], S[1], S[2]));
		Matrix4f m = parent[i] >= 0 ? new Matrix4f(resolveBlend(parent[i], t, from, fromT, w, world)).mul(local) : local;
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
