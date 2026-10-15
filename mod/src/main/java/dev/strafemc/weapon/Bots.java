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
	private static final double SIGHT = 80, CHASE = 160; // blocks
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
		return (float) (8 + 27 * skill());
	}

	private static double spreadScale() {
		return 1.8 - 1.4 * skill();
	}

	private static final double SPREAD_DEG = 0.9, MOVING_SPREAD_DEG = 2.5;

	private static final class Bot {
		final String name;
		final boolean t;
		Mannequin e;
		long deadSince, seenSince, nextShot, reloadUntil, burstPause, strafeUntil, wanderUntil;
		LivingEntity target;
		int clip = -1, burstLeft;
		float strafe, wanderYaw;
		boolean aimHead;

		Bot(String name, boolean t) {
			this.name = name;
			this.t = t;
		}
	}

	private static final List<Bot> BOTS = new ArrayList<>();
	private static final Random RNG = new Random();

	private Bots() {}

	public static boolean isBot(Entity e) {
		return e instanceof Mannequin && e.entityTags().contains(TAG);
	}

	/** Every server tick: keep bot_quota bots, respawn the dead, think for the living. */
	public static synchronized void tick(MinecraftServer server) {
		int quota = StrafeSettings.botQuota();
		while (BOTS.size() < quota) {
			long t = BOTS.stream().filter(b -> b.t).count();
			BOTS.add(new Bot(BotNames.next(), t * 2 < BOTS.size() + 1 ? true : false));
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
		BOTS.add(new Bot(BotNames.next(), t != null ? t : ts * 2 < BOTS.size() + 1));
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
		var pick = dev.strafemc.world.SpawnPoints.pick();
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
		m.setCustomName(Component.literal("BOT " + b.name));
		m.addTag(TAG);
		m.snapTo(at.x, at.y, at.z, RNG.nextFloat() * 360, 0);
		m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(WeaponItems.GUNS.get(weapon(b))));
		CsTeam.set(m, b.t);
		level.addFreshEntity(m);
		b.e = m;
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
		// the target: the nearest enemy it can see; else the nearest anywhere, to walk to
		LivingEntity seen = null, far = null;
		double seenD = SIGHT * SIGHT, farD = CHASE * CHASE;
		for (LivingEntity o : level.getEntitiesOfClass(LivingEntity.class, e.getBoundingBox().inflate(CHASE), o -> enemy(b, o))) {
			double d = o.distanceToSqr(e);
			if (d < farD) {
				farD = d;
				far = o;
			}
			if (d < seenD && visible(level, e, eye, o)) {
				seenD = d;
				seen = o;
			}
		}
		if (seen != b.target) {
			b.target = seen;
			b.seenSince = now;
			b.burstLeft = 0;
		}
		float forward = 0, side = 0;
		if (seen != null) {
			if (b.burstLeft <= 0 && now >= b.burstPause) { // a new burst: 3-6 shots, the head one time in four
				b.burstLeft = 3 + RNG.nextInt(4);
				b.aimHead = RNG.nextDouble() < 0.05 + 0.45 * skill();
			}
			Vec3 aim = b.aimHead ? seen.getEyePosition() : seen.getBoundingBox().getCenter().add(0, seen.getBbHeight() * 0.1, 0);
			boolean aimed = turn(e, aim.subtract(eye));
			boolean firing = b.burstLeft > 0 && now >= b.burstPause;
			if (firing && aimed && now - b.seenSince >= reactionMs() && now >= b.nextShot && now >= b.reloadUntil) {
				shoot(b, level, w, eye, Vec3.directionFromRotation(e.getXRot(), e.getYRot()), now, e.getDeltaMovement().horizontalDistanceSqr() > 0.001);
				if (--b.burstLeft <= 0) b.burstPause = now + 250 + RNG.nextInt(250);
			}
			// standing still while shooting (as CS players stop to shoot); between bursts, closing in or strafing
			if (!firing) {
				if (Math.sqrt(seenD) > 15) forward = 1;
				else {
					if (now >= b.strafeUntil) {
						b.strafe = RNG.nextBoolean() ? 1 : -1;
						b.strafeUntil = now + 400 + RNG.nextInt(600);
					}
					side = b.strafe;
				}
			}
		} else if (far != null) { // walk toward the nearest enemy
			turn(e, far.getEyePosition().subtract(eye));
			forward = 1;
		} else { // wander
			if (now >= b.wanderUntil) {
				b.wanderYaw = e.getYRot() + RNG.nextFloat() * 180 - 90;
				b.wanderUntil = now + 2000 + RNG.nextInt(3000);
			}
			turnYaw(e, b.wanderYaw, 0);
			forward = 0.6f;
		}
		e.setSpeed(SPEED);
		e.zza = forward;
		e.xxa = side;
		e.setJumping(e.horizontalCollision && e.onGround() && (forward != 0 || side != 0));
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
		// spread: a small random cone, wider on the move (FLAGGED: ours)
		double spread = Math.toRadians((moving ? MOVING_SPREAD_DEG : SPREAD_DEG) * spreadScale() * Math.abs(RNG.nextGaussian()));
		double turnA = RNG.nextDouble() * Math.PI * 2;
		Vec3 side = dir.cross(new Vec3(0, 1, 0));
		if (side.lengthSqr() < 1e-6) side = new Vec3(1, 0, 0);
		side = side.normalize();
		Vec3 up = side.cross(dir).normalize();
		dir = dir.add(side.scale(Math.cos(turnA) * Math.tan(spread))).add(up.scale(Math.sin(turnA) * Math.tan(spread))).normalize();
		Vec3 end = eye.add(dir.scale(Math.min(w.range() / units, 160)));
		HitResult block = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e));
		if (block.getType() != HitResult.Type.MISS) end = block.getLocation();
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(e, eye, end, new AABB(eye, end).inflate(1.0),
				o -> o != e && o.isPickable() && !o.isSpectator() && o instanceof LivingEntity, eye.distanceToSqr(end));
		sound(level, e, GunServer.shootSound(w, 0));
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
