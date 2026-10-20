package dev.strafemc.client.particle;

import dev.strafemc.StrafeMc;
import dev.strafemc.data.AssetCache;
import dev.strafemc.data.Kv3;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulation of one Source 2 particle system (.vpcf from the CS2 copy) and its children: emitters, initializers,
 * operators with force generators, pre-emission operators, fixed sub-stepping. Port of VRF ParticleSystemSimulation (MIT).
 * Renderer definitions are kept raw for the Minecraft-side renderer.
 */
public final class PSystem {
	private static final Map<String, Map<String, Object>> DEFINITIONS = new ConcurrentHashMap<>();
	private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

	public final String name;
	final int behaviorVersion, maxParticles, groupId, initialParticles, firstMultipleOverride;
	final float minimumTimeStep, maximumTimeStep, maximumSimTime, minimumSimTime, stopSimulationAfterTime, preSimulationTime;
	final int minimumFrames;
	final List<PFunction.PreEmission> preEmission = new ArrayList<>();
	final List<PFunction.Emitter> emitters = new ArrayList<>();
	final List<PFunction.Initializer> initializers = new ArrayList<>();
	final List<PFunction.Operator> operators = new ArrayList<>();
	final List<PFunction.Force> forces = new ArrayList<>();
	public final List<Map<String, Object>> renderers;
	public final List<PSystem> children = new ArrayList<>();
	final PCollection particles;
	final PState state;
	boolean childEnabled = true, isEndCapChild;
	float startDelay;
	private boolean started;
	private int emitted, simulatedFrames;
	private float currentFrameTime, targetDrawTime, previousSimTime = 1e23f;

	/** Loads a system by its resource path, e.g. "particles/unified_weapon_fx/uweapon_muzflsh_ak47_fps.vpcf". */
	public static PSystem load(String vpcf) {
		return load(vpcf, null, new HashSet<>());
	}

	private static PSystem load(String vpcf, PState parent, Set<String> ancestors) {
		Map<String, Object> def = definition(vpcf);
		if (def == null) return null;
		ancestors.add(vpcf);
		return new PSystem(vpcf, def, parent, ancestors);
	}

	static Map<String, Object> definition(String vpcf) {
		String key = vpcf.endsWith("_c") ? vpcf : vpcf + "_c";
		return DEFINITIONS.computeIfAbsent(key, k -> {
			try {
				String text = Files.readString(AssetCache.dataBlock(k));
				int h = text.indexOf("-->"), i = text.indexOf('{', h < 0 ? 0 : h + 3);
				Map<String, Object> def = Kv3.parseObject(i >= 0 ? text.substring(i) : text);
				// file format version ("format:vpcf58"): VRF upgrades older formats before simulating; we apply the steps we need
				java.util.regex.Matcher fm = java.util.regex.Pattern.compile("format:vpcf([0-9]+)").matcher(text.substring(0, Math.max(0, i)));
				def.put("__format", fm.find() ? Long.parseLong(fm.group(1)) : 0L);
				return def;
			} catch (Exception e) {
				StrafeMc.LOG.warn("[strafePE] particle system {} failed to load: {}", k, e.toString());
				if (Boolean.getBoolean("strafemc.trace")) e.printStackTrace();
				return null;
			}
		});
	}

