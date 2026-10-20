package dev.strafemc.weapon;

import dev.strafemc.config.StrafeConfig;
import dev.strafemc.net.StrafePayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A molotov / incendiary fire as CS2's server runs it (its inferno), in Source units with Source axes (x east, y north,
 * z up). Flames spread from the first one: every think (0.1 s; incendiary 0.01 s) each flame still spreading earns a
 * try, and in turn (at most inferno_max_trace_per_tick traces a think) flames with tries make up to 4 attempts at a new
 * flame: half the range ahead (less 0-20 units), turned from their own direction by inferno_spawn_angle, pushed along by
 * what's left of the grenade's velocity, laid on the floor found by a trace. All flames die together when the fire's
 * time is up. Anything within 60 units (incendiary 50) of a flame's centre, 40 units up, burns.
 */
final class Inferno {
	// CS2's inferno_* settings (defaults)
	static final float SPAWN_ANGLE = 45, MAX_RANGE = 150, LIFETIME = 7, LIFETIME_INCENDIARY = 5.5f, PER_FLAME_SPAWN_DURATION = 3,
			INITIAL_SPAWN_INTERVAL = 0.02f, CHILD_SPAWN_INTERVAL_MULTIPLIER = 0.1f, MAX_CHILD_SPAWN_INTERVAL = 0.5f, VELOCITY_FACTOR = 0.003f,
			VELOCITY_DECAY_FACTOR = 0.2f, FORWARD_REDUCTION_FACTOR = 0.9f, DAMAGE = 40, SPREAD_SPEED_MULT = 1, SPREAD_SPEED_MULT_INCENDIARY = 10;
	static final int MAX_FLAMES = 16, CHILD_SPAWN_MAX_DEPTH = 4, MAX_TRACE_PER_TICK = 16;
	/** How close something must be to a flame's centre to burn, and the centre's height over the flame. */
	static final float REACH = 60, REACH_INCENDIARY = 50, CENTER = 40, PLAYER_CENTER = 30;
	/** Damage is dealt every 0.2 s (inferno_damage per second). */
	static final double DAMAGE_INTERVAL = 0.2;
	/** A smoke this young (seconds) keeps new flames out of it. */
	static final double SMOKE_BLOCKS_FOR = 17.3;
	/** After the flames are out, the fire is kept this long (clients finish its effect), then dropped. */
	static final double LINGER = 1;

	static final class Fire {
		final double x, y, z, nx, ny, nz;
		final Fire parent;
		final int depth;
		int credits;
		boolean burning = true;
		/** Seconds (fire time) until it stops spreading, and its try interval with the next try's time. */
		final double spawnUntil, interval;
		double nextTry;

