package dev.strafemc.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.strafemc.StrafeMc;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server-wide strafeMC settings, saved per world (<world>/strafemc_settings.json) and sent to every player in the
 * gameplay sync. Only operators change them (ConsoleActions). Source movement is one of these: on a server, everyone
 * plays with the same movement, and the server (fall damage, stair ramps) relies on it instead of the client's word.
 */
public final class StrafeSettings {
	private static final Gson GSON = new Gson();
	private static volatile boolean sourceMovement = true;
	/** Gun and knife hits push targets back like Minecraft hits (off = CS2: no knockback, the hit slows instead). */
	private static volatile boolean mcKnockback = false;
	/** On: every player turns Source movement on/off for themselves (PERSONAL); off: operators decide for everyone. */
	private static volatile boolean playerChoice = false;
	private static final java.util.Map<java.util.UUID, Boolean> PERSONAL = new java.util.concurrent.ConcurrentHashMap<>();
	/** Lag compensation (rewind shots to what the shooter saw) and how far back it may go (CS2's sv_maxunlag is 0.2 s). */
	private static volatile boolean lagComp = true;
	/** Step height of half a block (a slab) instead of CS2's sv_stepsize 18; works on any CS2 server (sv_stepsize is developer-only without the plugin). */
	private static volatile boolean slabStep = false;
	/** strafemc_buymenu: who may buy (weapon.BuyMenu): BUY_OFF nobody, BUY_CREATIVE creative players, BUY_EVERYONE everyone. */
	public static final int BUY_OFF = 0, BUY_CREATIVE = 1, BUY_EVERYONE = 2;
	private static volatile int buyMode = BUY_CREATIVE;
	/** strafemc_buytime: seconds after (re)spawning that buying stays open, like CS2's mp_buytime; 0: no limit. */
	private static volatile int buyTime = 0;
	/** strafemc_loadout_keep: loadout items (gloves, wraps) stay with you when you die (default) or drop like the inventory. */
	private static volatile boolean loadoutKeep = true;
	/** strafemc_spawnblocks: players who die respawn on a random spawn block (when the world has any). */
	private static volatile boolean spawnBlocks = true;
	/** Case drops roll a float (wear) at all, and per rarity (gold, covert, ... consumer: off ones drop Factory New, 0). */
	private static volatile boolean wear = true;
	/** Bots: how many (CS2's bot_quota; 0 by default), whether they fight everyone, whose skins they wear (empty: random). */
	private static volatile int botQuota = 0;
	private static volatile boolean botFfa = false;
	private static volatile java.util.List<String> botSkins = java.util.List.of();
	private static final java.util.Set<String> WEAR_OFF = java.util.concurrent.ConcurrentHashMap.newKeySet();
	public static final java.util.List<String> WEAR_RARITIES = java.util.List.of("gold", "covert", "classified", "restricted", "milspec", "industrial", "consumer");
	private static volatile int maxUnlagMs = 300;
	private static Path file;

	private StrafeSettings() {}

	public static boolean sourceMovement() {
		return sourceMovement;
	}

	/** This player's Source movement: their own choice while players may choose, else the server's setting. */
	public static boolean sourceMovement(net.minecraft.world.entity.player.Player p) {
		if (!playerChoice || p == null) return sourceMovement;
		Boolean b = PERSONAL.get(p.getUUID());
		return b != null ? b : sourceMovement;
	}

	public static boolean playerChoice() {
		return playerChoice;
	}

	public static void setPlayerChoice(boolean on) {
		playerChoice = on;
		save();
		GameplaySync.resendAll();
	}

	public static void setPersonal(java.util.UUID id, boolean on) {
		PERSONAL.put(id, on);
		save();
		GameplaySync.resendAll();
	}

	/** Who chose what (sent in the gameplay sync; each client picks its own). */
	public static java.util.Map<String, Boolean> personal() {
		java.util.Map<String, Boolean> m = new java.util.TreeMap<>();
		PERSONAL.forEach((k, v) -> m.put(k.toString(), v));
		return m;
	}

