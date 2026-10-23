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
 * Smokes and fires burning right now (server):
 * <ul>
 * <li>Smoke: CS2's simulated voxel smoke (SmokeVolume), stepped ten times a second as CS2's server does; gone at
 * 22 s.</li>
 * <li>Fire: CS2's inferno (Inferno): flames spread over the floor, burn what's near them, die together, and go out
 * under smoke.</li>
 * </ul>
 */
public final class GrenadeZones {
	static final double SMOKE_SECONDS = 22;
	static final int SMOKE_TICKS = (int) (SMOKE_SECONDS * 20);

	record Smoke(ServerLevel level, Vec3 at, long start, long until, SmokeVolume volume) {}

	private record Burning(Inferno fire, long start) {}

	private static final List<Smoke> SMOKES = new ArrayList<>();
	private static final List<Burning> FIRES = new ArrayList<>();

	private GrenadeZones() {}

	static void smoke(ServerLevel level, Vec3 at, net.minecraft.world.entity.Entity thrower) {
		long now = level.getGameTime();
		SMOKES.add(new Smoke(level, at, now, now + SMOKE_TICKS, SmokeVolume.create(level, at)));
		GrenadeEffects.event(level, CsTeam.isT(thrower) ? GrenadeEffects.EV_SMOKE_T : GrenadeEffects.EV_SMOKE, at);
	}

	/** An HE grenade went off: smokes it reaches are pushed apart (their simulations take the blast). */
	static void blast(ServerLevel level, Vec3 at) {
		for (Smoke s : SMOKES) if (s.level == level && s.volume.origin.distanceTo(at) * StrafeConfig.unitsPerBlock < 640) s.volume.blast(at);
	}

	/**
	 * Starts a fire on the floor at `at`, carried on by the grenade's velocity when it landed; false when its first flame
	 * can't burn there (in a smoke or water: it fizzles, like CS).
	 */
	static boolean fire(ServerLevel level, Vec3 at, ServerPlayer owner, GrenadeEntity source, boolean incendiary) {
		return fire(level, at, owner, source != null ? source.landingVelocity() : Vec3.ZERO, source != null ? source.flightHeading() : Float.NaN, incendiary);
	}

	/** A fire as if a grenade landed at `at` moving at `velocity` (blocks/s) (also the developer bench's). */
	public static boolean fire(ServerLevel level, Vec3 at, ServerPlayer owner, Vec3 velocity, float heading, boolean incendiary) {
		Inferno f = new Inferno(level, at, new Vec3(0, 1, 0), velocity, incendiary, owner != null ? owner.getUUID() : null);
		if (f.fires.isEmpty()) return false;
		FIRES.add(new Burning(f, level.getGameTime()));
		GrenadeEffects.event(level, incendiary ? GrenadeEffects.EV_FIRE_INC : GrenadeEffects.EV_FIRE, at, heading);
		f.sync();
		return true;
	}

	/** Does the line from -> to pass through a smoke (CS2's through-smoke kill)? Checked every quarter block. */
	public static boolean throughSmoke(net.minecraft.world.level.Level level, Vec3 from, Vec3 to) {
		if (SMOKES.isEmpty()) return false;
		double len = from.distanceTo(to);
		for (Smoke s : SMOKES) {
			if (s.level != level) continue;
			for (double d = 0; d < len; d += 0.25)
				if (s.volume().contains(from.add(to.subtract(from).scale(d / len)))) return true;
		}
		return false;
	}

	/** Is a flame burning near this spot? (A smoke grenade lying in a fire goes off at once.) */
	static boolean burning(ServerLevel level, Vec3 at) {
		for (Burning b : FIRES) if (b.fire.level == level && b.fire.end == 0 && b.fire.burns(at)) return true;
		return false;
	}

	/** Is a spot on the ground inside a smoke (a voxel just above it is filled)? */
	static boolean inSmoke(ServerLevel level, Vec3 spot) {
		for (Smoke s : SMOKES) if (s.level == level && s.volume.contains(spot.add(0, 0.25, 0))) return true;
		return false;
	}

	/** As inSmoke, counting only smokes that went off less than `seconds` ago. */
	static boolean inYoungSmoke(ServerLevel level, Vec3 spot, double seconds) {
		long now = level.getGameTime();
		for (Smoke s : SMOKES)
			if (s.level == level && (now - s.start) / 20.0 <= seconds && s.volume.contains(spot.add(0, 0.25, 0))) return true;
		return false;
	}

	/** One burn from a fire (CS2: inferno_damage x 0.2 every 0.2 s). */
	static void burn(Inferno f, LivingEntity e, double damage) {
		// burning for a moment: mobs that die in the fire drop cooked meat (a molotov-killed chicken drops cooked chicken)
		if (!(e instanceof Player)) e.setRemainingFireTicks(Math.max(e.getRemainingFireTicks(), 10));
		e.invulnerableTime = 0;
		Vec3 motion = e.getDeltaMovement();
		boolean marked = e.hurtMarked;
		boolean wasAlive = e.isAlive();
		ServerPlayer burner = f.owner != null ? f.level.getServer().getPlayerList().getPlayer(f.owner) : null;
		damage *= dev.strafemc.mode.GameModes.teamDamage(burner, e, "grenade"); // the mode's friendly fire (none in Casual)
		if (damage <= 0) return;
		if (burner != null) KillFeed.damaged(burner, e, Math.min(damage, e.getHealth() / StrafeConfig.hpScale));
		e.hurtServer(f.level, f.level.damageSources().inFire(), (float) (damage * StrafeConfig.hpScale));
		if (wasAlive && e.isDeadOrDying() && burner != null) KillFeed.notice(burner, e, "inferno", 0);
		e.setDeltaMovement(motion);
		e.hurtMarked = marked;
		// CS2 tagging by the fire's own weapon data: incendiary fire slows you (0.7 / 0.8), molotov fire doesn't (1 / 1)
		if (e instanceof ServerPlayer sp) GunServer.tag(sp, f.incendiary ? Grenade.INCENDIARY.cls : Grenade.MOLOTOV.cls);
	}

	/** Every server tick: smokes step, fires think at their own rates; burnt-out ones are dropped. */
	public static void tick(MinecraftServer server) {
		for (Iterator<Smoke> it = SMOKES.iterator(); it.hasNext(); ) {
			Smoke s = it.next();
			long t = s.level.getGameTime();
			if (t >= s.until) {
				it.remove();
				continue;
			}
			s.volume.runUntil((t - s.start) / 20.0);
		}
		for (Iterator<Burning> it = FIRES.iterator(); it.hasNext(); ) {
			Burning b = it.next();
			Inferno f = b.fire;
			int before = f.end;
			boolean keep = f.run((f.level.getGameTime() - b.start) / 20.0);
			if (before == 0 && f.end != 0) {
				Vec3 at = Inferno.toMc(f.ox, f.oy, f.oz);
				GrenadeEffects.event(f.level, f.end == dev.strafemc.net.StrafePayloads.InfernoState.EXTINGUISHED ? GrenadeEffects.EV_FIRE_OUT
						: GrenadeEffects.EV_FIRE_END, at);
			}
			f.sync();
			if (!keep) it.remove();
		}
	}

	public static void clear() {
		SMOKES.clear();
		FIRES.clear();
	}
}