	private PSystem(String name, Map<String, Object> def, PState parent, Set<String> ancestors) {
		this.name = name;
		Pd p0 = new Pd(def, 0);
		behaviorVersion = p0.i("m_nBehaviorVersion", 0);
		Pd p = new Pd(def, behaviorVersion, (int) ((Number) def.getOrDefault("__format", 0L)).longValue());
		firstMultipleOverride = p.i("m_nFirstMultipleOverride_BackwardCompat", -1);
		groupId = p.i("m_nGroupID", 0);
		initialParticles = p.i("m_nInitialParticles", 0);
		maxParticles = Math.min(p.i("m_nMaxParticles", 1000), 20000);
		minimumTimeStep = p.f("m_flMinimumTimeStep", 0);
		float maxStep = Math.max(minimumTimeStep, p.f("m_flMaximumTimeStep", 0.1f));
		maximumTimeStep = maxStep <= 0 ? 0.1f : maxStep;
		minimumSimTime = p.f("m_flMinimumSimTime", 0);
		maximumSimTime = p.f("m_flMaximumSimTime", 0);
		minimumFrames = p.i("m_nMinimumFrames", 0);
		preSimulationTime = p.f("m_flPreSimulationTime", 0);
		stopSimulationAfterTime = p.f("m_flStopSimulationAfterTime", 0);
		currentFrameTime = maximumTimeStep;
		particles = new PCollection(Particle.constants(p), maxParticles);
		state = new PState(parent, this);
		setup(p.array("m_Emitters"), "emitter", p);
		setup(p.array("m_Initializers"), "initializer", p);
		setup(p.array("m_ForceGenerators"), "force", p);
		setup(p.array("m_Operators"), "operator", p);
		setup(p.array("m_PreEmissionOperators"), "preemission", p);
		renderers = p.array("m_Renderers");
		for (Map<String, Object> c : p.array("m_Children")) {
			Pd cp = p.nested(c);
			if (behaviorVersion >= 5 && cp.b("m_bDisableChild", false)) continue;
			String ref = cp.str("m_ChildRef", null);
			if (ref == null || ancestors.contains(ref)) continue;
			PSystem child = load(ref, state, new HashSet<>(ancestors));
			if (child == null) continue;
			child.startDelay = cp.f("m_flDelay", 0);
			child.isEndCapChild = cp.b("m_bEndCap", false);
			children.add(child);
		}
	}

	private void setup(List<Map<String, Object>> defs, String kind, Pd root) {
		int index = 0;
		for (Map<String, Object> d : defs) {
			Pd p = root.nested(d);
			if (p.b("m_bDisableOperator", false)) {
				index++;
				continue;
			}
			String cls = p.str("_class", "?");
			PFunction f = Functions.create(kind, cls, p);
			if (f == null) {
				if (WARNED.add(cls)) StrafeMc.LOG.warn("[strafePE] unsupported {} {} ({})", kind, cls, name);
			} else {
				if (f instanceof PFunction.Initializer in) {
					in.definitionIndex = index;
					initializers.add(in);
				} else if (f instanceof PFunction.Emitter e) emitters.add(e);
				else if (f instanceof PFunction.Force fg) forces.add(fg);
				else if (f instanceof PFunction.PreEmission pe) preEmission.add(pe);
				else if (f instanceof PFunction.Operator o) operators.add(o);
			}
			index++;
		}
	}

	public ControlPoint controlPoint(int i) {
		return state.cp(i);
	}

	public void setCameraPosition(org.joml.Vector3f p) {
		state.cameraPosition.set(p);
		for (PSystem c : children) c.setCameraPosition(p);
	}

	// ------------------------------------------------------------------ lifecycle

	public void start() {
		for (int i = 0; i < initialParticles; i++) emit(0);
		rearm();
		for (PSystem c : children) c.start();
		if (preSimulationTime > 0) {
			float step = maximumTimeStep > 0 ? maximumTimeStep : preSimulationTime;
			int steps = Math.min(2048, (int) Math.ceil(preSimulationTime / step));
			for (int i = 0; i < steps; i++) updateFrame(step, true);
		}
	}

	private void rearm() {
		started = true;
		for (PFunction.PreEmission pe : preEmission) {
			pe.hasRun = false;
			pe.reset();
		}
		for (PFunction f : initializers) f.reset();
		for (PFunction f : operators) f.reset();
		for (PFunction.Emitter e : emitters) e.start(this, state);
	}

	public void stop() {
		for (PFunction.Emitter e : emitters) e.stop();
		for (PSystem c : children) c.stop();
	}

	/** Advances by one render frame (seconds); the root also records control point history. */
	public void update(float frameTime) {
		updateFrame(frameTime, false);
		if (state.parent == null) state.snapshotControlPoints(frameTime);
	}

	public boolean finished() {
		if (particles.count > 0) return false;
		for (PFunction.Emitter e : emitters) if (!e.finished()) return false;
		for (PSystem c : children) if (!c.isEndCapChild && c.childEnabled && !c.finished()) return false;
		return true;
	}

