package dev.strafemc.weapon;

import dev.strafemc.config.StrafeConfig;
import dev.strafemc.server.StrafeSettings;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * CS bots in Minecraft (first version): Minecraft's own mannequins (a player model with any player's skin, which every
 * client fetches and caches itself) moved and fought by the server. bot_quota of them (0 by default) on teams T / CT,
 * with CS2's default rifles (AK-47 / M4A4); they look for enemies (the other team's players and bots, or everyone with
 * strafemc_bot_ffa 1), turn to them, shoot in bursts with CS2's damage, range falloff, hit groups and armor, and
 * respawn a few seconds after dying on a random spawn block. FLAGGED: the behaviour (reaction time, turn speed,
 * spread, bursts, reload time, speed) is ours, not CS2's bot AI.
 */
public final class Bots {
	public static final String TAG = "strafemc_bot";
	private static final long RESPAWN_MS = 3000, RELOAD_MS = 2500;
	private static final float SPEED = 0.12f;

	/** Difficulty (bot_difficulty 1-10, 5 by default) as 0..1. */
	private static double skill() {
		return (StrafeSettings.botDifficulty() - 1) / 9.0;
	}

	// what the difficulty changes (FLAGGED: ours): reaction 700 -> 120 ms, turning 8 -> 35 deg a tick, spread x1.8 -> x0.4,
	// headshot tries 5% -> 50% of bursts
	private static long reactionMs() {
		return Math.round(700 - 580 * skill());
	}

	private static float turnDeg() {
		return (float) (6 + 18 * skill());
	}

	/** How far it sees: 30 blocks at difficulty 1 to 70 at 10 (FLAGGED: ours). */
	private static double sight() {
		return 30 + 40 * skill();
	}

	/** Half its field of view: enemies further off where it looks go unseen unless heard (FLAGGED: ours). */
	private static final double FOV_COS = Math.cos(Math.toRadians(65));
	private static final double HEARING = 25;

	/** A shot: bots within earshot know where the shooter is (even behind them). */
	public static synchronized void noise(LivingEntity source) {
		long now = System.currentTimeMillis();
		for (Bot b : BOTS)
			if (b.e != null && b.e.isAlive() && b.e.level() == source.level() && enemy(b, source) && b.e.distanceToSqr(source) < HEARING * HEARING) {
				b.aware = source;
				b.lastKnown = source.position();
				b.awareUntil = now + 8000;
			}
	}

	private static double spreadScale() {
		return 1.8 - 1.4 * skill();
	}


	private static final class Bot {
		/** Whose skin it wears, and the name it shows (the skin's owner, numbered when several bots share a skin). */
		final String name, label;
		final boolean t;
		Mannequin e;
		long deadSince, seenSince, nextShot, reloadUntil, burstPause, strafeUntil, wanderUntil;
		LivingEntity target;
		int clip = -1, burstLeft;
		float strafe, wanderYaw;
		boolean aimHead;
		/** This tick's walking direction (horizontal, unit) and whether the next path step goes up a block. */
		Vec3 move;
		boolean climb;
		/** CS2's fire penalty (inaccuracy added per shot, recovering), the spray's climb (degrees), the aim's error (blocks). */
		double penalty, recoil, recoilYaw;
		long lastShot;
		Vec3 aimErr = Vec3.ZERO;
		/** Someone it knows about without seeing them now (heard, shot by, last seen) and where: it goes there. */
		LivingEntity aware;
		Vec3 lastKnown;
		long awareUntil;
		/** Where it's walking: the path (feet blocks), how far along, where it leads, when it was worked out. */
		List<net.minecraft.core.BlockPos> path = List.of();
		int pathIdx;
		net.minecraft.core.BlockPos pathGoal;
		long pathAt;

		Bot(String name, boolean t) {
			this.name = name;
			this.t = t;
			long same = BOTS.stream().filter(o -> o.name.equals(name)).count();
			this.label = same == 0 ? name : name + " " + (same + 1);
		}
	}

	private static final List<Bot> BOTS = new ArrayList<>();
	private static final Random RNG = new Random();

	private Bots() {}

	private static java.util.Set<String> names() {
		java.util.Set<String> s = new java.util.HashSet<>();
		for (Bot b : BOTS) s.add(b.name);
		return s;
	}

	public static boolean isBot(Entity e) {
		return e instanceof Mannequin && e.entityTags().contains(TAG);
	}

