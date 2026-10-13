package dev.strafemc.weapon;

import dev.strafemc.config.StrafeConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The throw and the flight of a grenade, shared by the server (the real grenade) and the client (the trajectory
 * preview), so both always agree. Positions in blocks, velocities in blocks per second.
 * FLAGGED (CS:GO's grenade code as we know it; CS2's data files don't have it): throw speed clamp(m_flThrowVelocity x
 * 0.9, 15, 750) x (0.3 + 0.7 x strength), aimed 10 degrees higher at the horizon, from the eyes lowered by 12 units
 * for an underhand throw, plus 1.25 x the thrower's velocity; gravity 0.4 x sv_gravity, bounces keep 45%, stop below
 * 20 units/s on a floor; collision radius 3.6 units.
 */
public final class GrenadeFlight {
	public static final double GRAVITY_SCALE = 0.4, ELASTICITY = 0.45, STOP_SPEED = 20, STEP = 1 / 64.0, RADIUS = 3.6;

	private GrenadeFlight() {}

	public record Launch(Vec3 start, Vec3 vel) {}

	/** Where a throw starts and how fast (ownVel in units/s). */
	public static Launch launch(Level level, Entity thrower, Vec3 eye, float yaw, float pitchIn, double strength, Vec3 ownVel, double throwVel) {
		double upb = StrafeConfig.unitsPerBlock;
		strength = Math.max(0, Math.min(1, strength));
		double speed = Math.max(15, Math.min(750, throwVel * 0.9)) * (strength * 0.7 + 0.3);
		float pitch = Math.max(-90, Math.min(90, pitchIn));
		pitch -= (90 - Math.abs(pitch)) * 10f / 90f;
		Vec3 dir = Vec3.directionFromRotation(pitch, yaw);
		Vec3 src = eye.add(0, (strength * 12 - 12) / upb, 0);
		Vec3 ahead = src.add(dir.scale(22 / upb));
		HitResult b = level.clip(new ClipContext(src, ahead, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, thrower));
		Vec3 start = b.getType() == HitResult.Type.MISS ? ahead : b.getLocation().subtract(dir.scale(2 / upb));
		if (ownVel.length() > 3500) ownVel = ownVel.normalize().scale(3500);
		return new Launch(start, dir.scale(speed).add(ownVel.scale(1.25)).scale(1 / upb));
	}

	/** One 1/64 s step's result: new position and velocity, the surface normal hit (or null), whether it now rests. */
	public record Step(Vec3 pos, Vec3 vel, Vec3 normal, double impactSpeed, boolean resting) {}

	/**
	 * One step: half the gravity before the move, half after (as Source); bounce off blocks a radius from its centre,
	 * and off players and mobs (except `ignore`: the thrower right after the throw).
	 */
	public static Step step(Level level, Entity self, Entity ignore, Vec3 pos, Vec3 vel, double gravity) {
		double upb = StrafeConfig.unitsPerBlock, g = gravity * GRAVITY_SCALE / upb, r = RADIUS / upb;
		vel = vel.add(0, -g * STEP * 0.5, 0);
		Vec3 move = vel.scale(STEP), to = pos.add(move);
		double len = move.length();
		Vec3 reach = len > 1e-9 ? to.add(move.scale(r / len)) : to;
		BlockHitResult hit = level.clip(new ClipContext(pos, reach, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self));
		Vec3 end = hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : reach;
		var ent = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(level, self, pos, end,
				new net.minecraft.world.phys.AABB(pos, end).inflate(1), e -> e != ignore && e != self && e instanceof net.minecraft.world.entity.LivingEntity
						&& e.isAlive() && !e.isSpectator(), (float) r);
		if (ent != null) { // off a player or mob: the side of its box it hit
			var box = ent.getEntity().getBoundingBox();
			Vec3 at = ent.getLocation(), c = box.getCenter();
			Vec3 n = Math.abs(at.y - box.maxY) < r * 1.5 ? new Vec3(0, 1, 0) : new Vec3(at.x - c.x, 0, at.z - c.z).normalize();
			if (n.lengthSqr() < 1e-6) n = vel.scale(-1).normalize();
			double speed = vel.length() * upb;
			vel = vel.subtract(n.scale(Math.min(0, vel.dot(n)) * 2)).scale(ELASTICITY);
			return new Step(at.add(n.scale(r)), vel, n, speed, false);
		}
		if (hit.getType() == HitResult.Type.BLOCK) {
			Vec3 n = Vec3.atLowerCornerOf(hit.getDirection().getUnitVec3i());
			double speed = vel.length() * upb;
			vel = vel.subtract(n.scale(vel.dot(n) * 2)).scale(ELASTICITY);
			Vec3 at = hit.getLocation().add(n.scale(r));
			if (n.y > 0.7 && vel.length() * upb < STOP_SPEED) return new Step(at, Vec3.ZERO, n, speed, true);
			return new Step(at, vel, n, speed, false);
		}
		return new Step(to, vel.add(0, -g * STEP * 0.5, 0), null, 0, false);
	}

	/**
	 * The path a throw would take, one point per step, until it goes off (as GrenadeEntity does it: HE and flash at the
	 * fuse, a molotov on its first floor hit or when it fizzles in the air), rests, or after `seconds`.
	 */
	public static List<Vec3> simulate(Level level, Entity self, Launch l, double gravity, double seconds, Grenade type) {
		double end = seconds;
		if (type == Grenade.HE || type == Grenade.FLASHBANG) end = GrenadeEntity.FUSE;
		else if (type == Grenade.MOLOTOV || type == Grenade.INCENDIARY) end = GrenadeEntity.MOLOTOV_AIR_TIME;
		boolean molotov = type == Grenade.MOLOTOV || type == Grenade.INCENDIARY;
		List<Vec3> path = new ArrayList<>();
		Vec3 pos = l.start(), vel = l.vel();
		path.add(pos);
		for (int i = 0; i < end / STEP; i++) {
			Step s = step(level, self, self, pos, vel, gravity);
			pos = s.pos();
			vel = s.vel();
			path.add(pos);
			if (s.resting() || (molotov && s.normal() != null && s.normal().y > 0.7)) break;
		}
		return path;
	}

	/** sv_gravity (CS2's default 800 when unknown). */
	public static double gravity() {
		String v = dev.strafemc.bridge.Cs2Bridge.INSTANCE.cvar("sv_gravity");
		try {
			return v == null ? 800 : Double.parseDouble(v);
		} catch (NumberFormatException e) {
			return 800;
		}
	}
}
