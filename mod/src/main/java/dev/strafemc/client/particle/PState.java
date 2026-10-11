package dev.strafemc.client.particle;

import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-system runtime state: clock, randomness, particle count, end cap, control points (owned by the root system and
 * shared with its children). Port of VRF ParticleSystemState (MIT).
 */
final class PState {
	static final PState DEFAULT = new PState(null, null);
	static final Particle DEFAULT_PARTICLE = new Particle();

	final PState parent;
	final PSystem system;
	final PRandom random = new PRandom();
	final Vector3f cameraPosition = new Vector3f();
	private final Map<Integer, ControlPoint> controlPoints = new HashMap<>();
	long particleCount;
	float age, restartAge, frameStartAge, frameLength, endCapStartAge, endTime;
	boolean inEndCap, endEarly, frozen;

	PState(PState parent, PSystem system) {
		this.parent = parent;
		this.system = system;
	}

	int behaviorVersion() {
		return system == null ? 0 : system.behaviorVersion;
	}

	void beginFrame(float length) {
		frameStartAge = age;
		frameLength = length;
	}

	float endCapAge() {
		return inEndCap ? age - endCapStartAge : 0;
	}

	void startEndCap() {
		if (inEndCap) return;
		inEndCap = true;
		endCapStartAge = age;
	}

	void clearEndCap() {
		inEndCap = false;
		endCapStartAge = 0;
		frozen = false;
	}

	void setRestartTime(float duration) {
		endEarly = true;
		endTime = age + duration;
	}

	ControlPoint cp(int i) {
		if (parent != null) return parent.cp(i);
		return controlPoints.computeIfAbsent(i, k -> new ControlPoint());
	}

	/** Control point history for velocities, recorded once per frame by the root. */
	void snapshotControlPoints(float elapsed) {
		for (ControlPoint c : controlPoints.values()) {
			c.positionPrevious.set(c.position);
			c.previousStepTime = elapsed;
		}
	}
}
