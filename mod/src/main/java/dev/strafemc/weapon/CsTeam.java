package dev.strafemc.weapon;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each player's (and bot's) CS side, kept by the server: picked per player (strafemc_team, the team menu, a game mode)
 * and CT until then. Sent to every client with the entity (SIDE), so they can show the right side's agent model.
 * Used for CS2's team-tinted smoke (smoke_grenade_ct_color / smoke_grenade_t_color) and game modes.
 */
public final class CsTeam {
	private static final Map<UUID, Boolean> T = new ConcurrentHashMap<>();

	/** The side as clients see it (true: T). */
	public static final AttachmentType<Boolean> SIDE = AttachmentRegistry.create(Identifier.fromNamespaceAndPath("strafemc", "side"),
			b -> b.copyOnDeath().initializer(() -> false).syncWith(ByteBufCodecs.BOOL, AttachmentSyncPredicate.all()));

	private CsTeam() {}

	/** Loads the class (its attachment registers then). */
	public static void register() {}

	public static void set(Entity player, boolean t) {
		T.put(player.getUUID(), t);
		if (!player.level().isClientSide() && !Boolean.valueOf(t).equals(player.getAttached(SIDE))) player.setAttached(SIDE, t);
	}

	public static boolean isT(Entity player) {
		if (player == null) return false;
		if (player.level().isClientSide()) return Boolean.TRUE.equals(player.getAttached(SIDE));
		return T.getOrDefault(player.getUUID(), false);
	}
}
