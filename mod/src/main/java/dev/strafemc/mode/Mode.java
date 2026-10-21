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
	/** Respawn after the death cam (spec_freeze_time 2 in the modes' configs). */
	static final int RESPAWN = 2 * 20;
	static final int IMMUNITY = 10 * 20;

	final MinecraftServer server;
	final Map<UUID, Long> immuneUntil = new HashMap<>(), respawnAt = new HashMap<>();
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
	abstract void entry(LivingEntity e, JsonObject o);

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

	void join(ServerPlayer p) {
		GameModes.putAway(p);
		if (p.isAlive()) {
			toSpawn(p);
			spawned(p);
		}
	}

	void leave(ServerPlayer p) {
		respawnAt.remove(p.getUUID());
	}

	/** On a random spawn block, when the world has any and they're on (strafemc_spawnblocks). */
	void toSpawn(ServerPlayer p) {
		if (dev.strafemc.server.StrafeSettings.spawnBlocks()) dev.strafemc.world.SpawnPoints.respawn(p);
	}

	/** (Re)spawned: protection, kevlar and helmet, full health, the mode's gear. */
	void spawned(ServerPlayer p) {
		GameModes.putAway(p);
		immuneUntil.put(p.getUUID(), now() + IMMUNITY);
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
		if (item != null) p.getInventory().setItem(slot, new ItemStack(item));
	}

	static void knife(ServerPlayer p) {
		p.getInventory().setItem(2, new ItemStack(WeaponItems.KNIVES.get(CsTeam.isT(p) ? "weapon_knife_t" : "weapon_knife")));
	}

	static void select(ServerPlayer p, int slot) {
		p.getInventory().setSelectedSlot(slot);
		p.connection.send(new ClientboundSetHeldSlotPacket(slot));
	}

	void died(ServerPlayer p) {
		respawnAt.put(p.getUUID(), now() + RESPAWN);
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
		for (ServerPlayer o : server.getPlayerList().getPlayers()) o.sendSystemMessage(Component.literal(who + " wins!"));
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
			if (p != null && p.isDeadOrDying()) server.getPlayerList().respawn(p, false, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
		}
		for (ServerPlayer p : players()) {
			p.getFoodData().setFoodLevel(20);
			p.getFoodData().setSaturation(5);
		}
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
		state(p, o);
		JsonArray arr = new JsonArray();
		for (LivingEntity e : ranking()) {
			JsonObject x = new JsonObject();
			x.addProperty("name", name(e));
			x.addProperty("id", e.getUUID().toString());
			x.addProperty("t", CsTeam.isT(e));
			x.addProperty("bot", Bots.isBot(e));
			x.addProperty("hp", e.isAlive() ? e.getHealth() / e.getMaxHealth() : 0);
			entry(e, x);
			arr.add(x);
		}
		o.add("players", arr);
		return o.toString();
	}
}
