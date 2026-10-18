package dev.strafemc.weapon;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CS2's damage report: when you die, chat lists the CS damage you gave and took this life, per player or mob, in CS2's
 * words (Post_Round_Damage_Report_Given / _Taken: " • Damage Given → {player_name}: {damage} in {num_hits}").
 */
public final class DamageReport {
	private static final class Rec {
		final String name;
		double damage;
		int hits;

		Rec(String name) {
			this.name = name;
		}
	}

	private static final Map<UUID, Map<UUID, Rec>> GIVEN = new ConcurrentHashMap<>(), TAKEN = new ConcurrentHashMap<>();

	private DamageReport() {}

	/** CS damage done (already capped at the target's health): by a player and/or to a player. */
	static void hit(LivingEntity attacker, LivingEntity victim, double cs) {
		if (attacker == null || victim == null || attacker == victim || cs <= 0) return;
		if (attacker instanceof ServerPlayer ap) add(GIVEN, ap.getUUID(), victim, cs);
		if (victim instanceof ServerPlayer vp) add(TAKEN, vp.getUUID(), attacker, cs);
	}

	private static void add(Map<UUID, Map<UUID, Rec>> map, UUID who, LivingEntity other, double cs) {
		Rec r = map.computeIfAbsent(who, k -> new LinkedHashMap<>()).computeIfAbsent(other.getUUID(), k -> new Rec(other.getName().getString()));
		r.damage += cs;
		r.hits++;
	}

	/** The player died: their report, then a clean slate for the next life. */
	public static void died(ServerPlayer p) {
		Map<UUID, Rec> given = GIVEN.remove(p.getUUID()), taken = TAKEN.remove(p.getUUID());
		if (given != null) for (Rec r : given.values()) line(p, " • Damage Given → ", r);
		if (taken != null) for (Rec r : taken.values()) line(p, " • Damage Taken ← ", r);
	}

	private static void line(ServerPlayer p, String what, Rec r) {
		p.sendSystemMessage(Component.literal(what + r.name + ": " + Math.round(r.damage) + " in " + r.hits).withColor(0xA0A0A0));
	}

	public static void forget(ServerPlayer p) {
		GIVEN.remove(p.getUUID());
		TAKEN.remove(p.getUUID());
	}
}