	public static void setSourceMovement(boolean on) {
		sourceMovement = on;
		save();
		GameplaySync.resendAll();
	}

	public static boolean mcKnockback() {
		return mcKnockback;
	}

	public static void setMcKnockback(boolean on) {
		mcKnockback = on;
		save();
	}

	public static int buyMode() {
		return buyMode;
	}

	public static void setBuyMode(int mode) {
		buyMode = Math.max(BUY_OFF, Math.min(BUY_EVERYONE, mode));
		save();
		GameplaySync.resendAll();
	}

	public static boolean spawnBlocks() {
		return spawnBlocks;
	}

	public static int botQuota() {
		return botQuota;
	}

	public static void setBotQuota(int n) {
		botQuota = Math.max(0, Math.min(64, n));
		save();
	}

	public static boolean botFfa() {
		return botFfa;
	}

	public static void setBotFfa(boolean on) {
		botFfa = on;
		save();
	}

	public static java.util.List<String> botSkins() {
		return botSkins;
	}

	public static void setBotSkins(java.util.List<String> names) {
		botSkins = java.util.List.copyOf(names);
		save();
	}

	public static boolean wear() {
		return wear;
	}

	public static void setWear(boolean on) {
		wear = on;
		save();
	}

	/** Whether drops of this rarity (null: the rare special item, gold) get a float. */
	public static boolean wearFor(dev.strafemc.weapon.SkinWraps.Rarity r) {
		return wear && !WEAR_OFF.contains(rarityKey(r));
	}

	public static boolean wearFor(String key) {
		return !WEAR_OFF.contains(key);
	}

	public static void setWearFor(String key, boolean on) {
		if (on) WEAR_OFF.remove(key);
		else WEAR_OFF.add(key);
		save();
	}

	/** CS2's grade as the console names it: gold, covert, classified, restricted, milspec, industrial, consumer. */
	public static String rarityKey(dev.strafemc.weapon.SkinWraps.Rarity r) {
		if (r == null) return "gold";
		return switch (r) {
			case ANCIENT, IMMORTAL -> "covert";
			case LEGENDARY -> "classified";
			case MYTHICAL -> "restricted";
			case RARE -> "milspec";
			case UNCOMMON -> "industrial";
			case COMMON -> "consumer";
		};
	}

	public static void setSpawnBlocks(boolean on) {
		spawnBlocks = on;
		save();
	}

	public static boolean loadoutKeep() {
		return loadoutKeep;
	}

	public static void setLoadoutKeep(boolean on) {
		loadoutKeep = on;
		save();
		GameplaySync.resendAll();
	}

	public static int buyTime() {
		return buyTime;
	}

	public static void setBuyTime(int seconds) {
		buyTime = Math.max(0, Math.min(3600, seconds));
		save();
		GameplaySync.resendAll();
	}

	public static boolean slabStep() {
		return slabStep;
	}

	public static void setSlabStep(boolean on) {
		slabStep = on;
		save();
		GameplaySync.resendAll();
	}

	public static boolean lagComp() {
		return lagComp;
	}

	public static void setLagComp(boolean on) {
		lagComp = on;
		save();
	}

	public static int maxUnlagMs() {
		return maxUnlagMs;
	}

	public static void setMaxUnlagMs(int ms) {
		maxUnlagMs = Math.max(0, Math.min(1000, ms));
		save();
	}

