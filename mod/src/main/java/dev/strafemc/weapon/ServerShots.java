package dev.strafemc.weapon;

import dev.strafemc.config.StrafeConfig;
import dev.strafemc.net.StrafePayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server authority over where bullets go. Each player gets a secret-free but server-chosen seed (ShotSeed) and the
 * server counts their shots, so the random spread of shot n is fixed by the server; the client only predicts it.
 * The client also reports its inaccuracy and aim punch. The server keeps its own model of both (same CS code, driven
 * by its view of the player: speed from positions, ground contact, crouching, its own recoil from accepted shots) and
 * accepts a report only when it is no better than plausible. Otherwise it uses its own value and tells the shooter
 * (ShotCorrection) so their predicted bullet hole is replaced by the real one.
 */
public final class ServerShots {
	/** Tolerances for client reports: server estimates lag (20 Hz positions) and network timing jitters. */
	private static final double INACCURACY_SLACK = 0.8, PUNCH_SLACK_DEG = 0.35, PUNCH_SLACK_FRAC = 0.25;
	private static final SecureRandom SEEDS = new SecureRandom();
	/** Shots closer together than this count as one spray (shot-to-shot inaccuracy). */
	private static final long SPRAY_GAP_NS = 500_000_000L;

	private static final class St {
		long seed = SEEDS.nextLong();
		int shot, zoom;
		final AimPunch punch = new AimPunch();
		float recoilIndex;
		double penalty, lastX = Double.NaN, lastZ, speed, prevSpeed, lastTickTime;
		long lastShotNs;
		/** The inaccuracy right after the last shot, and the target / recovery time the last tick saw (for shot-to-shot recovery). */
		double penaltyAfterShot, target, recovery = 1;
	}

	/** Server's decision for one shot. */
	/** The shot's direction and number, and what went into it (for the other pellets of a shotgun shot). */
	public record Result(Vec3 dir, int shot, boolean corrected, float pitch, float yaw, double punchPitch, double punchYaw, double inaccuracy, long seed) {}

	private static final Map<UUID, St> STATES = new ConcurrentHashMap<>();

	private ServerShots() {}

	private static St st(ServerPlayer p) {
		return STATES.computeIfAbsent(p.getUUID(), u -> new St());
	}

	/** On join: the player's seed and the next shot number, for client prediction. */
	public static void sendSeed(ServerPlayer p) {
		St s = st(p);
		if (ServerPlayNetworking.canSend(p, StrafePayloads.ShotSeed.TYPE)) ServerPlayNetworking.send(p, new StrafePayloads.ShotSeed(s.seed, s.shot));
	}

	public static void forget(ServerPlayer p) {
		STATES.remove(p.getUUID());
	}

