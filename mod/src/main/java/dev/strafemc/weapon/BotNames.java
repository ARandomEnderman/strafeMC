package dev.strafemc.weapon;

import dev.strafemc.config.StrafeConfig;
import dev.strafemc.server.StrafeSettings;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bot names, which are also whose skin they wear. With strafemc_bot_skins set, the host's chosen players; else random
 * real Minecraft accounts: short words tried against Mojang's public profile lookup (api.mojang.com, the same one the
 * game uses) in the background, the ones that exist kept in cache/bot_names.txt. Skins themselves are fetched and
 * cached by each player's game (a mannequin's profile). Until any are found, the bots wear the default skin.
 */
public final class BotNames {
	private static final List<String> FOUND = new ArrayList<>();
	private static final Random RNG = new Random();
	private static final int WANT = 40;
	private static volatile boolean searching;
	/** Short common words: many are taken as Minecraft names. */
	private static final String[] WORDS = ("apple river storm frost blaze shadow tiger wolf eagle falcon raven ghost pixel spark comet orbit nova lunar solar "
			+ "ember flame stone rock steel iron gold silver copper jade ruby onyx pearl coral ocean wave tide cloud rain snow ice frozen "
			+ "thunder bolt sky star moon sun dawn dusk night knight king queen prince duke baron lord rider hunter ranger sniper archer "
			+ "ninja samurai pirate viking titan giant dragon phoenix hydra kraken golem zombie creeper enderman wither blaze toast bacon "
			+ "pickle cookie muffin waffle noodle potato tomato pepper ginger honey sugar candy cocoa coffee mango lemon cherry berry melon "
			+ "panda koala otter beaver badger fox lynx puma cobra viper python gecko turtle shark whale dolphin penguin parrot crow hawk owl").split(" ");

	private BotNames() {}

	/** Whose skin a new bot wears: one no other bot has, while there are any left. */
	public static synchronized String next(java.util.Set<String> used) {
		List<String> pool = new ArrayList<>(StrafeSettings.botSkins());
		if (pool.isEmpty()) {
			if (FOUND.isEmpty()) load();
			if (FOUND.size() < WANT) search();
			pool.addAll(FOUND);
		}
		if (pool.isEmpty()) return "Bot" + (100 + RNG.nextInt(900));
		List<String> free = new ArrayList<>(pool);
		free.removeAll(used);
		List<String> from = free.isEmpty() ? pool : free;
		return from.get(RNG.nextInt(from.size()));
	}

	private static Path file() {
		return StrafeConfig.cacheDir().resolve("bot_names.txt");
	}

	private static void load() {
		try {
			if (Files.exists(file()))
				for (String l : Files.readAllLines(file())) if (!l.isBlank() && !FOUND.contains(l.trim())) FOUND.add(l.trim());
		} catch (Exception ignored) {
		}
	}

	/** Finds more existing names in the background (one lookup every 400 ms, well under Mojang's rate limit). */
	private static void search() {
		if (searching) return;
		searching = true;
		Thread t = new Thread(() -> {
			try {
				HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
				Pattern nameField = Pattern.compile("\"name\"\\s*:\\s*\"([A-Za-z0-9_]{3,16})\"");
				for (int tries = 0; tries < 200; tries++) {
					synchronized (BotNames.class) {
						if (FOUND.size() >= WANT) break;
					}
					String w = WORDS[RNG.nextInt(WORDS.length)];
					String cand = switch (RNG.nextInt(3)) {
						case 0 -> w;
						case 1 -> Character.toUpperCase(w.charAt(0)) + w.substring(1) + WORDS[RNG.nextInt(WORDS.length)];
						default -> w + (RNG.nextInt(99) + 1);
					};
					if (cand.length() > 16) continue;
					HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + cand))
							.timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
					if (r.statusCode() == 200) {
						Matcher m = nameField.matcher(r.body());
						if (m.find()) synchronized (BotNames.class) {
							if (!FOUND.contains(m.group(1))) {
								FOUND.add(m.group(1));
								Files.createDirectories(file().getParent());
								Files.write(file(), FOUND);
							}
						}
					}
					Thread.sleep(400);
				}
			} catch (Exception e) {
				dev.strafemc.StrafeMc.LOG.warn("[strafemc] bot names: {}", e.toString());
			} finally {
				searching = false;
			}
		}, "strafemc-bot-names");
		t.setDaemon(true);
		t.start();
	}
}
