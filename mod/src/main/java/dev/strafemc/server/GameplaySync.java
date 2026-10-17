package dev.strafemc.server;

import com.google.gson.Gson;
import dev.strafemc.StrafeMc;
import dev.strafemc.bridge.Cs2Bridge;
import dev.strafemc.data.AnimClip;
import dev.strafemc.data.WeaponData;
import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.GunItem;
import dev.strafemc.weapon.GunServer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Minecraft server is the authority for gameplay data. On join, and whenever it changes, every player gets the
 * server's weapon numbers (the gameplay fields of the registered CS weapons' weapons.vdata entries), the gameplay timings inside weapon
 * animations (silent reload window, ammo-add point) and the CS2 cvars from the server's own CS2 bridge. Clients predict
 * with these (ClientGameplay) and use their own CS2 files only for looks and sound. Singleplayer goes through the same
 * path via the integrated server.
 */
public final class GameplaySync {
	private static final Gson GSON = new Gson();
	/** Joined players still waiting for the first sync (weapons.vdata loads in the background). */
	private static final Set<UUID> PENDING = new HashSet<>();
	private static String lastCvars = "";
	private static int tick;
	private static volatile int forceSeq;

	/** A server-wide setting changed: everyone gets a fresh sync within half a second. */
	public static void resendAll() {
		forceSeq++;
	}

	private GameplaySync() {}

	public static void onJoin(ServerPlayer p) {
		PENDING.add(p.getUUID());
	}

	public static void onLeave(ServerPlayer p) {
		dev.strafemc.weapon.CaseOpening.claim(p); // an unclaimed case drop goes into the inventory before it's saved
		PENDING.remove(p.getUUID());
	}

	/** Server tick: first syncs once the data is loaded, then a resend to everyone when the CS2 cvars change. */
	public static void tick(MinecraftServer server) {
		if (++tick % 10 != 0 || !WeaponData.loaded()) return;
		// anything players need to see change: the cvars, whether the server's CS2 is up, the server-wide settings
		String cvars = GSON.toJson(new java.util.TreeMap<>(Cs2Bridge.INSTANCE.cvars())) + Cs2Bridge.INSTANCE.connected()
				+ StrafeSettings.sourceMovement() + StrafeSettings.slabStep() + StrafeSettings.playerChoice() + StrafeSettings.personal() + forceSeq;
		boolean changed = !cvars.equals(lastCvars);
		if (!changed && PENDING.isEmpty()) return;
		if (building) return;
		lastCvars = cvars;
		building = true;
		Set<UUID> targets = changed ? null : new HashSet<>(PENDING);
		PENDING.clear();
		// built off the server thread: the first snapshot loads reload animations (asset conversion, ~1 s)
		java.util.concurrent.CompletableFuture.supplyAsync(GameplaySync::snapshot).whenComplete((json, err) -> server.execute(() -> {
			building = false;
			if (err != null) {
				StrafeMc.LOG.error("[strafemc] gameplay sync failed", err);
				if (targets != null) PENDING.addAll(targets);
				return;
			}
			StrafePayloads.GameplaySync msg = new StrafePayloads.GameplaySync(json);
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (targets != null && !targets.contains(p.getUUID())) continue;
				if (ServerPlayNetworking.canSend(p, StrafePayloads.GameplaySync.TYPE)) ServerPlayNetworking.send(p, msg);
			}
		}));
	}

	private static volatile boolean building;

	/** JSON: {"weapons": {cls: vdata entry}, "timings": {cls: {name: seconds}}, "cvars": {name: value}}. */
	static String snapshot() {
		Map<String, Object> weapons = new LinkedHashMap<>(), timings = new LinkedHashMap<>();
		Set<String> classes = new HashSet<>();
		classes.add("weapon_knife"); // movement falls back to the knife's speed with nothing CS held
		for (var item : BuiltInRegistries.ITEM) if (item instanceof GunItem g) classes.add(g.weaponClass);
		for (String cls : classes) {
			Map<String, Object> w = WeaponData.get(cls);
			if (w != null) {
				Map<String, Object> g = new LinkedHashMap<>();
				for (String k : dev.strafemc.weapon.GameplayKeys.SYNCED) if (w.containsKey(k)) g.put(k, w.get(k));
				weapons.put(cls, g);
			}
			Map<String, Double> t = timings(cls);
			if (!t.isEmpty()) timings.put(cls, t);
		}
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("weapons", weapons);
		root.put("timings", timings);
		root.put("cvars", new LinkedHashMap<>(Cs2Bridge.INSTANCE.cvars()));
		root.put("cs2Online", Cs2Bridge.INSTANCE.connected());
		root.put("sourceMovement", StrafeSettings.sourceMovement());
		root.put("movementChoice", StrafeSettings.playerChoice());
		root.put("slabStep", StrafeSettings.slabStep());
		root.put("buyMode", StrafeSettings.buyMode());
		root.put("buyTime", StrafeSettings.buyTime());
		root.put("killCardTime", StrafeSettings.killCardTime());
		root.put("loadoutKeep", StrafeSettings.loadoutKeep());
		root.put("drops", StrafeSettings.drops());
		root.put("movementPersonal", StrafeSettings.personal());
		String json = GSON.toJson(root);
		if (dev.strafemc.config.StrafeConfig.debug) StrafeMc.LOG.info("[strafemc] gameplay sync: {} weapons, {} chars", weapons.size(), json.length());
		return json;
	}

	/** Gameplay-relevant animation event times (the reload clip's silent window end and ammo-add point). */
	private static Map<String, Double> timings(String cls) {
		Map<String, Double> t = new LinkedHashMap<>();
		String path = GunServer.clipPath(cls, "reload");
		AnimClip c = path == null ? null : AnimClip.load(path);
		if (c == null) return t;
		for (AnimClip.Event e : c.events()) {
			if (e.name().equals("WPN_RELOAD_SILENT")) t.put("reloadSilentEnd", e.time() + e.duration());
			if (e.name().equals("WPN_RELOAD_ADD_AMMO")) t.put("reloadAddAmmo", e.time());
		}
		return t;
	}
}
