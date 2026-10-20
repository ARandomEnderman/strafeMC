package dev.strafemc.client.particle;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Functions CS2's ground fire effects (inferno_fx/*_groundfire) are built from: control point snapshots, the maintain
 * emitter, inheriting from parent particles, end cap fades, ground placement and the player control point.
 */
final class FireFunctions {
	private FireFunctions() {}

	static PFunction create(String kind, String cls, Pd p) {
		return switch (cls) {
			case "C_INIT_InitFromCPSnapshot" -> new InitFromSnapshot(p);
			case "C_OP_SetFromCPSnapshot" -> new SetFromSnapshot(p);
			case "C_OP_MaintainEmitter" -> new MaintainEmitter(p);
			case "C_INIT_InheritFromParentParticles" -> new InheritFromParent(p);
			case "C_OP_EndCapDecay" -> new EndCapDecay(p);
			case "C_OP_LerpEndCapScalar" -> new LerpEndCapScalar(p);
			case "C_OP_RadiusDecay" -> new RadiusDecay(p);
			case "C_INIT_PositionPlaceOnGround" -> new PlaceOnGround(p);
			case "C_INIT_VelocityFromNormal" -> new VelocityFromNormal(p);
			case "C_INIT_Orient2DRelToCP" -> new Orient2DRelToCP(p);
			case "C_OP_AttractToControlPoint" -> new AttractToControlPoint(p);
			case "C_OP_RemapDotProductToScalar" -> new RemapDotProduct(p);
			case "C_OP_SetControlPointToPlayer" -> new ControlPointToPlayer(p);
			case "C_OP_SetSingleControlPointPosition" -> new SingleControlPointPosition(p);
			case "C_OP_VectorNoise" -> new VectorNoise(p);
			case "C_OP_CurlNoiseForce" -> new CurlNoiseForce(p);
			case "C_OP_ExternalWindForce" -> new NoForce(p); // no wind volumes in Minecraft
			case "C_OP_WorldTraceConstraint" -> new WorldTraceConstraint(p);
			default -> null;
		};
	}

	static boolean isVectorField(int field) {
		return new Particle().vectorRef(field) != null;
	}

	/** Copies snapshot attribute `read` of point i into the particle's `write` attribute. */
	static void copy(Snapshot snap, int i, int read, int write, Particle q, float interp, PState s, int localCp) {
		if (isVectorField(write)) {
			Vector3f v = snap.vector(read, i);
			if (read == Particle.POSITION && localCp >= 0) v = Initializers.transformPosition(s, localCp, v);
			if (interp < 1) v = new Vector3f(q.vector(write)).lerp(v, interp);
			q.vector(write, v);
		} else {
			float v = snap.scalar(read, i);
			if (interp < 1) v = PMath.lerp(q.scalar(write), v, interp);
			q.scalar(write, v);
		}
	}

	// ------------------------------------------------------------------ snapshots

	/** Point index for particle `n`: every increment-th point from the start (or a random one per particle), optionally reversed. */
	static int index(Snapshot snap, int n, int increment, int start, boolean random, boolean reverse, Particle q, PState s, int seed) {
		int count = snap.count;
		int i;
		if (random) i = (int) (s.random.forParticle(q.id, 911 + seed) * count);
		else i = start + n * Math.max(1, increment);
		i = Math.floorMod(i, count);
		return reverse ? count - 1 - i : i;
	}

	static final class InitFromSnapshot extends PFunction.Initializer {
		final int cp, read, write, localCp, seed;
		final boolean random, reverse;
		final Num increment, manual;

		InitFromSnapshot(Pd p) {
			super(p);
			cp = p.i("m_nControlPointNumber", 0);
			read = p.field("m_nAttributeToRead", Particle.POSITION);
			write = p.field("m_nAttributeToWrite", Particle.POSITION);
			localCp = p.i("m_nLocalSpaceCP", -1);
			random = p.b("m_bRandom", false);
			reverse = p.b("m_bReverse", false);
			seed = p.i("m_nRandomSeed", 0);
			increment = p.num("m_nSnapShotIncrement", new Num.Literal(1));
			manual = p.num("m_nManualSnapshotIndex", new Num.Literal(-1));
		}

