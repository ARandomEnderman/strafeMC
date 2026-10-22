package dev.strafemc.mode;

import com.google.gson.JsonObject;
import dev.strafemc.weapon.Bots;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * CS2's Arms Race (gungameprogressive, gamemode_armsrace.cfg): T against CT, everyone moving through the same weapons,
 * one level per two kills; a kill with the knife on the last level wins, else the highest level when time runs out.
 * The weapons are drawn anew each match, as CS2 does: from each group in turn (auto snipers, AWP, rifles, machine guns,
 * SMGs, heavy pistols, pistols, shotguns) a shuffled pick of a fixed number, then the knife. Ammo is endless
 * (sv_infinite_ammo 2); 20 s warmup (mp_warmuptime), 10 minute match (mp_timelimit). Bots level too.
 */
final class ArmsRace extends Mode {
	/** CS2's draw: each group shuffled, the first n taken. */
	private static final String[][] GROUPS = {
			{"weapon_scar20", "weapon_g3sg1"},
			{"weapon_awp"},
			{"weapon_ak47", "weapon_aug", "weapon_famas", "weapon_galilar", "weapon_m4a1", "weapon_m4a1_silencer", "weapon_sg556"},
			{"weapon_m249", "weapon_negev"},
			{"weapon_mac10", "weapon_p90", "weapon_ump45", "weapon_bizon", "weapon_mp7", "weapon_mp5sd", "weapon_mp9"},
			{"weapon_deagle", "weapon_revolver", "weapon_ssg08"},
			{"weapon_elite", "weapon_fiveseven", "weapon_glock", "weapon_tec9", "weapon_hkp2000", "weapon_usp_silencer", "weapon_p250"},
			{"weapon_xm1014", "weapon_nova", "weapon_mag7", "weapon_sawedoff"}};
	private static final int[] PICKS = {1, 1, 4, 1, 3, 1, 3, 2};
	/** The last level: CS2's golden knife (weapon_knifegg); here the player's own knife until it has its own look. */
	static final String KNIFE = "weapon_knifegg";
	static final int KILLS_PER_LEVEL = 2;
	static final Map<String, String> CVARS = Map.of("sv_infinite_ammo", "2", "mp_free_armor", "2", "mp_respawn_immunitytime", "10",
			"mp_buy_anywhere", "0", "mp_buytime", "0", "mp_teammates_are_enemies", "0", "ammo_grenade_limit_total", "3", "spec_freeze_time", "2");

	private final Random rng = new Random();
	private List<String> weapons;
	private final Map<UUID, Integer> kills = new HashMap<>();
	/** When each player reached their level (game ticks): the earlier one leads a tie. */
	private final Map<UUID, Long> reached = new HashMap<>();
	private UUID leader;

	ArmsRace(MinecraftServer server) {
		super(server);
		reset();
	}

	@Override
	String id() {
		return "armsrace";
	}

	@Override
	Map<String, String> cvars() {
		return CVARS;
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
		List<String> w = new ArrayList<>();
		for (int g = 0; g < GROUPS.length; g++) {
			List<String> pool = new ArrayList<>(List.of(GROUPS[g]));
			for (int i = 0; i < pool.size() - 1; i++) Collections.swap(pool, i, i + rng.nextInt(pool.size() - i)); // Fisher-Yates, as CS2
			for (int i = 0; i < PICKS[g]; i++) w.add(pool.get(i));
		}
		w.add(KNIFE);
		weapons = w;
		kills.clear();
		reached.clear();
		stats.clear();
		leader = null;
		dev.strafemc.StrafeMc.LOG.info("[strafemc] strafeGM: Arms Race weapons {}", weapons);
	}

	int level(UUID id) {
		return Math.min(weapons.size() - 1, kills.getOrDefault(id, 0) / KILLS_PER_LEVEL);
	}

	/** Bots have no knife fight: they stay on the last gun (FLAGGED ours). */
	private int botLevel(UUID id) {
		return Math.min(weapons.size() - 2, level(id));
	}

