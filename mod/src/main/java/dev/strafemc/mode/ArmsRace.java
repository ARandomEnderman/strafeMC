package dev.strafemc.mode;

import com.google.gson.JsonObject;
import dev.strafemc.weapon.CsArmor;
import dev.strafemc.weapon.CsTeam;
import dev.strafemc.weapon.GunItem;
import dev.strafemc.weapon.KnifeItem;
import dev.strafemc.weapon.WeaponItems;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

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
 * SMGs, heavy pistols, pistols, shotguns) a shuffled pick of a fixed number, then the knife. Respawns come on random
 * spawn blocks with kevlar and helmet (mp_free_armor 2) and 10 s of protection that ends on firing
 * (mp_respawn_immunitytime 10); teammates can't hurt each other (ff_damage_reduction_* 0); ammo is endless
 * (sv_infinite_ammo 2); 20 s warmup (mp_warmuptime), 10 minute match (mp_timelimit).
 */
final class ArmsRace {
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
	private static final int WARMUP = 20 * 20, MATCH = 10 * 60 * 20, IMMUNITY = 10 * 20;
	/** Respawn after the death cam (spec_freeze_time 2 in the mode's config). */
	private static final int RESPAWN = 2 * 20;
	/** From the win to the next match. FLAGGED ours. */
	private static final int RESTART = 15 * 20;
	/** The mode's cvars the rest of strafeMC reads (gamemode_armsrace.cfg). */
	static final Map<String, String> CVARS = Map.of("sv_infinite_ammo", "2", "mp_free_armor", "2", "mp_respawn_immunitytime", "10",
			"mp_buy_anywhere", "0", "mp_buytime", "0", "mp_teammates_are_enemies", "0", "ammo_grenade_limit_total", "3");

	private final MinecraftServer server;
	private final Random rng = new Random();
	private List<String> weapons;
	private final Map<UUID, Integer> kills = new HashMap<>();
	/** When each player reached their level (game ticks): the earlier one leads a tie. */
	private final Map<UUID, Long> reached = new HashMap<>();
	private final Map<UUID, Long> immuneUntil = new HashMap<>(), respawnAt = new HashMap<>();
	private UUID leader;
	private long start, over = -1;
	private String winner = "";

	ArmsRace(MinecraftServer server) {
		this.server = server;
		newMatch();
	}

	private long now() {
		return server.getTickCount();
	}

	private void newMatch() {
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
		leader = null;
		start = now();
		over = -1;
		winner = "";
		for (ServerPlayer p : server.getPlayerList().getPlayers()) if (p.hasAttached(GameModes.SAVED) && p.isAlive()) spawned(p);
		dev.strafemc.StrafeMc.LOG.info("[strafemc] strafeGM: Arms Race weapons {}", weapons);
	}

	void skipWarmup() {
		start = now() - WARMUP;
	}

	boolean live() {
		return over < 0 && now() - start >= WARMUP;
	}

	int level(UUID id) {
		return Math.min(weapons.size() - 1, kills.getOrDefault(id, 0) / KILLS_PER_LEVEL);
	}

	void join(ServerPlayer p) {
		GameModes.putAway(p);
		if (p.isAlive()) spawned(p);
	}

	void leave(ServerPlayer p) {
		respawnAt.remove(p.getUUID());
	}

	/** (Re)spawned: the level's weapon, the knife, kevlar and helmet, protection. */
	void spawned(ServerPlayer p) {
		GameModes.putAway(p);
		immuneUntil.put(p.getUUID(), now() + IMMUNITY);
		CsArmor.set(p, 100, true);
		p.setHealth(p.getMaxHealth());
		gear(p);
	}

