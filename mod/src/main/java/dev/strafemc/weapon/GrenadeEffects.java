package dev.strafemc.weapon;

import dev.strafemc.StrafeMc;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.net.StrafePayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/** What grenades do when they go off (server), and the effect message every nearby player gets. */
public final class GrenadeEffects {
	/** Explosion effects and sounds per grenade (effect files from CS2, converted by the setup). */
	public static final String HE_EFFECT = "particles/explosions_fx/explosion_hegrenade.vpcf",
			FLASH_EFFECT = "particles/explosions_fx/explosion_flashbang.vpcf",
			MOLOTOV_GROUND_EFFECT = "particles/inferno_fx/molotov_explosion.vpcf",
			INCENDIARY_GROUND_EFFECT = "particles/inferno_fx/incendiary_explosion.vpcf",
			EXTINGUISH_EFFECT = "particles/inferno_fx/extinguish_fire.vpcf",
			MOLOTOV_AIR_EFFECT = "particles/inferno_fx/explosion_molotov_air.vpcf",
			INCENDIARY_AIR_EFFECT = "particles/inferno_fx/explosion_incend_air.vpcf",
			// CS2's ground fire: one effect for a whole fire, shaped by its flames (client.weapon.InfernoClient), and the
			// smouldering scorch it leaves
			MOLOTOV_FIRE_EFFECT = "particles/inferno_fx/molotov_groundfire.vpcf",
			INCENDIARY_FIRE_EFFECT = "particles/inferno_fx/incendiary_groundfire.vpcf",
			FIRE_REMNANT_EFFECT = "particles/inferno_fx/molotov_groundfire_remnant.vpcf",
			MOLOTOV_HELD_EFFECT = "particles/weapons/cs_weapon_fx/weapon_molotov_held.vpcf",
			// a decoy lying on the ground: sparks and smoke while it lies there (CS2's client), a burst with each fake shot (server)
			DECOY_GROUND_EFFECT = "particles/weapons/cs_weapon_fx/weapon_decoy_ground_effect.vpcf",
			DECOY_SHOT_EFFECT = "particles/weapons/cs_weapon_fx/weapon_decoy_ground_effect_shot.vpcf",
			// the planted C4: its blast, the light its LED blinks with each beep, and the flash of the trigger a second before it blows
			C4_EFFECT = "particles/explosions_fx/explosion_c4_500.vpcf",
			C4_LIGHT_EFFECT = "particles/explosions_fx/c4_timer_light.vpcf",
			C4_TRIGGER_EFFECT = "particles/explosions_fx/c4_timer_light_trigger.vpcf";
	/** Every CS2 effect the grenades use (the setup converts these). */
	public static final String[] EFFECTS = {HE_EFFECT, FLASH_EFFECT, MOLOTOV_GROUND_EFFECT, MOLOTOV_AIR_EFFECT, INCENDIARY_AIR_EFFECT,
			MOLOTOV_FIRE_EFFECT, INCENDIARY_FIRE_EFFECT, FIRE_REMNANT_EFFECT, MOLOTOV_HELD_EFFECT, INCENDIARY_GROUND_EFFECT, EXTINGUISH_EFFECT,
			DECOY_GROUND_EFFECT, DECOY_SHOT_EFFECT, C4_EFFECT, C4_LIGHT_EFFECT, C4_TRIGGER_EFFECT};

	/** What happened, for the clients (GrenadeFx.kind). */
	public static final int EV_HE = 0, EV_FLASH = 1, EV_SMOKE = 2, EV_FIRE = 3, EV_FIRE_INC = 4, EV_FIRE_OUT = 5, EV_FIRE_END = 6,
			EV_FIZZLE = 7, EV_FIZZLE_INC = 8, EV_DECOY_POP = 9,
			EV_SMOKE_T = 12, // a T's smoke (EV_SMOKE: CT): CS2 tints them differently
			EV_DECOY_SHOT = 13,
			EV_C4 = 14, // the planted C4 blows
			EV_C4_BEEP = 15, // and beeps: A site, B site, and each in its last 11 seconds (CS2 adds its _10sec beep then)
			EV_C4_BEEP_B = 16, EV_C4_BEEP_10 = 17, EV_C4_BEEP_B_10 = 18,
			EV_C4_TRIP = 19; // a second before it blows: the trigger's click and flash
	/** How far away players still get the effect message (blocks). */
	static final double FX_RANGE = 160;

	private GrenadeEffects() {}

