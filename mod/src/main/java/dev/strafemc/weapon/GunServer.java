package dev.strafemc.weapon;

import dev.strafemc.StrafeMc;
import dev.strafemc.bridge.Cs2Bridge;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.data.AnimClip;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative CS2 gun logic on the Minecraft server: deploy time, fire rate, magazine ammo, reloads timed by the real
 * reload animation's WPN_RELOAD_ADD_AMMO event, hitscan with CS2 range falloff and headshot multiplier, damage through
 * Minecraft's own damage system (CS damage x hpScale).
 */
public final class GunServer {
	/** CS range falloff: damage *= rangeModifier ^ (distance / 500 units) (Source/CS:GO; not exposed by CS2 data). */
	public static final double FALLOFF_UNITS = 500.0;
	/** Hitgroup multipliers besides the weapon's own headshot multiplier (Source/CS:GO values; flagged, not in CS2 data). */
	public static final double STOMACH_MULT = 1.25, LEG_MULT = 0.75;

	private static final Map<String, WeaponStats> STATS = new ConcurrentHashMap<>();
	private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

	private static final class State {
		ItemStack lastHeld = ItemStack.EMPTY;
		/** The last gun held (a decoy fakes its shots). */
		String lastGun = "weapon_ak47";
		long deployUntil, nextFire, lastTick, knifeNextLight, knifeNextHeavy;
		/** Current reload in animation time (null = not reloading); see ReloadTimeline for CS2 silent reload. */
		ReloadTimeline reload;
		double addAmmoAt, lockout;
		boolean ammoAdded, reloadKeyHeld;
		/** Reload foley other players will hear (event, clip time). */
		final java.util.List<Object[]> pendingSounds = new java.util.ArrayList<>();

		boolean reloadLocked() {
			return reload != null && reload.animTime < lockout;
		}
	}

	private static final java.util.Random IMPACT_SEEDS = new java.util.Random();

	/** The impact as the client draws it: on slope blocks (surf ramps, straight stairs) the hit and normal follow the slope. */
	public static dev.strafemc.net.StrafePayloads.BulletImpact impactMessage(net.minecraft.world.level.Level level,
																			 net.minecraft.world.phys.BlockHitResult bh, Vec3 eye, int seed) {
		return impactMessage(level, bh, eye, seed, false);
	}

	public static dev.strafemc.net.StrafePayloads.BulletImpact impactMessage(net.minecraft.world.level.Level level,
																			 net.minecraft.world.phys.BlockHitResult bh, Vec3 eye, int seed, boolean knife) {
		var state = level.getBlockState(bh.getBlockPos());
		Vec3 at = bh.getLocation(), n = Vec3.atLowerCornerOf(bh.getDirection().getUnitVec3i());
		Vec3[] slope = dev.strafemc.world.SlopeHit.hit(level, bh.getBlockPos(), eye, at.subtract(eye).normalize());
		if (slope != null) {
			at = slope[0];
			n = slope[1];
		}
		return new dev.strafemc.net.StrafePayloads.BulletImpact(at.x, at.y, at.z, bh.getDirection().get3DDataValue(), bh.getBlockPos().asLong(),
				dev.strafemc.world.SurfaceMap.surface(state), seed, (float) n.x, (float) n.y, (float) n.z, knife);
	}

	/** Bullet hit a block: other nearby players get the decal + the surface's impact sound (the shooter predicts his own). */
	private static void impact(ServerPlayer p, net.minecraft.world.phys.BlockHitResult bh) {
		impact(p, bh, false);
	}

	/** A bullet or knife hit on a block: everyone else sees it (the shooter drew their own prediction). */
	private static void impact(ServerPlayer p, net.minecraft.world.phys.BlockHitResult bh, boolean knife) {
		var hitState = p.level().getBlockState(bh.getBlockPos());
		if (hitState.is(net.minecraft.world.level.block.Blocks.DRAGON_EGG)) hitState.useWithoutItem(p.level(), p, bh); // it teleports, as when clicked
		var msg = impactMessage(p.level(), bh, p.getEyePosition(), IMPACT_SEEDS.nextInt(), knife);
		for (ServerPlayer o : p.level().players())
			if (o != p && o.distanceToSqr(bh.getLocation()) < SOUND_SEND_RANGE * SOUND_SEND_RANGE
					&& net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(o, dev.strafemc.net.StrafePayloads.BulletImpact.TYPE))
				net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(o, msg);
	}

	/** How far other players get shot/reload sounds sent (blocks); the CS2 distance curves fade them well before this. */
	private static final double SOUND_SEND_RANGE = 96;