		@Override
		long writes() {
			return mask(write);
		}

		@Override
		void init(Particle q, PCollection c, PState s) {
			Snapshot snap = s.cp(cp).snapshot;
			if (snap == null || snap.count == 0) return;
			int m = (int) manual.next(q, s);
			int i = m >= 0 ? Math.min(m, snap.count - 1)
					: index(snap, q.uniqueId, (int) increment.next(q, s), 0, random, reverse, q, s, seed); // by emission number: slots refill at the end
			copy(snap, i, read, write, q, 1, s, localCp);
		}
	}

	static final class SetFromSnapshot extends PFunction.Operator {
		final int cp, read, write, localCp, seed;
		final boolean random, reverse;
		final Num increment, start, interpolation;

		SetFromSnapshot(Pd p) {
			super(p);
			cp = p.i("m_nControlPointNumber", 0);
			read = p.field("m_nAttributeToRead", Particle.POSITION);
			write = p.field("m_nAttributeToWrite", Particle.POSITION);
			localCp = p.i("m_nLocalSpaceCP", -1);
			random = p.b("m_bRandom", false);
			reverse = p.b("m_bReverse", false);
			seed = p.i("m_nRandomSeed", 0);
			increment = p.num("m_nSnapShotIncrement", new Num.Literal(1));
			start = p.num("m_nSnapShotStartPoint", new Num.Literal(0));
			interpolation = p.num("m_flInterpolation", new Num.Literal(1));
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			Snapshot snap = s.cp(cp).snapshot;
			if (snap == null || snap.count == 0) return;
			int inc = (int) increment.next(s), st = (int) start.next(s);
			for (int k = 0; k < c.count; k++) {
				Particle q = c.current[k];
				int i = index(snap, k, inc, st, random, reverse, q, s, seed);
				copy(snap, i, read, write, q, Math.min(1, interpolation.next(q, s) * strength), s, localCp);
			}
		}
	}

	/**
	 * Keeps a number of particles alive: m_nParticlesToMaintain (times the snapshot's point count when it follows one,
	 * times m_flScale), topping up as they die, at most m_flEmissionRate per second unless emitted all at once.
	 */
	static final class MaintainEmitter extends PFunction.Emitter {
		final Num toMaintain, scale, startTime;
		final float rate, duration;
		final int snapshotCp;
		final boolean instantly;
		PSystem sys;
		boolean finished;
		float owed;

		MaintainEmitter(Pd p) {
			super(p);
			toMaintain = p.num("m_nParticlesToMaintain", new Num.Literal(100));
			scale = p.num("m_flScale", new Num.Literal(1));
			startTime = p.num("m_flStartTime", new Num.Literal(0));
			rate = p.f("m_flEmissionRate", 0); // FLAGGED: no rate given = top up at once (100/s left the fire sparse)
			duration = p.f("m_flEmissionDuration", 0);
			snapshotCp = p.i("m_nSnapshotControlPoint", -1);
			instantly = p.b("m_bEmitInstantaneously", false);
		}

		@Override
		void onStart(PSystem s) {
			sys = s;
			finished = false;
			owed = 0;
		}

		@Override
		void stop() {
			finished = true;
			sys = null;
		}

		@Override
		boolean finished() {
			return finished;
		}

		@Override
		void emit(float dt, PState s, float strength) {
			if (finished || sys == null) return;
			float elapsed = s.age - startAge;
			if (elapsed < startTime.next(s)) return;
			if (duration > 0 && elapsed > startTime.next(s) + duration) {
				finished = true;
				return;
			}
			float target = toMaintain.next(s) * scale.next(s) * strength;
			if (snapshotCp >= 0) {
				Snapshot snap = s.cp(snapshotCp).snapshot;
				target *= snap == null ? 0 : snap.count;
			}
			int missing = (int) target - sys.particles.count;
			if (missing <= 0) {
				owed = 0;
				return;
			}
			int n = missing;
			if (!instantly && rate > 0) {
				owed += rate * dt;
				n = Math.min(missing, (int) owed);
				owed -= n;
			}
			for (int i = 0; i < n; i++) sys.emit(0);
		}
	}

