package dev.strafemc.data;

import dev.strafemc.StrafeMc;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Timing data of a CS2 animation clip (.vnmclip): its duration and the events the animation fires (sound events like
 * Weapon_AK47.Clipout, gameplay IDs like WPN_RELOAD_ADD_AMMO), read from the clip's DATA block in the user's CS2 copy.
 * Event times in the file are normalized (0..1 of the clip); {@link Event#time()} is in seconds.
 */
public record AnimClip(String path, double duration, List<Event> events) {
	/** name: sound/ID event name, or the particle system for CNmParticleEvent; extra: particle m_tags + m_config. */
	public record Event(String kind, String name, double time, double duration, String extra) {
		public boolean isSound() {
			return kind.equals("CNmSoundEvent");
		}
	}

	private static final Map<String, AnimClip> CACHE = new ConcurrentHashMap<>();
	private static final Pattern DURATION = Pattern.compile("m_flDuration = ([0-9.eE+-]+)");
	private static final Pattern EVENT = Pattern.compile(
			"_class = \"(\\w+)\".*?m_flStartTime = \\s*\\{\\s*m_flValue = ([0-9.eE+-]+)\\s*\\}\\s*m_flDuration = \\s*\\{\\s*m_flValue = ([0-9.eE+-]+)\\s*\\}(.*?)\\}\\s*,?\\s*(?=\\{|\\])",
			Pattern.DOTALL);
	private static final Pattern NAME = Pattern.compile("m_(?:name|ID) = \"([^\"]*)\""),
			PARTICLE = Pattern.compile("m_hParticleSystem = (?:resource:)?\"([^\"]*)\""), TAGS = Pattern.compile("m_tags = \"([^\"]*)\""),
			CONFIG = Pattern.compile("m_config = \"([^\"]*)\"");

	/** Blocking (first call runs VRF once); call off the render thread. Returns null if the clip can't be read. */
	public static AnimClip load(String vnmclipPath) {
		return CACHE.computeIfAbsent(vnmclipPath, p -> {
			try {
				String t = Files.readString(AssetCache.dataBlock(p));
				Matcher d = DURATION.matcher(t);
				double dur = d.find() ? Double.parseDouble(d.group(1)) : 0;
				List<Event> ev = new ArrayList<>();
				int i = t.lastIndexOf("m_events =");
				if (i >= 0) {
					Matcher m = EVENT.matcher(t.substring(i));
					while (m.find()) {
						String body = m.group(4);
						Matcher n = NAME.matcher(body), pm = PARTICLE.matcher(body), tm = TAGS.matcher(body), cm = CONFIG.matcher(body);
						boolean particle = m.group(1).equals("CNmParticleEvent") && pm.find();
						ev.add(new Event(m.group(1), particle ? pm.group(1) : n.find() ? n.group(1) : "", Double.parseDouble(m.group(2)) * dur,
								Double.parseDouble(m.group(3)) * dur, (tm.find() ? tm.group(1) : "") + "|" + (cm.find() ? cm.group(1) : "")));
					}
				}
				StrafeMc.LOG.info("[strafemc] clip {}: {} s, {} events", p, String.format("%.3f", dur), ev.size());
				return new AnimClip(p, dur, List.copyOf(ev));
			} catch (Exception e) {
				StrafeMc.LOG.warn("[strafemc] clip {} unreadable: {}", p, e.toString());
				return null;
			}
		});
	}

	/** Time (s) of the first event with this name, or fallback. */
	public double timeOf(String name, double fallback) {
		for (Event e : events) if (e.name().equals(name)) return e.time();
		return fallback;
	}
}
