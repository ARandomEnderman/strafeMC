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
			MOLOTOV_GROUND_EFFECT = "particles/inferno_fx/explosion_molotov_ground.vpcf",
			MOLOTOV_AIR_EFFECT = "particles/inferno_fx/explosion_molotov_air.vpcf",
			INCENDIARY_AIR_EFFECT = "particles/inferno_fx/explosion_incend_air.vpcf";
	/** Every CS2 effect the grenades use (the setup converts these). */
	public static final String[] EFFECTS = {HE_EFFECT, FLASH_EFFECT, MOLOTOV_GROUND_EFFECT, MOLOTOV_AIR_EFFECT, INCENDIARY_AIR_EFFECT};

	/** What happened, for the clients (GrenadeFx.kind). */
	public static final int EV_HE = 0, EV_FLASH = 1, EV_SMOKE = 2, EV_FIRE = 3, EV_FIRE_INC = 4, EV_FIRE_OUT = 5, EV_FIRE_END = 6,
			EV_FIZZLE = 7, EV_FIZZLE_INC = 8, EV_DECOY_POP = 9;
	/** How far away players still get the effect message (blocks). */
	private static final double FX_RANGE = 160;

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
			double dmg = damage * Math.exp(-(dist * dist) / (2 * sigma * sigma));
			if (dmg < 1) continue;
			Vec3 motion = e.getDeltaMovement();
			boolean marked = e.hurtMarked;
			e.invulnerableTime = 0;
			e.hurtServer(level, level.damageSources().explosion(g, owner), (float) (dmg * StrafeConfig.hpScale));
			if (!dev.strafemc.server.StrafeSettings.mcKnockback()) {
				e.setDeltaMovement(motion);
				e.hurtMarked = marked;
			}
			if (StrafeConfig.debug) StrafeMc.LOG.info("[strafemc] HE hit {} at {}u: {} CS dmg", e.getType().toShortString(), Math.round(dist), String.format("%.1f", dmg));
		}
		event(level, EV_HE, at);
	}

	/**
	 * Flashbang: every player who can see the flash (line of sight to their eyes) is blinded; looking at it and being
	 * close make it longer. FLAGGED (CS:GO-like, not in CS2's data): up to 4.9 s, x1 within 60 degrees of the view,
	 * x0.6 to the side, x0.25 behind; full within 800 units, fading out to 2400.
	 */
	static void flash(ServerLevel level, GrenadeEntity g) {
		Vec3 at = g.position();
		double upb = StrafeConfig.unitsPerBlock;
		for (ServerPlayer p : level.players()) {
			Vec3 eye = p.getEyePosition();
			double d = eye.distanceTo(at) * upb;
			if (d > 2400) continue;
			if (level.clip(new ClipContext(at, eye, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, g)).getType() != HitResult.Type.MISS) continue;
			double dot = p.getLookAngle().dot(at.subtract(eye).normalize());
			double angle = dot > 0.5 ? 1 : dot > -0.3 ? 0.6 : 0.25;
			double dist = d < 800 ? 1 : Math.max(0, 1 - (d - 800) / 1600);
			double seconds = 4.9 * angle * dist;
			if (seconds < 0.25) continue;
			if (ServerPlayNetworking.canSend(p, StrafePayloads.Flashed.TYPE)) ServerPlayNetworking.send(p, new StrafePayloads.Flashed((float) seconds));
		}
		event(level, EV_FLASH, at);
	}

	/** The decoy's fake gunfire: the thrower's last held gun's shot sound from the grenade, heard like a real shot. */
	static void decoyShot(ServerLevel level, GrenadeEntity g) {
		WeaponStats w = GunServer.stats(g.decoyGun);
		if (w == null) w = GunServer.stats("weapon_ak47");
		if (w != null && w.shootSound() != null) GunServer.soundAt(level, w.shootSound(), g.position(), g);
	}

	/** Tells nearby players what a grenade did here (EV_*), for its effect and sound (client: client.weapon.GrenadeClient). */
	static void event(ServerLevel level, int kind, Vec3 at) {
		var msg = new StrafePayloads.GrenadeFx(kind, at.x, at.y, at.z);
		for (ServerPlayer p : level.players())
			if (p.distanceToSqr(at) < FX_RANGE * FX_RANGE && ServerPlayNetworking.canSend(p, StrafePayloads.GrenadeFx.TYPE)) ServerPlayNetworking.send(p, msg);
	}
}
