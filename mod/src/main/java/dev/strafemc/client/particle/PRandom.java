package dev.strafemc.client.particle;

import org.joml.Vector3f;

/**
 * Source 2 particle randomness: indices into Valve's fixed random float table, offset per system seed, operator and
 * particle id, so "constant" random inputs are stable per particle. Port of VRF ParticleRandom (MIT).
 */
final class PRandom {
	static final int OPERATOR_STRIDE = 17;
	private static final java.util.Random SEEDS = new java.util.Random();
	int seed = SEEDS.nextInt() & 0xFFF;
	int operatorOffset;
	private int queries;

	void reseed() {
		seed = SEEDS.nextInt() & 0xFFF;
		queries = 0;
	}

	static float at(int i) {
		return RandomFloats.LIST[Integer.remainderUnsigned(i, RandomFloats.LIST.length)];
	}

	float next() {
		return at(queries++ + seed);
	}

	private float nextInterior() {
		return next() * 0.99989998f + 0.000099999997f;
	}

	/** Uniform point in the unit ball; radiusFraction[0] receives the radius fraction. */
	Vector3f nextInUnitBall(float[] radiusFraction) {
		float in = nextInterior();
		float cosPolar = in + in - 1f;
		float azimuth = nextInterior() * (float) (Math.PI * 2);
		float rf = (float) Math.pow(nextInterior(), 1.0 / 3.0);
		if (radiusFraction != null) radiusFraction[0] = rf;
		float sinPolar = (float) Math.sqrt(Math.max(0f, 1f - cosPolar * cosPolar));
		float ring = sinPolar * rf;
		return new Vector3f(ring * (float) Math.cos(azimuth), ring * (float) Math.sin(azimuth), rf * cosPolar);
	}

	float nextBetween(float min, float max) {
		return next() * (max - min) + min;
	}

	Vector3f nextBetweenPerComponent(Vector3f min, Vector3f max) {
		return new Vector3f(nextBetween(min.x, max.x), nextBetween(min.y, max.y), nextBetween(min.z, max.z));
	}

	float forParticle(int particleId, int fieldOffset) {
		return at(seed + operatorOffset + fieldOffset + particleId);
	}

	float forParticleBetween(int particleId, int fieldOffset, float min, float max) {
		return forParticle(particleId, fieldOffset) * (max - min) + min;
	}

	static float forSampleBetween(int sample, float min, float max) {
		return at(sample) * (max - min) + min;
	}
}
