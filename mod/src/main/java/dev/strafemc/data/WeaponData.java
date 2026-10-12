package dev.strafemc.data;

import dev.strafemc.StrafeMc;

import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Weapon definitions straight from the game's scripts/weapons.vdata (CBasePlayerWeaponVData / CCSWeaponBaseVData),
 * with _base prefab inheritance resolved. Entries are keyed by name ("weapon_ak47") and by item definition index.
 */
public final class WeaponData {
	private static volatile Map<String, Map<String, Object>> resolved = Collections.emptyMap();
	private static volatile boolean loaded;

	private WeaponData() {}

	public static boolean loaded() {
		return loaded;
	}

	private static boolean started;

	/** Loads in the background, once; safe to call repeatedly. */
	public static synchronized void loadAsync() {
		if (started) return;
		started = true;
		Thread t = new Thread(() -> {
			try {
				var file = AssetCache.decompiled("scripts/weapons.vdata_c");
				Map<String, Object> root = Kv3.parseObject(Files.readString(file));
				Map<String, Map<String, Object>> out = new LinkedHashMap<>();
				for (String k : root.keySet()) {
					if (root.get(k) instanceof Map) out.put(k, resolve(root, k, 0));
				}
				resolved = out;
				loaded = true;
				long weapons = out.keySet().stream().filter(k -> k.startsWith("weapon_")).count();
				StrafeMc.LOG.info("[strafemc] weapons.vdata: {} entries, {} named weapons; knife maxspeed={}, ak47 dmg={}",
						out.size(), weapons, maxSpeed("weapon_knife", false), get("weapon_ak47") == null ? null : get("weapon_ak47").get("m_nDamage"));
			} catch (Exception e) {
				StrafeMc.LOG.error("[strafemc] could not load weapons.vdata from the CS2 copy", e);
			}
		}, "strafemc-weapondata");
		t.setDaemon(true);
		t.start();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> resolve(Map<String, Object> root, String key, int depth) {
		Map<String, Object> e = (Map<String, Object>) root.get(key);
		Map<String, Object> r = new LinkedHashMap<>();
		Object base = e.get("_base");
		if (base instanceof String b && root.get(b) instanceof Map && depth < 16) r.putAll(resolve(root, b, depth + 1));
		r.putAll(e);
		return r;
	}

	public static Map<String, Object> get(String nameOrDefIndex) {
		return resolved.get(nameOrDefIndex);
	}

	/** m_flMaxSpeed[0 = normal, 1 = alt/scoped]; NaN if unknown. */
	public static double maxSpeed(String weapon, boolean alt) {
		var w = get(weapon);
		if (w == null) return Double.NaN;
		Object v = w.get("m_flMaxSpeed");
		if (v instanceof List<?> l && l.size() == 2) return ((Number) l.get(alt ? 1 : 0)).doubleValue();
		if (v instanceof Number n) return n.doubleValue();
		return Double.NaN;
	}
}
