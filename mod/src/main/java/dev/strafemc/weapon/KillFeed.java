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
	public static final int HEADSHOT = 1, PENETRATE = 2, THROUGH_SMOKE = 4, ATTACKER_BLIND = 8, IN_AIR = 16, NOSCOPE = 32, FLASH_ASSIST = 64,
			REVENGE = 128, DOMINATION = 256, SUICIDE = 512, VILLAGER = 1024;
	/** CS:GO's assist: more than this much damage (FLAGGED: CS2 doesn't publish its own). */
	static final double ASSIST_DAMAGE = 40;
	/** CS:GO's domination: this many kills in a row on the same player (FLAGGED). */
	static final int DOMINATION_KILLS = 4;

	/** CS damage each attacker has done to each target (by entity id), with when it was last hit. */
	private static final Map<Integer, Map<UUID, Double>> DAMAGE = new ConcurrentHashMap<>();
	private static final Map<Integer, Long> LAST_HIT = new ConcurrentHashMap<>();
	/** Who flashed each player last. */
	private static final Map<UUID, UUID> FLASHED_BY = new ConcurrentHashMap<>();
	/** Kills in a row: "killer>victim" -> count (reset when the victim kills the killer). */
	private static final Map<String, Integer> STREAK = new ConcurrentHashMap<>();

	/** When each player stops counting as blind (CS:GO's Blind: now + hold + fade / 2, FLAGGED). */
	private static final Map<UUID, Long> BLIND_UNTIL = new ConcurrentHashMap<>();

	private KillFeed() {}

	static void blinded(ServerPlayer p, ServerPlayer by, double hold, double fade) {
		long until = System.currentTimeMillis() + (long) ((hold + fade / 2) * 1000);
		BLIND_UNTIL.merge(p.getUUID(), until, Math::max);
		if (by != null) FLASHED_BY.put(p.getUUID(), by.getUUID());
	}

	/** CS damage done to a target, counted for assists. */
	static void damaged(ServerPlayer attacker, LivingEntity victim, double csDamage) {
		if (attacker == null || attacker == victim || csDamage <= 0) return;
		long now = System.currentTimeMillis();
		DAMAGE.computeIfAbsent(victim.getId(), k -> new ConcurrentHashMap<>()).merge(attacker.getUUID(), csDamage, Double::sum);
		LAST_HIT.put(victim.getId(), now);
		LAST_HIT.entrySet().removeIf(e -> {
			boolean old = now - e.getValue() > 120_000; // targets nobody finished off
			if (old) DAMAGE.remove(e.getKey());
			return old;
		});
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
		Map<UUID, Double> dealt = DAMAGE.remove(victim.getId());
		LAST_HIT.remove(victim.getId());
		if (attacker == null || !(victim.level() instanceof ServerLevel level)) return;
		if (attacker == victim) flags |= SUICIDE;
		if (victim instanceof net.minecraft.world.entity.npc.villager.Villager) flags |= VILLAGER; // counts for the kill cards like a player
		// the assist: the most damage over CS's threshold besides the killer's, else whoever flashed the victim (still blind)
		ServerPlayer assister = null;
		double most = ASSIST_DAMAGE;
		if (dealt != null)
			for (var e : dealt.entrySet())
				if (!e.getKey().equals(attacker.getUUID()) && e.getValue() > most && level.getServer().getPlayerList().getPlayer(e.getKey()) instanceof ServerPlayer sp) {
					assister = sp;
					most = e.getValue();
				}
		if (assister == null && victim instanceof ServerPlayer vp && blind(vp) && FLASHED_BY.get(vp.getUUID()) instanceof UUID fb && !fb.equals(attacker.getUUID())
				&& !fb.equals(vp.getUUID()) && level.getServer().getPlayerList().getPlayer(fb) instanceof ServerPlayer flasher) {
			assister = flasher;
			flags |= FLASH_ASSIST;
		}
		// dominations and revenge between players
		if (victim instanceof ServerPlayer vp && attacker != victim) {
			String mine = attacker.getUUID() + ">" + vp.getUUID(), theirs = vp.getUUID() + ">" + attacker.getUUID();
			if (STREAK.getOrDefault(theirs, 0) >= DOMINATION_KILLS) flags |= REVENGE;
			STREAK.remove(theirs);
			if (STREAK.merge(mine, 1, Integer::sum) >= DOMINATION_KILLS && (flags & REVENGE) == 0) flags |= DOMINATION;
		}
		var msg = new StrafePayloads.KillNotice(attacker.getScoreboardName(), CsTeam.isT(attacker), attacker.getId(), victim.getName().getString(),
				victim instanceof ServerPlayer vp && CsTeam.isT(vp), victim.getId(), victim instanceof ServerPlayer, icon, flags,
				assister == null ? "" : assister.getScoreboardName(), assister != null && CsTeam.isT(assister));
		if (victim instanceof ServerPlayer) {
			for (ServerPlayer o : level.players()) if (ServerPlayNetworking.canSend(o, StrafePayloads.KillNotice.TYPE)) ServerPlayNetworking.send(o, msg);
		} else if (ServerPlayNetworking.canSend(attacker, StrafePayloads.KillNotice.TYPE)) ServerPlayNetworking.send(attacker, msg);
	}
}
