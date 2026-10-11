package dev.strafemc.data;

import dev.strafemc.StrafeMc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CS2 surface properties straight from the copy: surfaceproperties.vsurf (hierarchy via m_baseNameHash = MurmurHash2 of the
 * lowercased name, seed 0x31415926; bullet impact sounds), surfaceproperties_impact_effects.txt (impact particle, decal
 * groups) and surfaceproperties_game.txt (bullet penetration modifiers). Values inherit from the base surface.
 */
public final class Surfaces {
	public record Surface(String name, String base, String bulletImpactSound, String impactEffect, String decalGroup,
						  String grazingDecalGroup, double penetrationDistance, double penetrationDamage) {}

	private static volatile Map<String, Surface> surfaces = Map.of();

	private Surfaces() {}

	public static boolean loaded() {
		return !surfaces.isEmpty();
	}

	/** Resolved surface by name (case-insensitive), or "default". */
	public static Surface get(String name) {
		Map<String, Surface> m = surfaces;
		Surface s = name == null ? null : m.get(name.toLowerCase(Locale.ROOT));
		return s != null ? s : m.get("default");
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(String vpkPath) throws Exception {
		Map<String, Object> root = Kv3.parseObject(Files.readString(AssetCache.decompiled(vpkPath)));
		return (List<Map<String, Object>>) root.get("SurfacePropertiesList");
	}

	public static synchronized void load() throws Exception {
		Map<String, Map<String, Object>> raw = new HashMap<>(); // lower name -> merged fields
		Map<Long, String> byHash = new HashMap<>();
		Map<String, Long> baseHash = new HashMap<>();
		for (Map<String, Object> e : list("surfaceproperties/surfaceproperties.vsurf_c")) {
			String n = String.valueOf(e.get("surfacePropertyName")).toLowerCase(Locale.ROOT);
			raw.computeIfAbsent(n, k -> new HashMap<>()).putAll(e);
			byHash.put(murmur2(n), n);
			if (e.get("m_baseNameHash") instanceof Number b) baseHash.put(n, b.longValue() & 0xffffffffL);
		}
		for (String f : new String[] {"scripts/surfaceproperties_impact_effects.txt", "scripts/surfaceproperties_game.txt"})
			for (Map<String, Object> e : list(f))
				raw.computeIfAbsent(String.valueOf(e.get("surfacePropertyName")).toLowerCase(Locale.ROOT), k -> new HashMap<>()).putAll(e);
		Map<String, Surface> out = new HashMap<>();
		for (String n : raw.keySet()) {
			Long bh = baseHash.get(n);
			String base = bh == null || bh == 0 ? null : byHash.get(bh);
			out.put(n, new Surface(n, base, str(raw, n, "audiosounds", "bulletimpact"), str(raw, n, null, "effect"),
					str(raw, n, null, "impactDecalName"), str(raw, n, null, "impactGrazingDecalName"),
					num(raw, n, "bulletPenetrationDistanceModifier"), num(raw, n, "bulletPenetrationDamageModifier")));
		}
		// resolve inheritance: missing values come from the base chain, finally "default"
		Map<String, Surface> resolved = new HashMap<>();
		for (String n : out.keySet()) resolved.put(n, resolve(out, n, baseHash, byHash));
		surfaces = resolved;
		StrafeMc.LOG.info("[strafemc] CS2 surfaces: {} (concrete -> decals {}, sound {})", resolved.size(),
				resolved.get("concrete") == null ? "?" : resolved.get("concrete").decalGroup(),
				resolved.get("concrete") == null ? "?" : resolved.get("concrete").bulletImpactSound());
	}

	private static Surface resolve(Map<String, Surface> all, String n, Map<String, Long> baseHash, Map<Long, String> byHash) {
		Surface s = all.get(n);
		String sound = s.bulletImpactSound(), effect = s.impactEffect(), decal = s.decalGroup(), grazing = s.grazingDecalGroup();
		double pd = s.penetrationDistance(), pdm = s.penetrationDamage();
		String cur = s.base();
		for (int depth = 0; depth < 16; depth++) {
			Surface b = cur != null ? all.get(cur) : (n.equals("default") ? null : all.get("default"));
			if (b == null) break;
			if (sound == null) sound = b.bulletImpactSound();
			if (effect == null) effect = b.impactEffect();
			if (decal == null) decal = b.decalGroup();
			if (grazing == null) grazing = b.grazingDecalGroup();
			if (Double.isNaN(pd)) pd = b.penetrationDistance();
			if (Double.isNaN(pdm)) pdm = b.penetrationDamage();
			if (cur == null) break; // reached default
			cur = b.base();
		}
		return new Surface(n, s.base(), sound, effect, decal, grazing, pd, pdm);
	}

	@SuppressWarnings("unchecked")
	private static String str(Map<String, Map<String, Object>> raw, String n, String group, String key) {
		Object o = raw.get(n);
		if (group != null) o = o instanceof Map<?, ?> m ? m.get(group) : null;
		Object v = o instanceof Map<?, ?> m ? ((Map<String, Object>) m).get(key) : null;
		return v == null ? null : v.toString();
	}

	private static double num(Map<String, Map<String, Object>> raw, String n, String key) {
		Object v = raw.get(n).get(key);
		return v instanceof Number x ? x.doubleValue() : Double.NaN;
	}

	/** Valve's string token hash: MurmurHash2 of the lowercased name, seed 0x31415926. */
	static long murmur2(String s) {
		byte[] key = s.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
		final int m = 0x5bd1e995;
		int len = key.length, h = 0x31415926 ^ len, i = 0;
		while (len >= 4) {
			int k = (key[i] & 0xff) | (key[i + 1] & 0xff) << 8 | (key[i + 2] & 0xff) << 16 | (key[i + 3] & 0xff) << 24;
			k *= m;
			k ^= k >>> 24;
			k *= m;
			h *= m;
			h ^= k;
			i += 4;
			len -= 4;
		}
		switch (len) {
			case 3:
				h ^= (key[i + 2] & 0xff) << 16;
			case 2:
				h ^= (key[i + 1] & 0xff) << 8;
			case 1:
				h ^= key[i] & 0xff;
				h *= m;
			default:
		}
		h ^= h >>> 13;
		h *= m;
		h ^= h >>> 15;
		return h & 0xffffffffL;
	}
}