		Fire(double x, double y, double z, double nx, double ny, double nz, Fire parent, int depth, double spawnUntil, double interval, double now) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.nx = nx;
			this.ny = ny;
			this.nz = nz;
			this.parent = parent;
			this.depth = depth;
			this.spawnUntil = spawnUntil;
			this.interval = interval;
			this.nextTry = now + interval;
		}
	}

	private static int ids;

	final int id = ++ids;
	final ServerLevel level;
	final boolean incendiary;
	final double ox, oy, oz;
	final float lifetime;
	final List<Fire> fires = new ArrayList<>();
	final UUID owner;
	/** What's left of the grenade's velocity (units/s): it carries new flames forward, less after every miss. */
	double vx, vy, vz;
	/** Fire time (seconds since it started). */
	double time, nextThink, nextDamage;
	int nextSpread;
	/** 0 burning, else StrafePayloads.InfernoState.ENDED / EXTINGUISHED; when it ended (fire time). */
	int end;
	double endedAt;
	private boolean dirty = true;
	private final Set<UUID> told = new HashSet<>();
	private final RandomSource random;

	Inferno(ServerLevel level, Vec3 at, Vec3 normal, Vec3 velocity, boolean incendiary, UUID owner) {
		this.level = level;
		this.incendiary = incendiary;
		this.owner = owner;
		this.random = level.getRandom();
		double u = StrafeConfig.unitsPerBlock;
		ox = at.x * u;
		oy = -at.z * u;
		oz = at.y * u;
		vx = velocity.x * u;
		vy = -velocity.z * u;
		vz = velocity.y * u;
		lifetime = incendiary ? LIFETIME_INCENDIARY : LIFETIME;
		addFire(ox, oy, oz, normal.x, -normal.z, normal.y, null, 0);
	}

	float reach() {
		return incendiary ? REACH_INCENDIARY : REACH;
	}

	/** Spread interval: 0.1 s divided by inferno_spread_speed_mult (incendiary: _ct), once it has fewer than 64 flames. */
	double thinkInterval() {
		return fires.size() >= 64 ? 0.1 : 0.1 / (incendiary ? SPREAD_SPEED_MULT_INCENDIARY : SPREAD_SPEED_MULT);
	}

	static Vec3 toMc(double x, double y, double z) {
		double u = StrafeConfig.unitsPerBlock;
		return new Vec3(x / u, z / u, -y / u);
	}

	// ------------------------------------------------------------------ adding flames

	static final int ADDED = 0, TOO_CLOSE = 2, IN_SMOKE = 3, IN_WATER = 5, FULL = 6;

	/** CS2's AddFire: refused when the fire is full, near a burning flame's centre, in a young smoke or in water. */
	int addFire(double x, double y, double z, double nx, double ny, double nz, Fire parent, int depth) {
		if (fires.size() >= Math.min(MAX_FLAMES, 64)) return FULL;
		float reach = reach();
		for (Fire f : fires) {
			if (!f.burning) continue;
			double dx = x - f.x, dy = y - f.y, dz = z - (f.z + CENTER);
			if (dx * dx + dy * dy + dz * dz < reach * reach) return TOO_CLOSE;
		}
		Vec3 mc = toMc(x, y, z);
		if (GrenadeZones.inYoungSmoke(level, mc, SMOKE_BLOCKS_FOR)) return IN_SMOKE;
		if (level.getFluidState(BlockPos.containing(mc.add(0, 0.05, 0))).is(FluidTags.WATER)) return IN_WATER;
		double spawnUntil, interval;
		if (parent == null) {
			spawnUntil = time + PER_FLAME_SPAWN_DURATION;
			interval = INITIAL_SPAWN_INTERVAL;
		} else { // a child spreads for what's left of its parent's spreading, at a fraction of its interval
			spawnUntil = parent.spawnUntil;
			interval = Math.min(MAX_CHILD_SPAWN_INTERVAL, parent.interval * CHILD_SPAWN_INTERVAL_MULTIPLIER);
		}
		fires.add(new Fire(x, y, z, nx, ny, nz, parent, depth, spawnUntil, interval, time));
		dirty = true;
		return ADDED;
	}

	// ------------------------------------------------------------------ thinking

	/** Advances the fire to `seconds` of fire time (thinks at its own rate); false once it can be dropped. */
	boolean run(double seconds) {
		while (nextThink <= seconds) {
			time = nextThink;
			think();
			if (end != 0 && time - endedAt > LINGER) return false;
			nextThink += end != 0 ? 0.2 : thinkInterval();
		}
		time = seconds;
		return true;
	}

	private void think() {
		if (end != 0) return;
		extinguishCovered();
		if (end != 0) return;
		boolean any = false;
		for (Fire f : fires) any |= f.burning;
		if (!any) { // every flame put out one by one
			finish(StrafePayloads.InfernoState.EXTINGUISHED);
			return;
		}
		if (time >= lifetime) {
			for (Fire f : fires) f.burning = false;
			finish(StrafePayloads.InfernoState.ENDED);
			return;
		}
		if (fires.size() < Math.min(MAX_FLAMES, 64)) spread();
		while (nextDamage <= time) {
			damage();
			nextDamage += DAMAGE_INTERVAL;
		}
	}

	void finish(int how) {
		end = how;
		endedAt = time;
		dirty = true;
	}

	/** Smokes put out the flames they cover; when more than a third of over two burning flames go out, all do. */
	private void extinguishCovered() {
		int burning = 0, out = 0;
		for (Fire f : fires) {
			if (!f.burning) continue;
			burning++;
			if (GrenadeZones.inSmoke(level, toMc(f.x, f.y, f.z))) {
				f.burning = false;
				out++;
				dirty = true;
			}
		}
		if (burning > 2 && burning / 3f < out) {
			for (Fire f : fires) f.burning = false;
			finish(StrafePayloads.InfernoState.EXTINGUISHED);
		} else if (out > 0 && burning == out) finish(StrafePayloads.InfernoState.EXTINGUISHED);
	}

	private void spread() {
		for (Fire f : fires)
			if (f.burning && time < f.spawnUntil && time >= f.nextTry) {
				f.nextTry = time + f.interval;
				f.credits++;
			}
		int traces = MAX_TRACE_PER_TICK, last = nextSpread;
		for (int k = 0; k < fires.size() && traces > 0; k++) {
			if (fires.size() >= Math.min(MAX_FLAMES, 64)) break;
			last = (nextSpread + k) % fires.size();
			Fire f = fires.get(last);
			if (f.credits == 0 || !f.burning || f.depth + 1 >= CHILD_SPAWN_MAX_DEPTH) continue;
			f.credits--;
			int quadrant = random.nextInt(4);
			for (int attempt = 0; attempt < 4; attempt++) {
				double dx, dy, dz;
				if (f.parent == null) { // the first flame: anywhere around, a different quarter each attempt
					double a = random.nextDouble() * (Math.PI / 2) + ((attempt + quadrant) & 3) * (Math.PI / 2);
					dx = Math.cos(a);
					dy = Math.sin(a);
					dz = 0;
				} else { // onwards from its parent, turned by up to the spawn angle (attempts 3 and 4: one angle each way first)
					double px = f.x - f.parent.x, py = f.y - f.parent.y, pz = f.z - f.parent.z;
					double len = Math.sqrt(px * px + py * py + pz * pz);
					double yaw = len > 0 ? Math.toDegrees(Math.atan2(py, px)) : 0;
					double pitch = len > 0 ? Math.toDegrees(Math.atan2(-pz, Math.sqrt(px * px + py * py))) : 0;
					if (attempt == 2) yaw -= SPAWN_ANGLE;
					else if (attempt == 3) yaw += SPAWN_ANGLE;
					yaw += (random.nextDouble() * 2 - 1) * SPAWN_ANGLE;
					double cp = Math.cos(Math.toRadians(pitch));
					dx = cp * Math.cos(Math.toRadians(yaw));
					dy = cp * Math.sin(Math.toRadians(yaw));
					dz = -Math.sin(Math.toRadians(pitch));
				}
				// the grenade's push: what's left of the flame's spreading time, less with each generation
				double push = Math.max(0, Math.min(1, (f.spawnUntil - time) / PER_FLAME_SPAWN_DURATION))
						* Math.pow(VELOCITY_DECAY_FACTOR, f.depth) * VELOCITY_FACTOR;
				dx += vx * push;
				dy += vy * push;
				dz += vz * push;
				// along the flame's surface: drop the part along its normal
				double dn = dx * f.nx + dy * f.ny + dz * f.nz;
				dx -= f.nx * dn;
				dy -= f.ny * dn;
				dz -= f.nz * dn;
				double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (dl < 1e-17) {
					dx = 1;
					dy = dz = 0;
				} else {
					dx /= dl;
					dy /= dl;
					dz /= dl;
				}
				double step = MAX_RANGE * 0.5 - random.nextDouble() * 20;
				double nx = f.x + dx * step, ny = f.y + dy * step, nz = f.z + dz * step;
				double rx = nx - ox, ry = ny - oy, rz = nz - oz, rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
				if (rl > MAX_RANGE) { // never farther than the range from where it started
					nx = ox + rx / rl * MAX_RANGE;
					ny = oy + ry / rl * MAX_RANGE;
					nz = oz + rz / rl * MAX_RANGE;
				}
				// the floor: from 50 units over the spot down to 200 below the flame
				BlockHitResult floor = level.clip(new ClipContext(toMc(nx, ny, nz + 50), toMc(nx, ny, f.z - 200), ClipContext.Block.COLLIDER,
						ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
				traces--;
				if (floor.getType() == HitResult.Type.MISS || floor.isInside()) {
					slowDown();
					continue;
				}
				traces--; // CS2 also traces the line of sight from the old flame here (only drawn when debugging)
				double u = StrafeConfig.unitsPerBlock;
				nz = floor.getLocation().y * u;
				var d = floor.getDirection();
				if (GrenadeZones.inYoungSmoke(level, toMc(nx, ny, nz), SMOKE_BLOCKS_FOR)) continue;
				int r = addFire(nx, ny, nz, d.getStepX(), -d.getStepZ(), d.getStepY(), f, f.depth + 1);
				if (r == ADDED) break;
				if (r != TOO_CLOSE) slowDown();
			}
		}
		nextSpread = last + 1;
	}

	private void slowDown() {
		vx *= FORWARD_REDUCTION_FACTOR;
		vy *= FORWARD_REDUCTION_FACTOR;
		vz *= FORWARD_REDUCTION_FACTOR;
	}

	// ------------------------------------------------------------------ burning things

	/** Is a spot (blocks) within a burning flame's reach? */
	boolean burns(Vec3 at) {
		double u = StrafeConfig.unitsPerBlock, x = at.x * u, y = -at.z * u, z = at.y * u;
		float reach = reach();
		for (Fire f : fires) {
			if (!f.burning) continue;
			double dx = x - f.x, dy = y - f.y, dz = z - (f.z + CENTER);
			if (dx * dx + dy * dy + dz * dz < reach * reach) return true;
		}
		return false;
	}

	/** CS2's touch test: the nearest point of the entity's box within reach of a flame's centre, in its line of sight. */
	boolean touching(LivingEntity e) {
		double u = StrafeConfig.unitsPerBlock;
		float reach = reach();
		AABB box = e.getBoundingBox();
		for (Fire f : fires) {
			if (!f.burning) continue;
			double cz = f.z + CENTER + (e instanceof Player ? PLAYER_CENTER : 0);
			Vec3 c = toMc(f.x, f.y, cz);
			Vec3 near = new Vec3(Math.clamp(c.x, box.minX, box.maxX), Math.clamp(c.y, box.minY, box.maxY), Math.clamp(c.z, box.minZ, box.maxZ));
			if (near.distanceToSqr(c) * u * u >= reach * reach) continue;
			if (clear(c, near)) return true;
			if (e instanceof Player) { // blocked from the raised centre: CS2 tries again from the flame's own centre
				Vec3 c2 = toMc(f.x, f.y, f.z + CENTER);
				Vec3 near2 = new Vec3(Math.clamp(c2.x, box.minX, box.maxX), Math.clamp(c2.y, box.minY, box.maxY), Math.clamp(c2.z, box.minZ, box.maxZ));
				if (near2.distanceToSqr(c2) * u * u < reach * reach && clear(c2, near2)) return true;
			}
		}
		return false;
	}

	private boolean clear(Vec3 a, Vec3 b) {
		return level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()))
				.getType() == HitResult.Type.MISS;
	}

	/** The box the flames' reach covers (blocks), for finding what to burn. */
	AABB extent() {
		double u = StrafeConfig.unitsPerBlock, r = (reach() + 32 + 60) / u;
		AABB box = null;
		for (Fire f : fires) {
			Vec3 p = toMc(f.x, f.y, f.z + CENTER);
			AABB b = new AABB(p, p).inflate(r);
			box = box == null ? b : box.minmax(b);
		}
		return box;
	}

	private void damage() {
		AABB box = extent();
		if (box == null) return;
		double hit = DAMAGE * DAMAGE_INTERVAL;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive))
			if (touching(e)) GrenadeZones.burn(this, e, hit);
	}

	// ------------------------------------------------------------------ clients

	/** Sends the fire's state to players in range: everyone when it changed, else players who haven't had it yet. */
	void sync() {
		StrafePayloads.InfernoState msg = null;
		Vec3 at = toMc(ox, oy, oz);
		for (ServerPlayer p : level.players()) {
			if (p.distanceToSqr(at) > GrenadeEffects.FX_RANGE * GrenadeEffects.FX_RANGE || !ServerPlayNetworking.canSend(p, StrafePayloads.InfernoState.TYPE)) {
				told.remove(p.getUUID());
				continue;
			}
			if (!dirty && told.contains(p.getUUID())) continue;
			if (msg == null) msg = state();
			ServerPlayNetworking.send(p, msg);
			told.add(p.getUUID());
		}
		dirty = false;
	}

	private StrafePayloads.InfernoState state() {
		double u = StrafeConfig.unitsPerBlock;
		int n = StrafePayloads.InfernoState.FLOATS;
		float[] d = new float[fires.size() * n];
		for (int i = 0; i < fires.size(); i++) {
			Fire f = fires.get(i);
			Fire p = f.parent != null ? f.parent : f;
			int o = i * n;
			d[o] = (float) ((f.x - ox) / u);
			d[o + 1] = (float) ((f.z - oz) / u);
			d[o + 2] = (float) (-(f.y - oy) / u);
			d[o + 3] = (float) ((p.x - ox) / u);
			d[o + 4] = (float) ((p.z - oz) / u);
			d[o + 5] = (float) (-(p.y - oy) / u);
			d[o + 6] = (float) f.nx;
			d[o + 7] = (float) f.nz;
			d[o + 8] = (float) -f.ny;
			d[o + 9] = f.burning ? 1 : 0;
		}
		Vec3 at = toMc(ox, oy, oz);
		return new StrafePayloads.InfernoState(id, incendiary, at.x, at.y, at.z, lifetime, (float) time, end, d);
	}
}