	/**
	 * Copies an attribute from a particle of the parent system: every m_nIncrement-th by this particle's number, or a
	 * random one. m_nFieldOutput's default is the radius (the ground fire's flames take their size from it).
	 */
	static final class InheritFromParent extends PFunction.Initializer {
		final int field, increment, seed;
		final float scale;
		final boolean random;

		InheritFromParent(Pd p) {
			super(p);
			field = p.field("m_nFieldOutput", Particle.RADIUS);
			increment = Math.max(1, p.i("m_nIncrement", 1));
			seed = p.i("m_nRandomSeed", 0);
			scale = p.f("m_flScale", 1);
			random = p.b("m_bRandomDistribution", false);
		}

		@Override
		long writes() {
			return mask(field);
		}

		@Override
		void init(Particle q, PCollection c, PState s) {
			PCollection parent = s.parent == null || s.parent.system == null ? null : s.parent.system.particles;
			if (parent == null || parent.count == 0) { // nothing to take from: such a particle would sit at the origin
				q.kill();
				return;
			}
			int i = random ? (int) (s.random.forParticle(q.id, 733 + seed) * parent.count) : q.uniqueId * increment;
			Particle from = parent.current[Math.floorMod(i, parent.count)];
			if (isVectorField(field)) q.vector(field, from.vector(field).mul(field == Particle.POSITION || field == Particle.POSITION_PREVIOUS ? 1 : scale));
			else q.scalar(field, from.scalar(field) * scale);
		}
	}

	// ------------------------------------------------------------------ end cap

	/** Particles die once the system is stopped (its end cap). */
	static final class EndCapDecay extends PFunction.Operator {
		EndCapDecay(Pd p) {
			super(p);
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			if (!s.inEndCap) return;
			for (int i = 0; i < c.count; i++) c.current[i].kill();
		}
	}

	/** In the end cap, a value goes linearly to m_flOutput over m_flLerpTime. */
	static final class LerpEndCapScalar extends PFunction.Operator {
		final int field;
		final float output, time;

