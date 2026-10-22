package dev.strafemc.mode;

import com.google.gson.JsonObject;
import dev.strafemc.weapon.CsTeam;
import dev.strafemc.weapon.WeaponTable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * CS2's Deathmatch (gamemode_deathmatch.cfg) and Team Deathmatch (gamemode_teamdeathmatch.cfg): ten minutes of
 * respawning (mp_roundtime 10) after a 20 s warmup, everyone against everyone (or T against CT), guns from the buy menu
 * anywhere but only while spawn protection lasts (mp_buy_anywhere 1, mp_buy_during_immunity 1), no grenades
 * (mp_buy_allow_grenades 0), the bought guns kept for the next life, a knife and the side's pistol to start
 * (mp_t/ct_default_secondary), endless ammo. A kill scores mp_dm_kill_base_score (8) plus a bonus for cheap weapons,
 * as CS2 counts it by the weapon's price: knife +17, up to $200 +10, $500 +8, $700 +6, $1400 +5, $2500 +3, $3500 +2;
 * a kill with the bonus weapon of the moment adds mp_dm_bonus_percent (50 %). In Team Deathmatch each kill also gives
 * the team mp_dm_teammode_kill_score (1), the bonus weapon mp_dm_teammode_bonus_score (1) more.
 */
final class Deathmatch extends Mode {
	private final boolean teams;
	private final Random rng = new Random();
	private final Map<UUID, Integer> score = new HashMap<>();
	/** Bought guns, kept for the next life: [primary, secondary]. */
	private final Map<UUID, String[]> loadout = new HashMap<>();
	private int scoreT, scoreCt;
	/** The bonus weapon and when it changes (none between bonuses). */
	private String bonus = "";
	private long bonusUntil, nextBonus;

	Deathmatch(MinecraftServer server, boolean teams) {
		super(server);
		this.teams = teams;
		reset();
	}

	@Override
	String id() {
		return teams ? "teamdeathmatch" : "deathmatch";
	}

	@Override
	Map<String, String> cvars() {
		return Map.ofEntries(Map.entry("sv_infinite_ammo", "2"), Map.entry("mp_free_armor", "2"), Map.entry("mp_respawn_immunitytime", "10"),
				Map.entry("mp_buy_anywhere", "1"), Map.entry("mp_buytime", "0"), Map.entry("mp_buy_during_immunity", "1"),
				Map.entry("mp_teammates_are_enemies", teams ? "0" : "1"), Map.entry("mp_buy_allow_grenades", "0"), Map.entry("mp_forcecamera", "0"),
				Map.entry("spec_freeze_time", "2"), Map.entry("ff_damage_reduction_grenade_self", "0"));
	}

	@Override
	boolean ffa() {
		return !teams;
	}

	@Override
	int warmupTicks() {
		return 20 * 20;
	}

	@Override
	int matchTicks() {
		return 10 * 60 * 20;
	}

	@Override
	void reset() {
		score.clear();
		scoreT = scoreCt = 0;
		stats.clear();
		bonus = "";
		nextBonus = now() + warmupTicks() + between();
	}

	/** Between bonus weapons: 30-40 s (mp_dm_time_between_bonus_min / _max). FLAGGED: read from the cvars' limits. */
	private long between() {
		return (30 + rng.nextInt(11)) * 20L;
	}

	@Override
	void gear(ServerPlayer p) {
		String[] l = loadout.get(p.getUUID());
		String primary = l == null ? null : l[0];
		String secondary = l != null && l[1] != null ? l[1] : dev.strafemc.weapon.MenuLoadout.startPistol(p);
		if (primary != null) give(p, 0, primary);
		give(p, 1, secondary);
		knife(p);
		select(p, primary != null ? 0 : 1);
	}

	static boolean pistol(String cls) {
		WeaponTable.Entry e = WeaponTable.get(cls);
		return e != null && e.slot() != null && e.slot().startsWith("secondary");
	}

	@Override
	String buyRefusal(ServerPlayer p) {
		return immune(p.getUUID()) ? "" : "You can only buy while protected after spawning";
	}

