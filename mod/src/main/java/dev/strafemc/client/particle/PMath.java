package dev.strafemc.client.particle;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Math helpers of the particle port (VRF ParticleMath / MathUtils, MIT). */
final class PMath {
	private PMath() {}

	static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	static float saturate(float v) {
		return Math.max(0f, Math.min(1f, v));
	}

	/** (v - a) / (b - a). */
	static float remap(float v, float a, float b) {
		return b == a ? 0f : (v - a) / (b - a);
	}

	static float remapRange(float v, float a, float b, float c, float d) {
		return c + (d - c) * remap(v, a, b);
	}

	static float wrap(float v, float min, float max) {
		float r = max - min;
		if (r == 0) return min;
		float t = (v - min) / r;
		return min + r * (t - (float) Math.floor(t));
	}

	static float bias(float x, float bias) {
		if (bias <= 0f) return 0f;
		if (bias >= 1f) return 1f;
		return x / ((1f - x) * (1f / bias - 2f) + 1f);
	}

	static float biasFromParameter(float x, float param, String type) {
		if ("PF_BIAS_TYPE_EXPONENTIAL".equals(type)) {
			float e = param >= 0 ? 1f - saturate(param) : 20f - saturate(param + 1f) * 19f;
			if (e <= 0) return 1;
			if (x <= 0) return 0;
			if (x >= 1) return 1;
			return (float) Math.pow(x, Math.min(e, 20f));
		}
		if (!"PF_BIAS_TYPE_STANDARD".equals(type) && !"PF_BIAS_TYPE_GAIN".equals(type)) return 0;
		float b = saturate((param + 1f) * 0.5f);
		if (b <= 0) return 0;
		if (b >= 1) return 1;
		if ("PF_BIAS_TYPE_GAIN".equals(type)) return x < 0.5f ? bias(x + x, b) * 0.5f : 1f - bias(2f - x - x, b) * 0.5f;
		return bias(x, b);
	}

	static Vector3f safeNormalize(Vector3f v) {
		float l = v.length();
		return l > 1e-6f ? new Vector3f(v).div(l) : new Vector3f();
	}

	/** Rotation that turns +X into the given forward direction, keeping +Z up where possible (Source convention). */
	static Quaternionf forwardToRotation(Vector3f forward) {
		Vector3f x = safeNormalize(forward);
		if (x.lengthSquared() == 0) return new Quaternionf();
		Vector3f up = Math.abs(x.z) > 0.999f ? new Vector3f(0, 1, 0) : new Vector3f(0, 0, 1);
		Vector3f y = new Vector3f(up).cross(x).normalize();
		Vector3f z = new Vector3f(x).cross(y).normalize();
		return new Quaternionf().setFromNormalized(new Matrix3f(x, y, z));
	}
}
