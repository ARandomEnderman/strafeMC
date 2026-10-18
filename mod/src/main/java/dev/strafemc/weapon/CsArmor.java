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
	/** Kevlar (players), else a Minecraft armor piece on that part. */
	public static boolean protects(LivingEntity target, String group) {
		if (target instanceof ServerPlayer p && protects(of(p), group)) return true;
		net.minecraft.world.entity.EquipmentSlot slot = group.equals("head") ? net.minecraft.world.entity.EquipmentSlot.HEAD
				: group.equals("legs") ? net.minecraft.world.entity.EquipmentSlot.LEGS : net.minecraft.world.entity.EquipmentSlot.CHEST;
		var piece = target.getItemBySlot(slot);
		return !piece.isEmpty() && piece.has(net.minecraft.core.component.DataComponents.EQUIPPABLE);
	}

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
		if (!(target instanceof ServerPlayer p) || !protects(of(p), group)) return minecraftArmor(target, damage, armorRatio, group);
		Armor a = of(p);
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

	/**
	 * Minecraft armor against CS weapons, by body part like Kevlar (CS damage to health = damage x armor ratio x 0.5): the
	 * helmet covers the head, the chestplate the chest, stomach and arms, the leggings the legs. What it soaks up wears
	 * the piece (1 durability per 2 damage, as Kevlar's points). Vanilla's own armor maths is skipped for these hits
	 * (strafemc:bullet bypasses armor). FLAGGED ours: CS2 has no Minecraft armor.
	 */
	static double minecraftArmor(LivingEntity target, double damage, double armorRatio, String group) {
		net.minecraft.world.entity.EquipmentSlot slot = switch (group) {
			case "head" -> net.minecraft.world.entity.EquipmentSlot.HEAD;
			case "legs" -> net.minecraft.world.entity.EquipmentSlot.LEGS;
			default -> net.minecraft.world.entity.EquipmentSlot.CHEST;
		};
		net.minecraft.world.item.ItemStack piece = target.getItemBySlot(slot);
		if (piece.isEmpty() || !piece.has(net.minecraft.core.component.DataComponents.EQUIPPABLE)) return damage;
		double health = damage * armorRatio * 0.5, toArmor = (damage - health) * ARMOR_BONUS;
		if (target.level() instanceof net.minecraft.server.level.ServerLevel) piece.hurtAndBreak(Math.max(1, (int) Math.round(toArmor)), target, slot);
		return Math.min(damage, health);
	}

	/** Armor is lost on death (checked every tick). */
	public static void tick(net.minecraft.server.MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers())
			if (!p.isAlive() && ARMOR.remove(p.getUUID()) != null) sync(p);
	}
}