	@Override
	void bought(ServerPlayer p, String cls) {
		boolean sec = pistol(cls);
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) // one primary and one pistol, as in CS
			if (inv.getItem(i).getItem() instanceof dev.strafemc.weapon.GunItem g && !(inv.getItem(i).getItem() instanceof dev.strafemc.weapon.KnifeItem)
					&& WeaponTable.get(g.weaponClass) != null && pistol(g.weaponClass) == sec)
				inv.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
		give(p, sec ? 1 : 0, cls);
		select(p, sec ? 1 : 0);
		loadout.computeIfAbsent(p.getUUID(), k -> new String[2])[sec ? 1 : 0] = cls;
	}

	@Override
	void scored(LivingEntity attacker, LivingEntity victim, String icon) {
		boolean knife = icon.contains("knife") || icon.contains("bayonet");
		int pts = 8 + (knife ? 17 : priceBonus(price("weapon_" + icon)));
		boolean isBonus = !bonus.isEmpty() && bonus.equals("weapon_" + icon);
		if (isBonus) pts += pts * 50 / 100;
		score.merge(key(attacker), pts, Integer::sum);
		if (teams) {
			int team = 1 + (isBonus ? 1 : 0);
			if (CsTeam.isT(attacker)) scoreT += team;
			else scoreCt += team;
		}
		if (attacker instanceof ServerPlayer a) a.sendSystemMessage(net.minecraft.network.chat.Component.literal("+" + pts + " points"), true);
	}

	/** CS2's points for a cheap weapon, by its price. */
	static int priceBonus(int price) {
		if (price <= 200) return 10;
		if (price <= 500) return 8;
		if (price <= 700) return 6;
		if (price <= 1400) return 5;
		if (price <= 2500) return 3;
		if (price <= 3500) return 2;
		return 0;
	}

	static int price(String cls) {
		var w = dev.strafemc.data.WeaponData.get(cls);
		Object v = w == null ? null : w.get("m_nPrice");
		return v instanceof Number n ? n.intValue() : 0;
	}

	@Override
	void tick() {
		super.tick();
		long t = now();
		if (!live()) return;
		if (!bonus.isEmpty() && t >= bonusUntil) {
			bonus = "";
			nextBonus = t + between();
		} else if (bonus.isEmpty() && t >= nextBonus) { // a random gun is worth more for 30 s (mp_dm_bonus_length_min/_max)
			List<String> guns = new ArrayList<>();
			for (WeaponTable.Entry e : WeaponTable.guns()) if (price(e.cls()) > 0) guns.add(e.cls());
			if (!guns.isEmpty()) {
				bonus = guns.get(rng.nextInt(guns.size()));
				bonusUntil = t + 30 * 20;
				for (ServerPlayer p : players())
					dev.strafemc.server.Toasts.info(p, "Bonus weapon: " + dev.strafemc.weapon.SkinWraps.WEAPON_NAMES.getOrDefault(bonus, bonus));
			}
		}
	}

	@Override
	String leaderName() {
		if (teams) return scoreT == scoreCt ? null : scoreT > scoreCt ? "Terrorists" : "Counter-Terrorists";
		List<LivingEntity> r = ranking();
		return r.isEmpty() || !score.containsKey(key(r.get(0))) ? null : name(r.get(0));
	}

	@Override
	List<LivingEntity> ranking() {
		List<LivingEntity> all = participants();
		all.sort((x, y) -> Integer.compare(score.getOrDefault(key(y), 0), score.getOrDefault(key(x), 0)));
		return all;
	}

	@Override
	void state(ServerPlayer p, JsonObject o) {
		o.addProperty("score", score.getOrDefault(p.getUUID(), 0));
		o.addProperty("bonus", bonus);
		if (teams) {
			o.addProperty("scoreT", scoreT);
			o.addProperty("scoreCT", scoreCt);
		}
	}

	@Override
	void entry(LivingEntity e, JsonObject o, boolean ally) {
		o.addProperty("value", String.valueOf(score.getOrDefault(key(e), 0)));
	}
}
