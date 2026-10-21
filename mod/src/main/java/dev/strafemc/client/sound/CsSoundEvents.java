package dev.strafemc.client.sound;

import dev.strafemc.StrafeMc;
import dev.strafemc.data.AssetCache;
import dev.strafemc.data.Kv3;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * CS2 sound event definitions straight from the game's soundevents/*.vsndevts (base inheritance resolved):
 * which files an event plays, its volume/pitch and its distance -> volume curve.
 */
public final class CsSoundEvents {
	public static final String[] FILES = {"soundevents/game_sounds_footsteps.vsndevts_c", "soundevents/game_sounds_player.vsndevts_c",
			"soundevents/game_sounds_weapons.vsndevts_c", "soundevents/game_sounds_physics.vsndevts_c", "soundevents/game_sounds_ui.vsndevts_c",
			"soundevents/vo/announcer/game_sounds_cs2_classic.vsndevts_c", "soundevents/music/valve_cs2_01/game_sounds_music.vsndevts_c"};

	/**
	 * CS2's sound mixer lifts some mix groups well above their events' own volume (its mix data isn't readable here):
	 * hit feedback (the helmet dink at volume 0.5) has to cut through gunfire. FLAGGED: the x2 is ours; volume still caps
	 * at 1 as in Minecraft.
	 */
	private static final java.util.Map<String, Double> MIX_GAIN = java.util.Map.of("PlayerAttackerFeedback", 2.0, "PlayerVictim", 2.0);

	public record Event(String name, List<String> files, double volume, double pitch, double pitchRandMin, double pitchRandMax,
						double[][] distanceCurve, double offsetUp, boolean suppression, double suppressionVolume, double suppressionFalloff,
						List<String> children) {
		private static final Random RNG = new Random();

		/** A file to play, or null for an event that only starts its child events. */
		public String pickFile() {
			return files.isEmpty() ? null : files.get(RNG.nextInt(files.size()));
		}

		public double randomPitch() {
			return pitch + pitchRandMin + RNG.nextDouble() * (pitchRandMax - pitchRandMin);
		}

		/** Volume multiplier at a distance (Hammer units) from the game's distance_volume_mapping_curve (cubic Hermite). */
		public double volumeAt(double dist) {
			if (distanceCurve == null || distanceCurve.length == 0) return 1;
			double[][] c = distanceCurve;
			if (dist <= c[0][0]) return c[0][1];
			for (int i = 0; i < c.length - 1; i++) {
				double x0 = c[i][0], x1 = c[i + 1][0];
				if (dist > x1) continue;
				double h = x1 - x0, t = (dist - x0) / h;
				double y0 = c[i][1], y1 = c[i + 1][1], m0 = c[i][3] * h, m1 = c[i + 1][2] * h;
				double t2 = t * t, t3 = t2 * t;
				double v = (2 * t3 - 3 * t2 + 1) * y0 + (t3 - 2 * t2 + t) * m0 + (-2 * t3 + 3 * t2) * y1 + (t3 - t2) * m1;
				return Math.max(0, Math.min(1, v));
			}
			return c[c.length - 1][1];
		}
	}

	private static volatile Map<String, Event> events = Map.of();

	private CsSoundEvents() {}

	public static Event get(String name) {
		Event e = events.get(name);
		if (e != null || name == null) return e;
		// vdata references are not always cased like the event definitions ("Default.nearlyempty" vs Default.NearlyEmpty)
		for (var en : events.entrySet()) if (en.getKey().equalsIgnoreCase(name)) return en.getValue();
		return null;
	}

	public static boolean loaded() {
		return !events.isEmpty();
	}

	/** Blocking; call off the render thread. */
	@SuppressWarnings("unchecked")
	public static void load() throws Exception {
		Map<String, Map<String, Object>> raw = new LinkedHashMap<>();
		for (String f : FILES) {
			try { // one file missing (an older setup, no converter at hand) leaves the others playing
				Map<String, Object> root = Kv3.parseObject(Files.readString(AssetCache.decompiled(f)));
				for (var e : root.entrySet()) if (e.getValue() instanceof Map) raw.put(e.getKey(), (Map<String, Object>) e.getValue());
			} catch (Exception e) {
				StrafeMc.LOG.warn("[strafemc] sound events {}: {}", f, e.toString());
			}
		}
		Map<String, Event> out = new HashMap<>();
		for (String name : raw.keySet()) {
			Map<String, Object> r = resolve(raw, name, 0);
			// child events (soundevent_01): CS2 plays them together with the event, each with its own settings; a gun's
			// crack and mechanical click are children of its shot (the Glock: Element_CRB, Element_Click), next to its distant layer
			List<String> children = new ArrayList<>();
			if (!Boolean.FALSE.equals(r.get("enable_child_events")) && r.get("soundevent_01") instanceof List<?> ch)
				for (Object o : ch) children.add(o.toString());
			Object files = r.get("vsnd_files_track_01");
			List<String> fl = new ArrayList<>();
			if (files instanceof List<?> l) for (Object o : l) fl.add(o.toString());
			if (fl.isEmpty() && children.isEmpty()) continue;
			double mix = MIX_GAIN.getOrDefault(String.valueOf(r.get("mixgroup")), 1.0);
			out.put(name, new Event(name, fl, num(r.get("volume"), 1) * mix, num(r.get("pitch"), 1), num(r.get("pitch_random_min"), 0),
					num(r.get("pitch_random_max"), 0),
					Boolean.TRUE.equals(r.get("use_distance_volume_mapping_curve")) ? curve(r.get("distance_volume_mapping_curve")) : null,
					r.get("position_offset") instanceof List<?> po && po.size() == 3 ? num(po.get(2), 0) : 0,
					Boolean.TRUE.equals(r.get("suppression_enable")), num(r.get("suppression_volume_multiplier"), 1),
					num(r.get("suppression_falloff_multiplier"), 1), List.copyOf(children)));
		}
		events = out;
		StrafeMc.LOG.info("[strafemc] CS2 sound events: {} playable (e.g. CT_Concrete.StepLeft: {})", out.size(),
				out.containsKey("CT_Concrete.StepLeft") ? out.get("CT_Concrete.StepLeft").files().size() + " files" : "missing");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> resolve(Map<String, Map<String, Object>> raw, String name, int depth) {
		Map<String, Object> e = raw.get(name);
		Map<String, Object> r = new HashMap<>();
		if (e == null) return r;
		Object base = e.get("base");
		if (base instanceof String b && raw.containsKey(b) && depth < 8) r.putAll(resolve(raw, b, depth + 1));
		r.putAll(e);
		return r;
	}

	private static double[][] curve(Object o) {
		if (!(o instanceof List<?> l)) return null;
		double[][] c = new double[l.size()][];
		for (int i = 0; i < l.size(); i++) {
			List<?> p = (List<?>) l.get(i);
			c[i] = new double[p.size()];
			for (int k = 0; k < p.size(); k++) c[i][k] = num(p.get(k), 0);
		}
		return c;
	}

	private static double num(Object o, double d) {
		return o instanceof Number n ? n.doubleValue() : d;
	}
}
