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
	public static final String HE_EFFECT = "particles/explosions_fx/explosion_hegrenade.vpcf";
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
		Vec3 at = g.position().add(0, 0.05, 0);
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
		effect(level, Grenade.HE, at);
	}

	/** Tells nearby players to show the grenade's effect and play its sound (client: client.weapon.GrenadeClient). */
	static void effect(ServerLevel level, Grenade type, Vec3 at) {
		var msg = new StrafePayloads.GrenadeFx(type.ordinal(), at.x, at.y, at.z);
		for (ServerPlayer p : level.players())
			if (p.distanceToSqr(at) < FX_RANGE * FX_RANGE && ServerPlayNetworking.canSend(p, StrafePayloads.GrenadeFx.TYPE)) ServerPlayNetworking.send(p, msg);
	}
}