		LerpEndCapScalar(Pd p) {
			super(p);
			field = p.field("m_nFieldOutput", Particle.ALPHA);
			output = p.f("m_flOutput", 1);
			time = p.f("m_flLerpTime", 1);
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			if (!s.inEndCap) return;
			float left = time - s.endCapAge() + dt; // time left before this step: moving dt/left of the way keeps it linear
			float k = left <= dt ? 1 : dt / left;
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				q.scalar(field, PMath.lerp(q.scalar(field), output, k));
			}
		}
	}

	/** Kills particles whose radius is down to m_flMinRadius. */
	static final class RadiusDecay extends PFunction.Operator {
		final float min;

		RadiusDecay(Pd p) {
			super(p);
			min = p.f("m_flMinRadius", 1);
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			for (int i = 0; i < c.count; i++) if (c.current[i].radius <= min) c.current[i].kill();
		}
	}

	// ------------------------------------------------------------------ world

	/**
	 * Puts a new particle on the surface below it (or behind it along its normal): traced up to m_flMaxTraceLength,
	 * lifted by m_flOffset (+ radius * m_flOffsetByRadiusFactor); a miss kills it when asked.
	 */
	static final class PlaceOnGround extends PFunction.Initializer {
		final Num offset, maxLength;
		final float radiusFactor;
		final boolean water, setNormal, alongNormal, kill;

		PlaceOnGround(Pd p) {
			super(p);
			offset = p.num("m_flOffset", new Num.Literal(0));
			maxLength = p.num("m_flMaxTraceLength", new Num.Literal(128));
			radiusFactor = p.f("m_flOffsetByRadiusFactor", 0);
			water = p.b("m_bIncludeWater", false);
			setNormal = p.b("m_bSetNormal", false);
			alongNormal = p.b("m_bTraceAlongNormal", false);
			kill = p.str("m_nTraceMissBehavior", "").equals("PARTICLE_TRACE_MISS_BEHAVIOR_KILL");
		}

		@Override
		long writes() {
			return mask(Particle.POSITION) | (setNormal ? mask(Particle.NORMAL) : 0);
		}

		@Override
		void init(Particle q, PCollection c, PState s) {
			Vector3f dir = alongNormal && q.normal.lengthSquared() > 0 ? new Vector3f(q.normal).negate().normalize() : new Vector3f(0, 0, -1);
			float len = maxLength.next(q, s);
			Vector3f from = new Vector3f(q.position), to = new Vector3f(dir).mul(len).add(from);
			PWorld.Hit hit = s.world().trace(from, to, water);
			if (hit == null) {
				if (kill) q.kill();
				return;
			}
			float lift = offset.next(q, s) + q.radius * radiusFactor;
			Vector3f up = setNormal || alongNormal ? new Vector3f(hit.normal()) : new Vector3f(dir).negate();
			q.position.set(hit.position()).add(new Vector3f(up).mul(lift));
			if (setNormal) q.normal.set(hit.normal());
		}
	}

	static final class VelocityFromNormal extends PFunction.Initializer {
		final float min, max;

		VelocityFromNormal(Pd p) {
			super(p);
			min = p.f("m_fSpeedMin", 0);
			max = p.f("m_fSpeedMax", 0);
		}

		@Override
		long writes() {
			return mask(Particle.POSITION_PREVIOUS);
		}

		@Override
		void init(Particle q, PCollection c, PState s) {
			q.velocity.set(q.normal).mul(s.random.forParticleBetween(q.id, 457, min, max));
		}
	}

	/** Roll (or m_nFieldOutput) faces away from the control point in the ground plane, plus m_flRotOffset degrees. */
	static final class Orient2DRelToCP extends PFunction.Initializer {
		final int cp, field;
		final float offset;

		Orient2DRelToCP(Pd p) {
			super(p);
			cp = p.i("m_nCP", 0);
			field = p.field("m_nFieldOutput", Particle.ROLL);
			offset = (float) Math.toRadians(p.f("m_flRotOffset", 0));
		}

		@Override
		long writes() {
			return mask(field);
		}

		@Override
		void init(Particle q, PCollection c, PState s) {
			Vector3f d = new Vector3f(q.position).sub(s.cp(cp).position);
			q.scalar(field, (float) Math.atan2(d.y, d.x) + offset);
		}
	}

	/** A force towards a control point: m_fForceAmount / distance^m_fFalloffPower. */
	static final class AttractToControlPoint extends PFunction.Force {
		final Num amount;
		final float falloff;
		final Vector3f scale;
		final Xform transform;

		AttractToControlPoint(Pd p) {
			super(p);
			amount = p.num("m_fForceAmount", new Num.Literal(100));
			falloff = p.f("m_fFalloffPower", -2);
			scale = p.vec("m_vecComponentScale", new Vector3f(1, 1, 1));
			transform = p.transform("m_TransformInput", Xform.CP0);
		}

		@Override
		void addForces(PCollection c, PState s, float strength) {
			Vector3f at = transform.matrix(s).getTranslation(new Vector3f());
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				Vector3f d = new Vector3f(at).sub(q.position).mul(scale);
				float len = d.length();
				if (len < 1e-4f) continue;
				float f = amount.next(q, s) * strength / (float) Math.pow(len, falloff);
				q.forceAccumulator.add(d.div(len).mul(f));
			}
		}
	}

	/**
	 * A value from the dot product of two control points' forward directions (the first replaced by the particle's
	 * normal or direction of travel when asked), remapped from [m_flInputMin, m_flInputMax] to the output range.
	 */
	static final class RemapDotProduct extends PFunction.Operator {
		final int cp1, cp2, field, method;
		final float inMin, inMax, outMin, outMax;
		final boolean useNormal, useVelocity, activeRange;

		RemapDotProduct(Pd p) {
			super(p);
			cp1 = p.i("m_nInputCP1", 0);
			cp2 = p.i("m_nInputCP2", 0);
			field = p.field("m_nFieldOutput", Particle.ALPHA);
			method = Particle.setMethod(p.str("m_nSetMethod", null));
			inMin = p.f("m_flInputMin", 0);
			inMax = p.f("m_flInputMax", 1);
			outMin = p.f("m_flOutputMin", 0);
			outMax = p.f("m_flOutputMax", 1);
			useNormal = p.b("m_bUseParticleNormal", false);
			useVelocity = p.b("m_bUseParticleVelocity", false);
			activeRange = p.b("m_bActiveRange", false);
		}

		private static Vector3f forward(ControlPoint c) {
			return c.rotationOrIdentity().transform(new Vector3f(1, 0, 0));
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			Vector3f f1 = forward(s.cp(cp1)), f2 = forward(s.cp(cp2));
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				Vector3f a = f1;
				if (useNormal) a = PMath.safeNormalize(new Vector3f(q.normal));
				else if (useVelocity) a = PMath.safeNormalize(new Vector3f(q.position).sub(q.positionPrevious));
				float in = a.dot(f2);
				if (activeRange && (in < Math.min(inMin, inMax) || in > Math.max(inMin, inMax))) continue;
				float t = PMath.saturate((in - inMin) / (inMax - inMin));
				float v = PMath.lerp(outMin, outMax, t);
				q.scalar(field, PMath.lerp(q.scalar(field), q.modifyScalar(c, field, v, method), strength));
			}
		}
	}

	/** A control point at the local player: the eyes, facing where they look when m_bOrientToEyes. */
	static final class ControlPointToPlayer extends PFunction.PreEmission {
		final int cp;
		final Vector3f offset;
		final boolean eyes;

		ControlPointToPlayer(Pd p) {
			super(p);
			cp = p.i("m_nCP1", 1);
			offset = p.vec("m_vecCP1Pos", new Vector3f());
			eyes = p.b("m_bOrientToEyes", false);
		}

		@Override
		void operate(PSystem sys, PState s, float dt) {
			PWorld w = s.world();
			Vector3f fwd = w.eyeForward();
			Quaternionf rot = eyes ? PMath.forwardToRotation(fwd) : null;
			Vector3f at = w.eyePosition().add(rot != null ? rot.transform(new Vector3f(offset)) : new Vector3f(offset));
			s.ownCp(cp).set(at, rot, s);
		}
	}

	/** Sets a control point (for this system and its children) to a vector, in a control point's space or the world's. */
	static final class SingleControlPointPosition extends PFunction.PreEmission {
		final int cp;
		final Vec value;
		final Xform transform;
		final boolean once;
		boolean done;

		SingleControlPointPosition(Pd p) {
			super(p);
			cp = p.i("m_nCP1", 1);
			value = p.vecInput("m_vecCP1Pos", Vec.literal(new Vector3f()));
			transform = p.transform("m_transformInput", new Xform(0, false, true));
			once = p.b("m_bSetOnce", false);
		}

		@Override
		void reset() {
			done = false;
		}

		@Override
		void operate(PSystem sys, PState s, float dt) {
			if (once && done) return;
			done = true;
			s.ownCp(cp).set(transform.matrix(s).transformPosition(value.next(s)), null, s);
		}
	}

	/** Smooth noise added to (or set into) a vector attribute, animated over time. */
	static final class VectorNoise extends PFunction.Operator {
		final int field;
		final Vector3f min, max;
		final float scale, timeScale;
		final boolean additive, offset;

		VectorNoise(Pd p) {
			super(p);
			field = p.field("m_nFieldOutput", Particle.POSITION);
			min = p.vec("m_vecOutputMin", new Vector3f());
			max = p.vec("m_vecOutputMax", new Vector3f(1, 1, 1));
			scale = p.f("m_fl4NoiseScale", 0.01f);
			timeScale = p.f("m_flNoiseAnimationTimeScale", 0);
			additive = p.b("m_bAdditive", false);
			offset = p.b("m_bOffset", false);
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			float t = s.age * timeScale;
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				float base = q.id * 13.37f + t;
				Vector3f n = new Vector3f(Noise.at(base, 0), Noise.at(base, 101), Noise.at(base, 211)); // 0..1
				Vector3f v = new Vector3f(PMath.lerp(min.x, max.x, n.x), PMath.lerp(min.y, max.y, n.y), PMath.lerp(min.z, max.z, n.z)).mul(strength);
				Vector3f cur = q.vector(field);
				q.vector(field, additive ? cur.add(v.mul(dt * 30)) : v);
			}
		}
	}

	/** Swirling force from smooth noise over position and time (stand-in for CS2's curl noise field). */
	static final class CurlNoiseForce extends PFunction.Force {
		final Vec freq, amount;

		CurlNoiseForce(Pd p) {
			super(p);
			freq = p.vecInput("m_vecNoiseFreq", Vec.literal(new Vector3f(1, 1, 1)));
			amount = p.vecInput("m_vecNoiseScale", Vec.literal(new Vector3f(1, 1, 1)));
		}

		@Override
		void addForces(PCollection c, PState s, float strength) {
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				Vector3f f = freq.next(q, s), a = amount.next(q, s);
				float x = q.position.x * f.x, y = q.position.y * f.y, z = q.position.z * f.z;
				Vector3f n = new Vector3f(Noise.at(y + z, s.age) - 0.5f, Noise.at(z + x, s.age + 17) - 0.5f, Noise.at(x + y, s.age + 31) - 0.5f);
				q.forceAccumulator.add(n.mul(a).mul(2 * strength));
			}
		}
	}

	static final class NoForce extends PFunction.Force {
		NoForce(Pd p) {
			super(p);
		}

		@Override
		void addForces(PCollection c, PState s, float strength) {}
	}

	/** Particles stop at world surfaces they move into (slide along them), optionally taking the surface normal. */
	static final class WorldTraceConstraint extends PFunction.Operator {
		final boolean setNormal;

		WorldTraceConstraint(Pd p) {
			super(p);
			setNormal = p.b("m_bSetNormal", false);
		}

		@Override
		void operate(PCollection c, float dt, PState s, float strength) {
			PWorld w = s.world();
			for (int i = 0; i < c.count; i++) {
				Particle q = c.current[i];
				if (q.position.distanceSquared(q.positionPrevious) < 1e-6f) continue;
				PWorld.Hit h = w.trace(q.positionPrevious, q.position, false);
				if (h == null) continue;
				Vector3f n = h.normal();
				Vector3f move = new Vector3f(q.position).sub(h.position());
				move.sub(new Vector3f(n).mul(move.dot(n))); // slide along the surface
				q.position.set(h.position()).add(new Vector3f(n).mul(0.5f)).add(move);
				if (setNormal) q.normal.set(n);
			}
		}
	}

	/** Cheap smooth 1D value noise (0..1) on a seeded lattice. */
	static final class Noise {
		static float at(float x, float seed) {
			int i = (int) Math.floor(x);
			float f = x - i, u = f * f * (3 - 2 * f);
			return PMath.lerp(hash(i, seed), hash(i + 1, seed), u);
		}

		private static float hash(int i, float seed) {
			int h = i * 374761393 + Float.floatToIntBits(seed) * 668265263;
			h = (h ^ (h >>> 13)) * 1274126177;
			return ((h ^ (h >>> 16)) & 0xFFFFFF) / (float) 0xFFFFFF;
		}
	}
}
