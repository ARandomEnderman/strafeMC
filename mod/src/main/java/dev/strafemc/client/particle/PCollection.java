package dev.strafemc.client.particle;

/** Live particles of one system plus their spawn snapshots ("initial" values). Port of VRF ParticleCollection (MIT). */
final class PCollection {
	final Particle constants;
	Particle[] initial, current;
	int count, killedLastPass;
	float currentFrameTime, previousFrameTime;

	PCollection(Particle constants, int max) {
		this.constants = constants;
		int cap = max <= 0 ? 5000 : Math.min(max, 5000);
		initial = new Particle[cap];
		current = new Particle[cap];
		for (int i = 0; i < cap; i++) {
			initial[i] = constants.copy();
			current[i] = new Particle();
		}
	}

	int add() {
		if (count >= initial.length) return -1;
		initial[count].set(constants);
		return count++;
	}

	void pruneExpired() {
		int alive = 0;
		for (int i = 0; i < count; i++) {
			if (current[i].killed) continue;
			if (i != alive) { // swap the objects so the pool keeps its instances
				Particle ti = initial[alive], tc = current[alive];
				initial[alive] = initial[i];
				current[alive] = current[i];
				initial[i] = ti;
				current[i] = tc;
				initial[alive].index = alive;
				current[alive].index = alive;
			}
			alive++;
		}
		killedLastPass = count - alive;
		count = alive;
	}

	void clear() {
		count = 0;
	}
}
