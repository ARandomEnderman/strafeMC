package dev.strafemc.weapon;

import dev.strafemc.config.StrafeConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Smokes and fires ("infernos") burning right now (server), with CS2's values:
 * <ul>
 * <li>Smoke (server code): fades in over 0.1..1.5 s, full until 17 s, gone at 22 s (smoothstep). Its radius (144
 * units) is still ours (FLAGGED: CS2's smoke is a voxel volume).</li>
 * <li>Fire (CS2's inferno_* settings): starts with one flame where it lands; flames spawn new ones inferno_flame_spacing
 * (42) units away, within inferno_spawn_angle (45) degrees of their own direction, for inferno_per_flame_spawn_duration
 * (3) s each, at most inferno_max_flames (16), never farther than inferno_max_range (150; incendiary 110) from the
 * start. Spawn interval starts at inferno_initial_spawn_interval (0.02 s), grows by
 * inferno_child_spawn_interval_multiplier (0.1) per generation up to inferno_max_child_spawn_interval (0.5 s), divided
 * by inferno_spread_speed_mult (1; incendiary 10). Each flame lives inferno_flame_lifetime (7 s; incendiary 5.5 s)
 * and hurts inferno_damage (40) per second. FLAGGED (ours): a flame hurts within 21 units (half the spacing).</li>
 * </ul>
 * Fires go out when a smoke covers them.
 */
public final class GrenadeZones {
	static final double SMOKE_RADIUS = 144;
	static final double SMOKE_SECONDS = 22;
	static final int SMOKE_TICKS = (int) (SMOKE_SECONDS * 20);

	static final double FLAME_SPACING = 42, SPAWN_ANGLE = 45, SPAWN_DURATION = 3, INITIAL_INTERVAL = 0.02, INTERVAL_MULT = 0.1,
			MAX_INTERVAL = 0.5, FLAME_REACH = 21;
	static final int MAX_FLAMES = 16;

	record Smoke(ServerLevel level, Vec3 at, long until) {}

	private static final class Flame {
		final Vec3 at;
		final Vec3 dir;
		final int depth;
		final long born, dies;
		long nextSpawn;

		Flame(Vec3 at, Vec3 dir, int depth, long born, long dies, long nextSpawn) {
			this.at = at;
			this.dir = dir;
			this.depth = depth;
			this.born = born;
			this.dies = dies;
			this.nextSpawn = nextSpawn;
		}
	}

	static final class Fire {
		final ServerLevel level;
		final Vec3 origin;
		final boolean incendiary;
		final double dps, range, lifetime, speed;
		final List<Flame> flames = new ArrayList<>();
		int spawned;

		Fire(ServerLevel level, Vec3 origin, boolean incendiary, double dps) {
			this.level = level;
			this.origin = origin;
			this.incendiary = incendiary;
			this.dps = dps;
			this.range = incendiary ? 110 : 150;
			this.lifetime = incendiary ? 5.5 : 7;
			this.speed = incendiary ? 10 : 1;
		}
	}

	private static final List<Smoke> SMOKES = new ArrayList<>();
	private static final List<Fire> FIRES = new ArrayList<>();

	private GrenadeZones() {}

	static void smoke(ServerLevel level, Vec3 at) {
		SMOKES.add(new Smoke(level, at, level.getGameTime() + SMOKE_TICKS));
		GrenadeEffects.event(level, GrenadeEffects.EV_SMOKE, at);
		for (Iterator<Fire> it = FIRES.iterator(); it.hasNext(); ) { // a smoke on a fire puts it out
			Fire f = it.next();
			if (f.level == level && covered(at, f.origin)) {
				GrenadeEffects.event(level, GrenadeEffects.EV_FIRE_OUT, f.origin);
				it.remove();
			}
		}
	}

	/** Starts a fire on the ground at `at`; false if a smoke already covers that spot (it fizzles, like CS). */
	static boolean fire(ServerLevel level, Vec3 at, ServerPlayer owner, GrenadeEntity source, boolean incendiary) {
		for (Smoke s : SMOKES) if (s.level == level && covered(s.at, at)) return false;
		java.util.Map<String, Object> d = dev.strafemc.data.WeaponData.get(incendiary ? Grenade.INCENDIARY.cls : Grenade.MOLOTOV.cls);
		Fire f = new Fire(level, at, incendiary, d != null && d.get("m_nDamage") instanceof Number n ? n.doubleValue() : 40);
		long t = level.getGameTime();
		f.flames.add(new Flame(at, Vec3.ZERO, 0, t, t + lifetimeTicks(f, level.getRandom()), t));
		f.spawned = 1;
		FIRES.add(f);
		GrenadeEffects.event(level, incendiary ? GrenadeEffects.EV_FIRE_INC : GrenadeEffects.EV_FIRE, at);
		flameEvent(f, at);
		return true;
	}

	/** inferno_flame_lifetime is an average: +-20% (FLAGGED spread). */
	private static long lifetimeTicks(Fire f, RandomSource r) {
		return (long) (f.lifetime * 20 * (0.8 + r.nextDouble() * 0.4));
	}

	private static void flameEvent(Fire f, Vec3 at) {
		GrenadeEffects.event(f.level, f.incendiary ? GrenadeEffects.EV_FLAME_INC : GrenadeEffects.EV_FLAME, at);
	}

	private static boolean covered(Vec3 smoke, Vec3 spot) {
		double upb = StrafeConfig.unitsPerBlock, dx = (smoke.x - spot.x) * upb, dz = (smoke.z - spot.z) * upb, dy = (spot.y - smoke.y) * upb;
		return dx * dx + dz * dz < SMOKE_RADIUS * SMOKE_RADIUS && dy > -64 && dy < 160;
	}

	/** Every server tick: flames spread and hurt (four times a second); burnt-out smokes and fires are dropped. */
	public static void tick(MinecraftServer server) {
		for (Iterator<Smoke> it = SMOKES.iterator(); it.hasNext(); ) {
			Smoke s = it.next();
			if (s.level.getGameTime() >= s.until) it.remove();
		}
		double upb = StrafeConfig.unitsPerBlock;
		for (Iterator<Fire> it = FIRES.iterator(); it.hasNext(); ) {
			Fire f = it.next();
			long t = f.level.getGameTime();
			f.flames.removeIf(fl -> t >= fl.dies);
			if (f.flames.isEmpty()) {
				GrenadeEffects.event(f.level, GrenadeEffects.EV_FIRE_END, f.origin);
				it.remove();
				continue;
			}
			spread(f, t, upb);
			if (t % 5 != 0) continue;
			double reach = FLAME_REACH / upb;
			for (LivingEntity e : f.level.getEntitiesOfClass(LivingEntity.class, new AABB(f.origin, f.origin).inflate(f.range / upb + 1, 2, f.range / upb + 1),
					LivingEntity::isAlive)) {
				boolean in = false;
				for (Flame fl : f.flames) {
					double dx = e.getX() - fl.at.x, dz = e.getZ() - fl.at.z, dy = e.getY() - fl.at.y;
					double r = reach + e.getBbWidth() / 2;
					if (dx * dx + dz * dz <= r * r && dy > -0.6 && dy < 1.2) {
						in = true;
						break;
					}
				}
				if (!in) continue;
				// burning for a moment: mobs that die in the fire drop cooked meat (a molotov-killed chicken drops cooked chicken)
				if (!(e instanceof Player)) e.setRemainingFireTicks(Math.max(e.getRemainingFireTicks(), 10));
				e.invulnerableTime = 0;
				Vec3 motion = e.getDeltaMovement();
				boolean marked = e.hurtMarked;
				e.hurtServer(f.level, f.level.damageSources().inFire(), (float) (f.dps * 0.25 * StrafeConfig.hpScale));
				e.setDeltaMovement(motion);
				e.hurtMarked = marked;
			}
		}
	}

	/** New flames from flames still spawning (see the class comment). */
	private static void spread(Fire f, long t, double upb) {
		if (f.spawned >= MAX_FLAMES) return;
		RandomSource rnd = f.level.getRandom();
		List<Flame> born = new ArrayList<>();
		for (Flame parent : f.flames) {
			if (f.spawned + born.size() >= MAX_FLAMES) break;
			if (t - parent.born > SPAWN_DURATION * 20 || t < parent.nextSpawn) continue;
			double interval = Math.min(MAX_INTERVAL, INITIAL_INTERVAL + INTERVAL_MULT * parent.depth) / f.speed;
			parent.nextSpawn = t + Math.max(1, Math.round(interval * 20));
			// direction: the parent's own, turned by up to the spawn angle (the first flame: any direction)
			double base = parent.dir == Vec3.ZERO ? rnd.nextDouble() * 360 : Math.toDegrees(Math.atan2(parent.dir.z, parent.dir.x));
			double a = Math.toRadians(base + (parent.dir == Vec3.ZERO ? 0 : (rnd.nextDouble() * 2 - 1) * SPAWN_ANGLE));
			Vec3 dir = new Vec3(Math.cos(a), 0, Math.sin(a));
			Vec3 want = parent.at.add(dir.scale(FLAME_SPACING / upb));
			Vec3 spot = ground(f.level, want);
			if (spot == null || spot.subtract(f.origin).horizontalDistance() * upb > f.range) continue;
			if (f.level.clip(new ClipContext(parent.at.add(0, 0.2, 0), spot.add(0, 0.2, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
					(net.minecraft.world.entity.Entity) null)).getType() != HitResult.Type.MISS) continue; // not through walls
			boolean crowded = false;
			for (Flame o : f.flames) if (o.at.distanceTo(spot) * upb < FLAME_SPACING * 0.99) crowded = true;
			for (Flame o : born) if (o.at.distanceTo(spot) * upb < FLAME_SPACING * 0.99) crowded = true;
			if (crowded) continue;
			born.add(new Flame(spot, dir, parent.depth + 1, t, t + lifetimeTicks(f, rnd), t));
		}
		for (Flame b : born) {
			f.flames.add(b);
			flameEvent(f, b.at);
		}
		f.spawned += born.size();
	}

	/** The ground under (or just over) a spot, within a block and a half: fire runs over the floor, not through walls. */
	private static Vec3 ground(ServerLevel level, Vec3 spot) {
		Vec3 top = spot.add(0, 1.5, 0), bottom = spot.add(0, -1.5, 0);
		HitResult h = level.clip(new ClipContext(top, bottom, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, (net.minecraft.world.entity.Entity) null));
		return h.getType() == HitResult.Type.MISS ? null : h.getLocation();
	}

	public static void clear() {
		SMOKES.clear();
		FIRES.clear();
	}
}