	public static void load(MinecraftServer server) {
		file = server.getWorldPath(LevelResource.ROOT).resolve("strafemc_settings.json");
		sourceMovement = true;
		mcKnockback = false;
		lagComp = true;
		maxUnlagMs = 300;
		slabStep = false;
		buyMode = BUY_CREATIVE;
		buyTime = 0;
		loadoutKeep = true;
		spawnBlocks = true;
		wear = true;
		WEAR_OFF.clear();
		botQuota = 0;
		botFfa = false;
		botSkins = java.util.List.of();
		playerChoice = false;
		PERSONAL.clear();
		try {
			if (Files.exists(file)) {
				JsonObject o = GSON.fromJson(Files.readString(file), JsonObject.class);
				if (o != null && o.has("sourceMovement")) sourceMovement = o.get("sourceMovement").getAsBoolean();
				if (o != null && o.has("mcKnockback")) mcKnockback = o.get("mcKnockback").getAsBoolean();
				if (o != null && o.has("lagComp")) lagComp = o.get("lagComp").getAsBoolean();
				if (o != null && o.has("slabStep")) slabStep = o.get("slabStep").getAsBoolean();
				if (o != null && o.has("buyAnywhere")) buyMode = o.get("buyAnywhere").getAsBoolean() ? BUY_EVERYONE : BUY_CREATIVE; // older worlds
				if (o != null && o.has("buyMode")) buyMode = o.get("buyMode").getAsInt();
				if (o != null && o.has("buyTime")) buyTime = o.get("buyTime").getAsInt();
				if (o != null && o.has("loadoutKeep")) loadoutKeep = o.get("loadoutKeep").getAsBoolean();
				if (o != null && o.has("spawnBlocks")) spawnBlocks = o.get("spawnBlocks").getAsBoolean();
				if (o != null && o.has("wear")) wear = o.get("wear").getAsBoolean();
				if (o != null && o.has("botQuota")) botQuota = o.get("botQuota").getAsInt();
				if (o != null && o.has("botFfa")) botFfa = o.get("botFfa").getAsBoolean();
				if (o != null && o.has("botSkins")) {
					java.util.List<String> l = new java.util.ArrayList<>();
					for (var e : o.getAsJsonArray("botSkins")) l.add(e.getAsString());
					botSkins = java.util.List.copyOf(l);
				}
				if (o != null && o.has("wearOff")) for (var e : o.getAsJsonArray("wearOff")) WEAR_OFF.add(e.getAsString());
				if (o != null && o.has("maxUnlagMs")) maxUnlagMs = o.get("maxUnlagMs").getAsInt();
				if (o != null && o.has("playerChoice")) playerChoice = o.get("playerChoice").getAsBoolean();
				if (o != null && o.has("personal"))
					for (var e : o.getAsJsonObject("personal").entrySet()) PERSONAL.put(java.util.UUID.fromString(e.getKey()), e.getValue().getAsBoolean());
			}
		} catch (Exception e) {
			StrafeMc.LOG.error("[strafemc] could not read " + file, e);
		}
	}

	private static void save() {
		if (file == null) return;
		JsonObject o = new JsonObject();
		o.addProperty("sourceMovement", sourceMovement);
		o.addProperty("mcKnockback", mcKnockback);
		o.addProperty("lagComp", lagComp);
		o.addProperty("slabStep", slabStep);
		o.addProperty("buyMode", buyMode);
		o.addProperty("buyTime", buyTime);
		o.addProperty("loadoutKeep", loadoutKeep);
		o.addProperty("spawnBlocks", spawnBlocks);
		o.addProperty("wear", wear);
		o.addProperty("botQuota", botQuota);
		o.addProperty("botFfa", botFfa);
		var skins = new com.google.gson.JsonArray();
		botSkins.forEach(skins::add);
		o.add("botSkins", skins);
		var off = new com.google.gson.JsonArray();
		WEAR_OFF.forEach(off::add);
		o.add("wearOff", off);
		o.addProperty("maxUnlagMs", maxUnlagMs);
		o.addProperty("playerChoice", playerChoice);
		JsonObject pers = new JsonObject();
		PERSONAL.forEach((k, v) -> pers.addProperty(k.toString(), v));
		o.add("personal", pers);
		try {
			Files.writeString(file, GSON.toJson(o));
		} catch (Exception e) {
			StrafeMc.LOG.error("[strafemc] could not save " + file, e);
		}
	}

	public static void unload() {
		file = null;
		sourceMovement = true;
		mcKnockback = false;
		playerChoice = false;
		PERSONAL.clear();
	}
}
