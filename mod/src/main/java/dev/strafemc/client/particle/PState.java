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
	/** Control points a child system set itself (C_OP_SetSingleControlPointPosition...): its own and its children's. */
	private final Map<Integer, ControlPoint> local = new HashMap<>();
	/** Traces and the viewer, for the root (children use their root's). */
	PWorld world = PWorld.NONE;
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
		if (parent != null) {
			ControlPoint own = local.isEmpty() ? null : local.get(i);
			return own != null ? own : parent.cp(i);
		}
		return controlPoints.computeIfAbsent(i, k -> new ControlPoint());
	}

	/** A control point this system sets for itself and its children (the root's are everyone's). */
	ControlPoint ownCp(int i) {
		if (parent == null) return cp(i);
		return local.computeIfAbsent(i, k -> new ControlPoint());
	}

	PWorld world() {
		return parent != null ? parent.world() : world;
	}

	/** Control point history for velocities, recorded once per frame by the root. */
	void snapshotControlPoints(float elapsed) {
		for (ControlPoint c : controlPoints.values()) {
			c.positionPrevious.set(c.position);
			c.previousStepTime = elapsed;
		}
	}
}