	/** Every server tick: keep bot_quota bots, respawn the dead, think for the living. */
	public static synchronized void tick(MinecraftServer server) {
		int quota = StrafeSettings.botQuota();
		while (BOTS.size() < quota) {
			long t = BOTS.stream().filter(b -> b.t).count();
			BOTS.add(new Bot(BotNames.next(names()), t * 2 < BOTS.size() + 1));
		}
		while (BOTS.size() > quota) {
			Bot b = BOTS.remove(BOTS.size() - 1);
			if (b.e != null) b.e.discard();
		}
		long now = System.currentTimeMillis();
		for (Bot b : BOTS) {
			if (b.e == null || b.e.isRemoved() || !b.e.isAlive()) {
				if (b.e != null && b.deadSince == 0) b.deadSince = now;
				if (b.e == null || now - b.deadSince > RESPAWN_MS) spawn(server, b);
				continue;
			}
			try {
				think(b, (ServerLevel) b.e.level(), now);
			} catch (RuntimeException ex) {
				dev.strafemc.StrafeMc.LOG.warn("[strafemc] bot {}: {}", b.name, ex.toString());
			}
		}
	}

	/** bot_add: one more (on a given team, or the smaller one). */
	public static synchronized void add(Boolean t) {
		StrafeSettings.setBotQuota(StrafeSettings.botQuota() + 1);
		long ts = BOTS.stream().filter(b -> b.t).count();
		BOTS.add(new Bot(BotNames.next(names()), t != null ? t : ts * 2 < BOTS.size() + 1));
	}

	/** bot_kick: all of them, and the quota to 0. */
	public static synchronized void kickAll() {
		StrafeSettings.setBotQuota(0);
		for (Bot b : BOTS) if (b.e != null) b.e.discard();
		BOTS.clear();
	}

	/** A bot loaded from the world that this server didn't make (left over from before a restart): gone. */
	public static synchronized void onLoad(Entity e) {
		if (!isBot(e)) return;
		for (Bot b : BOTS) if (b.e == e) return;
		e.discard();
	}

