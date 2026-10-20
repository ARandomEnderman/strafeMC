package dev.strafemc.client.particle;

import org.joml.Vector3f;

/**
 * One Source 2 particle: the attribute set the CS2 weapon effects use. Field ids are ParticleField (ParticleAttribute)
 * numbers as stored in .vpcf files (m_nOutputField etc). Ported from ValveResourceFormat's particle simulation (MIT).
 */
public final class Particle {
	// ParticleField ids
	public static final int POSITION = 0, LIFE_DURATION = 1, POSITION_PREVIOUS = 2, RADIUS = 3, ROLL = 4, ROLL_SPEED = 5, COLOR = 6,
			ALPHA = 7, CREATION_TIME = 8, SEQUENCE_NUMBER = 9, TRAIL_LENGTH = 10, PARTICLE_ID = 11, YAW = 12, SECOND_SEQUENCE = 13,
			HITBOX_OFFSET = 15, ALPHA_ALTERNATE = 16, SCRATCH_VECTOR = 17, SCRATCH_FLOAT = 18, PITCH = 20, NORMAL = 21, GLOW_RGB = 22,
			GLOW_ALPHA = 23, SCRATCH_FLOAT1 = 26, SCRATCH_FLOAT2 = 27, SCRATCH_VECTOR2 = 30, FORCE_SCALE = 34, MANUAL_FRAME = 38;

	public int uniqueId, id, index, sequence, secondSequence;
	public final Vector3f position = new Vector3f(), positionPrevious = new Vector3f(), color = new Vector3f(1, 1, 1),
			rotation = new Vector3f(), rotationSpeed = new Vector3f(), velocity = new Vector3f(), normal = new Vector3f(0, 0, 1),
			forceAccumulator = new Vector3f(), scratchVector = new Vector3f(), scratchVector2 = new Vector3f(), glowRgb = new Vector3f(),
			hitboxOffset = new Vector3f();
	public float age, lifetime = 1, alpha = 1, alphaAlternate = 1, radius = 5, trailLength = 0.1f, forceScale = 1, scratch0, scratch1,
			scratch2, glowAlpha, creationTime, manualFrame;
	public boolean killed;
	/** While spawning: an initializer set the previous position itself (kept, less the velocity). */
	public boolean prevSet;

	public Particle copy() {
		Particle p = new Particle();
		p.set(this);
		return p;
	}

	public void set(Particle o) {
		uniqueId = o.uniqueId;
		id = o.id;
		index = o.index;
		sequence = o.sequence;
		secondSequence = o.secondSequence;
		position.set(o.position);
		positionPrevious.set(o.positionPrevious);
		color.set(o.color);
		rotation.set(o.rotation);
		rotationSpeed.set(o.rotationSpeed);
		velocity.set(o.velocity);
		normal.set(o.normal);
		forceAccumulator.set(o.forceAccumulator);
		scratchVector.set(o.scratchVector);
		scratchVector2.set(o.scratchVector2);
		glowRgb.set(o.glowRgb);
		hitboxOffset.set(o.hitboxOffset);
		age = o.age;
		lifetime = o.lifetime;
		alpha = o.alpha;
		alphaAlternate = o.alphaAlternate;
		radius = o.radius;
		trailLength = o.trailLength;
		forceScale = o.forceScale;
		scratch0 = o.scratch0;
		scratch1 = o.scratch1;
		scratch2 = o.scratch2;
		glowAlpha = o.glowAlpha;
		creationTime = o.creationTime;
		manualFrame = o.manualFrame;
		killed = o.killed;
	}

