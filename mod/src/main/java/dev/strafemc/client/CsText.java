package dev.strafemc.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** CS2's own English UI text (resource/csgo_english.txt from the player's files), by token; the fallback without CS2. */
public final class CsText {
	private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
	private static volatile java.util.List<String> lines;

	private CsText() {}

	public static String get(String token, String fallback) {
		return CACHE.computeIfAbsent(token, t -> {
			try {
				if (lines == null) {
					if (!dev.strafemc.data.AssetCache.available()) return fallback;
					lines = java.nio.file.Files.readAllLines(dev.strafemc.data.AssetCache.raw("resource/csgo_english.txt"), java.nio.charset.StandardCharsets.UTF_8);
				}
				var p = java.util.regex.Pattern.compile("^[ \t]*\"" + java.util.regex.Pattern.quote(t) + "\"[ \t]+\"([^\"]*)\"", java.util.regex.Pattern.CASE_INSENSITIVE);
				for (String l : lines) {
					var m = p.matcher(l);
					if (m.find()) return m.group(1);
				}
			} catch (Exception ignored) {
			}
			return fallback;
		});
	}
}