	/** Every tick: the server's own inaccuracy, recoil decay and punch for players holding a gun. */
	public static void tick(MinecraftServer server) {
		double now = System.nanoTime() / 1e9;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			St s = st(p);
			double dt = s.lastTickTime == 0 ? 0 : Math.min(0.25, now - s.lastTickTime);
			s.lastTickTime = now;
			double sp = Double.isNaN(s.lastX) ? 0 : Math.hypot(p.getX() - s.lastX, p.getZ() - s.lastZ) * 20 * StrafeConfig.unitsPerBlock;
			s.lastX = p.getX();
			s.lastZ = p.getZ();
			s.prevSpeed = s.speed;
			s.speed = sp;
			s.punch.advance(now);
			if (!(p.getMainHandItem().getItem() instanceof GunItem g) || g instanceof KnifeItem) continue;
			WeaponStats w = GunItem.statsOf(g.weaponClass, g.modeOf(p.getMainHandItem(), s.zoom, false)); // scoped: the scoped numbers
			if (w == null) continue;
			if (System.nanoTime() >= s.lastShotNs + (long) (w.cycleTime() * 1e9)) s.recoilIndex = Recoil.decayIndex(s.recoilIndex, dt);
			boolean ground = !p.level().noCollision(p, p.getBoundingBox().move(0, -0.06, 0));
			// lenient: the slower of the last two ticks (positions trail the client's movement)
			double target = InaccuracyModel.target(w, p.onClimbable(), ground, p.isCrouching(), Math.min(s.speed, s.prevSpeed));
			Recoil rc = Recoil.of(w.cls());
			double rec = rc != null ? rc.recoveryTime(ground, p.isCrouching(), s.recoilIndex) : p.isCrouching() ? w.recoveryTimeCrouch() : w.recoveryTimeStand();
			s.penalty = InaccuracyModel.recover(s.penalty, target, rec, dt);
			s.target = target;
			s.recovery = rec;
		}
	}

	/** The player's zoom level (right-click on a scoped gun; 0 when they switch weapons), for the server's inaccuracy. */
	public static void setZoom(ServerPlayer p, int level) {
		st(p).zoom = Math.max(0, Math.min(2, level));
	}

	/**
	 * A shot the server rejected (fire rate, deploy, empty) still uses up its number, like it did on the client. Only
	 * the expected number advances the counter, so a client can't skip ahead to pick a favourable spread.
	 */
	public static void skip(ServerPlayer p, int clientShot) {
		St s = st(p);
		if (clientShot == s.shot) s.shot++;
	}

	/** Decides one accepted shot (fire rate / ammo already checked) from the client's report. */
	public static Result resolve(ServerPlayer p, WeaponStats w, StrafePayloads.Fire f, long shotAtNs) {
		St s = st(p);
		double now = shotAtNs / 1e9; // the shot's own time (GunServer's shot clock), not the packet's arrival
		s.punch.advance(now);
		boolean corrected = f.shot() != s.shot;
		int shot = s.shot++;
		// during a spray the inaccuracy also runs on the shots' own times: what the last shot left, recovered for the time
		// between the two shots (the tick-based value adds up two shots handled in one tick without the recovery between)
		if (s.lastShotNs != 0 && shotAtNs > s.lastShotNs && shotAtNs - s.lastShotNs < SPRAY_GAP_NS)
			s.penalty = InaccuracyModel.recover(s.penaltyAfterShot, s.target, s.recovery, (shotAtNs - s.lastShotNs) / 1e9);
		// inaccuracy: the client's claim, unless it's better than the server's (lenient) estimate allows
		double floor = s.penalty * INACCURACY_SLACK;
		double inacc = Double.isFinite(f.inaccuracy()) ? Math.min(1, Math.max(0, f.inaccuracy())) : floor;
		if (inacc < floor) {
			inacc = s.penalty;
			corrected = true;
		}
		// recoil: the client's punch if close to the server's own integration, else the server's
		double pp = f.punchPitch(), py = f.punchYaw();
		if (!close(pp, s.punch.angle[0]) || !close(py, s.punch.angle[1])) {
			pp = s.punch.angle[0];
			py = s.punch.angle[1];
			corrected = true;
		}
		Vec3 dir = ShotMath.direction(f.pitch(), f.yaw(), pp, py, inacc, w.spread(), s.seed, shot);
		// the server's own recoil and inaccuracy move on exactly like the client's
		s.penalty = Math.max(s.penalty, inacc) + w.inaccuracyFire();
		Recoil rc = Recoil.of(w.cls());
		if (rc != null) {
			int i = ((int) s.recoilIndex) & (Recoil.TABLE - 1);
			s.punch.kick(now, rc.angle[w.mode()][i], rc.magnitude[w.mode()][i]);
			s.recoilIndex += 1;
		}
		s.lastShotNs = shotAtNs;
		s.penaltyAfterShot = s.penalty;
		return new Result(dir, shot, corrected, f.pitch(), f.yaw(), pp, py, inacc, s.seed);
	}

	private static boolean close(double client, double server) {
		return Double.isFinite(client) && Math.abs(client - server) <= PUNCH_SLACK_DEG + PUNCH_SLACK_FRAC * Math.abs(server);
	}
}
