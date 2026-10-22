package dev.strafemc.setup;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * strafeMC's first-time setup: converts everything the mod uses from the player's own CS2 install into its cache, up
 * front: every gun and knife (models, animations, sounds, icons), the arms, impact effects and decals, scripts and sound
 * events. Weapon textures are kept as 1024 px base-colour copies only (that's all the mod reads), which keeps the cache
 * small. Each step has a version: when a mod update adds to a step, only that step runs again. A marker file lists every
 * file the setup produced, so a start-up check can tell whether anything is missing.
 *
 * No Minecraft classes: the standalone setup tool runs this same code.
 */
public final class Setup {
	/** Bump a step's version when it starts converting more (a new kind of asset): only that step re-runs. */
	public static final Map<String, Integer> STEPS = stepVersions();

	private static Map<String, Integer> stepVersions() {
		Map<String, Integer> m = new LinkedHashMap<>();
		m.put("scripts", 1);
		m.put("sounds", 1);
		m.put("clips", 1);
		m.put("graphs", 1);
		m.put("models", 2); // 2: AO baked into the colour + metalness mask
		m.put("icons", 2); // 2: armor icons
		m.put("effects", 6); // 2: HE explosion, 3: all grenade effects, 4: fire per flame + lit molotov, 5: decoy sparks, 6: C4 timer lights
		m.put("decals", 1);
		m.put("smoke", 1); // volumetric smoke: CS2's 3D smoke noise and the blue noise its raymarch is jittered with
		m.put("stickers", 1); // every sticker's picture (tournament, autograph, community) and every capsule's
		m.put("wraps", 7); // skin wrap pictures; 3: skin pieces only (the runtime pack puts them on the wrap template); 4: colour-only skins; 5: Doppler phases; 6: gun wraps one per gun; 7: the Gallery case's skins
		return m;
	}

	public static final String ARMS = "agents/models/shared/arms/glove_fingerless/glove_fingerless.vmdl_c";
	private static final String MARKER = "setup.json";
	private static final Gson GSON = new Gson();
	private static final Pattern RES = Pattern.compile("resource(?:_name)?:\"([^\"]+)\"");

	private final Path cache, vpk, vrfExe;
	private final Vrf vrf;
	/** (fraction done 0..1, what is happening) */
	private final BiConsumer<Double, String> progress;

	public Setup(Path cs2Folder, Path converter, Path cache, BiConsumer<Double, String> progress) throws IOException {
		this.cache = cache;
		this.vpk = cs2Folder.resolve("game").resolve("csgo").resolve("pak01_dir.vpk");
		Files.createDirectories(cache);
		this.vrf = new Vrf(converter, vpk, cache);
		this.vrfExe = converter;
		this.progress = progress;
	}

	/** Paths and a missing-files count, for the start-up check (fast: existence checks only). */
	public record Status(boolean done, List<String> staleSteps, int missingFiles, String cs2Build) {
		public boolean needsSetup() {
			return !done || !staleSteps.isEmpty() || missingFiles > 0;
		}
	}

	public static Status status(Path cache) {
		Path marker = cache.resolve(MARKER);
		if (!Files.exists(marker)) return new Status(false, new ArrayList<>(STEPS.keySet()), 0, null);
		try {
			JsonObject m = GSON.fromJson(Files.readString(marker), JsonObject.class);
			JsonObject steps = m.getAsJsonObject("steps");
			List<String> stale = new ArrayList<>();
			for (var e : STEPS.entrySet())
				if (steps == null || !steps.has(e.getKey()) || steps.get(e.getKey()).getAsInt() < e.getValue()) stale.add(e.getKey());
			int missing = 0;
			JsonArray files = m.getAsJsonArray("files");
			if (files != null) for (var f : files) if (!Files.exists(cache.resolve(f.getAsString()))) missing++;
			return new Status(true, stale, missing, m.has("cs2Build") ? m.get("cs2Build").getAsString() : null);
		} catch (Exception e) {
			return new Status(false, new ArrayList<>(STEPS.keySet()), 0, null);
		}
	}

	/**
	 * What a step reads from other steps: effects follows the effects named in weapons.vdata, the surfaces' impact
	 * effects (scripts) and the animation events (clips); decals reads the decal groups (scripts). The rest stand alone.
	 */
	private static final Map<String, List<String>> NEEDS = Map.of("effects", List.of("scripts", "clips"), "decals", List.of("scripts"));

	/** Each running step's own progress (0..1), averaged for the whole. */
	private final Map<String, Double> stepProgress = new java.util.concurrent.ConcurrentHashMap<>();
	private static final ThreadLocal<String> STEP = new ThreadLocal<>();

	/**
	 * Runs every step that's missing or outdated (all of them the first time), side by side where they don't depend on
	 * each other (the converter runs share the CPU, see Vrf), then writes the marker.
	 */
	public void run(boolean all) throws IOException {
		if (!Files.isRegularFile(vpk)) throw new IOException("Counter-Strike 2 files not found at " + vpk);
		Status st = status(cache);
		List<String> todo = all || st.missingFiles() > 0 ? new ArrayList<>(STEPS.keySet()) : st.staleSteps();
		Map<String, Long> pak = vrf.list(null);
		vrf.pak(pak);
		progress(0, "Reading the list of CS2 files");
		for (String step : todo) stepProgress.put(step, 0.0);
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, todo.size()));
		Map<String, java.util.concurrent.CompletableFuture<Void>> runs = new LinkedHashMap<>();
		try {
			for (String step : todo) {
				var deps = NEEDS.getOrDefault(step, List.of()).stream().filter(runs::containsKey).map(runs::get)
						.toArray(java.util.concurrent.CompletableFuture[]::new);
				runs.put(step, java.util.concurrent.CompletableFuture.allOf(deps).thenRunAsync(() -> {
					STEP.set(step);
					try {
						step(step, pak);
						stepProgress.put(step, 1.0);
					} catch (IOException e) {
						throw new java.io.UncheckedIOException(e);
					} finally {
						STEP.remove();
					}
				}, pool));
			}
			java.util.concurrent.CompletableFuture.allOf(runs.values().toArray(java.util.concurrent.CompletableFuture[]::new)).join();
		} catch (java.util.concurrent.CompletionException e) {
			Throwable c = e.getCause();
			if (c instanceof java.io.UncheckedIOException u) throw u.getCause();
			throw new IOException(c);
		} finally {
			pool.shutdownNow();
		}
		writeMarker(cs2Build());
		progress(1, "Done");
	}

	private void step(String step, Map<String, Long> pak) throws IOException {
		switch (step) {
			case "scripts" -> scripts(0, 1);
			case "sounds" -> sounds(0, 1);
			case "clips" -> clips(0, 1);
			case "graphs" -> graphs(0, 1);
			case "models" -> models(pak, 0, 1);
			case "icons" -> icons(0, 1);
			case "effects" -> effects(pak, 0, 1);
			case "decals" -> decals(0, 1);
			case "wraps" -> wraps(pak, 0, 1);
			case "smoke" -> smoke(0, 1);
			case "stickers" -> stickers(0, 1);
			default -> {
			}
		}
	}

	/** A step's progress (0..1 of that step, on its own thread) or the whole run's; reported as the steps' average. */
	private void progress(double f, String what) {
		if (progress == null) return;
		String step = STEP.get();
		if (step == null || stepProgress.isEmpty()) {
			progress.accept(Math.max(0, Math.min(1, f)), what);
			return;
		}
		stepProgress.put(step, Math.max(0, Math.min(1, f)));
		double sum = 0;
		for (double v : stepProgress.values()) sum += v;
		progress.accept(sum / stepProgress.size(), what);
	}

	// ---------------------------------------------------------------- steps

	private void scripts(double base, double span) throws IOException {
		progress(base, "Weapon data, surfaces and sound events");
		vrf.decompile(List.of("scripts/weapons.vdata_c", "scripts/decalgroups.vdata_c", "surfaceproperties/surfaceproperties.vsurf_c",
				"scripts/surfaceproperties_impact_effects.txt", "scripts/surfaceproperties_game.txt",
				"soundevents/game_sounds_footsteps.vsndevts_c", "soundevents/game_sounds_player.vsndevts_c",
				"soundevents/game_sounds_weapons.vsndevts_c", "soundevents/game_sounds_physics.vsndevts_c", "soundevents/game_sounds_ui.vsndevts_c",
				"soundevents/vo/announcer/game_sounds_cs2_classic.vsndevts_c", "soundevents/music/valve_cs2_01/game_sounds_music.vsndevts_c"), null, cache);
	}

	private void sounds(double base, double span) throws IOException {
		String[] folders = {"sounds/weapons/", "sounds/player/", "sounds/physics/"};
		for (int k = 0; k < folders.length; k++) {
			progress(base + span * k / folders.length, "Sounds: " + folders[k]);
			vrf.decompile(List.of(folders[k]), "vsnd_c", cache);
		}
		// the mod's own "already extracted" markers, so it never re-extracts these folders while playing
		for (String d : new String[] {"sounds/player/", "sounds/physics/surfaces/", "sounds/weapons/knife/", "sounds/weapons/knife_falchion/"}) {
			Path m = cache.resolve(d + ".extracted");
			Files.createDirectories(m.getParent());
			Files.writeString(m, "ok");
		}
	}

	private void clips(double base, double span) throws IOException {
		progress(base, "Weapon animations (events)");
		vrf.blocks(List.of("animation/anims/viewmodel/"), "vnmclip_c", "DATA", cache);
		progress(base + span / 2, "Weapon animations (motion)");
		vrf.glb(List.of("animation/anims/viewmodel/"), "vnmclip_c", cache.resolve("gltf"), false);
	}

	private void graphs(double base, double span) throws IOException {
		progress(base, "Animation graphs");
		vrf.blocks(List.of("animation/graphs/viewmodel/"), "vnmgraph_c", "DATA", cache);
	}

	/** Every gun and knife model plus the arms: models with their textures, attachments, then shrunk base colours only. */
	private void models(Map<String, Long> pak, double base, double span) throws IOException {
		List<String> models = new ArrayList<>();
		for (String p : pak.keySet())
			if (p.startsWith("weapons/models/") && p.matches(".*/weapon_[^/]+\\.vmdl_c")) models.add(p);
		models.add(ARMS);
		progress(base, "Gun and knife models (" + models.size() + ")");
		vrf.glb(models, null, cache.resolve("gltf"), true);
		progress(base + span * 0.6, "Weapon attachments");
		vrf.blocks(models, null, "MDAT", cache);
		progress(base + span * 0.75, "Shrinking weapon textures");
		shrinkTextures(models);
	}

	private void icons(double base, double span) throws IOException {
		progress(base, "Weapon icons");
		vrf.decompile(List.of("panorama/images/econ/weapons/base_weapons/"), "vtex_c", cache);
		vrf.decompile(List.of("panorama/images/hud/ammo_reserve_magazine.vsvg_c", "panorama/images/hud/ammo_fullauto.vsvg_c",
				"panorama/images/hud/ammo_reserve_generic_bullet.vsvg_c", "panorama/images/icons/equipment/kevlar.vsvg_c",
				"panorama/images/icons/equipment/assaultsuit.vsvg_c"), null, cache);
	}

	/**
	 * Impact and weapon effects: starts from the surfaces' impact effects, the effects named in weapons.vdata and in the
	 * animation events, follows every child effect, then converts the textures they use (with their sprite sheets).
	 */
	private void effects(Map<String, Long> pak, double base, double span) throws IOException {
		progress(base, "Effects");
		Set<String> roots = new LinkedHashSet<>();
		for (String f : new String[] {"scripts/surfaceproperties_impact_effects.txt", "scripts/weapons.vdata"}) {
			Path p = cache.resolve(f);
			if (Files.exists(p)) collect(Files.readString(p), ".vpcf", roots);
		}
		Path clips = cache.resolve("animation/anims/viewmodel");
		if (Files.isDirectory(clips)) try (Stream<Path> s = Files.walk(clips)) {
			for (Path p : s.filter(x -> x.toString().endsWith(".DATA.kv3")).toList()) collect(Files.readString(p), ".vpcf", roots);
		}
		roots.addAll(java.util.List.of(dev.strafemc.weapon.GrenadeEffects.EFFECTS));
		Set<String> done = new LinkedHashSet<>(), textures = new LinkedHashSet<>(), materials = new LinkedHashSet<>();
		Set<String> frontier = new LinkedHashSet<>();
		for (String r : roots) frontier.add(compiled(r));
		while (!frontier.isEmpty()) {
			frontier.removeIf(f -> !pak.containsKey(f));
			if (frontier.isEmpty()) break;
			vrf.blocks(frontier, null, "DATA", cache);
			done.addAll(frontier);
			Set<String> next = new LinkedHashSet<>();
			for (String f : frontier) {
				Path p = cache.resolve(f + ".DATA.kv3");
				if (!Files.exists(p)) continue;
				String t = Files.readString(p);
				Set<String> children = new LinkedHashSet<>();
				collect(t, ".vpcf", children);
				for (String c : children) if (!done.contains(compiled(c))) next.add(compiled(c));
				collect(t, ".vtex", textures);
				collect(t, ".vmat", materials); // projected layers (a fire's scorch) draw a material
			}
			frontier = next;
		}
		List<String> vmats = materials.stream().map(Setup::compiled).filter(pak::containsKey).toList();
		if (!vmats.isEmpty()) {
			vrf.blocks(vmats, null, "DATA", cache);
			for (String m : vmats) {
				Path p = cache.resolve(m + ".DATA.kv3");
				if (Files.exists(p)) collect(Files.readString(p), ".vtex", textures);
			}
		}
		progress(base + span * 0.5, "Effect textures (" + textures.size() + ")");
		List<String> tex = textures.stream().map(Setup::compiled).filter(pak::containsKey).toList();
		vrf.blocks(tex, null, "DATA", cache); // sprite sheets
		vrf.decompile(tex, null, cache);
	}

	/** Every decal group's materials (bullet holes, knife marks) and their colour / occlusion textures. */
	private void decals(double base, double span) throws IOException {
		progress(base, "Bullet holes and marks");
		Path groups = cache.resolve("scripts/decalgroups.vdata");
		if (!Files.exists(groups)) return;
		Set<String> mats = new LinkedHashSet<>();
		collect(Files.readString(groups), ".vmat", mats);
		List<String> vmats = mats.stream().map(Setup::compiled).toList();
		vrf.blocks(vmats, null, "DATA", cache);
		Set<String> tex = new LinkedHashSet<>();
		for (String m : vmats) {
			Path p = cache.resolve(m + ".DATA.kv3");
			if (Files.exists(p)) collect(Files.readString(p), ".vtex", tex);
		}
		progress(base + span / 2, "Bullet hole textures (" + tex.size() + ")");
		vrf.decompile(tex.stream().map(Setup::compiled).toList(), null, cache);
	}

	/** Every sticker picture (~11,500: each tournament's team and autograph stickers too) and every capsule picture. */
	private void stickers(double base, double span) throws IOException {
		progress(base, "Sticker pictures");
		vrf.decompile(List.of("panorama/images/econ/stickers/"), "vtex_c", cache);
		progress(base + span * 0.8, "Capsule pictures");
		vrf.decompile(List.of("panorama/images/econ/weapon_cases/"), "vtex_c", cache);
	}

	/**
	 * Volumetric smoke: the 3D noise CS2's smoke shader (materials/dev/smoke_volume.vmat, g_tSmokeNoiseTex) samples, as
	 * one PNG per depth slice, and the blue noise texture from the core pak that jitters each ray's start.
	 */
	private void smoke(double base, double span) throws IOException {
		progress(base, "Smoke noise");
		vrf.decompile(List.of("materials/dev/noise/"), "vtex_c", cache);
		Path core = vpk.getParent().getParent().resolve("core").resolve("pak01_dir.vpk");
		if (!Files.isRegularFile(core)) return;
		// one matching file: the converter writes it next to the -o path, named after the texture
		Path dir = Files.createDirectories(cache.resolve("textures/dev"));
		Path stem = dir.resolve("blue_noise_256");
		new Vrf(vrfExe, core, cache).decompile(List.of("textures/dev/blue_noise_256.vtex_c"), null, stem);
		if (Files.isRegularFile(stem)) Files.delete(stem); // the converter's own dump of the texture's header
	}

	/**
	 * Skin wrap pictures: every weapon skin's material in one run, the pattern textures the wraps use, then one icon per
	 * wrap (a piece of its texture, WrapIcons) into cache/wrap_swatches/. The runtime pack makes the pictures and models.
	 */
	private void wraps(Map<String, Long> pak, double base, double span) throws IOException {
		progress(base, "Skin wraps: materials");
		String vmats = "materials/models/weapons/customization/paints/vmats/";
		vrf.blocks(List.of(vmats), "vmat_c", "DATA", cache);
		List<dev.strafemc.weapon.SkinWraps.Wrap> wraps = dev.strafemc.weapon.SkinWraps.all();
		java.util.Map<String, dev.strafemc.client.viewmodel.GloveSkinBaker.Params> params = new java.util.HashMap<>();
		Set<String> patterns = new LinkedHashSet<>();
		for (var w : wraps) {
			String kit = dev.strafemc.weapon.SkinWraps.iconKit(w);
			Path p = kit == null ? null : cache.resolve(vmats + kit + ".vmat_c.DATA.kv3");
			if (p == null || !Files.exists(p)) continue;
			var par = dev.strafemc.client.viewmodel.GloveSkinBaker.Params.parse(Files.readAllLines(p));
			params.put(w.id(), par);
		}
		// newer skins exist only as composites: their colour texture is named in the composite itself (a custom paint)
		Map<String, String> composites = new java.util.HashMap<>();
		for (String f : pak.keySet())
			if (f.startsWith("weapons/paints/") && f.endsWith(".vcompmat_c"))
				composites.put(f.substring(f.lastIndexOf('/') + 1, f.length() - ".vcompmat_c".length()), f);
		Map<String, String> missing = new java.util.LinkedHashMap<>();
		for (var w : wraps) {
			String kit = dev.strafemc.weapon.SkinWraps.iconKit(w);
			if (!params.containsKey(w.id()) && kit != null && composites.containsKey(kit)) missing.put(w.id(), composites.get(kit));
		}
		progress(base + span * 0.15, "Skin wraps: newer skins (" + missing.size() + ")");
		vrf.blocks(new ArrayList<>(new LinkedHashSet<>(missing.values())), null, "DATA", cache);
		Pattern texture = Pattern.compile("m_strTextureRuntimeResourcePath = resource_name:\"([^\"]+[.]vtex)\"");
		for (var e : missing.entrySet()) {
			Path p = cache.resolve(e.getValue() + ".DATA.kv3");
			if (!Files.exists(p)) continue;
			String text = Files.readString(p);
			var par = new dev.strafemc.client.viewmodel.GloveSkinBaker.Params();
			// the composite's own variables: a "g_tPattern" texture and g_vColor0..3 (0..255) like the older materials
			Matcher v = Pattern.compile("m_strName = \"(g_tPattern|g_vColor[0-3])\"(.*?)(?=m_strName = |\\z)", Pattern.DOTALL).matcher(text);
			int colours = 0;
			while (v.find()) {
				Matcher t = texture.matcher(v.group(2)), c = Pattern.compile("m_cValueColor4 = \\[ *(\\d+), *(\\d+), *(\\d+)").matcher(v.group(2));
				if (v.group(1).equals("g_tPattern") && t.find() && !t.group(1).startsWith("materials/default/")) par.textures.put("g_tPattern", t.group(1));
				else if (v.group(1).startsWith("g_vColor") && c.find()) {
					par.values.put(v.group(1), new double[] {Integer.parseInt(c.group(1)) / 255.0, Integer.parseInt(c.group(2)) / 255.0, Integer.parseInt(c.group(3)) / 255.0});
					colours++;
				}
			}
			if (par.textures.containsKey("g_tPattern")) par.values.put("F_PAINT_STYLE", new double[] {colours > 0 ? 1 : 6}); // a mask for the colours, else a picture
			else { // else its colour texture by name, used as a custom paint
				Matcher m = texture.matcher(text);
				while (m.find()) {
					String t = m.group(1), name = t.substring(t.lastIndexOf('/') + 1);
					if (!t.startsWith("materials/default/") && (name.contains("albedo") || name.contains("_color") || name.contains("_diffuse"))) {
						par.textures.put("g_tPattern", t);
						break;
					}
				}
				if (!par.textures.containsKey("g_tPattern")) {
					if (colours == 0) continue;
					par.values.put("F_PAINT_STYLE", new double[] {0}); // colours only (so_extended_template): a solid colour
				} else par.values.put("F_PAINT_STYLE", new double[] {6});
			}
			params.put(e.getKey(), par);
		}
		for (var par : params.values()) {
			String pat = par.textures.get("g_tPattern");
			if (pat != null && !pat.startsWith("materials/default/")) patterns.add(compiled(pat));
		}
		progress(base + span * 0.3, "Skin wraps: textures (" + patterns.size() + ")");
		patterns.removeIf(t -> Files.exists(cache.resolve(t.substring(0, t.length() - "vtex_c".length()) + "png")));
		vrf.decompile(patterns, null, cache);
		progress(base + span * 0.6, "Skin wraps: pictures (" + wraps.size() + ")");
		// each pattern read once (only the part a swatch shows), the swatches in parallel
		Map<String, java.util.Optional<int[]>> pieces = new java.util.concurrent.ConcurrentHashMap<>();
		Files.createDirectories(cache.resolve("wrap_swatches"));
		wraps.parallelStream().forEach(w -> {
			var par = params.get(w.id());
			if (par == null) return;
			String pat = par.textures.get("g_tPattern");
			int[] piece = null;
			if (pat != null && !pat.startsWith("materials/default/")) {
				Path png = cache.resolve(pat.substring(0, pat.length() - ".vtex".length()) + ".png");
				piece = pieces.computeIfAbsent(png.toString(), k -> java.util.Optional.ofNullable(Files.exists(png) ? WrapIcons.piece(png) : null)).orElse(null);
			}
			try {
				WrapIcons.write(WrapIcons.swatchOf(par, piece), cache.resolve("wrap_swatches/" + WrapIcons.fileName(w.id()) + ".png"));
			} catch (Exception e) { // one odd texture shouldn't stop the setup: that wrap keeps the plain picture
				System.err.println("[strafemc] wrap picture " + w.id() + ": " + e);
			}
		});
	}

	// ---------------------------------------------------------------- helpers

	private static void collect(String text, String ext, Set<String> out) {
		Matcher m = RES.matcher(text);
		while (m.find()) if (m.group(1).endsWith(ext)) out.add(m.group(1));
	}

	private static String compiled(String path) {
		return path.endsWith("_c") ? path : path + "_c";
	}

	/**
	 * Keeps a 1024 px copy of each model's base-colour textures (what the mod renders) with CS2's ambient occlusion
	 * multiplied in (.vm1024c.png), plus a 1024 px metalness mask (.vm1024m.png) where the material has metal parts,
	 * and deletes the full-size files.
	 */
	private void shrinkTextures(List<String> models) throws IOException {
		Set<Path> keep = new LinkedHashSet<>(), folders = new LinkedHashSet<>();
		List<Path[]> work = new ArrayList<>();
		for (String m : models) {
			Path glb = cache.resolve("gltf").resolve(m.substring(0, m.lastIndexOf('.')) + ".glb");
			if (!Files.exists(glb)) continue;
			folders.add(glb.getParent());
			for (Path[] mat : materials(glb)) {
				Path tex = mat[0], small = tex.resolveSibling(tex.getFileName().toString().replace(".png", ".vm1024c.png")),
						mask = tex.resolveSibling(tex.getFileName().toString().replace(".png", ".vm1024m.png"));
				if (keep.add(small)) work.add(new Path[] {tex, mat[1], small, mat[2], mask}); // models share textures: each once
				keep.add(mask);
			}
		}
		// the textures in parallel (decoding big PNGs is most of the time)
		java.util.concurrent.atomic.AtomicReference<IOException> failed = new java.util.concurrent.atomic.AtomicReference<>();
		work.parallelStream().forEach(w -> {
			try {
				if (!Files.exists(w[2]) && Files.exists(w[0])) composite(w[0], w[1], w[2]);
				if (!Files.exists(w[4]) && w[3] != null && Files.exists(w[3])) metalMask(w[3], w[0], w[4]);
			} catch (IOException e) {
				failed.compareAndSet(null, e);
			}
		});
		if (failed.get() != null) throw failed.get();
		for (Path dir : folders)
			try (Stream<Path> s = Files.list(dir)) {
				for (Path p : s.toList())
					if (p.toString().endsWith(".png") && !keep.contains(p)) Files.deleteIfExists(p);
			}
	}

	/** Per material with a base colour: {colour, ambient occlusion or null, metalness or null} texture files. */
	private static List<Path[]> materials(Path glb) throws IOException {
		byte[] b = Files.readAllBytes(glb);
		ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
		JsonObject g = GSON.fromJson(new String(b, 20, bb.getInt(12), StandardCharsets.UTF_8), JsonObject.class);
		List<Path[]> out = new ArrayList<>();
		if (!g.has("materials") || !g.has("textures") || !g.has("images")) return out;
		java.util.function.Function<JsonObject, Path> file = ref -> {
			if (ref == null || !ref.has("index")) return null;
			int img = g.getAsJsonArray("textures").get(ref.get("index").getAsInt()).getAsJsonObject().get("source").getAsInt();
			JsonObject im = g.getAsJsonArray("images").get(img).getAsJsonObject();
			return im.has("uri") ? glb.getParent().resolve(im.get("uri").getAsString()) : null;
		};
		for (var me : g.getAsJsonArray("materials")) {
			JsonObject mat = me.getAsJsonObject(), pbr = mat.getAsJsonObject("pbrMetallicRoughness");
			if (pbr == null || !pbr.has("baseColorTexture")) continue;
			Path color = file.apply(pbr.getAsJsonObject("baseColorTexture"));
			if (color == null) continue;
			out.add(new Path[] {color, file.apply(mat.getAsJsonObject("occlusionTexture")), file.apply(pbr.getAsJsonObject("metallicRoughnessTexture"))});
		}
		return out;
	}

	/** The colour shrunk to 1024 px with the ambient occlusion (its red channel, same UVs) multiplied in. */
	private static void composite(Path color, Path ao, Path out) throws IOException {
		shrink(color, out);
		if (ao == null || !Files.exists(ao)) return;
		BufferedImage c = ImageIO.read(out.toFile()), a = ImageIO.read(ao.toFile());
		if (c == null || a == null) return;
		int w = c.getWidth(), h = c.getHeight(), aw = a.getWidth(), ah = a.getHeight();
		int[] px = c.getRGB(0, 0, w, h, null, 0, w), occ = a.getRGB(0, 0, aw, ah, null, 0, aw);
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++) {
				int i = y * w + x, p = px[i], o = occ[(y * ah / h) * aw + x * aw / w] >> 16 & 255;
				int r = (p >> 16 & 255) * o / 255, gg = (p >> 8 & 255) * o / 255, bl = (p & 255) * o / 255;
				px[i] = p & 0xFF000000 | r << 16 | gg << 8 | bl;
			}
		c.setRGB(0, 0, w, h, px, 0, w);
		ImageIO.write(c, "png", out.toFile());
	}

	/**
	 * CS2's metalness (green channel of its g_tMetalness texture; blue if the export moved it there, as glTF does) as a
	 * grey 1024 px mask; none if nothing is metal.
	 */
	private static void metalMask(Path metal, Path color, Path out) throws IOException {
		BufferedImage m = ImageIO.read(metal.toFile());
		if (m == null) return;
		int[] size = size(color);
		int w = Math.min(1024, size != null ? size[0] : 1024), h = Math.min(1024, size != null ? size[1] : 1024);
		int mw = m.getWidth(), mh = m.getHeight();
		int[] src = m.getRGB(0, 0, mw, mh, null, 0, mw);
		int maxG = 0, maxB = 0;
		for (int y = 0; y < mh; y += 8)
			for (int x = 0; x < mw; x += 8) {
				int p = src[y * mw + x];
				maxG = Math.max(maxG, p >> 8 & 255);
				maxB = Math.max(maxB, p & 255);
			}
		int shift = maxB > 0 ? 0 : 8;
		if ((shift == 0 ? maxB : maxG) == 0) return;
		int[] dst = new int[w * h];
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++) {
				int v = src[(y * mh / h) * mw + x * mw / w] >> shift & 255;
				dst[y * w + x] = v << 16 | v << 8 | v;
			}
		BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		o.setRGB(0, 0, w, h, dst, 0, w);
		ImageIO.write(o, "png", out.toFile());
	}

	/** An image's width and height from its header (no decoding), or null. */
	private static int[] size(Path png) {
		try (var in = ImageIO.createImageInputStream(png.toFile())) {
			var readers = ImageIO.getImageReaders(in);
			if (!readers.hasNext()) return null;
			var r = readers.next();
			try {
				r.setInput(in, true, true);
				return new int[] {r.getWidth(0), r.getHeight(0)};
			} finally {
				r.dispose();
			}
		} catch (IOException e) {
			return null;
		}
	}

	/** Same filter as the viewmodel's own downscale: power-of-two box filter per channel, alpha kept as is. */
	private static void shrink(Path in, Path out) throws IOException {
		BufferedImage img = ImageIO.read(in.toFile());
		if (img == null) return;
		int w = img.getWidth(), h = img.getHeight(), f = 1;
		while (Math.max(w, h) / f > 1024) f *= 2;
		int ow = w / f, oh = h / f, n = f * f;
		int[] src = img.getRGB(0, 0, w, h, null, 0, w), dst = new int[ow * oh];
		for (int y = 0; y < oh; y++)
			for (int x = 0; x < ow; x++) {
				long a = 0, r = 0, g = 0, bl = 0;
				for (int dy = 0; dy < f; dy++)
					for (int dx = 0; dx < f; dx++) {
						int p = src[(y * f + dy) * w + x * f + dx];
						a += p >>> 24;
						r += p >> 16 & 255;
						g += p >> 8 & 255;
						bl += p & 255;
					}
				dst[y * ow + x] = (int) (a / n) << 24 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (bl / n);
			}
		BufferedImage o = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_ARGB);
		o.setRGB(0, 0, ow, oh, dst, 0, ow);
		ImageIO.write(o, "png", out.toFile());
	}

	/** CS2's build number (steam.inf PatchVersion), recorded so a later CS2 update can be noticed. */
	private String cs2Build() {
		try {
			Path inf = vpk.getParent().resolve("steam.inf");
			for (String l : Files.readAllLines(inf)) if (l.startsWith("PatchVersion=")) return l.substring("PatchVersion=".length()).trim();
		} catch (IOException ignored) {
		}
		return "unknown";
	}

	private void writeMarker(String build) throws IOException {
		JsonObject m = new JsonObject();
		JsonObject steps = new JsonObject();
		STEPS.forEach(steps::addProperty);
		m.add("steps", steps);
		m.addProperty("cs2Build", build);
		JsonArray files = new JsonArray();
		Map<String, Boolean> sorted = new TreeMap<>();
		try (Stream<Path> s = Files.walk(cache)) {
			for (Path p : s.filter(Files::isRegularFile).toList()) {
				String rel = cache.relativize(p).toString().replace('\\', '/');
				if (!rel.equals(MARKER) && !rel.endsWith(".log") && !rel.contains("vrfblocks") && !rel.contains("vpklist")) sorted.put(rel, true);
			}
		}
		sorted.keySet().forEach(files::add);
		m.add("files", files);
		Files.writeString(cache.resolve(MARKER), GSON.toJson(m));
	}
}
