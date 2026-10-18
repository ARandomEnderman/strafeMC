package dev.strafemc.weapon;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The strafeMC advancement tab (data/strafemc/advancement): most are granted from here as things happen (kills and
 * their modifiers, unboxings, StatTrak, care packages, movement speed); crafting ones use vanilla's inventory trigger.
 */
public final class Advancements {
	/** "Ace": five kills within this many ms (ours: CS2's ace is per round). */
	private static final long ACE_MS = 10_000;

	private static final Map<UUID, ArrayDeque<Long>> RECENT_KILLS = new ConcurrentHashMap<>();

	private Advancements() {}

	/** Grants strafemc:&lt;id&gt; (all its criteria); nothing if it's done or doesn't exist. */
	public static void award(ServerPlayer p, String id) {
		MinecraftServer server = p.level().getServer();
		if (server == null) return;
		var h = server.getAdvancements().get(Identifier.fromNamespaceAndPath("strafemc", id));
		if (h == null) return;
		var progress = p.getAdvancements().getOrStartProgress(h);
		if (progress.isDone()) return;
		for (String c : progress.getRemainingCriteria()) p.getAdvancements().award(h, c);
	}

	/** A kill the killfeed announced: the kill advancements for the player who made it. */
	static void kill(ServerPlayer p, LivingEntity victim, String icon, int flags, ServerPlayer assister) {
		if (assister != null && (flags & KillFeed.FLASH_ASSIST) != 0) award(assister, "flash_assist");
		if (victim == p) return;
		award(p, "first_kill");
		if ((flags & KillFeed.HEADSHOT) != 0) award(p, "headshot");
		if ((flags & KillFeed.PENETRATE) != 0) award(p, "wallbang");
		if ((flags & KillFeed.NOSCOPE) != 0) award(p, "noscope");
		if ((flags & KillFeed.THROUGH_SMOKE) != 0) award(p, "smoke_kill");
		if ((flags & KillFeed.ATTACKER_BLIND) != 0) award(p, "blind_kill");
		if ((flags & KillFeed.IN_AIR) != 0) award(p, "air_kill");
		if (icon.contains("knife") || icon.equals("bayonet")) award(p, "knife_kill");
		if (icon.equals("taser")) {
			award(p, "zeus_kill");
			if (victim instanceof net.minecraft.world.entity.monster.EnderMan) award(p, "zeus_enderman");
		}
		if (icon.equals("hegrenade")) award(p, "he_kill");
		if (icon.equals("inferno")) {
			award(p, "molotov_kill");
			if (victim instanceof net.minecraft.world.entity.animal.chicken.Chicken) award(p, "chicken_dinner");
		}
		if (victim instanceof ServerPlayer) award(p, "player_kill");
		if (victim instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon) award(p, "dragon_kill");
		long now = System.currentTimeMillis();
		ArrayDeque<Long> recent = RECENT_KILLS.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>());
		synchronized (recent) {
			recent.addLast(now);
			while (!recent.isEmpty() && now - recent.peekFirst() > ACE_MS) recent.removeFirst();
			if (recent.size() >= 5) award(p, "ace");
		}
	}

	/** An unboxed item (CaseOpening): the case itself, then what came out. rarity null: a rare special item. */
	static void unboxed(ServerPlayer p, net.minecraft.world.item.ItemStack s, SkinWraps.Rarity rarity) {
		award(p, "open_case");
		if (rarity == null) award(p, "rare_special");
		else if (rarity.ordinal() >= SkinWraps.Rarity.ANCIENT.ordinal()) award(p, "covert");
		if (StatTrak.kills(s) >= 0) award(p, "stattrak");
	}

	/** The client reached a speed goal with its own u/s (SpeedGoals): bhop, speed_demon or surf. */
	public static void speedGoal(ServerPlayer p, String goal) {
		if (goal.equals("bhop") || goal.equals("speed_demon") || goal.equals("surf")) award(p, goal);
	}
}