	@Override
	void gear(ServerPlayer p) {
		String cls = weapons.get(level(p.getUUID()));
		boolean gun = !cls.equals(KNIFE);
		if (gun) give(p, 0, cls);
		knife(p);
		select(p, gun ? 0 : 2);
	}

	@Override
	String botWeapon(LivingEntity bot) {
		return weapons.get(botLevel(key(bot)));
	}

	@Override
	void scored(LivingEntity attacker, LivingEntity victim, String icon) {
		UUID id = key(attacker);
		int before = level(id);
		boolean knife = icon.contains("knife") || icon.contains("bayonet");
		if (knife && before == weapons.size() - 1) { // the golden knife kill: the match is won
			for (ServerPlayer o : server.getPlayerList().getPlayers()) sound(o, "UI.ArmsRace.FinalKill_Knife");
			win(name(attacker));
			return;
		}
		// a knife kill takes a level from the victim and gives one (FLAGGED: CS:GO's rule; CS2's count not confirmed)
		kills.merge(id, knife ? KILLS_PER_LEVEL : 1, Integer::sum);
		if (Bots.isBot(attacker)) kills.put(id, Math.min(kills.get(id), (weapons.size() - 2) * KILLS_PER_LEVEL + 1));
		if (knife) {
			int lv = level(key(victim));
			if (lv > 0) {
				kills.put(key(victim), (lv - 1) * KILLS_PER_LEVEL);
				if (victim instanceof ServerPlayer v) sound(v, "UI.ArmsRace.Demoted");
			}
		}
		int after = level(id);
		if (after > before) reached.put(id, now());
		if (attacker instanceof ServerPlayer a) {
			sound(a, "UI.ArmsRace.Kill1");
			if (after > before) {
				sound(a, after == weapons.size() - 1 ? "UI.ArmsRace.ReachedKnife" : "UI.ArmsRace.Weapon_LevelUp");
				if (after == weapons.size() - 1) sound(a, "UI.ArmsRace.ReachedKnife_Music");
				if (a.isAlive()) {
					clearGuns(a);
					gear(a);
				}
			}
		}
		UUID lead = leaderId();
		if (lead != null && lead.equals(id) && !id.equals(leader) && attacker instanceof ServerPlayer a) sound(a, "UI.ArmsRace.BecomeMatchLeader");
		leader = lead;
	}

	private UUID leaderId() {
		List<LivingEntity> r = ranking();
		return r.isEmpty() || !kills.containsKey(key(r.get(0))) ? null : key(r.get(0));
	}

	@Override
	String leaderName() {
		List<LivingEntity> r = ranking();
		return r.isEmpty() || !kills.containsKey(key(r.get(0))) ? null : name(r.get(0));
	}

	@Override
	List<LivingEntity> ranking() {
		List<LivingEntity> all = participants();
		all.sort((x, y) -> {
			int c = Integer.compare(level(key(y)), level(key(x)));
			return c != 0 ? c : Long.compare(reached.getOrDefault(key(x), Long.MAX_VALUE), reached.getOrDefault(key(y), Long.MAX_VALUE));
		});
		return all;
	}

	@Override
	void state(ServerPlayer p, JsonObject o) {
		int lv = level(p.getUUID());
		o.addProperty("level", lv + 1);
		o.addProperty("levels", weapons.size());
		o.addProperty("weapon", weapons.get(lv));
		o.addProperty("next", lv + 1 < weapons.size() ? weapons.get(lv + 1) : "");
	}

	@Override
	void entry(LivingEntity e, JsonObject o, boolean ally) {
		int lv = Bots.isBot(e) ? botLevel(key(e)) : level(key(e));
		o.addProperty("value", String.valueOf(lv + 1));
		o.addProperty("weapon", weapons.get(lv));
		o.addProperty("gold", lv == weapons.size() - 1);
	}
}
