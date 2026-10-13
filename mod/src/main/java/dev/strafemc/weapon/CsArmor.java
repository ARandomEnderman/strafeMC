package dev.strafemc.weapon;

import dev.strafemc.net.StrafePayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CS armor on players (server): kevlar points (0..100) and a helmet. It protects the chest, stomach and arms, the head
 * only with a helmet, the legs never. CS's armor maths (CS:GO's player damage code as we know it; CS2's data has
 * each weapon's m_flArmorRatio): health damage = damage x ratio / 2; the armor takes half of the rest; if that's more
 * than the armor left, the armor breaks and the remainder goes to health.
 */
public final class CsArmor {
	public record Armor(int value, boolean helmet) {}

	private static final Map<UUID, Armor> ARMOR = new ConcurrentHashMap<>();
	private static final double ARMOR_BONUS = 0.5;

	private CsArmor() {}

	public static Armor of(LivingEntity e) {
		return e instanceof ServerPlayer p ? ARMOR.getOrDefault(p.getUUID(), new Armor(0, false)) : new Armor(0, false);
	}

	public static void set(ServerPlayer p, int value, boolean helmet) {
		ARMOR.put(p.getUUID(), new Armor(Math.max(0, Math.min(100, value)), helmet));
		sync(p);
	}

	public static void sync(ServerPlayer p) {
		Armor a = of(p);
		if (ServerPlayNetworking.canSend(p, StrafePayloads.ArmorSync.TYPE)) ServerPlayNetworking.send(p, new StrafePayloads.ArmorSync(a.value(), a.helmet()));
	}

	/** Is this hit group covered (CS: head only with a helmet, legs never)? */
	public static boolean protects(Armor a, String group) {
		if (a.value() <= 0) return false;
		return switch (group) {
			case "head" -> a.helmet();
			case "legs" -> false;
			default -> true;
		};
	}

	/** CS damage after armor for a hit on `group` (and the armor worn down); unprotected hits come back unchanged. */
	public static double apply(LivingEntity target, double damage, double armorRatio, String group) {
		if (!(target instanceof ServerPlayer p)) return damage;
		Armor a = of(p);
		if (!protects(a, group)) return damage;
		double health = damage * armorRatio * 0.5, toArmor = (damage - health) * ARMOR_BONUS;
		int left = a.value();
		if (toArmor > left) {
			health = damage - left / ARMOR_BONUS;
			left = 0;
		} else left -= (int) Math.round(toArmor);
		ARMOR.put(p.getUUID(), new Armor(left, a.helmet()));
		sync(p);
		return health;
	}

	/** Armor is lost on death (checked every tick). */
	public static void tick(net.minecraft.server.MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers())
			if (!p.isAlive() && ARMOR.remove(p.getUUID()) != null) sync(p);
	}
}
