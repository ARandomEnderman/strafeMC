package dev.strafemc.weapon;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The damage between you and the others this life (CS damage and hits, per player or mob), for CS2's death panel
 * (DeathCam): when you die it gets the damage given to and taken from your killer. (A chat report was dropped: user.)
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

	private record Killer(LivingEntity who, String icon, int flags) {}

	private static final Map<UUID, Killer> KILLED_BY = new ConcurrentHashMap<>();

	/** The killfeed's line for a player's death: who and with what (the death panel's). */
	static void killedBy(ServerPlayer victim, LivingEntity attacker, String icon, int flags) {
		if (attacker != null) KILLED_BY.put(victim.getUUID(), new Killer(attacker, icon, flags));
	}

	/** Deaths to report at the end of the tick: the death event comes inside the killing hit, before the killfeed's line. */
	private static final java.util.Set<ServerPlayer> DIED = ConcurrentHashMap.newKeySet();

	/** Who killed a player by other means than the killfeed knows (a zombie's hit, a skeleton's arrow): the panel's fallback. */
	private static final Map<UUID, LivingEntity> FALLBACK = new ConcurrentHashMap<>();

	public static void died(ServerPlayer p, net.minecraft.world.damagesource.DamageSource source) {
		LivingEntity by = source.getEntity() instanceof LivingEntity le ? le : p.getKillCredit();
		if (by != null) FALLBACK.put(p.getUUID(), by);
		DIED.add(p);
	}

	public static void tick(net.minecraft.server.MinecraftServer server) {
		if (DIED.isEmpty()) return;
		for (ServerPlayer p : DIED.toArray(new ServerPlayer[0])) {
			DIED.remove(p);
			report(p);
		}
	}

	/** The player died: CS2's death panel for them, their report, then a clean slate for the next life. */
	private static void report(ServerPlayer p) {
		Map<UUID, Rec> given = GIVEN.remove(p.getUUID()), taken = TAKEN.remove(p.getUUID());
		Killer k = KILLED_BY.remove(p.getUUID());
		LivingEntity fallback = FALLBACK.remove(p.getUUID());
		if (k == null && fallback != null) k = new Killer(fallback, null, 0); // not a CS weapon kill: still the panel
		if (k != null && net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, dev.strafemc.net.StrafePayloads.DeathInfo.TYPE)) {
			Rec g = given == null ? null : given.get(k.who().getUUID()), t = taken == null ? null : taken.get(k.who().getUUID());
			String[] w = weapon(k);
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new dev.strafemc.net.StrafePayloads.DeathInfo(k.who().getName().getString(),
					k.who().getId(), k.who() == p, w[0], w[1], w[2], (int) Math.ceil(Math.max(0, k.who().getHealth()) / dev.strafemc.config.StrafeConfig.hpScale),
					g == null ? 0 : (int) Math.round(g.damage), g == null ? 0 : g.hits, t == null ? 0 : (int) Math.round(t.damage), t == null ? 0 : t.hits));
		}
	}

	/** CS2's grenade names (its weapons have theirs in SkinWraps). */
	private static final Map<String, String> GRENADE_NAMES = Map.of("weapon_hegrenade", "High Explosive Grenade", "weapon_molotov", "Molotov",
			"weapon_incgrenade", "Incendiary Grenade", "weapon_flashbang", "Flashbang", "weapon_smokegrenade", "Smoke Grenade", "weapon_decoy", "Decoy Grenade");

	/** The kill's weapon: {name with the killer's skin, weapon class, paint kit or ""}. */
	private static String[] weapon(Killer k) {
		if (k.icon() == null) { // a mob's own attack: what it holds, or its bare hands
			var held = k.who().getMainHandItem();
			return new String[] {held.isEmpty() ? "bare hands" : held.getHoverName().getString(), "", ""};
		}
		String icon = k.icon();
		String cls = icon.equals("inferno") ? "weapon_molotov" : "weapon_" + icon;
		String name = SkinWraps.WEAPON_NAMES.getOrDefault(cls, GRENADE_NAMES.getOrDefault(cls, cls.replace("weapon_", "")));
		String kit = "";
		if (k.who() instanceof ServerPlayer kp) { // the skin they had on it: their loadout's wrap for that gun (or the knife)
			boolean knife = cls.contains("knife") || cls.equals("weapon_bayonet");
			var wrap = Loadout.get(kp, knife ? "knife" : cls);
			if (wrap.getItem() instanceof SkinWrapItem) {
				String id = SkinWrapItem.skin(wrap), kitFor = SkinWraps.kitFor(id, cls);
				if (kitFor != null) {
					kit = kitFor;
						name = wrap.getHoverName().getString().replace(" Knife Wrap", "").replace(" Wrap", ""); // its name already says StatTrak
					if (knife && !name.contains("|")) name = SkinWraps.WEAPON_NAMES.getOrDefault(cls, cls) + " | " + name;
				}
			}
		}
		return new String[] {name, cls, kit};
	}


	public static void forget(ServerPlayer p) {
		GIVEN.remove(p.getUUID());
		TAKEN.remove(p.getUUID());
	}
}
