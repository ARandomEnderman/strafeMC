package dev.strafemc.weapon;

import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each player's CS side (server), chosen per player in the console quick menu (strafemc_team) until agents exist.
 * CT unless they picked T. Used for CS2's team-tinted smoke (smoke_grenade_ct_color / smoke_grenade_t_color).
 */
public final class CsTeam {
	private static final Map<UUID, Boolean> T = new ConcurrentHashMap<>();

	private CsTeam() {}

	public static void set(Entity player, boolean t) {
		T.put(player.getUUID(), t);
	}

	public static boolean isT(Entity player) {
		return player != null && T.getOrDefault(player.getUUID(), false);
	}
}