	/** Constant attributes of a system definition (m_ConstantColor, m_flConstantRadius, ...). */
	static Particle constants(Pd d) {
		Particle p = new Particle();
		if (d.has("m_ConstantColor")) {
			float[] c = d.floats("m_ConstantColor");
			p.color.set(c[0] / 255f, c[1] / 255f, c[2] / 255f);
			p.alpha = c.length > 3 ? c[3] / 255f : 1;
		}
		p.radius = d.f("m_flConstantRadius", p.radius);
		p.lifetime = d.f("m_flConstantLifespan", p.lifetime);
		p.rotation.z = (float) Math.toRadians(d.f("m_flConstantRotation", 0));
		p.rotationSpeed.z = (float) Math.toRadians(d.f("m_flConstantRotationSpeed", 0));
		if (d.has("m_ConstantNormal")) {
			Vector3f n = d.vec("m_ConstantNormal", new Vector3f(0, 0, 1));
			if (n.lengthSquared() > 0) p.normal.set(n);
		}
		p.sequence = d.i("m_nConstantSequenceNumber", 0);
		p.secondSequence = d.i("m_nConstantSequenceNumber1", 0);
		return p;
	}

	public float normalizedAge() {
		return age / Math.max(0.0001f, lifetime);
	}

	public float speed() {
		return velocity.length();
	}

	public void kill() {
		killed = true;
	}

	public static boolean isAngle(int field) {
		return field == ROLL || field == YAW || field == PITCH || field == ROLL_SPEED;
	}

	public float scalar(int field) {
		return switch (field) {
			case ALPHA -> alpha;
			case ALPHA_ALTERNATE -> alphaAlternate;
			case RADIUS -> radius;
			case TRAIL_LENGTH -> trailLength;
			case CREATION_TIME -> creationTime;
			case LIFE_DURATION -> lifetime;
			case YAW -> rotation.x;
			case PARTICLE_ID -> id;
			case PITCH -> rotation.y;
			case ROLL -> rotation.z;
			case ROLL_SPEED -> rotationSpeed.z;
			case SEQUENCE_NUMBER -> sequence;
			case SECOND_SEQUENCE -> secondSequence;
			case MANUAL_FRAME -> manualFrame;
			case SCRATCH_FLOAT -> scratch0;
			case SCRATCH_FLOAT1 -> scratch1;
			case SCRATCH_FLOAT2 -> scratch2;
			case FORCE_SCALE -> forceScale;
			case GLOW_ALPHA -> glowAlpha;
			default -> 0;
		};
	}

	public void scalar(int field, float v) {
		switch (field) {
			case GLOW_ALPHA -> glowAlpha = v;
			case RADIUS -> radius = v;
			case ALPHA -> alpha = v;
			case ALPHA_ALTERNATE -> alphaAlternate = v;
			case TRAIL_LENGTH -> trailLength = v;
			case ROLL_SPEED -> rotationSpeed.z = v;
			case YAW -> rotation.x = v;
			case PITCH -> rotation.y = v;
			case ROLL -> rotation.z = v;
			case LIFE_DURATION -> lifetime = v;
			case SEQUENCE_NUMBER -> sequence = (int) v;
			case SECOND_SEQUENCE -> secondSequence = (int) v;
			case MANUAL_FRAME -> manualFrame = v;
			case PARTICLE_ID -> id = (int) v;
			case CREATION_TIME -> creationTime = v;
			case SCRATCH_FLOAT -> scratch0 = v;
			case SCRATCH_FLOAT1 -> scratch1 = v;
			case SCRATCH_FLOAT2 -> scratch2 = v;
			case FORCE_SCALE -> forceScale = v;
			default -> {
			}
		}
	}

	/** The vector attribute itself (live reference) or null for non-vector fields. */
	public Vector3f vectorRef(int field) {
		return switch (field) {
			case POSITION -> position;
			case POSITION_PREVIOUS -> positionPrevious;
			case COLOR -> color;
			case HITBOX_OFFSET -> hitboxOffset;
			case SCRATCH_VECTOR -> scratchVector;
			case SCRATCH_VECTOR2 -> scratchVector2;
			case NORMAL -> normal;
			case GLOW_RGB -> glowRgb;
			default -> null;
		};
	}