	/** Sends a CS2 sound event at p's eye to every other player nearby (the shooter plays its own sounds locally). */
	static void broadcast(ServerPlayer p, String event) {
		broadcast(p, event, false);
	}

	static void broadcast(ServerPlayer p, String event, boolean suppressed) {
		if (event == null) return;
		var msg = new dev.strafemc.net.StrafePayloads.CsSound(event, p.getX(), p.getEyeY(), p.getZ(), suppressed, p.getId());
		for (ServerPlayer o : p.level().players())
			if (o != p && o.distanceToSqr(p) < SOUND_SEND_RANGE * SOUND_SEND_RANGE
					&& net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(o, dev.strafemc.net.StrafePayloads.CsSound.TYPE))
				net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(o, msg);
	}

	private GunServer() {}

	public static WeaponStats stats(String cls) {
		WeaponStats s = STATS.get(cls);
		if (s == null) {
			s = WeaponStats.of(cls);
			if (s != null) STATS.put(cls, s);
		}
		return s;
	}

	/** First-person animation clips of a weapon (folder/suffix mapping; derived from the viewmodel graph later). */
	public static String clipPath(String cls, String anim) {
		return switch (cls) {
			case "weapon_ak47" -> "animation/anims/viewmodel/rifle/rifle_ak/" + anim + "_ak.vnmclip_c";
			case "weapon_knife_falchion" -> "animation/anims/viewmodel/knife/knife_falchion/" + (anim.equals("idle") ? "idle1" : anim) + "_falchion.vnmclip_c";
			default -> {
				Grenade g = Grenade.of(cls);
				yield g == null ? null : g.clipPath(anim);
			}
		};
	}

	/** A CS2 sound at a position for every player in range, following an entity (bounces follow the grenade). */
	static void soundAt(net.minecraft.server.level.ServerLevel level, String event, Vec3 at, Entity follow) {
		var msg = new dev.strafemc.net.StrafePayloads.CsSound(event, at.x, at.y, at.z, false, follow == null ? -1 : follow.getId());
		for (ServerPlayer o : level.players())
			if (o.distanceToSqr(at) < SOUND_SEND_RANGE * SOUND_SEND_RANGE
					&& net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(o, dev.strafemc.net.StrafePayloads.CsSound.TYPE))
				net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(o, msg);
	}

	/**
	 * Throws the held grenade like CS: speed = clamp(m_flThrowVelocity x 0.9, 15, 750) x (0.3 + 0.7 x strength), aimed
	 * 10 degrees higher at the horizon (pitch -= (90 - |pitch|) x 10 / 90), from the eyes lowered by 12 units for an
	 * underhand throw, plus 1.25 x the thrower's velocity. FLAGGED: CS:GO's throw code as we know it.
	 */
	public static void throwGrenade(ServerPlayer p, dev.strafemc.net.StrafePayloads.Throw t) {
		ItemStack held = p.getMainHandItem();
		if (!(held.getItem() instanceof GrenadeItem gi)) return;
		State st = state(p);
		long now = System.nanoTime();
		if (now < st.deployUntil - 120_000_000L) return;
		java.util.Map<String, Object> d = dev.strafemc.data.WeaponData.get(gi.type.cls);
		double throwVel = d != null && d.get("m_flThrowVelocity") instanceof Number n ? n.doubleValue() : 750;
		GrenadeFlight.Launch l = GrenadeFlight.launch(p.level(), p, p.getEyePosition(), t.yaw(), t.pitch(), t.strength(),
				new Vec3(t.vx(), t.vy(), t.vz()), throwVel);
		Vec3 start = l.start(), vel = l.vel();
		GrenadeEntity thrown = new GrenadeEntity(p.level(), p, held.copyWithCount(1), start, vel);
		thrown.decoyGun = st.lastGun;
		p.level().addFreshEntity(thrown);
		if (!p.getAbilities().instabuild) held.shrink(1);
		broadcast(p, gi.type.sounds + ".Throw");
		st.deployUntil = now + sec(0.4); // the next one comes out after the throw
	}