	private static void spawn(MinecraftServer server, Bot b) {
		var pick = StrafeSettings.spawnBlocks() ? dev.strafemc.world.SpawnPoints.pick() : null; // like players: spawn blocks when they're on
		ServerLevel level = pick != null ? pick.level() : server.overworld();
		Vec3 at;
		if (pick != null) at = pick.pos();
		else {
			var spawn = level.getRespawnData().pos();
			at = Vec3.atBottomCenterOf(level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
					spawn.offset(RNG.nextInt(17) - 8, 0, RNG.nextInt(17) - 8)));
		}
		Mannequin m = new Mannequin(EntityType.MANNEQUIN, level);
		m.setComponent(DataComponents.PROFILE, ResolvableProfile.createUnresolved(b.name));
		((dev.strafemc.mixin.MannequinAccessor) m).strafemc$setHideDescription(true);
		m.setCustomName(Component.literal("BOT " + b.label));
		m.addTag(TAG);
		m.snapTo(at.x, at.y, at.z, RNG.nextFloat() * 360, 0);
		m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(WeaponItems.GUNS.get(weapon(b))));
		CsTeam.set(m, b.t);
		b.e = m; // before it's added: adding fires the load event, which removes bots that aren't ours
		level.addFreshEntity(m);
		dev.strafemc.StrafeMc.LOG.info("[strafemc] bot {} spawned {} at {}", b.label, pick != null ? "on a spawn block" : "at the world spawn", m.blockPosition().toShortString());
		b.deadSince = 0;
		b.target = null;
		b.clip = -1;
	}

	/** CS2's default rifles: AK-47 for T, M4A4 for CT. */
	private static String weapon(Bot b) {
		return b.t ? "weapon_ak47" : "weapon_m4a1";
	}

	private static boolean enemy(Bot b, LivingEntity o) {
		if (o == b.e || !o.isAlive() || o.isSpectator()) return false;
		boolean ffa = StrafeSettings.botFfa();
		if (o instanceof ServerPlayer p) return !p.isCreative() && (ffa || CsTeam.isT(p) != b.t);
		if (isBot(o)) return ffa || CsTeam.isT(o) != b.t;
		return false;
	}

	private static void think(Bot b, ServerLevel level, long now) {
		Mannequin e = b.e;
		WeaponStats w = GunServer.stats(weapon(b));
		if (w == null) return;
		if (b.clip < 0) b.clip = w.clip();
		Vec3 eye = e.getEyePosition();
		// hit by someone: it knows where they are
		if (e.getLastHurtByMob() != null && e.tickCount - e.getLastHurtByMobTimestamp() < 40 && enemy(b, e.getLastHurtByMob())) {
			b.aware = e.getLastHurtByMob();
			b.lastKnown = b.aware.position();
			b.awareUntil = now + 8000;
		}
		// the target: the nearest enemy in sight (range by difficulty, within its field of view unless it knows about them)
		LivingEntity seen = null;
		double range = sight(), seenD = range * range;
		Vec3 look = Vec3.directionFromRotation(e.getXRot(), e.getYRot());
		for (LivingEntity o : level.getEntitiesOfClass(LivingEntity.class, e.getBoundingBox().inflate(range), o -> enemy(b, o))) {
			double d = o.distanceToSqr(e);
			if (d >= seenD) continue;
			boolean known = o == b.target || (o == b.aware && now < b.awareUntil);
			if (!known && o.getEyePosition().subtract(eye).normalize().dot(look) < FOV_COS) continue;
			if (visible(level, e, eye, o)) {
				seenD = d;
				seen = o;
			}
		}
		if (seen != null) {
			b.lastKnown = seen.position();
			b.awareUntil = now + 8000;
		}
		if (now >= b.awareUntil) {
			b.aware = null;
			b.lastKnown = null;
		}
		if (seen != b.target) {
			b.target = seen;
			b.seenSince = now;
			b.burstLeft = 0;
			// first sight: off by up to ~1.6 blocks at difficulty 1, ~0.2 at 10, corrected over time (FLAGGED: ours)
			double err = 0.2 + 1.4 * (1 - skill());
			b.aimErr = new Vec3(RNG.nextGaussian(), RNG.nextGaussian() * 0.6, RNG.nextGaussian()).normalize().scale(err * (0.5 + RNG.nextDouble()));
		}
		b.aimErr = b.aimErr.scale(1 - (0.02 + 0.08 * skill())); // tracking: the error shrinks every tick
		// recoil and the fire penalty recover between shots (CS2: back to 10% over the stand recovery time)
		double recover = Math.pow(0.1, 0.05 / Math.max(0.05, w.recoveryTimeStand()));
		b.penalty *= recover;
		if (now - b.lastShot > 150) {
			b.recoil *= 0.8;
			b.recoilYaw *= 0.8;
		}
		float forward = 0, side = 0;
		if (seen != null) {
			if (b.burstLeft <= 0 && now >= b.burstPause) { // a new burst: 3-6 shots, the head one time in four
				b.burstLeft = 3 + RNG.nextInt(4);
				b.aimHead = RNG.nextDouble() < 0.05 + 0.45 * skill();
			}
			Vec3 aim = (b.aimHead ? seen.getEyePosition() : seen.getBoundingBox().getCenter().add(0, seen.getBbHeight() * 0.1, 0)).add(b.aimErr);
			// it has to notice first: no turning until its reaction time has passed
			boolean aimed = now - b.seenSince >= reactionMs() && turn(e, aim.subtract(eye));
			boolean firing = b.burstLeft > 0 && now >= b.burstPause;
			if (firing && aimed && now - b.seenSince >= reactionMs() && now >= b.nextShot && now >= b.reloadUntil) {
				// the shot leaves along the view plus the spray's climb the bot didn't pull down
				shoot(b, level, w, eye, Vec3.directionFromRotation((float) (e.getXRot() - b.recoil), (float) (e.getYRot() + b.recoilYaw)), now,
						e.getDeltaMovement().horizontalDistanceSqr() > 0.001);
				if (--b.burstLeft <= 0) b.burstPause = now + 250 + RNG.nextInt(250);
			}
			// standing still while shooting (as CS players stop to shoot); between bursts, closing in along a path or strafing
			if (!firing) {
				if (Math.sqrt(seenD) > 15) walk(b, level, seen.blockPosition(), now);
				else {
					if (now >= b.strafeUntil) {
						b.strafe = RNG.nextBoolean() ? 1 : -1;
						b.strafeUntil = now + 400 + RNG.nextInt(600);
					}
					side = b.strafe;
				}
			}
		} else if (b.lastKnown != null) { // where it last saw or heard an enemy (no knowing where anyone is through walls)
			walk(b, level, net.minecraft.core.BlockPos.containing(b.lastKnown), now);
			if (b.move == null && e.position().distanceToSqr(b.lastKnown) < 4) b.awareUntil = 0; // got there: nobody
			faceMove(e);
		} else { // wander to random spots
			if (now >= b.wanderUntil || b.pathGoal == null) {
				b.pathGoal = e.blockPosition().offset(RNG.nextInt(25) - 12, 0, RNG.nextInt(25) - 12);
				b.wanderUntil = now + 4000 + RNG.nextInt(4000);
				b.pathAt = 0;
			}
			walk(b, level, b.pathGoal, now);
			faceMove(e);
		}
		e.setSpeed(SPEED);
		if (b.move != null) { // the path's direction, as forward / sideways input for where the bot faces
			double yaw = Math.toRadians(e.getYRot()), c = Math.cos(yaw), sn = Math.sin(yaw);
			forward = (float) (-b.move.x * sn + b.move.z * c);
			side = (float) (b.move.x * c + b.move.z * sn);
		}
		e.zza = forward;
		e.xxa = side;
		// a jump only where the path climbs (or when stuck against something low); the mannequin has no jump of its own
		boolean climb = b.move != null && b.climb;
		if (e.onGround() && (climb || (e.horizontalCollision && b.move != null))) {
			Vec3 v = e.getDeltaMovement();
			e.setDeltaMovement(v.x, 0.42, v.z);
		}
		b.move = null;
		b.climb = false;
	}

	/** Walks along a path to goal (worked out again every second, or when the goal moved): sets this tick's direction. */
	private static void walk(Bot b, ServerLevel level, net.minecraft.core.BlockPos goal, long now) {
		Mannequin e = b.e;
		if (b.path.isEmpty() || now - b.pathAt > 1000 || b.pathGoal == null || b.pathGoal.distManhattan(goal) > 2) {
			b.path = BotPath.find(level, e.blockPosition(), goal);
			b.pathIdx = 0;
			b.pathGoal = goal;
			b.pathAt = now;
		}
		// past the steps it has reached (within a third of a block across, on its level)
		while (b.pathIdx < b.path.size()) {
			var p = b.path.get(b.pathIdx);
			double dx = p.getX() + 0.5 - e.getX(), dz = p.getZ() + 0.5 - e.getZ();
			if (dx * dx + dz * dz < 0.12 && Math.abs(p.getY() - e.getY()) < 1.2) b.pathIdx++;
			else break;
		}
		if (b.pathIdx >= b.path.size()) return;
		var p = b.path.get(b.pathIdx);
		Vec3 d = new Vec3(p.getX() + 0.5 - e.getX(), 0, p.getZ() + 0.5 - e.getZ());
		if (d.lengthSqr() < 1e-4) return;
		b.move = d.normalize();
		b.climb = p.getY() > e.getBlockY() && d.lengthSqr() < 1.6 * 1.6;
	}

	/** Faces where it walks (no one to look at). */
	private static void faceMove(Mannequin e) {
		Bot b = null;
		for (Bot o : BOTS) if (o.e == e) b = o;
		if (b != null && b.move != null) turnYaw(e, (float) (Mth.atan2(b.move.z, b.move.x) * Mth.RAD_TO_DEG) - 90, 0);
	}

	private static boolean visible(ServerLevel level, Entity from, Vec3 eye, LivingEntity o) {
		for (Vec3 p : new Vec3[] {o.getEyePosition(), o.getBoundingBox().getCenter()})
			if (level.clip(new ClipContext(eye, p, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, from)).getType() == HitResult.Type.MISS) return true;
		return false;
	}

	/** Turns toward a direction, at most TURN_DEG a tick; true once it's within a couple of degrees. */
	private static boolean turn(Mannequin e, Vec3 d) {
		float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90, pitch = (float) -(Mth.atan2(d.y, d.horizontalDistance()) * Mth.RAD_TO_DEG);
		return turnYaw(e, yaw, pitch);
	}

	private static boolean turnYaw(Mannequin e, float yaw, float pitch) {
		float dy = Mth.wrapDegrees(yaw - e.getYRot()), dp = pitch - e.getXRot();
		float turn = turnDeg();
		float ny = e.getYRot() + Mth.clamp(dy, -turn, turn), np = e.getXRot() + Mth.clamp(dp, -turn, turn);
		e.setYRot(ny);
		e.setXRot(np);
		e.setYHeadRot(ny);
		e.yBodyRot = ny;
		return Math.abs(dy) < 2.5 && Math.abs(dp) < 2.5;
	}

	private static void shoot(Bot b, ServerLevel level, WeaponStats w, Vec3 eye, Vec3 dir, long now, boolean moving) {
		Mannequin e = b.e;
		double units = StrafeConfig.unitsPerBlock;
		// CS2's spread: the inaccuracy (standing / moving, plus the fire penalty) and the weapon's spread, each a random
		// distance in a random direction, as offsets across the view
		double inacc = (moving ? w.inaccuracyMove() : w.inaccuracyStand()) + b.penalty;
		inacc *= spreadScale(); // the difficulty's hand: x1.8 at 1 to x0.4 at 10 (FLAGGED: ours)
		double a1 = RNG.nextDouble() * Math.PI * 2, r1 = RNG.nextDouble() * inacc, a2 = RNG.nextDouble() * Math.PI * 2, r2 = RNG.nextDouble() * w.spread();
		Vec3 side = dir.cross(new Vec3(0, 1, 0));
		if (side.lengthSqr() < 1e-6) side = new Vec3(1, 0, 0);
		side = side.normalize();
		Vec3 up = side.cross(dir).normalize();
		dir = dir.add(side.scale(Math.cos(a1) * r1 + Math.cos(a2) * r2)).add(up.scale(Math.sin(a1) * r1 + Math.sin(a2) * r2)).normalize();
		b.penalty += w.inaccuracyFire();
		// the spray climbs about a degree a shot (FLAGGED: CS2's per-gun patterns not used); better bots pull it down
		b.recoil = Math.min(12, b.recoil + 0.9 * (1 - 0.75 * skill()));
		b.recoilYaw += (RNG.nextDouble() - 0.5) * 0.6;
		b.lastShot = now;
		Vec3 end = eye.add(dir.scale(Math.min(w.range() / units, 160)));
		HitResult block = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e));
		if (block.getType() != HitResult.Type.MISS) end = block.getLocation();
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(e, eye, end, new AABB(eye, end).inflate(1.0),
				o -> o != e && o.isPickable() && !o.isSpectator() && o instanceof LivingEntity, eye.distanceToSqr(end));
		sound(level, e, GunServer.shootSound(w, 0));
		noise(e); // other bots hear it too
		if (hit != null && hit.getEntity() instanceof LivingEntity target) damage(b, level, w, eye, target, hit.getLocation());
		else if (block instanceof net.minecraft.world.phys.BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK) {
			var msg = GunServer.impactMessage(level, bh, eye, RNG.nextInt());
			for (ServerPlayer o : level.players())
				if (o.distanceToSqr(bh.getLocation()) < 96 * 96 && ServerPlayNetworking.canSend(o, dev.strafemc.net.StrafePayloads.BulletImpact.TYPE))
					ServerPlayNetworking.send(o, msg);
		}
		b.nextShot = now + (long) (w.cycleTime() * 1000);
		if (--b.clip <= 0) {
			b.clip = w.clip();
			b.reloadUntil = now + RELOAD_MS;
		}
	}

	/** CS2's damage: range falloff, the hit group's multiplier, armor; the kill goes in the killfeed. */
	private static void damage(Bot b, ServerLevel level, WeaponStats w, Vec3 eye, LivingEntity target, Vec3 at) {
		double units = StrafeConfig.unitsPerBlock;
		double dmg = w.damage() * Math.pow(w.rangeModifier(), at.distanceTo(eye) * units / GunServer.FALLOFF_UNITS);
		String group = GunServer.hitgroup(target.getBoundingBox(), target.getEyeY() - target.getBoundingBox().minY, at);
		dmg *= switch (group) {
			case "head" -> w.headshotMultiplier();
			case "stomach" -> GunServer.STOMACH_MULT;
			case "legs" -> GunServer.LEG_MULT;
			default -> 1.0;
		};
		dmg = CsArmor.apply(target, dmg, w.armorRatio(), group);
		Vec3 motion = target.getDeltaMovement();
		boolean marked = target.hurtMarked, wasAlive = target.isAlive();
		target.invulnerableTime = 0;
		target.hurtServer(level, level.damageSources().mobAttack(b.e), (float) (dmg * StrafeConfig.hpScale));
		if (!StrafeSettings.mcKnockback()) {
			target.setDeltaMovement(motion);
			target.hurtMarked = marked;
		}
		if (wasAlive && target.isDeadOrDying())
			KillFeed.notice(b.e, target, weapon(b).replace("weapon_", ""), group.equals("head") ? KillFeed.HEADSHOT : 0);
	}

	private static void sound(ServerLevel level, Entity e, String event) {
		if (event == null) return;
		var msg = new dev.strafemc.net.StrafePayloads.CsSound(event, e.getX(), e.getEyeY(), e.getZ(), false, e.getId());
		for (ServerPlayer o : level.players())
			if (o.distanceToSqr(e) < 96 * 96 && ServerPlayNetworking.canSend(o, dev.strafemc.net.StrafePayloads.CsSound.TYPE))
				ServerPlayNetworking.send(o, msg);
	}
}