	void emit(float ageAtSpawn) {
		int i = particles.add();
		if (i < 0) return;
		state.particleCount++;
		Particle p = particles.current[i];
		p.set(particles.initial[i]);
		p.uniqueId = emitted++;
		p.id = p.uniqueId + state.random.seed;
		p.index = i;
		p.position.set(state.cp(0).position);
		p.creationTime = state.age - ageAtSpawn;
		p.age = ageAtSpawn;
		long written = 1L << Particle.CREATION_TIME;
		for (PFunction.Initializer in : initializers) {
			if (!in.runsInCurrentPhase(state)) continue;
			long f = in.writes();
			if (behaviorVersion < 6 && (firstMultipleOverride < 0 || in.definitionIndex < firstMultipleOverride) && (f & ~written) == 0) continue;
			in.init(p, particles, state);
			written |= f;
		}
		// initial velocity into the Verlet pair (prev = pos - vel * dt); BasicMovement integrates from positions
		p.positionPrevious.set(p.position).sub(new org.joml.Vector3f(p.velocity).mul(currentFrameTime));
		particles.initial[i].set(p);
	}

	private void updateFrame(float frameTime, boolean presim) {
		if (!started) start();
		float maxStep = maximumTimeStep > 0 ? maximumTimeStep : 0.1f;
		if (maximumSimTime != 0 && simulatedFrames <= minimumFrames) {
			if (state.age + frameTime > maximumSimTime) frameTime = Math.max(minimumSimTime, maximumSimTime - state.age);
			simulatedFrames++;
		}
		float remaining = Math.min(frameTime, maxStep * 10f);
		targetDrawTime += remaining;
		if (targetDrawTime >= previousSimTime && state.age > targetDrawTime) remaining = 0;
		float length = 0;
		for (float left = remaining; left > 0; ) {
			float st = substep(left, maxStep);
			length += st;
			left -= st;
		}
		state.beginFrame(length);
		while (remaining > 0) {
			float st = substep(remaining, maxStep);
			remaining -= st;
			simulate(st);
		}
		if (stopSimulationAfterTime > 0 && state.age >= stopSimulationAfterTime) return;
		for (PSystem c : children) {
			if (!c.childEnabled) continue;
			if (c.isEndCapChild ? !state.inEndCap : state.age < c.startDelay) continue;
			c.updateFrame(frameTime, presim);
		}
	}

	private float substep(float remaining, float maxStep) {
		return remaining > maxStep ? maxStep : Math.max(remaining, minimumTimeStep);
	}

	private void simulate(float dt) {
		if (stopSimulationAfterTime > 0 && state.age >= stopSimulationAfterTime) return;
		if (state.frozen) return;
		dt = Math.min(dt, maximumTimeStep);
		currentFrameTime = dt;
		particles.currentFrameTime = dt;
		previousSimTime = state.age;
		state.age += dt;
		for (int i = 0; i < particles.count; i++) particles.current[i].age = state.age - particles.current[i].creationTime;
		state.random.operatorOffset = 0;
		for (PFunction.PreEmission pe : preEmission) {
			if (pe.runStrength(state) <= 0) continue;
			if (pe.runOnce) {
				if (pe.hasRun) continue;
				pe.hasRun = true;
			}
			pe.operate(this, state, dt);
			state.random.operatorOffset += PRandom.OPERATOR_STRIDE;
		}
		for (PFunction.Emitter e : emitters) {
			float st = e.runStrength(state);
			if (st > 0) e.emit(dt, state, st);
		}
		state.random.operatorOffset = 0;
		for (PFunction.Operator o : operators) {
			float st = o.runStrength(state);
			if (st <= 0) continue;
			o.operate(particles, dt, state, st);
			state.random.operatorOffset += PRandom.OPERATOR_STRIDE;
		}
		particles.pruneExpired();
		particles.previousFrameTime = dt;
		if (state.endEarly && state.age > state.endTime) restart();
		state.particleCount = particles.count;
	}

	private void restart() {
		stop();
		state.endEarly = false;
		state.clearEndCap();
		state.restartAge = state.age;
		simulatedFrames = 0;
		rearm();
		for (PSystem c : children) c.restart();
	}

	/** C_OP_ChooseRandomChildrenInGroup. */
	void chooseRandomChildrenInGroup(int group, int count) {
		int remaining = 0;
		for (PSystem c : children) if (c.groupId == group) remaining++;
		int needed = Math.max(0, Math.min(count, remaining));
		for (PSystem c : children) {
			if (c.groupId != group) continue;
			boolean chosen = needed > 0 && state.random.next() * remaining < needed;
			c.childEnabled = chosen;
			if (chosen) needed--;
			remaining--;
		}
	}

	// ------------------------------------------------------------------ renderer access

	public int count() {
		return particles.count;
	}

	public Particle particle(int i) {
		return particles.current[i];
	}

	public float age() {
		return state.age;
	}

	float frameTime() {
		return currentFrameTime;
	}
}
