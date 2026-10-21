package dev.strafemc.mode;

import com.google.gson.JsonObject;
import dev.strafemc.weapon.Bots;
import dev.strafemc.weapon.CsArmor;
import dev.strafemc.weapon.CsTeam;
import dev.strafemc.weapon.GunItem;
import dev.strafemc.weapon.KnifeItem;
import dev.strafemc.weapon.PlantedC4;
import dev.strafemc.weapon.WeaponItems;
import dev.strafemc.world.SpawnBlock;
import dev.strafemc.world.SpawnPoints;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CS2's round-based bomb defusal modes, each from its gamemode config: Competitive (gamemode_competitive.cfg), Wingman
 * (gamemode_competitive2v2.cfg) and Casual (gamemode_casual.cfg). A warmup with respawns and money to spare, then
 * rounds: freeze time on the side's own spawn blocks, buying near them for the buy time, the round clock; a round ends
 * when a side is wiped out, the bomb goes off (T) or is defused (CT), or time runs out before a plant (CT). Money as CS2
 * pays it: a kill pays the weapon's kill award (m_nKillAward x cash_player_killed_enemy_factor), plant and defuse pay
 * the player, the winners get the reward for how they won, the losers the loss bonus that grows with each loss in a
 * row (cash_team_loser_bonus + _consecutive_rounds, up to four steps, CS2's counter going down one on a win), capped at
 * mp_maxmoney. Survivors keep their gear; the dead come back with the side's pistol. A T gets the C4 each round, which
 * plants only at a bomb site (BombSiteBlock). Halftime swaps the sides and resets money; the first to win more than
 * half the rounds wins the match, a tie on the last round is a draw.
 */
final class RoundMode extends Mode {
	/** One mode's numbers from its CS2 config. */
	record Config(String id, int maxRounds, boolean halftime, int freeze, int buy, int roundTime, int startMoney, int maxMoney, int killDefault,
			double killFactor, int teamElimination, int teamBomb, int teamDefuse, int teamTime, int loserBonus, int loserConsecutive, int plantedDefused,
			int playerPlant, int playerDefuse, boolean freeArmor, int warmup, int restartDelay, int startingLosses, double ffBullets, double ffGrenade,
			double ffOther, int flashLimit, int grenadeLimit) {}

	static final Config COMPETITIVE = new Config("competitive", 24, true, 15, 20, 115, 800, 16000, 300, 1, 3250, 3500, 3500, 3250, 1400, 500, 600, 300, 300,
			false, 120, 7, 1, 0.33, 0.85, 0.4, 2, 4);
	static final Config WINGMAN = new Config("wingman", 16, true, 10, 20, 90, 800, 8000, 300, 1, 2750, 3000, 3000, 2750, 2000, 300, 600, 300, 300,
			false, 120, 7, 0, 0.33, 0.85, 0.4, 2, 4);
	static final Config CASUAL = new Config("casual", 15, false, 5, 45, 180, 1000, 10000, 200, 0.5, 2700, 2700, 2700, 2700, 2400, 0, 200, 200, 200,
			true, 90, 10, 0, 0, 0, 0, 1, 3);
	/** How near a side's spawn blocks its players can buy (CS2's buy zones are drawn per map). FLAGGED ours. */
	static final double BUY_ZONE = 12;

	enum Phase { FREEZE, LIVE, OVER }

	private final Config c;
	private int round, scoreT, scoreCt, lossT, lossCt;
	private Phase phase = Phase.FREEZE;
	private long phaseStart;
	private final Map<UUID, Integer> money = new HashMap<>();
	/** Who died this round (they come back with the default pistol). */
	private final Set<UUID> diedThisRound = new HashSet<>();
	private PlantedC4 bomb;
	private UUID planter;
	private String roundWinner = "";

	RoundMode(MinecraftServer server, Config c) {
		super(server);
		this.c = c;
		reset();
	}

	@Override
	String id() {
		return c.id();
	}

	@Override
	Map<String, String> cvars() {
		Map<String, String> m = new HashMap<>();
		m.put("sv_infinite_ammo", "0");
		m.put("mp_free_armor", c.freeArmor() ? "2" : "0");
		m.put("mp_buy_anywhere", "0");
		m.put("mp_buytime", String.valueOf(c.buy()));
		m.put("mp_teammates_are_enemies", "0");
		m.put("mp_startmoney", String.valueOf(c.startMoney()));
		m.put("mp_maxmoney", String.valueOf(c.maxMoney()));
		m.put("ammo_grenade_limit_flashbang", String.valueOf(c.flashLimit()));
		m.put("ammo_grenade_limit_total", String.valueOf(c.grenadeLimit()));
		m.put("ff_damage_reduction_bullets", String.valueOf(c.ffBullets()));
		m.put("ff_damage_reduction_grenade", String.valueOf(c.ffGrenade()));
		m.put("ff_damage_reduction_other", String.valueOf(c.ffOther()));
		return m;
	}

	@Override
	int warmupTicks() {
		return c.warmup() * 20;
	}

	/** The match has no clock of its own: it ends on rounds. */
	@Override
	int matchTicks() {
		return Integer.MAX_VALUE / 4;
	}

	boolean warmup() {
		return over < 0 && now() - start < warmupTicks();
	}

	@Override
	void reset() {
		round = 0;
		scoreT = scoreCt = 0;
		lossT = lossCt = c.startingLosses();
		money.clear();
		diedThisRound.clear();
		removeBomb();
		phase = Phase.FREEZE;
	}

	private int money(UUID id) {
		return money.getOrDefault(id, c.startMoney());
	}

	private void pay(LivingEntity e, int amount) {
		if (!(e instanceof ServerPlayer p)) return;
		money.put(p.getUUID(), Math.max(0, Math.min(c.maxMoney(), money(p.getUUID()) + amount)));
	}

	private void payTeam(boolean t, int amount) {
		for (ServerPlayer p : players()) if (CsTeam.isT(p) == t) pay(p, amount);
	}

	// ---------------------------------------------------------------- rounds

	@Override
	void begin() {
		super.begin(); // warmup: respawning like Deathmatch until the clock runs out
	}

	private void startRound() {
		round++;
		phase = Phase.FREEZE;
		phaseStart = now();
		roundWinner = "";
		removeBomb();
		planter = null;
		for (ServerPlayer p : players()) {
			if (p.isDeadOrDying()) server.getPlayerList().respawn(p, false, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
		}
		for (ServerPlayer p : players()) {
			p.setGameMode(GameType.ADVENTURE);
			SpawnPoints.respawn(p, CsTeam.isT(p) ? SpawnBlock.Side.T : SpawnBlock.Side.CT);
			p.setHealth(p.getMaxHealth());
			if (diedThisRound.contains(p.getUUID()) || round == 1) {
				var inv = p.getInventory();
				for (int i = 0; i < inv.getContainerSize(); i++) if (!inv.getItem(i).isEmpty() && !(inv.getItem(i).getItem() instanceof KnifeItem)) inv.setItem(i, ItemStack.EMPTY);
				give(p, 1, CsTeam.isT(p) ? "weapon_glock" : "weapon_hkp2000");
				knife(p);
				select(p, 1);
				CsArmor.set(p, c.freeArmor() ? 100 : 0, c.freeArmor());
			} else if (c.freeArmor()) CsArmor.set(p, 100, true);
			removeC4(p);
			p.inventoryMenu.broadcastChanges();
		}
		diedThisRound.clear();
		for (LivingEntity b : Bots.living(server)) { // bots back to their side's spawn
			var pick = SpawnPoints.pick(CsTeam.isT(b) ? SpawnBlock.Side.T : SpawnBlock.Side.CT);
			if (pick != null && b.isAlive()) b.teleportTo(pick.pos().x, pick.pos().y, pick.pos().z);
			if (b.isAlive()) b.setHealth(b.getMaxHealth());
		}
		List<ServerPlayer> ts = new ArrayList<>();
		for (ServerPlayer p : players()) if (CsTeam.isT(p)) ts.add(p);
		if (!ts.isEmpty()) { // one T carries the bomb
			ServerPlayer carrier = ts.get(new java.util.Random().nextInt(ts.size()));
			ItemStack c4 = new ItemStack(WeaponItems.C4);
			if (!carrier.getInventory().add(c4)) carrier.drop(c4, false);
		}
		sendAll();
	}

	private static void removeC4(ServerPlayer p) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(WeaponItems.C4)) inv.setItem(i, ItemStack.EMPTY);
	}

	private void removeBomb() {
		if (bomb != null && !bomb.isRemoved()) bomb.discard();
		bomb = null;
	}

	private void endRound(boolean tWins, int reward, String why) {
		phase = Phase.OVER;
		phaseStart = now();
		if (tWins) scoreT++;
		else scoreCt++;
		roundWinner = (tWins ? "Terrorists" : "Counter-Terrorists") + " win";
		// the winners' reward; the losers' bonus by their losses in a row (a win takes one off, as in CS2)
		payTeam(tWins, reward);
		int losses = tWins ? ++lossCt : ++lossT;
		if (tWins) lossT = Math.max(0, lossT - 1);
		else lossCt = Math.max(0, lossCt - 1);
		payTeam(!tWins, c.loserBonus() + c.loserConsecutive() * Math.max(0, Math.min(losses, 5) - 1));
		if (!tWins && planter != null) payTeam(true, c.plantedDefused());
		for (ServerPlayer p : server.getPlayerList().getPlayers()) p.sendSystemMessage(Component.literal(roundWinner + " (" + why + ")"));
		dev.strafemc.StrafeMc.LOG.info("[strafemc] strafeGM: round {} to {} ({}), {}-{}", round, tWins ? "T" : "CT", why, scoreCt, scoreT);
		sendAll();
	}

	/** After a round's end: the next round, halftime, or the match's end. */
	private void afterRound() {
		int half = c.maxRounds() / 2, toWin = half + 1;
		if (scoreT >= toWin || scoreCt >= toWin) {
			swapIfHalf(false);
			win(scoreT > scoreCt ? "Terrorists" : "Counter-Terrorists");
			return;
		}
		if (round >= c.maxRounds()) {
			win("Nobody (draw)");
			return;
		}
		if (c.halftime() && round == half) swapIfHalf(true);
		startRound();
	}

	/** Halftime: everyone to the other side, money back to the start, the scores go with the teams. */
	private void swapIfHalf(boolean swap) {
		if (!swap) return;
		for (LivingEntity e : participants()) CsTeam.set(e, !CsTeam.isT(e));
		int s = scoreT;
		scoreT = scoreCt;
		scoreCt = s;
		lossT = lossCt = c.startingLosses();
		money.clear();
		diedThisRound.clear();
		for (ServerPlayer p : players()) {
			diedThisRound.add(p.getUUID()); // a fresh start: the pistol round again
			p.sendSystemMessage(Component.literal("Halftime: you are now " + (CsTeam.isT(p) ? "a Terrorist" : "a Counter-Terrorist")));
		}
	}

	private int alive(boolean t) {
		int n = 0;
		for (LivingEntity e : participants())
			if (CsTeam.isT(e) == t && e.isAlive() && !(e instanceof ServerPlayer p && p.isSpectator())) n++;
		return n;
	}

	@Override
	void tick() {
		long t = now();
		if (warmup()) {
			super.tick();
			return;
		}
		if (round == 0 && over < 0) { // the warmup is over: the first round
			money.clear();
			startRound();
		}
		for (var e : new ArrayList<>(respawnAt.entrySet())) { // the dead watch until the next round
			if (e.getValue() > t) continue;
			respawnAt.remove(e.getKey());
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p != null && p.isDeadOrDying()) server.getPlayerList().respawn(p, false, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
		}
		for (ServerPlayer p : players()) {
			p.getFoodData().setFoodLevel(20);
			p.getFoodData().setSaturation(5);
		}
		if (over >= 0) {
			if (t - over >= RESTART) {
				reset();
				over = -1;
				winner = "";
				start = t - warmupTicks();
				startRound();
			}
		} else if (phase == Phase.FREEZE && t - phaseStart >= c.freeze() * 20L) {
			phase = Phase.LIVE;
			phaseStart = t;
		} else if (phase == Phase.LIVE) {
			boolean planted = bomb != null && !bomb.isRemoved() && !bomb.defused();
			int ta = alive(true), cta = alive(false);
			if (cta == 0 && ta > 0) endRound(true, c.teamElimination(), "Counter-Terrorists eliminated");
			else if (ta == 0 && !planted && cta > 0) endRound(false, c.teamElimination(), "Terrorists eliminated");
			else if (ta == 0 && cta == 0 && !planted) endRound(false, c.teamTime(), "everyone eliminated");
			else if (!planted && t - phaseStart >= c.roundTime() * 20L) endRound(false, c.teamTime(), "time ran out");
		} else if (phase == Phase.OVER && t - phaseStart >= c.restartDelay() * 20L) afterRound();
		if (t % 20 == 0) sendAll();
	}

	@Override
	void spawned(ServerPlayer p) {
		GameModes.putAway(p);
		if (warmup()) { // respawning with money to spare
			money.put(p.getUUID(), c.maxMoney());
			super.spawned(p);
			p.setGameMode(GameType.ADVENTURE);
			return;
		}
		if (diedThisRound.contains(p.getUUID()) && phase != Phase.FREEZE) { // dead until the next round: watching
			p.setGameMode(GameType.SPECTATOR);
			return;
		}
		p.setGameMode(GameType.ADVENTURE);
	}

	@Override
	void gear(ServerPlayer p) {
		give(p, 1, CsTeam.isT(p) ? "weapon_glock" : "weapon_hkp2000");
		knife(p);
		select(p, 1);
	}

	@Override
	void join(ServerPlayer p) {
		GameModes.putAway(p);
		if (warmup()) {
			super.join(p);
			return;
		}
		diedThisRound.add(p.getUUID()); // joined mid-round: in from the next one
		p.setGameMode(GameType.SPECTATOR);
		SpawnPoints.respawn(p, CsTeam.isT(p) ? SpawnBlock.Side.T : SpawnBlock.Side.CT);
	}

	@Override
	void died(ServerPlayer p) {
		if (!warmup()) diedThisRound.add(p.getUUID());
		respawnAt.put(p.getUUID(), now() + RESPAWN);
	}

	/** The guns and the C4 drop where a player dies (mp_death_drop_gun 1: CS2 drops the best gun; ours drops both). */
	void deathDrops(ServerPlayer p) {
		if (warmup()) return;
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(WeaponItems.C4) || s.getItem() instanceof GunItem && !(s.getItem() instanceof KnifeItem)
					&& !(s.getItem() instanceof dev.strafemc.weapon.GrenadeItem)) {
				p.drop(s.copy(), true, false);
				inv.setItem(i, ItemStack.EMPTY);
			}
		}
	}

	@Override
	boolean allowDamage(LivingEntity victim, DamageSource src) {
		if (!inMode(victim)) return true;
		if (victim instanceof ServerPlayer v && warmup() && immune(v.getUUID())) return false;
		return true; // friendly fire is reduced where the damage is dealt (ff_damage_reduction_*)
	}

	@Override
	void kill(LivingEntity attacker, LivingEntity victim, String icon) {
		if (warmup() || over >= 0 || attacker == null || attacker == victim || !inMode(attacker) || !inMode(victim)) return;
		scored(attacker, victim, icon);
		sendAll();
	}

	@Override
	void scored(LivingEntity attacker, LivingEntity victim, String icon) {
		if (CsTeam.isT(attacker) == CsTeam.isT(victim)) { // a teammate: cash_player_killed_teammate
			pay(attacker, -300);
			return;
		}
		pay(attacker, killAward(icon));
	}

	/** The weapon's kill award (weapons.vdata m_nKillAward) x cash_player_killed_enemy_factor. */
	private int killAward(String icon) {
		String cls = icon.contains("knife") || icon.contains("bayonet") ? "weapon_knife" : "weapon_" + icon;
		var w = dev.strafemc.data.WeaponData.get(cls);
		Object v = w == null ? null : w.get("m_nKillAward");
		int award = v instanceof Number n ? n.intValue() : c.killDefault();
		return (int) Math.round(award * c.killFactor());
	}

	// ---------------------------------------------------------------- the bomb

	String plantRefusal(ServerPlayer p) {
		if (warmup()) return "";
		if (phase != Phase.LIVE) return "You can't plant the bomb now";
		if (!CsTeam.isT(p)) return "Only Terrorists plant the bomb";
		return BombSites.near(p) ? "" : "You must be at a bomb site to plant";
	}

	void planted(ServerPlayer p, PlantedC4 b) {
		if (warmup()) return;
		bomb = b;
		planter = p.getUUID();
		pay(p, c.playerPlant());
		for (ServerPlayer o : server.getPlayerList().getPlayers()) o.sendSystemMessage(Component.literal("The bomb has been planted."));
	}

	void exploded(PlantedC4 b) {
		if (b == bomb && phase == Phase.LIVE && over < 0) endRound(true, c.teamBomb(), "the bomb exploded");
	}

	void defused(ServerPlayer by, PlantedC4 b) {
		if (b != bomb || phase != Phase.LIVE || over >= 0) return;
		pay(by, c.playerDefuse());
		endRound(false, c.teamDefuse(), "the bomb was defused");
	}

	/** Tests: the warmup, or the freeze time, over at once. */
	@Override
	void skipWarmup() {
		if (warmup()) super.skipWarmup();
		else if (phase == Phase.FREEZE) phaseStart -= c.freeze() * 20L;
	}

	boolean botMayRespawn() {
		return warmup() || phase == Phase.FREEZE;
	}

	boolean frozen() {
		return !warmup() && over < 0 && phase == Phase.FREEZE;
	}

	// ---------------------------------------------------------------- buying

	@Override
	String buyRefusal(ServerPlayer p) {
		if (!warmup()) {
			if (phase == Phase.OVER) return "You cannot buy now";
			long since = now() - phaseStart + (phase == Phase.LIVE ? c.freeze() * 20L : 0);
			if (phase == Phase.LIVE && since > (c.freeze() + c.buy()) * 20L) return "The " + c.buy() + " second buy period has expired";
		}
		return BombSites.inBuyZone(p, BUY_ZONE) ? "" : "You are not in a buy zone";
	}

	/** Takes the price; false (and a message) when they can't afford it. */
	boolean charge(ServerPlayer p, int price) {
		int have = money(p.getUUID());
		if (have < price) {
			dev.strafemc.server.Toasts.warn(p, "Insufficient funds");
			return false;
		}
		money.put(p.getUUID(), have - price);
		return true;
	}

	void refund(ServerPlayer p, int price) {
		pay(p, price);
	}

	@Override
	void bought(ServerPlayer p, String cls) {
		boolean sec = Deathmatch.pistol(cls);
		var inv = p.getInventory();
		int keep = -1;
		for (int i = inv.getContainerSize() - 1; i >= 0; i--) { // one primary and one pistol: the old one drops, as in CS
			ItemStack s = inv.getItem(i);
			if (s.getItem() instanceof GunItem g && !(s.getItem() instanceof KnifeItem) && !(s.getItem() instanceof dev.strafemc.weapon.GrenadeItem)
					&& !s.is(WeaponItems.C4) && dev.strafemc.weapon.WeaponTable.get(g.weaponClass) != null && Deathmatch.pistol(g.weaponClass) == sec) {
				if (keep < 0 && g.weaponClass.equals(cls)) {
					keep = i;
					continue;
				}
				p.drop(s.copy(), true, false);
				inv.setItem(i, ItemStack.EMPTY);
			}
		}
		if (keep >= 0) {
			ItemStack s = inv.getItem(keep);
			inv.setItem(keep, ItemStack.EMPTY);
			inv.setItem(sec ? 1 : 0, s);
		}
		select(p, sec ? 1 : 0);
	}

	// ---------------------------------------------------------------- HUD

	@Override
	String leaderName() {
		return null;
	}

	@Override
	List<LivingEntity> ranking() {
		return participants();
	}

	@Override
	String state(ServerPlayer p) {
		String base = super.state(p);
		JsonObject o = com.google.gson.JsonParser.parseString(base).getAsJsonObject();
		long t = now();
		if (!warmup() && over < 0) { // the round's clock: freeze time, the round, the bomb, the pause after
			int left = switch (phase) {
				case FREEZE -> (int) ((c.freeze() * 20L - (t - phaseStart)) / 20);
				case LIVE -> (int) ((c.roundTime() * 20L - (t - phaseStart)) / 20);
				case OVER -> (int) ((c.restartDelay() * 20L - (t - phaseStart)) / 20);
			};
			o.addProperty("time", Math.max(0, left));
			o.addProperty("phase", phase == Phase.FREEZE ? "freeze" : phase == Phase.OVER ? "roundover" : "live");
		}
		return o.toString();
	}

	@Override
	void state(ServerPlayer p, JsonObject o) {
		o.addProperty("money", money(p.getUUID()));
		o.addProperty("scoreT", scoreT);
		o.addProperty("scoreCT", scoreCt);
		o.addProperty("round", round);
		o.addProperty("rounds", c.maxRounds());
		o.addProperty("roundWinner", roundWinner);
		o.addProperty("bomb", bomb != null && !bomb.isRemoved() && !bomb.defused());
		o.addProperty("frozen", frozen());
		o.addProperty("teams", true);
	}

	@Override
	void entry(LivingEntity e, JsonObject o) {
		o.addProperty("value", "");
	}
}
