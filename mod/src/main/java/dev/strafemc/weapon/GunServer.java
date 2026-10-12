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
		var state = level.getBlockState(bh.getBlockPos());
		Vec3 at = bh.getLocation(), n = Vec3.atLowerCornerOf(bh.getDirection().getUnitVec3i());
		Vec3[] slope = dev.strafemc.world.SlopeHit.hit(level, bh.getBlockPos(), eye, at.subtract(eye).normalize());
		if (slope != null) {
			at = slope[0];
			n = slope[1];
		}
		return new dev.strafemc.net.StrafePayloads.BulletImpact(at.x, at.y, at.z, bh.getDirection().get3DDataValue(), bh.getBlockPos().asLong(),
				dev.strafemc.world.SurfaceMap.surface(state), seed, (float) n.x, (float) n.y, (float) n.z);
	}

	/** Bullet hit a block: other nearby players get the decal + the surface's impact sound (the shooter predicts his own). */
	private static void impact(ServerPlayer p, net.minecraft.world.phys.BlockHitResult bh) {
		var msg = impactMessage(p.level(), bh, p.getEyePosition(), IMPACT_SEEDS.nextInt());
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
			default -> null;
		};
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
		Vec3 end = eye.add(dir.scale(w.range() / units));
		HitResult block = p.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		if (block.getType() != HitResult.Type.MISS) end = block.getLocation();
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(p, eye, end, new AABB(eye, end).inflate(1.0),
				e -> e != p && e.isPickable() && !e.isSpectator(), eye.distanceToSqr(end));
		if (shot.corrected() && net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, dev.strafemc.net.StrafePayloads.ShotCorrection.TYPE))
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new dev.strafemc.net.StrafePayloads.ShotCorrection(shot.shot()));
		if (hit == null && block instanceof net.minecraft.world.phys.BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK) {
			impact(p, bh);
			if (shot.corrected() && net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, dev.strafemc.net.StrafePayloads.BulletImpact.TYPE))
				net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, impactMessage(p.level(), bh, eye, IMPACT_SEEDS.nextInt())); // the real hole
		}
		if (hit == null || !(hit.getEntity() instanceof LivingEntity target)) return;

		Vec3 at = hit.getLocation();
		double distUnits = at.distanceTo(eye) * units;
		double dmg = w.damage() * Math.pow(w.rangeModifier(), distUnits / FALLOFF_UNITS);
		String group = hitgroup(target, at);
		dmg *= switch (group) {
			case "head" -> w.headshotMultiplier();
			case "stomach" -> STOMACH_MULT;
			case "legs" -> LEG_MULT;
			default -> 1.0;
		};
		float mc = (float) (dmg * StrafeConfig.hpScale);
		target.invulnerableTime = 0; // 600 RPM: every bullet counts, like CS
		target.hurtServer(p.level(), p.damageSources().playerAttack(p), mc);
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

	/** Seconds until the next {slash, stab} after: light miss, light hit, heavy miss, heavy hit (CS:GO, FLAGGED). */
	public static final double[][] KNIFE_COOLDOWN = {{0.4, 0.5}, {0.5, 0.5}, {1.0, 1.0}, {1.1, 1.1}};

	/** What a knife attack along the view hits: the entity (or null), else the block (or null); backstab for entities. */
	public record KnifeHit(Entity entity, net.minecraft.world.phys.BlockHitResult block, boolean backstab) {}

	public static KnifeHit knifeTrace(net.minecraft.world.entity.player.Player p, boolean heavy) {
		double units = StrafeConfig.unitsPerBlock;
		Vec3 eye = p.getEyePosition(), end = eye.add(p.getLookAngle().scale((heavy ? KNIFE_RANGE_HEAVY : KNIFE_RANGE_LIGHT) / units));
		HitResult block = p.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		if (block.getType() != HitResult.Type.MISS) end = block.getLocation();
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(p, eye, end, new AABB(eye, end).inflate(1.0),
				e -> e != p && e.isPickable() && !e.isSpectator(), eye.distanceToSqr(end) + 0.3);
		if (hit == null) { // CS falls back to a hull trace when the line misses: entities near the end of the reach
			AABB box = new AABB(end, end).inflate(8 / units);
			for (Entity e : p.level().getEntities(p, box, e -> e.isPickable() && !e.isSpectator() && e instanceof LivingEntity))
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

	public static void knife(ServerPlayer p, boolean heavy) {
		ItemStack held = p.getMainHandItem();
		if (!(held.getItem() instanceof KnifeItem)) return;
		State st = state(p);
		long now = System.nanoTime(), tolerance = 30_000_000L;
		if (now < st.deployUntil - tolerance || now < (heavy ? st.knifeNextHeavy : st.knifeNextLight) - tolerance) return;
		boolean firstSwing = now > st.knifeNextLight + sec(0.4);
		KnifeHit k = knifeTrace(p, heavy);
		boolean didHit = k.entity() != null || k.block() != null;
		double[] cd = KNIFE_COOLDOWN[(heavy ? 2 : 0) + (didHit ? 1 : 0)];
		st.knifeNextLight = now + sec(cd[0]);
		st.knifeNextHeavy = now + sec(cd[1]);
		broadcast(p, heavy ? "Weapon_Knife.Swish.Heavy" : "Weapon_Knife.Swish.Light");
		if (k.block() != null) broadcast(p, "Weapon_Knife.HitWall");
		if (!(k.entity() instanceof LivingEntity target)) return;
		double dmg = heavy ? (k.backstab() ? 180 : 65) : k.backstab() ? 90 : firstSwing ? 40 : 25;
		broadcast(p, heavy ? (k.backstab() ? "Weapon_Knife.Hit.Heavy.Backstab" : "Weapon_Knife.Hit.Heavy")
				: k.backstab() ? "Weapon_Knife.Hit.Light.Backstab" : "Weapon_Knife.Hit.Slice");
		target.invulnerableTime = 0;
		target.hurtServer(p.level(), p.damageSources().playerAttack(p), (float) (dmg * StrafeConfig.hpScale));
		if (StrafeConfig.debug)
			StrafeMc.LOG.info("[strafemc] knife {}{} {}: {} CS dmg", heavy ? "stab" : "slash", k.backstab() ? " backstab" : "", target.getType().toShortString(), dmg);
	}

	/** Vertical hitgroups on the target's box, using its eye height to find the head. */
	static String hitgroup(Entity t, Vec3 at) {
		AABB b = t.getBoundingBox();
		double h = b.getYsize(), rel = at.y - b.minY, eye = t.getEyeY() - b.minY;
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