	/**
	 * HE: damage = m_nDamage (99) with a Gaussian falloff over radius 3.5 x damage units (sigma = radius / 3), only to
	 * targets the blast can see (centre of their box). FLAGGED: CS:GO's HE damage model as we know it. No knockback
	 * unless the server turned Minecraft knockback on.
	 */
	static void explodeHE(ServerLevel level, GrenadeEntity g, ServerPlayer owner) {
		double upb = StrafeConfig.unitsPerBlock;
		Map<String, Object> d = dev.strafemc.data.WeaponData.get(Grenade.HE.cls);
		double damage = d != null && d.get("m_nDamage") instanceof Number n ? n.doubleValue() : 99;
		double radius = damage * 3.5, sigma = radius / 3;
		Vec3 at = g.position(); // the grenade's centre, kept a radius out of surfaces
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(radius / upb), LivingEntity::isAlive)) {
			Vec3 spot = e.getBoundingBox().getCenter();
			double dist = spot.distanceTo(at) * upb;
			if (dist > radius) continue;
			if (level.clip(new ClipContext(at, spot, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, g)).getType() != HitResult.Type.MISS) continue;
			double dmg = damage * Math.exp(-(dist * dist) / (2 * sigma * sigma)) * dev.strafemc.mode.GameModes.teamDamage(owner, e, "grenade");
			if (dmg < 1) continue;
			Vec3 motion = e.getDeltaMovement();
			boolean marked = e.hurtMarked;
			e.invulnerableTime = 0;
			double ratio = d != null && d.get("m_flArmorRatio") instanceof Number r ? r.doubleValue() : 1.2;
			dmg = CsArmor.apply(e, dmg, ratio, "chest");
			boolean wasAlive = e.isAlive();
			KillFeed.damaged(owner, e, Math.min(dmg, e.getHealth() / StrafeConfig.hpScale));
			e.hurtServer(level, level.damageSources().explosion(g, owner), (float) (dmg * StrafeConfig.hpScale));
			if (wasAlive && e.isDeadOrDying()) KillFeed.notice(owner, e, "hegrenade", owner != null ? KillFeed.attackerFlags(owner) : 0);
			if (!dev.strafemc.server.StrafeSettings.mcKnockback()) {
				e.setDeltaMovement(motion);
				e.hurtMarked = marked;
			}
			if (StrafeConfig.debug) StrafeMc.LOG.info("[strafemc] HE hit {} at {}u: {} CS dmg", e.getType().toShortString(), Math.round(dist), String.format("%.1f", dmg));
		}
		event(level, EV_HE, at);
		GrenadeZones.blast(level, at);
	}

	/**
	 * CS2's flashbang: strength 3 x (1 - distance / 3000 units) x how much of the flash you can see
	 * (1 with a clear line to your eyes, else 0.167 for each of three points next to it that you can see: 50 units
	 * up, 75 units to either side and 10 up). By where you look (dot of your view with the direction to it): >= 0.6
	 * holds x 1.25 and fades x 2.5, >= 0.3 x 0.8 / x 1.75, >= -0.2 x 0.5 / x 1, behind you x 0.25 / x 0.5. In seconds:
	 * point blank, looking at it, 3.75 s hold and 7.5 s fade.
	 */
	static void flash(ServerLevel level, GrenadeEntity g) {
		Vec3 at = g.position();
		double upb = StrafeConfig.unitsPerBlock;
		for (ServerPlayer p : level.players()) {
			Vec3 eye = p.getEyePosition();
			double strength = 3 * (1 - eye.distanceTo(at) * upb / 3000);
			if (strength <= 0) continue;
			double seen = seen(level, g, at, eye);
			if (seen <= 0) continue;
			double dot = p.getLookAngle().dot(at.subtract(eye).normalize());
			double hold, fade;
			if (dot >= 0.6) {
				hold = strength * 1.25;
				fade = strength * 2.5;
			} else if (dot >= 0.3) {
				hold = strength * 0.8;
				fade = strength * 1.75;
			} else if (dot >= -0.2) {
				hold = strength * 0.5;
				fade = strength;
			} else {
				hold = strength * 0.25;
				fade = strength * 0.5;
			}
			if (ServerPlayNetworking.canSend(p, StrafePayloads.Flashed.TYPE))
				ServerPlayNetworking.send(p, new StrafePayloads.Flashed((float) (hold * seen), (float) (fade * seen)));
			KillFeed.blinded(p, g.getOwner() instanceof ServerPlayer by ? by : null, hold * seen, fade * seen);
		}
		event(level, EV_FLASH, at);
	}

	/** How much of a flash at `at` reaches `eye` (CS2: 1 if in clear sight, else 0.167 per visible side point). */
	private static double seen(ServerLevel level, GrenadeEntity g, Vec3 at, Vec3 eye) {
		if (clear(level, g, at, eye)) return 1;
		double upb = StrafeConfig.unitsPerBlock;
		Vec3 back = eye.subtract(at).normalize(); // from the flash towards the eye: its right and up
		Vec3 right = back.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : right.normalize();
		Vec3 up = right.cross(back).normalize();
		double seen = 0;
		for (Vec3 off : new Vec3[] {up.scale(50 / upb), right.scale(75 / upb).add(up.scale(10 / upb)), right.scale(-75 / upb).add(up.scale(10 / upb))}) {
			// out to the side point (stopping at a wall), then from there to the eye
			HitResult out = level.clip(new ClipContext(at, at.add(off), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, g));
			if (clear(level, g, out.getLocation(), eye)) seen += 0.167;
		}
		return seen;
	}

	private static boolean clear(ServerLevel level, GrenadeEntity g, Vec3 from, Vec3 to) {
		return level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, g)).getType() == HitResult.Type.MISS;
	}


	/** The decoy's fake gunfire: the thrower's last held gun's shot sound from the grenade, heard like a real shot. */
	static void decoyShot(ServerLevel level, GrenadeEntity g) {
		WeaponStats w = GunServer.stats(g.decoyGun);
		if (w == null) w = GunServer.stats("weapon_ak47");
		if (w != null && w.shootSound() != null) GunServer.soundAt(level, w.shootSound(), g.position(), g);
		event(level, EV_DECOY_SHOT, g.position());
	}

	/** Tells nearby players what a grenade did here (EV_*), for its effect and sound (client: client.weapon.GrenadeClient). */
	static void event(ServerLevel level, int kind, Vec3 at) {
		event(level, kind, at, Float.NaN);
	}

	/** heading: the grenade's flight direction, for effects that splash forward (a molotov's burst). */
	static void event(ServerLevel level, int kind, Vec3 at, float heading) {
		var msg = new StrafePayloads.GrenadeFx(kind, at.x, at.y, at.z, heading);
		for (ServerPlayer p : level.players())
			if (p.distanceToSqr(at) < FX_RANGE * FX_RANGE && ServerPlayNetworking.canSend(p, StrafePayloads.GrenadeFx.TYPE)) ServerPlayNetworking.send(p, msg);
	}
}
