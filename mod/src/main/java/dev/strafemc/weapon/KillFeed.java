package dev.strafemc.weapon;

import dev.strafemc.net.StrafePayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CS2's death notices (server): a kill by a CS weapon is announced with the weapon's killfeed icon and CS2's kill
 * modifiers. Kills of players go to everyone in the world, kills of mobs only to the killer (a farm would flood it).
 */
public final class KillFeed {
	public static final int HEADSHOT = 1, PENETRATE = 2, THROUGH_SMOKE = 4, ATTACKER_BLIND = 8, IN_AIR = 16, NOSCOPE = 32;

	/** When each player stops counting as blind (CS:GO's Blind: now + hold + fade / 2, FLAGGED). */
	private static final Map<UUID, Long> BLIND_UNTIL = new ConcurrentHashMap<>();

	private KillFeed() {}

	static void blinded(ServerPlayer p, double hold, double fade) {
		long until = System.currentTimeMillis() + (long) ((hold + fade / 2) * 1000);
		BLIND_UNTIL.merge(p.getUUID(), until, Math::max);
	}

	static boolean blind(ServerPlayer p) {
		Long t = BLIND_UNTIL.get(p.getUUID());
		return t != null && t > System.currentTimeMillis();
	}

	/** The usual modifiers about the killer: blind while shooting, off the ground. */
	static int attackerFlags(ServerPlayer p) {
		return (blind(p) ? ATTACKER_BLIND : 0) | (!p.onGround() && !p.getAbilities().flying ? IN_AIR : 0);
	}

	/** Announces a kill (call only for a victim that was alive before the hit and is dead now). */
	static void notice(ServerPlayer attacker, LivingEntity victim, String icon, int flags) {
		if (attacker == null || !(victim.level() instanceof ServerLevel level)) return;
		var msg = new StrafePayloads.KillNotice(attacker.getScoreboardName(), CsTeam.isT(attacker), attacker.getId(), victim.getName().getString(),
				victim instanceof ServerPlayer vp && CsTeam.isT(vp), victim.getId(), victim instanceof ServerPlayer, icon, flags);
		if (victim instanceof ServerPlayer) {
			for (ServerPlayer o : level.players()) if (ServerPlayNetworking.canSend(o, StrafePayloads.KillNotice.TYPE)) ServerPlayNetworking.send(o, msg);
		} else if (ServerPlayNetworking.canSend(attacker, StrafePayloads.KillNotice.TYPE)) ServerPlayNetworking.send(attacker, msg);
	}
}