	static int infiniteAmmo() {
		String v = Cs2Bridge.INSTANCE.cvar("sv_infinite_ammo");
		try {
			return v == null ? 0 : (int) Double.parseDouble(v);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static State state(ServerPlayer p) {
		return STATES.computeIfAbsent(p.getUUID(), u -> new State());
	}

	/** Called every server tick: deploy on weapon switch, reload completion. */
	public static void tick(MinecraftServer server) {
		long now = System.nanoTime();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			State st = state(p);
			if (p.getMainHandItem().getItem() instanceof GunItem gun && !(gun instanceof KnifeItem) && !(gun instanceof GrenadeItem)) st.lastGun = gun.weaponClass;
			ItemStack held = p.getMainHandItem();
			if (held != st.lastHeld && !ItemStack.isSameItem(held, st.lastHeld)) {
				st.lastHeld = held;
				st.reload = null;
				st.pendingSounds.clear();
				if (held.getItem() instanceof GunItem g && g.stats() != null) st.deployUntil = now + sec(g.stats().deployDuration());
			} else {
				st.lastHeld = held;
			}
			double dt = st.lastTick == 0 ? 0 : Math.min(0.25, (now - st.lastTick) / 1e9);
			st.lastTick = now;
			ReloadTimeline r = st.reload;
			if (r != null) {
				r.advance(now / 1e9, dt, st.reloadKeyHeld);
				for (var it = st.pendingSounds.iterator(); it.hasNext(); ) {
					Object[] s = it.next();
					if (r.animTime < (Double) s[1]) continue;
					it.remove();
					broadcast(p, (String) s[0], r.stealthy); // stealthy (silent reload): suppressed foley
				}
				if (!st.ammoAdded && r.animTime >= st.addAmmoAt) {
					st.ammoAdded = true;
					if (held.getItem() instanceof GunItem g) finishReload(p, held, g);
				}
				if (st.ammoAdded && r.animTime >= st.lockout && st.pendingSounds.isEmpty()) st.reload = null;
			}
		}
	}

	public static void fire(ServerPlayer p, dev.strafemc.net.StrafePayloads.Fire f) {
		ItemStack held = p.getMainHandItem();
		WeaponStats w = held.getItem() instanceof GunItem gi && !(gi instanceof KnifeItem) ? gi.stats() : null;
		if (w == null) {
			ServerShots.skip(p, f.shot());
			return;
		}
		GunItem g = (GunItem) held.getItem();
		State st = state(p);
		long now = System.nanoTime(), tolerance = 5_000_000L; // 5 ms for network jitter
		int clip = g.clip(held), inf = infiniteAmmo();
		if (now < st.deployUntil - tolerance || st.reloadLocked() || now < st.nextFire - tolerance || (clip <= 0 && inf != 1)) {
			ServerShots.skip(p, f.shot()); // a rejected shot still uses up its number, so client and server stay in step
			return;
		}
		st.nextFire = Math.max(now, st.nextFire) + sec(w.cycleTime());
		broadcast(p, w.shootSound());
		if (inf != 1) held.set(WeaponItems.CLIP, clip - 1);

		double units = StrafeConfig.unitsPerBlock;
		Vec3 eye = p.getEyePosition();
		ServerShots.Result shot = ServerShots.resolve(p, w, f); // the server decides spread + recoil (the client only predicted)
		Vec3 dir = shot.dir();
		// the bullet's path: through up to 4 thin surfaces (wallbangs), losing damage in each (see penetrate)
		boolean lag = dev.strafemc.server.StrafeSettings.lagComp();
		Vec3 from = eye;
		double dmg = w.damage(), left = w.range() / units, travelled = 0; // dmg: CS2's current damage, worn down by range and walls
		EntityHitResult hit = null;
		dev.strafemc.server.LagHistory.Hit rewound = null;
		if (shot.corrected() && net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, dev.strafemc.net.StrafePayloads.ShotCorrection.TYPE))
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new dev.strafemc.net.StrafePayloads.ShotCorrection(shot.shot()));
		for (int pen = 0; pen <= 4 && left > 0; pen++) {
			Vec3 end = from.add(dir.scale(left));
			HitResult block = p.level().clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
			if (block.getType() != HitResult.Type.MISS) end = block.getLocation();
			// living targets: rewound to what the shooter saw (lag compensation); everything else (crystals, dragon parts) as it is now
			Vec3 segFrom = from, segEnd = end;
			hit = ProjectileUtil.getEntityHitResult(p, segFrom, segEnd, new AABB(segFrom, segEnd).inflate(1.0),
					e -> e != p && e.isPickable() && !e.isSpectator() && !(lag && e instanceof LivingEntity), segFrom.distanceToSqr(segEnd));
			rewound = lag ? rewound(p, segFrom, segEnd, f.viewTick(), f.claim(), 0.05) : null;
			if (rewound != null && (hit == null || rewound.at().distanceToSqr(segFrom) < hit.getLocation().distanceToSqr(segFrom)))
				hit = new EntityHitResult(rewound.entity(), rewound.at());
			else rewound = null;
			if (hit != null || !(block instanceof net.minecraft.world.phys.BlockHitResult bh) || bh.getType() != HitResult.Type.BLOCK) break;
			if (pen == 0) impact(p, bh);
			else impactAll(p, bh); // past a wallbang: the shooter's own client didn't predict this one
			if (pen == 0 && shot.corrected() && net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, dev.strafemc.net.StrafePayloads.BulletImpact.TYPE))
				net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, impactMessage(p.level(), bh, eye, IMPACT_SEEDS.nextInt())); // the real hole
			double seg = bh.getLocation().distanceTo(from) * units;
			dmg *= Math.pow(w.rangeModifier(), seg / FALLOFF_UNITS); // range falloff up to the wall (CS2 applies it before penetrating)
			travelled += seg;
			double[] through = penetrate(p, bh, dir, w.penetration(), dmg, travelled);
			if (through == null) break;
			Vec3 exit = new Vec3(through[0], through[1], through[2]);
			dmg = through[3];
			travelled += exit.distanceTo(bh.getLocation()) * units;
			left -= exit.distanceTo(from);
			from = exit.add(dir.scale(0.01));
		}
		if (hit == null) return;
		Vec3 at = hit.getLocation();
		double distUnits = at.distanceTo(eye) * units;
		dmg *= Math.pow(w.rangeModifier(), at.distanceTo(from) * units / FALLOFF_UNITS);
		if (!(hit.getEntity() instanceof LivingEntity target)) { // dragon body parts, end crystals, boats...: Minecraft's own damage path
			hurtOther(p, hit.getEntity(), (float) (dmg * StrafeConfig.hpScale));
			return;
		}
		String group = rewound != null ? hitgroup(rewound.box(), rewound.eyeHeight(), at)
				: hitgroup(target.getBoundingBox(), target.getEyeY() - target.getBoundingBox().minY, at);
		dmg *= switch (group) {
			case "head" -> w.headshotMultiplier();
			case "stomach" -> STOMACH_MULT;
			case "legs" -> LEG_MULT;
			default -> 1.0;
		};
		boolean armored = CsArmor.protects(CsArmor.of(target), group);
		dmg = CsArmor.apply(target, dmg, w.armorRatio(), group);
		float mc = (float) (dmg * StrafeConfig.hpScale);
		target.invulnerableTime = 0; // 600 RPM: every bullet counts, like CS
		hurtCs(p, target, mc, w.cls());
		hitSounds(p, target, group.equals("head"), armored, target.isDeadOrDying());
		if (StrafeConfig.debug)
			StrafeMc.LOG.info("[strafemc] {} hit {} ({}) at {}u: {} CS dmg -> {} MC HP", w.cls(), target.getType().toShortString(), group,
					String.format("%.0f", distUnits), String.format("%.1f", dmg), String.format("%.2f", mc));
	}

	/**
	 * CS knife numbers. Not in CS2's weapons.vdata (the knife's m_nDamage 50 / m_flCycleTime are unused placeholders); these
	 * are CS:GO's weapon_knife values, believed unchanged in CS2 (FLAGGED): slash 40 (25 when chained within 0.4 s of being
	 * ready again), slash backstab 90, stab 65, stab backstab 180; reach 48 / 32 units; backstab when the attacker is behind
	 * the target (2D attacker-to-target direction dot target forward > 0.475).
	 */
	public static final double KNIFE_RANGE_LIGHT = 48, KNIFE_RANGE_HEAVY = 32, BACKSTAB_DOT = 0.475;
	/** Extra knife reach the server allows for network delay (units; ~0.4 blocks). */
	public static final double KNIFE_LAG_SLACK = 16;

	/** Seconds until the next {slash, stab} after: light miss, light hit, heavy miss, heavy hit (CS:GO, FLAGGED). */
	public static final double[][] KNIFE_COOLDOWN = {{0.4, 0.5}, {0.5, 0.5}, {1.0, 1.0}, {1.1, 1.1}};

	/** What a knife attack along the view hits: the entity (or null), else the block (or null); backstab for entities. */
	public record KnifeHit(Entity entity, net.minecraft.world.phys.BlockHitResult block, boolean backstab) {}

	/**
	 * slack: extra reach in units. The server checks with some (KNIFE_LAG_SLACK) because it sees targets where they were
	 * a moment ago; without it, hits the client showed (hit / backstab animation) were sometimes misses on the server.
	 */
	public static KnifeHit knifeTrace(net.minecraft.world.entity.player.Player p, boolean heavy, double slack) {
		double units = StrafeConfig.unitsPerBlock;
		Vec3 eye = p.getEyePosition(), end = eye.add(p.getLookAngle().scale(((heavy ? KNIFE_RANGE_HEAVY : KNIFE_RANGE_LIGHT) + slack) / units));
		HitResult block = p.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		if (block.getType() != HitResult.Type.MISS) end = block.getLocation();
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(p, eye, end, new AABB(eye, end).inflate(1.0),
				e -> e != p && e.isPickable() && !e.isSpectator(), eye.distanceToSqr(end) + 0.3);
		if (hit == null) { // CS falls back to a hull trace when the line misses: entities near the end of the reach
			AABB box = new AABB(end, end).inflate((8 + slack) / units);
			for (Entity e : p.level().getEntities(p, box, e -> e.isPickable() && !e.isSpectator()))
				if (hit == null || e.distanceToSqr(eye) < hit.getEntity().distanceToSqr(eye)) hit = new EntityHitResult(e);
		}
		if (hit != null) {
			Entity t = hit.getEntity();
			Vec3 los = new Vec3(t.getX() - p.getX(), 0, t.getZ() - p.getZ()).normalize();
			Vec3 fwd = Vec3.directionFromRotation(0, t.getYRot());
			return new KnifeHit(t, null, los.dot(fwd) > BACKSTAB_DOT);
		}
		return new KnifeHit(null, block instanceof net.minecraft.world.phys.BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK ? bh : null, false);
	}

	/** The knife with lag compensation: living targets as the attacker saw them (CS's hull fallback as slack), else as now. */
	private static KnifeHit knifeRewound(ServerPlayer p, boolean heavy, dev.strafemc.net.StrafePayloads.Knife msg) {
		double units = StrafeConfig.unitsPerBlock;
		Vec3 eye = p.getEyePosition(), end = eye.add(p.getLookAngle().scale((heavy ? KNIFE_RANGE_HEAVY : KNIFE_RANGE_LIGHT) / units));
		HitResult block = p.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		if (block.getType() != HitResult.Type.MISS) end = block.getLocation();
		dev.strafemc.server.LagHistory.Hit h = rewound(p, eye, end, msg.viewTick(), msg.claim(), 8 / units);
		if (h == null) return knifeTrace(p, heavy, 0);
		Vec3 c = h.box().getCenter();
		Vec3 los = new Vec3(c.x - p.getX(), 0, c.z - p.getZ()).normalize();
		return new KnifeHit(h.entity(), null, los.dot(Vec3.directionFromRotation(0, h.entity().getYRot())) > BACKSTAB_DOT);
	}

	public static void knife(ServerPlayer p, dev.strafemc.net.StrafePayloads.Knife msg) {
		boolean heavy = msg.heavy();
		ItemStack held = p.getMainHandItem();
		if (!(held.getItem() instanceof KnifeItem)) return;
		State st = state(p);
		// 120 ms: covers network jitter and client/server disagreeing on hit vs miss (0.4 vs 0.5 s cooldown after a slash)
		long now = System.nanoTime(), tolerance = 120_000_000L;
		if (now < st.deployUntil - tolerance || now < (heavy ? st.knifeNextHeavy : st.knifeNextLight) - tolerance) return;
		boolean firstSwing = now > st.knifeNextLight + sec(0.4);
		KnifeHit k = dev.strafemc.server.StrafeSettings.lagComp() ? knifeRewound(p, heavy, msg) : knifeTrace(p, heavy, KNIFE_LAG_SLACK);
		boolean didHit = k.entity() != null || k.block() != null;
		double[] cd = KNIFE_COOLDOWN[(heavy ? 2 : 0) + (didHit ? 1 : 0)];
		st.knifeNextLight = now + sec(cd[0]);
		st.knifeNextHeavy = now + sec(cd[1]);
		broadcast(p, heavy ? "Weapon_Knife.Swish.Heavy" : "Weapon_Knife.Swish.Light");
		if (k.block() != null) {
			broadcast(p, "Weapon_Knife.HitWall");
			impact(p, k.block(), true); // knife scuff + surface particles for the others
		}
		if (k.entity() == null) return;
		double dmg = heavy ? (k.backstab() ? 180 : 65) : k.backstab() ? 90 : firstSwing ? 40 : 25;
		if (!(k.entity() instanceof LivingEntity target)) { // dragon parts, crystals...
			hurtOther(p, k.entity(), (float) (dmg * StrafeConfig.hpScale));
			return;
		}
		broadcast(p, heavy ? (k.backstab() ? "Weapon_Knife.Hit.Heavy.Backstab" : "Weapon_Knife.Hit.Heavy")
				: k.backstab() ? "Weapon_Knife.Hit.Light.Backstab" : "Weapon_Knife.Hit.Slice");
		target.invulnerableTime = 0;
		WeaponStats knifeStats = stats("weapon_knife");
		dmg = CsArmor.apply(target, dmg, knifeStats != null ? knifeStats.armorRatio() : 1.7, "chest");
		hurtCs(p, target, (float) (dmg * StrafeConfig.hpScale), "weapon_knife");
		if (StrafeConfig.debug)
			StrafeMc.LOG.info("[strafemc] knife {}{} {}: {} CS dmg", heavy ? "stab" : "slash", k.backstab() ? " backstab" : "", target.getType().toShortString(), dmg);
	}

	/**
	 * Wallbangs, with CS2's penetration maths (its server code): the solid is measured along the bullet (0.5 unit steps
	 * through block collision shapes) and the surface modifier is the lowest bulletPenetrationDistanceModifier of the
	 * blocks crossed (CS2 surface data, by SurfaceMap). Same material in and out: wood / cardboard count as 3, plastic
	 * as 2; glass or grate thinner than 6 units as 3 with a 0.05 damage fraction. Damage lost = damage x 0.16 +
	 * (3 / m_flPenetration x 1.25) x (3 / modifier) + thickness^2 / modifier / 24; the bullet stops below 1 damage,
	 * after 3000 units, or on a surface with a modifier of 0.1 or less. Leaves an exit hole. Returns {x, y, z, damage
	 * left} or null. FLAGGED: a content flag CS2 also checks (giving 32 / 0.00001) isn't modelled.
	 */
	static double[] penetrate(ServerPlayer p, net.minecraft.world.phys.BlockHitResult bh, Vec3 dir, double power, double dmg, double travelled) {
		var level = p.level();
		double upb = StrafeConfig.unitsPerBlock;
		if (travelled > 3000 || power <= 0) return null;
		var enter = surfaceAt(level, bh.getBlockPos());
		double mod = modifier(enter);
		Vec3 pt = bh.getLocation().add(dir.scale(0.001));
		var exitSurf = enter;
		double t = 0, stepU = 0.5;
		while (solid(level, pt)) {
			// even at the best case (a modifier of 3) this much more would cost all the damage left
			if (t * t / 3 / 24 > dmg) return null;
			net.minecraft.core.BlockPos bp = net.minecraft.core.BlockPos.containing(pt);
			exitSurf = surfaceAt(level, bp);
			mod = Math.min(mod, modifier(exitSurf));
			pt = pt.add(dir.scale(stepU / upb));
			t += stepU;
		}
		if (mod <= 0.1) return null;
		double frac = 0.16;
		String in = enter == null ? null : enter.gameMaterial(), out = exitSurf == null ? null : exitSurf.gameMaterial();
		if (in != null && in.equals(out)) {
			if (in.equals("W") || in.equals("U")) mod = 3;
			else if (in.equals("L")) mod = 2;
		}
		if (t < 6 && ("G".equals(in) || "Y".equals(in))) {
			frac = 0.05;
			mod = 3;
		}
		double penMod = Math.max(0, 1 / mod);
		double lost = dmg * frac + Math.max(0, 3 / power * 1.25) * (penMod * 3) + penMod * t * t / 24;
		double left = dmg - Math.max(0, lost);
		if (left < 1) return null;
		// the exit hole: the face the bullet leaves through
		HitResult back = level.clip(new ClipContext(pt.add(dir.scale(0.02)), pt.subtract(dir.scale(stepU * 2 / upb)), ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, p));
		if (back instanceof net.minecraft.world.phys.BlockHitResult hole && hole.getType() == HitResult.Type.BLOCK) impactAll(p, hole);
		return new double[] {pt.x, pt.y, pt.z, left};
	}

	private static dev.strafemc.data.Surfaces.Surface surfaceAt(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos bp) {
		return dev.strafemc.data.Surfaces.get(dev.strafemc.world.SurfaceMap.surface(level.getBlockState(bp)));
	}

	private static double modifier(dev.strafemc.data.Surfaces.Surface s) {
		return s != null && !Double.isNaN(s.penetrationDistance()) ? s.penetrationDistance() : 0.5;
	}

	/** A bullet hole everyone sees, the shooter included. */
	private static void impactAll(ServerPlayer p, net.minecraft.world.phys.BlockHitResult bh) {
		impact(p, bh);
		if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, dev.strafemc.net.StrafePayloads.BulletImpact.TYPE))
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, impactMessage(p.level(), bh, p.getEyePosition(), IMPACT_SEEDS.nextInt()));
	}

	private static boolean solid(net.minecraft.world.level.Level level, Vec3 pt) {
		net.minecraft.core.BlockPos bp = net.minecraft.core.BlockPos.containing(pt);
		var shape = level.getBlockState(bp).getCollisionShape(level, bp);
		if (shape.isEmpty()) return false;
		Vec3 local = pt.subtract(bp.getX(), bp.getY(), bp.getZ());
		for (AABB b : shape.toAabbs()) if (b.contains(local)) return true;
		return false;
	}

	/**
	 * Lag compensation: the living entity along eye -> end as the shooter saw it. The shooter's claim is checked first
	 * (its box anywhere within -2..+1 ticks of their view tick, inflated by slack blocks), else the closest box at the
	 * view tick. The view tick is clamped to the server's max rewind.
	 */
	static dev.strafemc.server.LagHistory.Hit rewound(ServerPlayer p, Vec3 eye, Vec3 end, float viewTick, int claim, double slack) {
		int now = p.level().getServer().getTickCount();
		float oldest = now - dev.strafemc.server.StrafeSettings.maxUnlagMs() / 50f;
		float view = viewTick < 0 ? now : Math.max(oldest, Math.min(now, viewTick));
		dev.strafemc.server.LagHistory.Hit h = claim >= 0
				? dev.strafemc.server.LagHistory.claim(p.level(), claim, eye, end, Math.max(oldest, view - 2), Math.min(now, view + 1), slack) : null;
		boolean claimed = h != null;
		if (h == null) h = dev.strafemc.server.LagHistory.trace(p.level(), p, eye, end, view);
		if (StrafeConfig.debug)
			StrafeMc.LOG.info("[strafemc] lagcomp {}: view {} ({} ms back), claim {} -> {}{}", p.getScoreboardName(), String.format("%.1f", view),
					Math.round((now - view) * 50), claim, h == null ? "miss" : h.entity().getType().toShortString(), claimed ? " (claim ok)" : "");
		return h;
	}

	/**
	 * A CS hit on a living target. Endermen are hit as by a projectile (so they teleport away, as with arrows). Without
	 * the server's "Minecraft knockback" setting the target isn't pushed back (its motion is kept and no motion packet is
	 * sent); a hit player gets CS2 tagging instead: their client slows them by the weapon's flinch modifiers.
	 */
	static void hurtCs(ServerPlayer p, LivingEntity target, float amount, String cls) {
		Vec3 motion = target.getDeltaMovement();
		boolean marked = target.hurtMarked;
		var src = target instanceof net.minecraft.world.entity.monster.EnderMan
				? new net.minecraft.world.damagesource.DamageSource(p.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE)
						.getOrThrow(net.minecraft.world.damagesource.DamageTypes.ARROW), p, p)
				: p.damageSources().playerAttack(p);
		target.hurtServer(p.level(), src, amount);
		if (dev.strafemc.server.StrafeSettings.mcKnockback()) return;
		target.setDeltaMovement(motion);
		target.hurtMarked = marked;
		if (target instanceof ServerPlayer victim && net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(victim, dev.strafemc.net.StrafePayloads.Tagged.TYPE)) {
			tag(victim, cls);
		}
	}

	/** CS2 tagging on a player: their client slows them by this weapon's flinch modifiers (1 = no slowdown). */
	static void tag(ServerPlayer victim, String cls) {
		if (!net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(victim, dev.strafemc.net.StrafePayloads.Tagged.TYPE)) return;
		java.util.Map<String, Object> d = dev.strafemc.data.WeaponData.get(cls);
		if (d == null) d = dev.strafemc.data.WeaponData.get("weapon_knife");
		float large = (float) flinch(d, "m_flFlinchVelocityModifierLarge"), small = (float) flinch(d, "m_flFlinchVelocityModifierSmall");
		if (large >= 1 && small >= 1) return; // e.g. molotov fire: no slowdown
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(victim, new dev.strafemc.net.StrafePayloads.Tagged(large, small));
	}

	private static double flinch(java.util.Map<String, Object> d, String key) {
		Object v = d == null ? null : d.get(key);
		if (v instanceof java.util.List<?> l && !l.isEmpty() && l.get(0) instanceof Number n) return n.doubleValue();
		return v instanceof Number n ? n.doubleValue() : 1.0; // unknown: no slowdown
	}

	/**
	 * CS2's hit sounds (game_sounds_player): the shooter hears the hit confirmation (AttackerFeedback), a hit player
	 * hears it as the victim, players nearby as onlookers. Headshot vs body, damage vs kill; a headshot kill adds the
	 * dink. (Helmet dinks on every headshot come with armor.)
	 */
	static void hitSounds(ServerPlayer p, LivingEntity target, boolean head, boolean armored, boolean died) {
		String base = (died ? "Player.Death" : "Player.Damage") + (head ? "HeadShot" : "Body") + (armored ? "Armor" : ""); // armored: the dink
		soundTo(p, base + ".AttackerFeedback", p);
		if (died && head) soundTo(p, "Player.DeathHeadShot.AttackerFeedback.Dink", p);
		if (target instanceof ServerPlayer victim) soundTo(victim, base + ".Victim", victim);
		for (ServerPlayer o : p.level().players())
			if (o != p && o != target && o.distanceToSqr(target) < SOUND_SEND_RANGE * SOUND_SEND_RANGE) soundTo(o, base + ".Onlooker", target);
	}

	/** One CS2 sound event for one player, at (and following) an entity. */
	static void soundTo(ServerPlayer to, String event, Entity at) {
		if (!net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(to, dev.strafemc.net.StrafePayloads.CsSound.TYPE)) return;
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(to,
				new dev.strafemc.net.StrafePayloads.CsSound(event, at.getX(), at.getEyeY(), at.getZ(), false, at.getId()));
	}

	/** Damage to a non-living entity: dragon parts pass it to the dragon (no hit cooldown, like bullets on mobs), crystals explode. */
	static void hurtOther(ServerPlayer p, Entity e, float mcDamage) {
		if (e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragonPart part) part.parentMob.invulnerableTime = 0;
		e.hurtServer(p.level(), p.damageSources().playerAttack(p), mcDamage);
	}

	/** Vertical hitgroups on the target's box, using its eye height to find the head. */
	static String hitgroup(AABB b, double eye, Vec3 at) {
		double h = b.getYsize(), rel = at.y - b.minY;
		if (rel >= eye - Math.min(0.25, h * 0.15)) return "head";
		if (rel < h * 0.45) return "legs";
		if (rel < h * 0.6) return "stomach";
		return "chest";
	}

	public static void reload(ServerPlayer p, int action) {
		if (action == dev.strafemc.net.StrafePayloads.Reload.RELEASE) {
			state(p).reloadKeyHeld = false; // a released key ends the silent reload (ReloadTimeline)
			return;
		}
		reload(p, action == dev.strafemc.net.StrafePayloads.Reload.START_HELD);
	}

	private static void reload(ServerPlayer p, boolean keyHeld) {
		ItemStack held = p.getMainHandItem();
		if (!(held.getItem() instanceof GunItem g)) return;
		WeaponStats w = g.stats();
		if (w == null) return;
		State st = state(p);
		long now = System.nanoTime();
		if (st.reload != null || now < st.deployUntil || g.clip(held) >= w.clip()) return;
		if (infiniteAmmo() != 2 && findMagazine(p, g) < 0) return;
		AnimClip clip = AnimClip.load(clipPath(w.cls(), "reload"));
		double addAt = clip != null ? clip.timeOf("WPN_RELOAD_ADD_AMMO", w.reloadLockout() * 0.45) : w.reloadLockout() * 0.45;
		double silentEnd = 0;
		st.pendingSounds.clear();
		if (clip != null)
			for (AnimClip.Event e : clip.events()) {
				if (e.name().equals("WPN_RELOAD_SILENT")) silentEnd = e.time() + e.duration();
				else if (e.isSound() && !e.name().isEmpty()) st.pendingSounds.add(new Object[] {e.name(), e.time()});
			}
		st.reloadKeyHeld = keyHeld;
		st.reload = new ReloadTimeline(silentEnd, keyHeld);
		st.addAmmoAt = addAt;
		st.lockout = w.reloadLockout();
		st.ammoAdded = false;
	}

	private static void finishReload(ServerPlayer p, ItemStack gun, GunItem g) {
		WeaponStats w = g.stats();
		if (infiniteAmmo() == 2) {
			gun.set(WeaponItems.CLIP, w.clip());
			return;
		}
		int slot = findMagazine(p, g);
		if (slot < 0) return;
		ItemStack mag = p.getInventory().getItem(slot);
		int rounds = ((MagazineItem) mag.getItem()).rounds(mag);
		mag.shrink(1);
		p.getInventory().setChanged();
		// mag-by-mag: the magazine that was in the gun is dropped with whatever rounds it still had
		gun.set(WeaponItems.CLIP, rounds);
	}

	/** Fullest matching magazine in the inventory, or -1. */
	private static int findMagazine(ServerPlayer p, GunItem g) {
		int best = -1, bestRounds = 0;
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.getItem() instanceof MagazineItem m && m.weaponClass.equals(g.weaponClass)) {
				int r = m.rounds(s);
				if (r > bestRounds) {
					bestRounds = r;
					best = i;
				}
			}
		}
		return best;
	}

	public static void forget(UUID id) {
		STATES.remove(id);
	}

	private static long sec(double s) {
		return (long) (s * 1e9);
	}
}