	/** Hands out the player's level: the weapon in the first slot (selected), the knife in the third, as CS's slots. */
	private void gear(ServerPlayer p) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.getItem() instanceof GunItem || s.getItem() instanceof dev.strafemc.weapon.MagazineItem) inv.setItem(i, ItemStack.EMPTY);
		}
		String cls = weapons.get(level(p.getUUID()));
		var gun = WeaponItems.GUNS.get(cls);
		if (gun != null) inv.setItem(0, new ItemStack(gun));
		inv.setItem(2, new ItemStack(WeaponItems.KNIVES.get(CsTeam.isT(p) ? "weapon_knife_t" : "weapon_knife")));
		int slot = gun != null ? 0 : 2;
		inv.setSelectedSlot(slot);
		p.connection.send(new ClientboundSetHeldSlotPacket(slot));
		p.inventoryMenu.broadcastChanges();
	}

	void died(ServerPlayer p) {
		respawnAt.put(p.getUUID(), now() + RESPAWN);
	}

	void fired(ServerPlayer p) {
		immuneUntil.remove(p.getUUID());
	}

	/** Protection and no team damage. */
	boolean allowDamage(LivingEntity victim, DamageSource src) {
		if (!(victim instanceof ServerPlayer v) || !v.hasAttached(GameModes.SAVED)) return true;
		if (immuneUntil.getOrDefault(v.getUUID(), 0L) > now()) return false;
		return !(src.getEntity() instanceof LivingEntity a) || a == v || CsTeam.isT(a) != CsTeam.isT(v);
	}

	void kill(LivingEntity attacker, LivingEntity victim, String icon) {
		if (!live() || !(attacker instanceof ServerPlayer a) || !a.hasAttached(GameModes.SAVED) || a == victim) return;
		if (!(victim instanceof ServerPlayer || dev.strafemc.weapon.Bots.isBot(victim)) || CsTeam.isT(a) == CsTeam.isT(victim)) return;
		UUID id = a.getUUID();
		int before = level(id);
		boolean knife = icon.contains("knife") || icon.contains("bayonet");
		if (knife && before == weapons.size() - 1) { // the golden knife kill: the match is won
			win(a);
			return;
		}
		// a knife kill takes a level from the victim and gives one (FLAGGED: CS:GO's rule; CS2's count not confirmed)
		kills.merge(id, knife ? KILLS_PER_LEVEL : 1, Integer::sum);
		if (knife && victim instanceof ServerPlayer v) {
			int lv = level(v.getUUID());
			if (lv > 0) {
				kills.put(v.getUUID(), (lv - 1) * KILLS_PER_LEVEL);
				sound(v, "UI.ArmsRace.Demoted");
			}
		}
		int after = level(id);
		sound(a, "UI.ArmsRace.Kill1");
		if (after > before) {
			reached.put(id, now());
			sound(a, after == weapons.size() - 1 ? "UI.ArmsRace.ReachedKnife" : "UI.ArmsRace.Weapon_LevelUp");
			if (after == weapons.size() - 1) sound(a, "UI.ArmsRace.ReachedKnife_Music");
			if (a.isAlive()) gear(a);
		}
		UUID lead = leader();
		if (lead != null && lead.equals(id) && !id.equals(leader)) sound(a, "UI.ArmsRace.BecomeMatchLeader");
		leader = lead;
		sendAll();
	}

	/** The highest level, the earliest to reach it. */
	private UUID leader() {
		UUID best = null;
		for (UUID id : kills.keySet()) {
			if (best == null) {
				best = id;
				continue;
			}
			int a = level(id), b = level(best);
			if (a > b || a == b && reached.getOrDefault(id, Long.MAX_VALUE) < reached.getOrDefault(best, Long.MAX_VALUE)) best = id;
		}
		return best;
	}

	private void win(ServerPlayer p) {
		over = now();
		winner = p.getScoreboardName();
		for (ServerPlayer o : server.getPlayerList().getPlayers()) {
			sound(o, "UI.ArmsRace.FinalKill_Knife");
			o.sendSystemMessage(Component.literal(winner + " won the Arms Race!"));
		}
		sendAll();
	}

	void tick() {
		long t = now();
		for (var e : new ArrayList<>(respawnAt.entrySet())) {
			if (e.getValue() > t) continue;
			respawnAt.remove(e.getKey());
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p != null && p.isDeadOrDying()) server.getPlayerList().respawn(p, false, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!p.hasAttached(GameModes.SAVED)) continue;
			p.getFoodData().setFoodLevel(20);
			p.getFoodData().setSaturation(5);
		}
		if (over < 0 && t - start >= WARMUP + MATCH) { // time: the leader wins
			UUID lead = leader();
			ServerPlayer p = lead == null ? null : server.getPlayerList().getPlayer(lead);
			if (p != null) win(p);
			else newMatch();
		}
		if (over >= 0 && t - over >= RESTART) {
			newMatch();
			sendAll();
		}
		if (t % 20 == 0) sendAll();
	}

	private void sound(ServerPlayer p, String event) {
		dev.strafemc.weapon.GunServer.soundTo(p, event, p);
	}

	private void sendAll() {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) GameModes.send(p, state(p));
	}

	/** What the player's HUD shows. */
	String state(ServerPlayer p) {
		JsonObject o = new JsonObject();
		o.addProperty("mode", "armsrace");
		long t = now() - start;
		o.addProperty("phase", over >= 0 ? "over" : t < WARMUP ? "warmup" : "live");
		o.addProperty("time", over >= 0 ? Math.max(0, (RESTART - (now() - over)) / 20) : t < WARMUP ? (WARMUP - t) / 20 : (WARMUP + MATCH - t) / 20);
		int lv = level(p.getUUID());
		o.addProperty("level", lv + 1);
		o.addProperty("levels", weapons.size());
		o.addProperty("kills", kills.getOrDefault(p.getUUID(), 0) % KILLS_PER_LEVEL);
		o.addProperty("perLevel", KILLS_PER_LEVEL);
		o.addProperty("weapon", weapons.get(lv));
		o.addProperty("next", lv + 1 < weapons.size() ? weapons.get(lv + 1) : "");
		UUID lead = leader();
		ServerPlayer lp = lead == null ? null : server.getPlayerList().getPlayer(lead);
		o.addProperty("leader", lp == null ? "" : lp.getScoreboardName());
		o.addProperty("leaderLevel", lead == null ? 0 : level(lead) + 1);
		o.addProperty("winner", winner);
		o.addProperty("immune", Math.max(0, immuneUntil.getOrDefault(p.getUUID(), 0L) - now()) / 20.0);
		// everyone in the mode, for the team counter: highest level first, then who got there first
		List<ServerPlayer> all = new ArrayList<>();
		for (ServerPlayer q : server.getPlayerList().getPlayers()) if (q.hasAttached(GameModes.SAVED)) all.add(q);
		all.sort((x, y) -> {
			int c = Integer.compare(level(y.getUUID()), level(x.getUUID()));
			return c != 0 ? c : Long.compare(reached.getOrDefault(x.getUUID(), Long.MAX_VALUE), reached.getOrDefault(y.getUUID(), Long.MAX_VALUE));
		});
		var arr = new com.google.gson.JsonArray();
		for (ServerPlayer q : all) {
			JsonObject e = new JsonObject();
			e.addProperty("name", q.getScoreboardName());
			e.addProperty("id", q.getUUID().toString());
			e.addProperty("t", CsTeam.isT(q));
			e.addProperty("level", level(q.getUUID()) + 1);
			e.addProperty("weapon", weapons.get(level(q.getUUID())));
			e.addProperty("hp", q.isAlive() ? q.getHealth() / q.getMaxHealth() : 0);
			arr.add(e);
		}
		o.add("players", arr);
		return o.toString();
	}
}