	public Vector3f vector(int field) {
		Vector3f v = vectorRef(field);
		return v == null ? new Vector3f() : new Vector3f(v);
	}

	public void vector(int field, Vector3f v) {
		if (field == NORMAL && v.lengthSquared() == 0) return; // VRF: a zero normal is ignored
		if (field == POSITION_PREVIOUS) prevSet = true;
		Vector3f t = vectorRef(field);
		if (t != null) t.set(v);
	}

	public float vectorComponent(int field, int c) {
		Vector3f v = vector(field);
		return v.get(Math.max(0, Math.min(2, c)));
	}

	public void vectorComponent(int field, int c, float value) {
		Vector3f v = vectorRef(field);
		if (v != null) v.setComponent(Math.max(0, Math.min(2, c)), value);
	}

	// ParticleSetMethod
	public static final int SET_REPLACE = 0, SET_SCALE_INITIAL = 1, SET_ADD_INITIAL = 2, SET_RAMP_CURRENT = 3, SET_SCALE_CURRENT = 4,
			SET_ADD_CURRENT = 5;

	static int setMethod(String s) {
		if (s == null) return SET_REPLACE;
		return switch (s) {
			case "PARTICLE_SET_SCALE_INITIAL_VALUE" -> SET_SCALE_INITIAL;
			case "PARTICLE_SET_ADD_TO_INITIAL_VALUE" -> SET_ADD_INITIAL;
			case "PARTICLE_SET_RAMP_CURRENT_VALUE" -> SET_RAMP_CURRENT;
			case "PARTICLE_SET_SCALE_CURRENT_VALUE" -> SET_SCALE_CURRENT;
			case "PARTICLE_SET_ADD_TO_CURRENT_VALUE" -> SET_ADD_CURRENT;
			default -> SET_REPLACE;
		};
	}

	static float apply(float value, float initial, float current, float dt, int method) {
		return switch (method) {
			case SET_SCALE_INITIAL -> value * initial;
			case SET_ADD_INITIAL -> value + initial;
			case SET_SCALE_CURRENT -> value * current;
			case SET_ADD_CURRENT -> value + current;
			case SET_RAMP_CURRENT -> current + value * dt;
			default -> value;
		};
	}

	static Vector3f apply(Vector3f value, Vector3f initial, Vector3f current, float dt, int method) {
		return switch (method) {
			case SET_SCALE_INITIAL -> new Vector3f(value).mul(initial);
			case SET_ADD_INITIAL -> new Vector3f(value).add(initial);
			case SET_SCALE_CURRENT -> new Vector3f(value).mul(current);
			case SET_ADD_CURRENT -> new Vector3f(value).add(current);
			case SET_RAMP_CURRENT -> new Vector3f(value).mul(dt).add(current);
			default -> new Vector3f(value);
		};
	}

	/** Set-method application during an operator: initial values come from the spawn snapshot. */
	float modifyScalar(PCollection c, int field, float value, int method) {
		float initial = method == SET_SCALE_INITIAL || method == SET_ADD_INITIAL ? c.initial[index].scalar(field) : 0;
		return apply(value, initial, scalar(field), c.currentFrameTime, method);
	}

	/** At spawn the initial value is the current one. */
	float modifyScalarAtSpawn(PCollection c, int field, float value, int method) {
		float cur = scalar(field);
		return apply(value, cur, cur, c.currentFrameTime, method);
	}

	Vector3f modifyVector(PCollection c, int field, Vector3f value, int method) {
		Vector3f initial = method == SET_SCALE_INITIAL || method == SET_ADD_INITIAL ? c.initial[index].vector(field) : new Vector3f();
		return apply(value, initial, vector(field), c.currentFrameTime, method);
	}

	Vector3f modifyVectorAtSpawn(PCollection c, int field, Vector3f value, int method) {
		Vector3f cur = vector(field);
		return apply(value, cur, cur, c.currentFrameTime, method);
	}
}
