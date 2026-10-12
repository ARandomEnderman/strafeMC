package dev.strafemc.client;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.strafemc.StrafeMc;
import dev.strafemc.bridge.Cs2Bridge;
import dev.strafemc.data.WeaponData;
import dev.strafemc.weapon.GunServer;
import dev.strafemc.weapon.Recoil;
import dev.strafemc.weapon.WeaponStats;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gameplay data as the client must use it: the Minecraft server's values (GameplaySync) once they've arrived, the
 * local CS2 data only before that. Weapon stats, recoil, gameplay animation timings and cvars all come through here,
 * so a client with a different CS2 build still plays by the server's rules; its own files only provide looks and sound.
 */
public final class ClientGameplay {
	private static final Gson GSON = new Gson();
	private static volatile Map<String, Map<String, Object>> weapons;
	private static volatile Map<String, Map<String, Double>> timings;
	private static volatile Map<String, String> cvars;
	private static final Map<String, WeaponStats> STATS = new ConcurrentHashMap<>();
	private static final Map<String, Recoil> RECOIL = new ConcurrentHashMap<>();
	/** Local vdata entry with the server's gameplay fields on top. */
	private static final Map<String, Map<String, Object>> MERGED = new ConcurrentHashMap<>();

	private ClientGameplay() {}

	@SuppressWarnings("unchecked")
	public static void apply(String json) {
		try {
			Map<String, Object> root = GSON.fromJson(json, new TypeToken<Map<String, Object>>() {}.getType());
			weapons = (Map<String, Map<String, Object>>) (Map<?, ?>) root.getOrDefault("weapons", Map.of());
			timings = (Map<String, Map<String, Double>>) (Map<?, ?>) root.getOrDefault("timings", Map.of());
			Map<String, String> cv = new ConcurrentHashMap<>();
			((Map<String, Object>) root.getOrDefault("cvars", Map.of())).forEach((k, v) -> cv.put(k, String.valueOf(v)));
			cvars = cv;
			STATS.clear();
			RECOIL.clear();
			MERGED.clear();
			StrafeMc.LOG.info("[strafemc] gameplay data from the server: {} weapons, {} cvars", weapons.size(), cv.size());
		} catch (Exception e) {
			StrafeMc.LOG.error("[strafemc] bad gameplay sync", e);
		}
	}

	/** Left the server: back to local data until the next one syncs. */
	public static void clear() {
		weapons = null;
		timings = null;
		cvars = null;
		STATS.clear();
		RECOIL.clear();
		MERGED.clear();
	}

	/** Local CS2 data may stand in only in your own world: on someone else's server, nothing local is ever used. */
	private static boolean localAllowed() {
		return !dev.strafemc.client.console.RelayTransport.remote();
	}

	public static boolean synced() {
		return weapons != null;
	}

	/**
	 * The weapon's vdata entry as the client should use it: the local entry (cosmetic fields: sounds, tracer) with the
	 * server's gameplay fields on top. A weapon the server doesn't have stays unknown (null).
	 */
	public static Map<String, Object> weapon(String cls) {
		Map<String, Map<String, Object>> w = weapons;
		if (w == null) return localAllowed() ? WeaponData.get(cls) : null;
		Map<String, Object> server = w.get(cls);
		if (server == null) return null;
		return MERGED.computeIfAbsent(cls, c -> {
			Map<String, Object> m = new java.util.LinkedHashMap<>();
			Map<String, Object> local = WeaponData.get(c);
			if (local != null) m.putAll(local);
			m.putAll(server);
			return m;
		});
	}

	public static WeaponStats stats(String cls) {
		if (weapons == null) return localAllowed() ? GunServer.stats(cls) : null;
		Map<String, Object> w = weapon(cls);
		return w == null ? null : STATS.computeIfAbsent(cls, c -> WeaponStats.of(c, w));
	}

	public static Recoil recoil(String cls) {
		if (weapons == null) return localAllowed() ? Recoil.of(cls) : null;
		Map<String, Object> w = weapon(cls);
		return w == null ? null : RECOIL.computeIfAbsent(cls, c -> Recoil.from(w));
	}

	/** m_flMaxSpeed of a weapon (alt = the second mode, e.g. scoped), NaN if unknown. */
	public static double maxSpeed(String cls, boolean alt) {
		Map<String, Object> w = weapon(cls);
		if (w == null) return Double.NaN;
		Object v = w.get("m_flMaxSpeed");
		if (v instanceof List<?> l && l.size() == 2) return ((Number) l.get(alt ? 1 : 0)).doubleValue();
		if (v instanceof Number n) return n.doubleValue();
		return Double.NaN;
	}

	/** A gameplay timing from the weapon's animation (e.g. "reloadSilentEnd"), the server's if synced, else the fallback. */
	public static double timing(String cls, String name, double fallback) {
		Map<String, Map<String, Double>> t = timings;
		if (t == null || t.get(cls) == null || t.get(cls).get(name) == null) return fallback;
		return ((Number) t.get(cls).get(name)).doubleValue();
	}

	/** A CS2 cvar as the server reports it; before a sync (or without one), the local bridge's value. */
	public static String cvar(String name) {
		Map<String, String> c = cvars;
		return c != null ? c.get(name) : localAllowed() ? Cs2Bridge.INSTANCE.cvar(name) : null;
	}

	public static boolean hasCvars() {
		Map<String, String> c = cvars;
		return c != null ? !c.isEmpty() : localAllowed() && Cs2Bridge.INSTANCE.hasCvars();
	}
}
