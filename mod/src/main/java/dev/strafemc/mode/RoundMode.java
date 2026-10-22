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
			double ffOther, int flashLimit, int grenadeLimit) {
		/** gamemode_casual.cfg's mp_defuser_allocation 2 (competitive and wingman: 0). */
		boolean freeDefuser() {
			return id.equals("casual");
		}
	}

	static final Config COMPETITIVE = new Config("competitive", 24, true, 15, 20, 115, 800, 16000, 300, 1, 3250, 3500, 3500, 3250, 1400, 500, 600, 300, 300,
			false, 120, 7, 1, 0.33, 0.85, 0.4, 2, 4);
	static final Config WINGMAN = new Config("wingman", 16, true, 10, 20, 90, 800, 8000, 300, 1, 2750, 3000, 3000, 2750, 2000, 300, 600, 300, 300,
			false, 120, 7, 0, 0.33, 0.85, 0.4, 2, 4);
	static final Config CASUAL = new Config("casual", 15, false, 5, 45, 180, 1000, 10000, 200, 0.5, 2700, 2700, 2700, 2700, 2400, 0, 200, 200, 200,
			true, 90, 10, 0, 0, 0, 0, 1, 3);
	/** CS2's overtime: halves of mp_overtime_maxrounds / 2 rounds with mp_overtime_startmoney each (when strafemc_overtime is on). */
	static final int OT_ROUNDS = 6, OT_MONEY = 10000;
	/** How long a bot holds still to plant (CS2's C4 arming time). */
	static final int BOT_PLANT_TICKS = 60;

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
	/** Overtimes played (0: regulation). */
	private int ot;
	/** What each bot carries (its bought gun or the side's pistol), which bot has the bomb, its planting. */
	private final Map<UUID, String> botGun = new HashMap<>();
	private UUID bombBot;
	private long botPlantStart = -1;
	/** This round: kills per participant (for the MVP), who defused, whether the ten-second cues played. */
	private final Map<UUID, Integer> roundKills = new HashMap<>();
	private UUID defuser;
	private boolean tenSecPlayed, bombTenPlayed;
	private String site = "";

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
		m.put("mp_forcecamera", id().equals("casual") ? "0" : "1"); // gamemode_casual.cfg 0, competitive / competitive2v2 1
		m.put("spec_freeze_time", id().equals("casual") ? "3.0" : "2.0");
		m.put("mp_limitteams", "2"); // CS2's default (its round mode configs don't set it)
		m.put("mp_autoteambalance", "0"); // ours: off unless an operator turns it on (CS2's default is 1)
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
		ot = 0;
		botGun.clear();
		bombBot = null;
		stats.clear();
	}

	@Override
	boolean counting() {
		return !warmup() && over < 0;
	}

	@Override
	void record(LivingEntity attacker, LivingEntity victim, LivingEntity assister) {
		super.record(attacker, victim, assister);
		if (counting() && phase == Phase.LIVE && attacker != null && attacker != victim && inMode(attacker) && CsTeam.isT(attacker) != CsTeam.isT(victim))
			roundKills.merge(key(attacker), 1, Integer::sum);
	}

	private int money(UUID id) {
		return money.getOrDefault(id, c.startMoney());
	}

	/** Money goes to players and bots alike (bots spend theirs at the start of a round). */
	private void pay(LivingEntity e, int amount) {
		if (e == null) return;
		UUID k = key(e);
		money.put(k, Math.max(0, Math.min(maxMoney(), money(k) + amount)));
	}

	private int maxMoney() {
		return ot > 0 ? Math.max(c.maxMoney(), OT_MONEY) : c.maxMoney();
	}

	private void payTeam(boolean t, int amount) {
		for (LivingEntity e : participants()) if (CsTeam.isT(e) == t) pay(e, amount);
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
		defuser = null;
		roundKills.clear();
		tenSecPlayed = bombTenPlayed = false;
		site = "";
		soundAll("Music.StartRound.valve_cs2_01");
		for (ServerPlayer p : players()) {
			if (p.isDeadOrDying()) server.getPlayerList().respawn(p, false, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
		}
		watching.clear();
		freeCam.clear();
		for (ServerPlayer p : players()) {
			p.setGameMode(GameType.ADVENTURE);
			p.setCamera(p);
			SpawnPoints.respawn(p, CsTeam.isT(p) ? SpawnBlock.Side.T : SpawnBlock.Side.CT);
			p.setHealth(p.getMaxHealth());
			if (diedThisRound.contains(p.getUUID()) || round == 1) {
				var inv = p.getInventory();
				for (int i = 0; i < inv.getContainerSize(); i++) if (!inv.getItem(i).isEmpty() && !(inv.getItem(i).getItem() instanceof KnifeItem)) inv.setItem(i, ItemStack.EMPTY);
				give(p, 1, dev.strafemc.weapon.MenuLoadout.startPistol(p));
				knife(p);
				select(p, 1);
				CsArmor.set(p, c.freeArmor() ? 100 : 0, c.freeArmor());
			} else if (c.freeArmor()) CsArmor.set(p, 100, true);
			if (c.freeDefuser() && !CsTeam.isT(p)) { // mp_defuser_allocation 2: every CT gets a kit
				boolean has = false;
				for (int i = 0; i < p.getInventory().getContainerSize(); i++) has |= p.getInventory().getItem(i).is(WeaponItems.DEFUSE_KIT);
				if (!has) p.getInventory().add(new ItemStack(WeaponItems.DEFUSE_KIT));
			}
			removeC4(p);
			p.inventoryMenu.broadcastChanges();
		}
		for (LivingEntity b : Bots.living(server)) { // bots back to their side's spawn, buying with their money
			var pick = SpawnPoints.pick(CsTeam.isT(b) ? SpawnBlock.Side.T : SpawnBlock.Side.CT);
			if (pick != null && b.isAlive()) b.teleportTo(pick.pos().x, pick.pos().y, pick.pos().z);
			if (b.isAlive()) b.setHealth(b.getMaxHealth());
			if (diedThisRound.contains(key(b)) || round == 1) botGun.remove(key(b));
			botBuy(b);
		}
		diedThisRound.clear();
		bombBot = null;
		botPlantStart = -1;
		List<LivingEntity> ts = new ArrayList<>();
		for (LivingEntity e : participants()) if (CsTeam.isT(e) && e.isAlive()) ts.add(e);
		if (!ts.isEmpty()) { // one T carries the bomb
			LivingEntity carrier = ts.get(new java.util.Random().nextInt(ts.size()));
			if (carrier instanceof ServerPlayer cp) {
				ItemStack c4 = new ItemStack(WeaponItems.C4);
				if (!cp.getInventory().add(c4)) cp.drop(c4, false);
			} else bombBot = key(carrier);
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
		if (needsBalance()) // CS2 says it at the round's end and moves players when the next round starts
			for (ServerPlayer p : server.getPlayerList().getPlayers()) p.sendSystemMessage(Component.literal("Auto-team balance next round"));
		// CS2's announcer and the music kit's round end, then the round's MVP: whoever planted the bomb that went off,
		// defused it, or else the winners' top fragger
		if (!tWins && defuser != null) soundAll("Event.BombDefused");
		soundAll(tWins ? "Event.TERWin" : "Event.CTWin");
		for (ServerPlayer p : players()) sound(p, CsTeam.isT(p) == tWins ? "Music.WonRound.valve_cs2_01" : "Music.LostRound.valve_cs2_01");
		UUID mvp = why.contains("exploded") ? planter : defuser;
		if (mvp == null) {
			int best = 0;
			for (LivingEntity e : participants())
				if (CsTeam.isT(e) == tWins && roundKills.getOrDefault(key(e), 0) > best) {
					best = roundKills.get(key(e));
					mvp = key(e);
				}
		}
		if (mvp != null) {
			stats.computeIfAbsent(mvp, k -> new int[4])[3]++;
			for (LivingEntity e : participants())
				if (key(e).equals(mvp)) {
					for (ServerPlayer o : server.getPlayerList().getPlayers()) o.sendSystemMessage(Component.literal("MVP: " + name(e)));
					if (e instanceof ServerPlayer mp) sound(mp, "Music.MVPAnthem.valve_cs2_01");
				}
		}
		dev.strafemc.StrafeMc.LOG.info("[strafemc] strafeGM: round {} to {} ({}), {}-{}", round, tWins ? "T" : "CT", why, scoreCt, scoreT);
		sendAll();
	}

	/**
	 * After a round's end: the next round, halftime, or the match's end. With overtime on, a tie on the last round
	 * plays overtimes of OT_ROUNDS (CS2's mp_overtime_maxrounds 6), each half starting everyone on OT_MONEY
	 * (mp_overtime_startmoney 10000) with sides swapped at its middle; the wins needed go up by half an overtime each
	 * time. FLAGGED: the sides stay as they ended regulation for the first overtime half.
	 */
	private void afterRound() {
		int half = c.maxRounds() / 2, toWin = half + 1 + ot * OT_ROUNDS / 2;
		if (scoreT >= toWin || scoreCt >= toWin) {
			win(scoreT > scoreCt ? "Terrorists" : "Counter-Terrorists");
			return;
		}
		int last = c.maxRounds() + ot * OT_ROUNDS;
		if (round >= last) {
			if (!dev.strafemc.server.StrafeSettings.overtime()) {
				win("Nobody (draw)");
				return;
			}
			ot++;
			overtimeMoney();
			for (ServerPlayer p : players()) p.sendSystemMessage(Component.literal("Overtime " + ot));
		} else if (c.halftime() && round == half) swapIfHalf(true);
		else if (ot > 0 && round == last - OT_ROUNDS / 2) {
			swapIfHalf(true);
			overtimeMoney();
		}
		balance();
		startRound();
	}

	@Override
	boolean carriesBomb(LivingEntity e) {
		if (e instanceof ServerPlayer p) {
			for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(WeaponItems.C4)) return true;
			return false;
		}
		return key(e).equals(bombBot);
	}

	private void overtimeMoney() {
		for (LivingEntity e : participants()) money.put(key(e), OT_MONEY);
		lossT = lossCt = 0;
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
		botGun.clear();
		for (LivingEntity e : Bots.living(server)) diedThisRound.add(key(e));
		for (ServerPlayer p : players()) {
			diedThisRound.add(p.getUUID()); // a fresh start: the pistol round again
			p.sendSystemMessage(Component.literal("Halftime: you are now " + (CsTeam.isT(p) ? "a Terrorist" : "a Counter-Terrorist")));
		}
	}

	/** Someone on each side (players or bots). */
	private boolean bothSides() {
		boolean t = false, ct = false;
		for (LivingEntity e : participants())
			if (CsTeam.isT(e)) t = true;
			else ct = true;
		return t && ct;
	}

	private int alive(boolean t) {
		int n = 0;
		for (LivingEntity e : participants())
			if (CsTeam.isT(e) == t && e.isAlive() && !(e instanceof ServerPlayer p && p.isSpectator())) n++;
		return n;
	}

	/** Dead players watching someone: whom each watches. */
	private final Map<UUID, UUID> watching = new HashMap<>();
	/** Dead players flying free (mp_forcecamera 0, after letting go with sneak): until they click onto someone. */
	private final Set<UUID> freeCam = new HashSet<>();

	/** CS2's mp_forcecamera: 0 anyone (and a free camera), 1 own team only, 2 nobody (the screen goes black). */
	int forceCamera() {
		try {
			return (int) Double.parseDouble(String.valueOf(dev.strafemc.bridge.Cs2Bridge.INSTANCE.cvar("mp_forcecamera")));
		} catch (NumberFormatException e) {
			return 1;
		}
	}

	/**
	 * Keeps a dead (spectating) player's view on a living player as CS2's mp_forcecamera allows: step 0 stays on the
	 * one watched while they live, 1 / -1 go to the next / previous one (mouse 1 / mouse 2). Teammates come first; with
	 * nobody to watch the view stays where it is.
	 */
	void spectate(ServerPlayer p, int step) {
		if (!p.isSpectator() || warmup()) return;
		int mode = forceCamera();
		UUID cur = watching.get(p.getUUID());
		if (mode >= 2) { // nobody: their own view, blacked out by the HUD
			if (p.getCamera() != p) p.setCamera(p);
			if (watching.remove(p.getUUID()) != null) GameModes.send(p, state(p));
			return;
		}
		if (mode == 0) {
			if (step == 0 && cur != null && p.getCamera() == p) { // let go (sneak): a free camera until they click
				watching.remove(p.getUUID());
				freeCam.add(p.getUUID());
				GameModes.send(p, state(p));
				return;
			}
			if (step == 0 && freeCam.contains(p.getUUID())) return;
		}
		freeCam.remove(p.getUUID());
		List<LivingEntity> mates = new ArrayList<>();
		for (LivingEntity e : participants())
			if (e != p && e.isAlive() && (mode == 0 || CsTeam.isT(e) == CsTeam.isT(p)) && !(e instanceof ServerPlayer sp && sp.isSpectator())) mates.add(e);
		mates.sort(java.util.Comparator.comparing((LivingEntity e) -> CsTeam.isT(e) != CsTeam.isT(p)).thenComparing(Mode::name));
		if (mates.isEmpty()) {
			if (p.getCamera() != p) p.setCamera(p);
			if (watching.remove(p.getUUID()) != null) GameModes.send(p, state(p));
			return;
		}
		int at = -1;
		for (int i = 0; i < mates.size(); i++) if (mates.get(i).getUUID().equals(cur)) at = i;
		LivingEntity target = mates.get(at < 0 ? 0 : Math.floorMod(at + step, mates.size()));
		if (p.getCamera() != target) p.setCamera(target);
		if (!target.getUUID().equals(cur)) {
			watching.put(p.getUUID(), target.getUUID());
			GameModes.send(p, state(p));
		}
	}

	@Override
	void tick() {
		long t = now();
		if (!warmup() && t % 5 == 0) for (ServerPlayer p : players()) if (p.isSpectator()) spectate(p, 0);
		if (warmup()) {
			super.tick();
			return;
		}
		if (round == 0 && over < 0) { // the warmup is over: the first round, once both sides have someone (CS2 waits for players)
			if (!bothSides()) {
				start = t - warmupTicks() + 20; // another second of warmup
				super.tick();
				return;
			}
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
			soundAll("Music.StartAction.valve_cs2_01");
		} else if (phase == Phase.LIVE) {
			boolean planted = bomb != null && !bomb.isRemoved() && !bomb.defused();
			// the music kit's ten-second count: the round's last ten seconds, or the bomb's
			if (!planted && !tenSecPlayed && c.roundTime() * 20L - (t - phaseStart) <= 200) {
				tenSecPlayed = true;
				soundAll("Music.TenSecCount.valve_cs2_01");
			}
			if (planted && !bombTenPlayed && bomb.secondsLeft() <= 10) {
				bombTenPlayed = true;
				soundAll("Music.BombTenSecCount.valve_cs2_01");
			}
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
		if (diedThisRound.contains(p.getUUID()) && phase != Phase.FREEZE) { // dead until the next round: watching a teammate
			p.setGameMode(GameType.SPECTATOR);
			spectate(p, 0);
			return;
		}
		p.setGameMode(GameType.ADVENTURE);
	}

	@Override
	void gear(ServerPlayer p) {
		give(p, 1, dev.strafemc.weapon.MenuLoadout.startPistol(p));
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
		spectate(p, 0);
	}

	@Override
	void died(ServerPlayer p) {
		if (!warmup()) diedThisRound.add(p.getUUID());
		respawnAt.put(p.getUUID(), now() + respawnTicks()); // the death camera first, then watching (or back in, in the warmup)
	}

	/**
	 * What drops where a player dies, as CS2's round modes set it: the best gun (mp_death_drop_gun 1: the primary, else
	 * the pistol), the grenade in hand or else the best one (mp_death_drop_grenade 2), the defuse kit
	 * (mp_death_drop_defuser 1) and the bomb. The best grenade's order is ours (FLAGGED): molotov / incendiary, HE,
	 * smoke, flash, decoy.
	 */
	void deathDrops(ServerPlayer p) {
		if (warmup()) return;
		var inv = p.getInventory();
		int primary = -1, pistol = -1, grenade = -1, grenadeRank = 99;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(WeaponItems.C4) || s.is(WeaponItems.DEFUSE_KIT)) {
				p.drop(s.copy(), true, false);
				inv.setItem(i, ItemStack.EMPTY);
			} else if (s.getItem() instanceof dev.strafemc.weapon.GrenadeItem g) {
				int rank = i == inv.getSelectedSlot() ? -1 : grenadeRank(g.weaponClass);
				if (rank < grenadeRank) {
					grenadeRank = rank;
					grenade = i;
				}
			} else if (s.getItem() instanceof GunItem g && !(s.getItem() instanceof KnifeItem) && dev.strafemc.weapon.WeaponTable.get(g.weaponClass) != null) {
				if (Deathmatch.pistol(g.weaponClass)) {
					if (pistol < 0) pistol = i;
				} else if (primary < 0) primary = i;
			}
		}
		for (int i : new int[] {primary >= 0 ? primary : pistol, grenade})
			if (i >= 0) {
				ItemStack s = inv.getItem(i);
				p.drop(s.copyWithCount(1), true, false);
				s.shrink(1);
			}
	}

	private static int grenadeRank(String cls) {
		return switch (cls) {
			case "weapon_molotov", "weapon_incgrenade" -> 0;
			case "weapon_hegrenade" -> 1;
			case "weapon_smokegrenade" -> 2;
			case "weapon_flashbang" -> 3;
			default -> 4;
		};
	}

	@Override
	boolean allowDamage(LivingEntity victim, DamageSource src) {
		if (!inMode(victim)) return true;
		if (victim instanceof ServerPlayer v && warmup() && immune(v.getUUID())) return false;
		return true; // friendly fire is reduced where the damage is dealt (ff_damage_reduction_*)
	}

	@Override
	void kill(LivingEntity attacker, LivingEntity victim, String icon) {
		if (warmup() || over >= 0) return;
		if (Bots.isBot(victim)) { // a bot's death: its gun is gone, and the bomb drops where it fell
			diedThisRound.add(key(victim));
			botGun.remove(key(victim));
			if (key(victim).equals(bombBot)) {
				bombBot = null;
				botPlantStart = -1;
				victim.spawnAtLocation((net.minecraft.server.level.ServerLevel) victim.level(), new ItemStack(WeaponItems.C4));
			}
		}
		if (attacker == null || attacker == victim || !inMode(attacker) || !inMode(victim)) return;
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

	void planted(LivingEntity p, PlantedC4 b) {
		if (warmup()) return;
		bomb = b;
		planter = key(p);
		pay(p, c.playerPlant());
		site = BombSites.siteLetter(p);
		b.setSite(site);
		for (ServerPlayer o : server.getPlayerList().getPlayers())
			o.sendSystemMessage(Component.literal("The bomb has been planted" + (site.isEmpty() ? "." : " at bomb site " + site + ".")));
		soundAll("Event.BombPlanted");
		soundAll("Music.BombPlanted.valve_cs2_01");
	}

	void exploded(PlantedC4 b) {
		if (b == bomb && phase == Phase.LIVE && over < 0) endRound(true, c.teamBomb(), "the bomb exploded");
	}

	void defused(LivingEntity by, PlantedC4 b) {
		if (b != bomb || phase != Phase.LIVE || over >= 0) return;
		pay(by, c.playerDefuse());
		defuser = key(by);
		endRound(false, c.teamDefuse(), "the bomb was defused");
	}

	/** Tests: the warmup, or the freeze time, over at once. */
	@Override
	void skipWarmup() {
		if (warmup()) super.skipWarmup();
		else if (phase == Phase.FREEZE) phaseStart -= c.freeze() * 20L;
	}

	// ---------------------------------------------------------------- bots

	/**
	 * A bot's buy at the start of a round, with its own money: the side's rifle if it can afford it, else the cheaper
	 * rifle, else an SMG; a bot that kept its gun keeps it. FLAGGED ours (CS2's bots buy by their own scripts): no armor
	 * or grenades.
	 */
	private void botBuy(LivingEntity b) {
		UUID k = key(b);
		String have = botGun.get(k);
		if (have != null && !Deathmatch.pistol(have)) return;
		String[] options = CsTeam.isT(b) ? new String[] {"weapon_ak47", "weapon_galilar", "weapon_mac10"}
				: new String[] {"weapon_m4a1", "weapon_famas", "weapon_mp9"};
		for (String o : options) {
			int price = dev.strafemc.weapon.BuyMenu.price(o, 0);
			if (price > 0 && money(k) >= price) {
				money.put(k, money(k) - price);
				botGun.put(k, o);
				return;
			}
		}
	}

	@Override
	String botWeapon(LivingEntity bot) {
		if (warmup()) return null;
		String g = botGun.get(key(bot));
		return g != null ? g : CsTeam.isT(bot) ? "weapon_glock" : "weapon_hkp2000";
	}

	/** The bomb carrier heads for the nearest bomb site; once it's planted, CTs head for the bomb. */
	net.minecraft.world.phys.Vec3 botGoal(LivingEntity bot) {
		if (warmup() || over >= 0 || phase != Phase.LIVE) return null;
		boolean planted = bomb != null && !bomb.isRemoved() && !bomb.defused();
		if (CsTeam.isT(bot) && !planted && key(bot).equals(bombBot)) {
			var site = BombSites.nearest(bot);
			if (site != null && bot.position().distanceToSqr(site) > 2.25) botPlantStart = -1; // walked off: start again
			return site;
		}
		if (!CsTeam.isT(bot) && planted) return bomb.position();
		return null;
	}

	/** At its objective: the carrier plants after CS2's arming time; a CT defuses (the bomb's own timing). */
	void botAtGoal(LivingEntity bot) {
		if (warmup() || phase != Phase.LIVE) return;
		if (CsTeam.isT(bot) && key(bot).equals(bombBot) && BombSites.near(bot)) {
			if (botPlantStart < 0) {
				botPlantStart = now();
				dev.strafemc.weapon.GunServer.soundAt((net.minecraft.server.level.ServerLevel) bot.level(), "c4.plant", bot.position(), bot);
			} else if (now() - botPlantStart >= BOT_PLANT_TICKS) {
				PlantedC4 b = new PlantedC4(bot.level(), null, new ItemStack(WeaponItems.C4));
				b.setPos(bot.getX(), bot.getY(), bot.getZ());
				b.setYRot(bot.getYRot());
				bot.level().addFreshEntity(b);
				bombBot = null;
				botPlantStart = -1;
				planted(bot, b);
			}
		} else if (!CsTeam.isT(bot) && bomb != null && !bomb.isRemoved()) bomb.defusePing(bot);
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
		return BombSites.inBuyZone(p, dev.strafemc.server.StrafeSettings.buyZone()) ? "" : "You are not in a buy zone";
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
		o.addProperty("ot", ot);
		o.addProperty("site", site);
		o.addProperty("waiting", warmup() && now() - start >= warmupTicks() - 40 && !bothSides());
		// CS2's money panel cart: you can buy here and now; the bomb icon: you carry it inside a bomb site
		o.addProperty("buyZone", p.isAlive() && !p.isSpectator() && buyRefusal(p).isEmpty());
		boolean carrying = false;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(WeaponItems.C4)) carrying = true;
		o.addProperty("bombZone", carrying && BombSites.near(p));
		o.addProperty("carrier", carrying);
		LivingEntity watched = null;
		UUID w = p.isSpectator() ? watching.get(p.getUUID()) : null;
		if (w != null) for (LivingEntity e : participants()) if (e.getUUID().equals(w)) watched = e;
		if (watched != null) o.addProperty("watching", name(watched));
		if (p.isSpectator() && !warmup()) {
			if (freeCam.contains(p.getUUID())) o.addProperty("freeCam", true);
			if (forceCamera() >= 2) o.addProperty("blackout", true);
		}
	}

	@Override
	void entry(LivingEntity e, JsonObject o, boolean ally) {
		o.addProperty("value", "");
		if (ally) o.addProperty("money", money(key(e))); // teammates' money only
	}
}
