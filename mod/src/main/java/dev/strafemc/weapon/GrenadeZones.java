package dev.strafemc.weapon;

import dev.strafemc.config.StrafeConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Smokes and fires that are burning right now (server). Fires hurt whoever stands in them and go out when a smoke
 * covers them, as in CS. FLAGGED (not in CS2's data files, CS:GO-like values): fire radius 120 units, 7 s; smoke
 * radius 144 units, 18 s. Fire damage per second is the grenade's m_nDamage (40).
 */
public final class GrenadeZones {
	static final double FIRE_RADIUS = 120, SMOKE_RADIUS = 144;
	static final int FIRE_TICKS = 7 * 20, SMOKE_TICKS = 18 * 20;

	record Smoke(ServerLevel level, Vec3 at, long until) {}

	static final class Fire {
		final ServerLevel level;
		final Vec3 at;
		final long until;
		final ServerPlayer owner;
		final GrenadeEntity source;
		final double dps;

		Fire(ServerLevel level, Vec3 at, long until, ServerPlayer owner, GrenadeEntity source, double dps) {
			this.level = level;
			this.at = at;
			this.until = until;
			this.owner = owner;
			this.source = source;
			this.dps = dps;
		}
	}

	private static final List<Smoke> SMOKES = new ArrayList<>();
	private static final List<Fire> FIRES = new ArrayList<>();

	private GrenadeZones() {}

	static void smoke(ServerLevel level, Vec3 at) {
		SMOKES.add(new Smoke(level, at, level.getGameTime() + SMOKE_TICKS));
		GrenadeEffects.event(level, GrenadeEffects.EV_SMOKE, at);
		// a smoke on a fire puts it out
		for (Iterator<Fire> it = FIRES.iterator(); it.hasNext(); ) {
			Fire f = it.next();
			if (f.level == level && covered(at, f.at)) {
				GrenadeEffects.event(level, GrenadeEffects.EV_FIRE_OUT, f.at);
				it.remove();
			}
		}
	}

	/** Starts a fire on the ground at `at`; false if a smoke already covers that spot (it fizzles, like CS). */
	static boolean fire(ServerLevel level, Vec3 at, ServerPlayer owner, GrenadeEntity source, boolean incendiary) {
		for (Smoke s : SMOKES) if (s.level == level && covered(s.at, at)) return false;
		java.util.Map<String, Object> d = dev.strafemc.data.WeaponData.get(incendiary ? Grenade.INCENDIARY.cls : Grenade.MOLOTOV.cls);
		double dps = d != null && d.get("m_nDamage") instanceof Number n ? n.doubleValue() : 40;
		FIRES.add(new Fire(level, at, level.getGameTime() + FIRE_TICKS, owner, source, dps));
		GrenadeEffects.event(level, incendiary ? GrenadeEffects.EV_FIRE_INC : GrenadeEffects.EV_FIRE, at);
		return true;
	}

	private static boolean covered(Vec3 smoke, Vec3 spot) {
		double upb = StrafeConfig.unitsPerBlock, dx = (smoke.x - spot.x) * upb, dz = (smoke.z - spot.z) * upb, dy = (spot.y - smoke.y) * upb;
		return dx * dx + dz * dz < SMOKE_RADIUS * SMOKE_RADIUS && dy > -64 && dy < 160;
	}

	/** Every server tick: fires hurt four times a second; burnt-out smokes and fires are dropped. */
	public static void tick(MinecraftServer server) {
		for (Iterator<Smoke> it = SMOKES.iterator(); it.hasNext(); ) {
			Smoke s = it.next();
			if (s.level.getGameTime() >= s.until) it.remove();
		}
		double upb = StrafeConfig.unitsPerBlock, r = FIRE_RADIUS / upb;
		for (Iterator<Fire> it = FIRES.iterator(); it.hasNext(); ) {
			Fire f = it.next();
			long t = f.level.getGameTime();
			if (t >= f.until) {
				GrenadeEffects.event(f.level, GrenadeEffects.EV_FIRE_END, f.at);
				it.remove();
				continue;
			}
			if (t % 5 != 0) continue;
			for (LivingEntity e : f.level.getEntitiesOfClass(LivingEntity.class, new AABB(f.at, f.at).inflate(r, 1.5, r), LivingEntity::isAlive)) {
				double dx = e.getX() - f.at.x, dz = e.getZ() - f.at.z, dy = e.getY() - f.at.y;
				if (dx * dx + dz * dz > r * r || dy < -0.6 || dy > 1.2) continue;
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

	public static void clear() {
		SMOKES.clear();
		FIRES.clear();
	}
}
