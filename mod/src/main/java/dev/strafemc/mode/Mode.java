package dev.strafemc.mode;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.strafemc.weapon.Bots;
import dev.strafemc.weapon.CsArmor;
import dev.strafemc.weapon.CsTeam;
import dev.strafemc.weapon.GunItem;
import dev.strafemc.weapon.WeaponItems;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What every strafeGM mode shares: players (and bots) taking part, a warmup and a match clock, respawns on random spawn
 * blocks after the death cam with spawn protection that ends on firing (mp_respawn_immunitytime), kevlar and helmet
 * on spawning (mp_free_armor 2), no damage between teammates unless everyone is an enemy (mp_teammates_are_enemies),
 * hunger kept full, and a new match mp_match_restart_delay (CS2's default 25 s) after one ends.
 */
abstract class Mode {
	/** CS2's mp_match_restart_delay default. */
	static final int RESTART = 25 * 20;

	final MinecraftServer server;
	final Map<UUID, Long> immuneUntil = new HashMap<>(), respawnAt = new HashMap<>();
	/** Kills, assists, deaths and MVPs this match, per participant (Mode.key). */
	final Map<UUID, int[]> stats = new HashMap<>();
	/** Players who picked their side themselves (the rest are balanced when they join). */
	final java.util.Set<UUID> picked = new java.util.HashSet<>();
	long start, over = -1;
	String winner = "";

	Mode(MinecraftServer server) {
		this.server = server;
	}

	abstract String id();

	/** The mode's cvars that the rest of strafeMC reads (its gamemode_*.cfg). */
	abstract Map<String, String> cvars();

	abstract int warmupTicks();

	abstract int matchTicks();

	/** Everyone fights everyone. */
	boolean ffa() {
		return false;
	}

	/** The player's gear on spawning (the inventory is already cleared of guns). */
	abstract void gear(ServerPlayer p);

	/** A kill by a participant (player or bot) of another, during the match. */
	abstract void scored(LivingEntity attacker, LivingEntity victim, String icon);

	/** Who's ahead when time runs out (null: nobody scored). */
	abstract String leaderName();

	/** The mode's own part of a player's HUD state. */
	abstract void state(ServerPlayer p, JsonObject o);

	/** A participant's HUD entry: their score or level and what's shown under it. */
	/** A scoreboard row's mode values; ally: on the viewer's team (or the viewer). */
	abstract void entry(LivingEntity e, JsonObject o, boolean ally);

	/** Participants in the order the HUD shows them. */
	abstract List<LivingEntity> ranking();

	/** The weapon a bot carries (null: its own rifle). */
	String botWeapon(LivingEntity bot) {
		return null;
	}

	/** Why the player can't buy now ("" if they can). */
	String buyRefusal(ServerPlayer p) {
		return "You cannot buy in this game mode";
	}

	/** After a purchase of a gun in this mode. */
	void bought(ServerPlayer p, String cls) {}

	long now() {
		return server.getTickCount();
	}

	boolean live() {
		return over < 0 && now() - start >= warmupTicks();
	}

	void skipWarmup() {
		start = now() - warmupTicks();
	}

	boolean immune(UUID id) {
		return immuneUntil.getOrDefault(id, 0L) > now();
	}

	static boolean inMode(LivingEntity e) {
		return e instanceof ServerPlayer p ? p.hasAttached(GameModes.SAVED) : Bots.isBot(e);
	}

	List<ServerPlayer> players() {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) if (p.hasAttached(GameModes.SAVED)) out.add(p);
		return out;
	}

	/** Players and bots taking part. */
	List<LivingEntity> participants() {
		List<LivingEntity> out = new ArrayList<>(players());
		out.addAll(Bots.living(server));
		return out;
	}

	/** Who a participant is across lives: a player's own id; a bot's from its name (each life is a new entity). */
	static UUID key(LivingEntity e) {
		return Bots.isBot(e) ? UUID.nameUUIDFromBytes(("bot:" + name(e)).getBytes(java.nio.charset.StandardCharsets.UTF_8)) : e.getUUID();
	}

	static String name(LivingEntity e) {
		return e instanceof ServerPlayer p ? p.getScoreboardName() : e.getName().getString().replaceFirst("^BOT ", "");
	}

	void begin() {
		start = now();
		over = -1;
		winner = "";
		for (ServerPlayer p : players()) {
			if (!p.isAlive()) continue;
			toSpawn(p);
			spawned(p);
		}
	}

	int[] stat(LivingEntity e) {
		return stats.computeIfAbsent(key(e), k -> new int[4]);
	}

	/** Whether kills count now (the match is on). */
	boolean counting() {
		return live();
	}

	/** The scoreboard's count of a kill: the killer's kill (an enemy, or anyone in a free-for-all), the victim's death, the assist. */
	void record(LivingEntity attacker, LivingEntity victim, LivingEntity assister) {
		if (!counting()) return;
		if (inMode(victim)) stat(victim)[2]++;
		if (attacker != null && attacker != victim && inMode(attacker) && (ffa() || CsTeam.isT(attacker) != CsTeam.isT(victim))) stat(attacker)[0]++;
		if (assister != null && assister != attacker && inMode(assister)) stat(assister)[1]++;
	}

	/** A CS2 sound event to everyone in the mode. */
	void soundAll(String event) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) sound(p, event);
	}

	/** The smaller side (by players and bots), T on a tie. */
	boolean smallerSideT() {
		int t = 0, ct = 0;
		for (LivingEntity e : participants())
			if (CsTeam.isT(e)) t++;
			else ct++;
		return t <= ct;
	}

	/** CS2's mp_limitteams (default 2, 0 = no limit): a side can't get more than that many players over the other. */
	boolean sideFull(boolean t, ServerPlayer joining) {
		int limit = (int) cvar("mp_limitteams", 2);
		if (limit <= 0) return false;
		int mine = 0, other = 0;
		for (LivingEntity e : participants()) {
			if (e == joining) continue;
			if (CsTeam.isT(e) == t) mine++;
			else other++;
		}
		return mine + 1 - other > limit;
	}

	/** When each participant (Mode.key) first took part: CS2's auto-balance moves whoever joined last. */
	final Map<UUID, Long> joinOrder = new HashMap<>();
	private long joins;

	long joinOrder(LivingEntity e) {
		return joinOrder.computeIfAbsent(key(e), k -> joins++);
	}

	/** T minus CT, players and bots. */
	int sideDifference() {
		int d = 0;
		for (LivingEntity e : participants()) d += CsTeam.isT(e) ? 1 : -1;
		return d;
	}

	/** mp_autoteambalance is on and one side has two or more players over the other (FLAGGED: CS2's threshold). */
	boolean needsBalance() {
		return !ffa() && cvar("mp_autoteambalance", 0) >= 1 && Math.abs(sideDifference()) >= 2;
	}

	/**
	 * CS2's auto-balance: half the difference moves from the bigger side to the smaller, whoever joined last first
	 * (bots added after the players are newest); the bomb carrier stays. True if anyone moved.
	 */
	boolean balance() {
		if (!needsBalance()) return false;
		int diff = sideDifference();
		boolean fromT = diff > 0;
		List<LivingEntity> side = new ArrayList<>();
		for (LivingEntity e : participants()) if (CsTeam.isT(e) == fromT && !carriesBomb(e)) side.add(e);
		side.sort((a, b) -> Long.compare(joinOrder(b), joinOrder(a)));
		int moves = Math.min(Math.abs(diff) / 2, side.size());
		for (int i = 0; i < moves; i++) {
			LivingEntity e = side.get(i);
			CsTeam.set(e, !fromT);
			if (e instanceof ServerPlayer p) dev.strafemc.server.Toasts.info(p, "You have been moved to the other team for game balance");
		}
		for (ServerPlayer o : server.getPlayerList().getPlayers()) dev.strafemc.server.Toasts.info(o, "The teams have been balanced");
		return moves > 0;
	}

	/**
	 * CS2's end of match line-up for one player (endofmatch-characters): free for all, the top three; else the player's
	 * own side, them first, then by kills, five at most. Each with their name, side and agent (bots: none, the side's).
	 */
	com.google.gson.JsonArray eom(ServerPlayer viewer) {
		List<LivingEntity> line = new ArrayList<>();
		if (ffa()) for (LivingEntity e : ranking()) {
			if (line.size() >= 3) break;
			line.add(e);
		}
		else {
			boolean t = CsTeam.isT(viewer);
			if (participants().contains(viewer)) line.add(viewer);
			List<LivingEntity> side = new ArrayList<>(participants().stream().filter(e -> e != viewer && CsTeam.isT(e) == t).toList());
			side.sort((a, b) -> Integer.compare(stat(b)[0], stat(a)[0]));
			for (LivingEntity e : side) if (line.size() < 5) line.add(e);
		}
		com.google.gson.JsonArray out = new com.google.gson.JsonArray();
		for (LivingEntity e : line) {
			JsonObject x = new JsonObject();
			x.addProperty("name", name(e));
			x.addProperty("t", CsTeam.isT(e));
			x.addProperty("agent", e instanceof ServerPlayer sp ? dev.strafemc.weapon.MenuLoadout.agent(sp, CsTeam.isT(e)) : "");
			x.addProperty("k", stat(e)[0]);
			x.addProperty("d", stat(e)[2]);
			x.addProperty("id", e.getId());
			var held = e.getMainHandItem();
			x.addProperty("w", held.getItem() instanceof GunItem g ? g.weaponClass : held.getItem() instanceof dev.strafemc.weapon.KnifeItem ? "weapon_knife"
					: Bots.isBot(e) ? Bots.weaponOf(e) : "weapon_knife");
			out.add(x);
		}
		return out;
	}

	/**
	 * Where CS2's end_of_match prefab goes for a player: its origin and facing (MC degrees), so that the camera's middle
	 * spot (prefab 32, -300) is over the nearest End of Match Camera block (looking the way it faces), else where the
	 * operator stood for strafemc_eom_spot (same dimension), else where the player
	 * stands, looking the way they look: the team lines up about 5 blocks ahead.
	 */
	JsonObject eomSpot(ServerPlayer p) {
		double x = p.getX(), y = p.getY(), z = p.getZ();
		float yaw = p.getYRot();
		String[] s = dev.strafemc.server.StrafeSettings.eomSpot().split("\\|");
		dev.strafemc.world.SpawnPoints.Marker cam = null;
		for (var m : dev.strafemc.world.SpawnPoints.markers())
			if (m.state().getBlock() instanceof dev.strafemc.world.EndOfMatchBlock && m.level() == p.level()
					&& (cam == null || m.pos().distSqr(p.blockPosition()) < cam.pos().distSqr(p.blockPosition()))) cam = m;
		if (cam != null) { // an End of Match Camera block: the camera over it, looking the way it faces
			var floor = dev.strafemc.world.SpawnPoints.standOn(cam.level(), cam.pos());
			x = floor.x;
			y = floor.y;
			z = floor.z;
			yaw = cam.state().getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING).toYRot();
		} else if (s.length == 5 && s[0].equals(p.level().dimension().identifier().toString())) {
			try {
				x = Double.parseDouble(s[1]);
				y = Double.parseDouble(s[2]);
				z = Double.parseDouble(s[3]);
				yaw = Float.parseFloat(s[4]);
			} catch (NumberFormatException ignored) {}
		}
		double r = Math.toRadians(yaw), k = 1 / dev.strafemc.config.StrafeConfig.unitsPerBlock;
		double fx = -Math.sin(r), fz = Math.cos(r), rx = -Math.cos(r), rz = -Math.sin(r);
		JsonObject o = new JsonObject();
		o.addProperty("x", x - rx * 32 * k + fx * 300 * k);
		o.addProperty("y", y);
		o.addProperty("z", z - rz * 32 * k + fz * 300 * k);
		o.addProperty("yaw", yaw);
		return o;
	}

	/** Kicked out of this match (round modes): not put back into it on joining or respawning. */
	boolean kickedFromMatch(UUID id) {
		return false;
	}

	/** Damage (CS hit points) one participant did to another (the round modes count team damage). */
	void damaged(LivingEntity victim, LivingEntity attacker, double cs) {}

	/** The planted bomb (round modes), or null. */
	net.minecraft.world.entity.Entity plantedBomb() {
		return null;
	}

	/** Whether this participant has the bomb (only round modes have one). */
	boolean carriesBomb(LivingEntity e) {
		return false;
	}

	/** A side picked (jointeam, the team menu): refused when it's full; mid-match the switch costs your life, as in CS2. */
	void joinTeam(ServerPlayer p, String side) {
		boolean t = side.equals("auto") ? smallerSideT() : side.equals("t");
		if (!side.equals("auto") && !ffa() && sideFull(t, p)) {
			dev.strafemc.server.Toasts.warn(p, "That team is full");
			return;
		}
		picked.add(p.getUUID());
		if (CsTeam.isT(p) == t) return;
		CsTeam.set(p, t);
		dev.strafemc.server.Toasts.info(p, "You are now " + (t ? "a Terrorist" : "a Counter-Terrorist"));
		if (p.isAlive() && !p.isSpectator()) {
			if (live() && !ffa()) p.kill((net.minecraft.server.level.ServerLevel) p.level());
			else {
				toSpawn(p);
				spawned(p);
			}
		}
		sendAll();
	}

	void join(ServerPlayer p) {
		if (kickedFromMatch(p.getUUID())) return;
		GameModes.putAway(p);
		joinOrder(p);
		if (!ffa() && !picked.contains(p.getUUID())) CsTeam.set(p, smallerSideT()); // balanced until they pick (mp_autoteambalance)
		if (p.isAlive()) {
			toSpawn(p);
			spawned(p);
		}
	}

	void leave(ServerPlayer p) {
		respawnAt.remove(p.getUUID());
	}

	/**
	 * On a random spawn block, when the world has any and they're on (strafemc_spawnblocks): team modes their side's
	 * (else "anyone" blocks), everyone-against-everyone modes the "anyone" blocks (else any block).
	 */
	void toSpawn(ServerPlayer p) {
		if (!dev.strafemc.server.StrafeSettings.spawnBlocks()) return;
		if (ffa()) {
			if (!dev.strafemc.world.SpawnPoints.respawn(p, dev.strafemc.world.SpawnBlock.Side.ANY)) dev.strafemc.world.SpawnPoints.respawn(p);
		} else dev.strafemc.world.SpawnPoints.respawn(p, CsTeam.isT(p) ? dev.strafemc.world.SpawnBlock.Side.T : dev.strafemc.world.SpawnBlock.Side.CT);
	}

	/** (Re)spawned: protection, kevlar and helmet, full health, the mode's gear. */
	void spawned(ServerPlayer p) {
		if (kickedFromMatch(p.getUUID())) return;
		GameModes.putAway(p);
		immuneUntil.put(p.getUUID(), now() + Math.round(cvar("mp_respawn_immunitytime", 4) * 20)); // the modes' configs: 10
		CsArmor.set(p, 100, true);
		p.setHealth(p.getMaxHealth());
		clearGuns(p);
		gear(p);
		p.inventoryMenu.broadcastChanges();
	}

	static void clearGuns(ServerPlayer p) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.getItem() instanceof GunItem || s.getItem() instanceof dev.strafemc.weapon.MagazineItem) inv.setItem(i, ItemStack.EMPTY);
		}
	}

	static void give(ServerPlayer p, int slot, String cls) {
		var item = WeaponItems.GUNS.get(cls);
		if (item != null) p.getInventory().setItem(slot, dev.strafemc.weapon.ModeWrap.stamp(new ItemStack(item), p)); // the gun keeps its owner's finish
	}

	/** The gun in a slot with CS2's reserve magazines (round modes have no infinite ammo): in the mode's mag pouch. */
	static void giveWithReserve(ServerPlayer p, int slot, String cls) {
		give(p, slot, cls);
		refillPouch(p);
	}

	/** The hotbar slot the mode's mag pouch sits in (the last). */
	static final int POUCH_SLOT = 8;

	/**
	 * The mode's mag pouch: every carried gun's CS2 reserve as full magazines, rebuilt on each spawn and round start.
	 * Loose magazines go (a gun bought into their slot used to push them out of the inventory).
	 */
	static void refillPouch(ServerPlayer p) {
		var inv = p.getInventory();
		var list = net.minecraft.core.NonNullList.withSize(dev.strafemc.weapon.MagPouchItem.SIZE, ItemStack.EMPTY);
		ItemStack pouch = null;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.getItem() instanceof dev.strafemc.weapon.MagazineItem) inv.setItem(i, ItemStack.EMPTY);
			else if (s.getItem() instanceof dev.strafemc.weapon.MagPouchItem && pouch == null) pouch = s;
			else if (s.getItem() instanceof GunItem g) addMags(list, dev.strafemc.weapon.BuyMenu.reserve(g.weaponClass));
		}
		if (pouch == null) {
			pouch = new ItemStack(dev.strafemc.weapon.WeaponItems.MAG_POUCH);
			if (inv.getItem(POUCH_SLOT).isEmpty()) inv.setItem(POUCH_SLOT, pouch);
			else if (!inv.add(pouch)) return;
		}
		dev.strafemc.weapon.MagPouchItem.store(pouch, list);
	}

	/** A bought gun's reserve into the pouch (the others' magazines stay as they are until the next round). */
	static void pouchMags(ServerPlayer p, String cls) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!(s.getItem() instanceof dev.strafemc.weapon.MagPouchItem)) continue;
			var list = dev.strafemc.weapon.MagPouchItem.contents(s);
			addMags(list, dev.strafemc.weapon.BuyMenu.reserve(cls));
			dev.strafemc.weapon.MagPouchItem.store(s, list);
			return;
		}
		refillPouch(p); // no pouch yet: one with every carried gun's reserve
	}

	/** Magazines into the pouch's free slots (not the gunpowder slot), split to their stack size. */
	private static void addMags(net.minecraft.core.NonNullList<ItemStack> list, ItemStack mags) {
		int left = mags.getCount();
		for (int i = 0; i < dev.strafemc.weapon.MagPouchItem.POWDER_SLOT && left > 0; i++) {
			if (!list.get(i).isEmpty()) continue;
			int n = Math.min(left, mags.getMaxStackSize());
			list.set(i, mags.copyWithCount(n));
			left -= n;
		}
	}

	/** The inventory slot of the carried gun of a kind (CS2 carries one primary and one pistol), or -1. */
	static int carriedGun(ServerPlayer p, boolean pistol) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++)
			if (inv.getItem(i).getItem() instanceof GunItem g && Deathmatch.pistol(g.weaponClass) == pistol) return i;
		return -1;
	}

	static void knife(ServerPlayer p) {
		p.getInventory().setItem(2, dev.strafemc.weapon.MenuLoadout.knife(p));
	}

	static void select(ServerPlayer p, int slot) {
		p.getInventory().setSelectedSlot(slot);
		p.connection.send(new ClientboundSetHeldSlotPacket(slot));
	}

	void died(ServerPlayer p) {
		respawnAt.put(p.getUUID(), now() + respawnTicks());
	}

	/**
	 * Ticks from a death to the respawn: CS2's freeze cam runs first (spec_freeze_deathanim_time + spec_freeze_traveltime
	 * + spec_freeze_time; CS2's defaults 0.8 / 0.3 / 3.0, the modes' configs set the hold).
	 */
	static int respawnTicks() {
		return (int) Math.round((cvar("spec_freeze_deathanim_time", 0.8) + cvar("spec_freeze_traveltime", 0.3) + cvar("spec_freeze_time", 3.0)) * 20);
	}

	static double cvar(String name, double def) {
		try {
			String v = dev.strafemc.bridge.Cs2Bridge.INSTANCE.cvar(name);
			return v == null ? def : Math.max(0, Math.min(10, Double.parseDouble(v)));
		} catch (NumberFormatException e) {
			return def;
		}
	}

	void fired(ServerPlayer p) {
		immuneUntil.remove(p.getUUID());
	}

	/** Spawn protection, and no damage between teammates unless it's everyone for themselves. */
	boolean allowDamage(LivingEntity victim, DamageSource src) {
		if (!inMode(victim)) return true;
		if (victim instanceof ServerPlayer v && immune(v.getUUID())) return false;
		if (ffa() || !(src.getEntity() instanceof LivingEntity a) || a == victim || !inMode(a)) return true;
		return CsTeam.isT(a) != CsTeam.isT(victim);
	}

	void kill(LivingEntity attacker, LivingEntity victim, String icon) {
		if (!live() || attacker == null || attacker == victim || !inMode(attacker) || !inMode(victim)) return;
		if (!ffa() && CsTeam.isT(attacker) == CsTeam.isT(victim)) return;
		scored(attacker, victim, icon);
		sendAll();
	}

	void win(String who) {
		over = now();
		winner = who;
		soundAll("Music.MatchEnd.valve_cs2_01"); // the win panel shows who
		sendAll();
	}

	/** A new match: the mode's own reset, then everyone back to a spawn. */
	abstract void reset();

	void tick() {
		long t = now();
		for (var e : new ArrayList<>(respawnAt.entrySet())) {
			if (e.getValue() > t) continue;
			respawnAt.remove(e.getKey());
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p != null && p.isDeadOrDying()) {
				ServerPlayer n = server.getPlayerList().respawn(p, false, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
				dev.strafemc.StrafeMc.LOG.info("[strafemc] strafeGM: respawned {} at {} in {} (alive {})", name(n), n.blockPosition().toShortString(),
						n.level().dimension().identifier(), n.isAlive());
			} else dev.strafemc.StrafeMc.LOG.info("[strafemc] strafeGM: respawn of {} skipped: {}", e.getKey(), p == null ? "not online" : "not dead");
		}
		for (ServerPlayer p : players()) {
			p.getFoodData().setFoodLevel(20);
			p.getFoodData().setSaturation(5);
		}
		if (t % 20 == 0) unstick(t);
		if (over < 0 && t - start >= warmupTicks() + matchTicks()) {
			String lead = leaderName();
			if (lead != null) win(lead);
			else {
				reset();
				begin();
			}
		}
		if (over >= 0 && t - over >= RESTART) {
			reset();
			begin();
			sendAll();
		}
		if (t % 20 == 0) sendAll();
	}

	/** When each player was first seen dead with no respawn coming (unstick). */
	private final Map<UUID, Long> deadSince = new java.util.HashMap<>();

	/**
	 * A player left dead with no respawn coming (seen once: the client played on while the server had them dead, so
	 * bots ignored them and their shots did nothing): after 5 s, logged with what state they're in, and respawned.
	 */
	private void unstick(long t) {
		for (ServerPlayer p : players()) {
			UUID id = p.getUUID();
			if (p.isAlive() || p.isSpectator() || respawnAt.containsKey(id)) {
				deadSince.remove(id);
				continue;
			}
			long since = deadSince.computeIfAbsent(id, k -> t);
			if (t - since < 100) continue;
			deadSince.remove(id);
			dev.strafemc.StrafeMc.LOG.warn("[strafemc] strafeGM: {} stuck dead (health {}, removed {} {}, dying {}), respawning", name(p), p.getHealth(),
					p.isRemoved(), p.getRemovalReason(), p.isDeadOrDying());
			if (p.isDeadOrDying()) server.getPlayerList().respawn(p, false, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
			else {
				p.setHealth(p.getMaxHealth());
				toSpawn(p);
				spawned(p);
			}
		}
	}

	void sound(ServerPlayer p, String event) {
		dev.strafemc.weapon.GunServer.soundTo(p, event, p);
	}

	void sendAll() {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) GameModes.send(p, state(p));
	}

	/** What the player's HUD shows. */
	String state(ServerPlayer p) {
		JsonObject o = new JsonObject();
		o.addProperty("mode", id());
		long t = now() - start;
		o.addProperty("phase", over >= 0 ? "over" : t < warmupTicks() ? "warmup" : "live");
		o.addProperty("time", over >= 0 ? Math.max(0, (RESTART - (now() - over)) / 20) : t < warmupTicks() ? (warmupTicks() - t) / 20
				: (warmupTicks() + matchTicks() - t) / 20);
		o.addProperty("winner", winner);
		o.addProperty("you", p.getUUID().toString());
		o.addProperty("immune", Math.max(0, immuneUntil.getOrDefault(p.getUUID(), 0L) - now()) / 20.0);
		o.addProperty("ffa", ffa());
		o.addProperty("t", CsTeam.isT(p));
		o.addProperty("picked", picked.contains(p.getUUID()));
		if (over >= 0) {
			o.add("eom", eom(p));
			o.add("eomSpot", eomSpot(p));
		}
		state(p, o);
		JsonArray arr = new JsonArray();
		for (LivingEntity e : ranking()) {
			JsonObject x = new JsonObject();
			x.addProperty("name", name(e));
			x.addProperty("id", e.getUUID().toString());
			x.addProperty("t", CsTeam.isT(e));
			x.addProperty("bot", Bots.isBot(e));
			// enemies' health and money stay on the server: clients only learn whether they're alive (CS2 shows no more)
			boolean ally = e == p || !ffa() && CsTeam.isT(e) == CsTeam.isT(p);
			x.addProperty("hp", !e.isAlive() ? 0 : ally ? e.getHealth() / e.getMaxHealth() : 1);
			x.addProperty("ally", ally);
			int[] st = stats.getOrDefault(key(e), new int[4]);
			x.addProperty("k", st[0]);
			x.addProperty("a", st[1]);
			x.addProperty("d", st[2]);
			x.addProperty("mvp", st[3]);
			entry(e, x, ally);
			arr.add(x);
		}
		o.add("players", arr);
		return o.toString();
	}
}
