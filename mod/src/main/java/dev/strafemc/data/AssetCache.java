package dev.strafemc.data;

import dev.strafemc.StrafeMc;
import dev.strafemc.config.StrafeConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Local cache of files converted from the user's CS2 copy by ValveResourceFormat (Source2Viewer-CLI).
 * Nothing from Valve is bundled in the mod; everything is extracted on the user's machine on first use.
 */
public final class AssetCache {
	private AssetCache() {}

	/** One lock per cached file: the same file is converted once, different files in parallel (VRF runs capped below). */
	private static final java.util.concurrent.ConcurrentHashMap<String, Object> LOCKS = new java.util.concurrent.ConcurrentHashMap<>();
	/** At most this many converter processes at once (each is a .NET process). */
	private static final java.util.concurrent.Semaphore VRF_SLOTS = new java.util.concurrent.Semaphore(4);

	private static Object lock(String key) {
		return LOCKS.computeIfAbsent(key, k -> new Object());
	}

	private static volatile boolean availableCache;
	private static volatile long availableAt;

	/** A usable CS2 install and converter exist (rechecked every few seconds). Without them, the client uses placeholders. */
	public static boolean available() {
		long now = System.currentTimeMillis();
		if (now - availableAt > 5000) {
			availableCache = Files.isRegularFile(vpk()) && Files.isRegularFile(Path.of(StrafeConfig.vrfPath));
			availableAt = now;
		}
		return availableCache;
	}

	public static Path vpk() {
		return Path.of(StrafeConfig.cs2Path, "game", "csgo", "pak01_dir.vpk");
	}

	/**
	 * Returns the decompiled/extracted cache path for a VPK entry (e.g. "scripts/weapons.vdata_c" ->
	 * cache/scripts/weapons.vdata), running VRF if it is not cached yet. Blocking; call off the render thread.
	 */
	public static Path decompiled(String vpkPath) throws IOException {
		synchronized (lock(vpkPath)) {
			Path out = StrafeConfig.cacheDir();
			Path expected = out.resolve(vpkPath.endsWith("_c") ? vpkPath.substring(0, vpkPath.length() - 2) : vpkPath);
			if (Files.exists(expected)) return expected;
			runVrf(List.of("-i", vpk().toString(), "--vpk_filepath", vpkPath, "-o", out.toString(), "-d"));
			if (!Files.exists(expected)) throw new IOException("VRF produced no " + expected);
			return expected;
		}
	}

	/**
	 * The DATA block of a resource as KV3 text (e.g. an animation clip's events), cached as "<path>.DATA.kv3".
	 * VRF prints it to stdout, which is redirected into the cache file.
	 */
	public static Path dataBlock(String vpkPath) throws IOException {
		return block(vpkPath, "DATA");
	}

	/** Any resource block as VRF's text dump (e.g. a model's "MDAT" with its attachments), cached as "<path>.<BLOCK>.kv3". */
	public static Path block(String vpkPath, String blockName) throws IOException {
		synchronized (lock(vpkPath + "." + blockName)) {
			Path out = StrafeConfig.cacheDir().resolve(vpkPath + "." + blockName + ".kv3");
			if (Files.exists(out) && Files.size(out) > 0) return out;
			if (!available()) throw new IOException("no CS2 install / converter (" + vpk() + ")");
			Files.createDirectories(out.getParent());
			Path exe = Path.of(StrafeConfig.vrfPath);
			Process p = new ProcessBuilder(exe.toString(), "-i", vpk().toString(), "--vpk_filepath", vpkPath, "--block", blockName)
					.redirectErrorStream(true).redirectOutput(out.toFile()).start();
			try {
				if (!p.waitFor(2, TimeUnit.MINUTES)) p.destroyForcibly();
			} catch (InterruptedException e) {
				p.destroyForcibly();
				throw new IOException("interrupted", e);
			}
			if (!Files.exists(out) || Files.size(out) == 0) throw new IOException("VRF printed no " + blockName + " block for " + vpkPath);
			return out;
		}
	}

	/**
	 * Every pak path under a folder prefix (e.g. "weapons/paints/"), from one converter listing saved in the cache
	 * (cache/<prefix>_list.txt); refreshed when missing.
	 */
	public static List<String> list(String prefix) throws IOException {
		synchronized (lock("list " + prefix)) {
			Path out = StrafeConfig.cacheDir().resolve(prefix + "_list.txt");
			if (!Files.exists(out) || Files.size(out) == 0) {
				if (!available()) throw new IOException("no CS2 install / converter (" + vpk() + ")");
				Files.createDirectories(out.getParent());
				Process p = new ProcessBuilder(StrafeConfig.vrfPath, "-i", vpk().toString(), "-l", "-f", prefix).redirectErrorStream(true)
						.redirectOutput(out.toFile()).start();
				try {
					if (!p.waitFor(2, TimeUnit.MINUTES)) p.destroyForcibly();
				} catch (InterruptedException e) {
					p.destroyForcibly();
					throw new IOException("interrupted", e);
				}
			}
			List<String> paths = new ArrayList<>();
			for (String l : Files.readAllLines(out)) {
				String t = l.trim();
				int sp = t.indexOf(' ');
				if (t.startsWith(prefix)) paths.add(sp > 0 ? t.substring(0, sp) : t);
			}
			return paths;
		}
	}

	/** Decompiles a single resource into the cache if its output isn't there yet (e.g. "sounds/weapons/ak47/ak47_01.vsnd_c"). */
	public static void ensureDecompiled(String vpkPath, Path expectedOutput) throws IOException {
		synchronized (lock(vpkPath)) {
			if (Files.exists(expectedOutput)) return;
			runVrf(List.of("-i", vpk().toString(), "--vpk_filepath", vpkPath, "-o", StrafeConfig.cacheDir().toString(), "-d"));
		}
	}

	/**
	 * Exports a model or animation clip to binary glTF (with skeleton/animations; materials+textures for models)
	 * into cache/gltf, once. Returns the .glb path.
	 */
	public static Path exportGlb(String vpkPath, boolean materials) throws IOException {
		synchronized (lock("gltf export")) { // exports write shared textures next to the models: one at a time
			Path dir = StrafeConfig.cacheDir().resolve("gltf");
			String base = vpkPath.substring(0, vpkPath.lastIndexOf('.'));
			Path glb = dir.resolve(base + ".glb");
			// a model first exported without materials (e.g. for its skeleton) has no textures next to it: export again
			if (Files.exists(glb) && (!materials || texturesPresent(glb))) return glb;
			Files.createDirectories(dir); // VRF treats a missing -o folder as an output file name
			List<String> args = new java.util.ArrayList<>(List.of("-i", vpk().toString(), "--vpk_filepath", vpkPath, "-o", dir.toString(), "-d",
					"--gltf_export_format", "glb", "--gltf_export_animations"));
			if (materials) args.add("--gltf_export_materials");
			runVrf(args);
			if (!Files.exists(glb)) throw new IOException("VRF produced no " + glb);
			return glb;
		}
	}

	/** Every image a .glb names (glTF "images" uris) exists next to it. */
	static boolean texturesPresent(Path glb) {
		try (var in = Files.newInputStream(glb)) {
			byte[] head = in.readNBytes(20);
			if (head.length < 20) return false;
			int len = java.nio.ByteBuffer.wrap(head, 12, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
			String json = new String(in.readNBytes(len), java.nio.charset.StandardCharsets.UTF_8);
			var root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
			if (!root.has("images")) return true;
			for (var img : root.getAsJsonArray("images")) {
				var o = img.getAsJsonObject();
				if (o.has("uri") && !Files.exists(glb.resolveSibling(o.get("uri").getAsString()))) return false;
			}
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/** Decompiles several resources in one converter run (paths joined for VRF's file filter). */
	public static void decompileAll(List<String> vpkPaths) throws IOException {
		synchronized (lock(String.join(",", vpkPaths))) {
			if (vpkPaths.isEmpty()) return;
			runVrf(List.of("-i", vpk().toString(), "--vpk_filepath", String.join(",", vpkPaths), "-o", StrafeConfig.cacheDir().toString(), "-d"));
		}
	}

	/** Decompiles every file under a VPK folder prefix (e.g. "sounds/player/") into the cache. */
	public static void extractFolder(String prefix) throws IOException {
		synchronized (lock(prefix)) {
			runVrf(List.of("-i", vpk().toString(), "--vpk_filepath", prefix, "-o", StrafeConfig.cacheDir().toString(), "-d"));
		}
	}

	/** Extracts a raw file (no decompile), e.g. "scripts/items/items_game.txt". */
	public static Path raw(String vpkPath) throws IOException {
		synchronized (lock(vpkPath)) {
			Path out = StrafeConfig.cacheDir();
			Path expected = out.resolve(vpkPath);
			if (Files.exists(expected)) return expected;
			runVrf(List.of("-i", vpk().toString(), "--vpk_filepath", vpkPath, "-o", out.toString()));
			if (!Files.exists(expected)) throw new IOException("VRF produced no " + expected);
			return expected;
		}
	}

	static void runVrf(List<String> args) throws IOException {
		Path exe = Path.of(StrafeConfig.vrfPath);
		if (!Files.isRegularFile(exe)) throw new IOException("ValveResourceFormat CLI not found at " + exe + " (set vrfPath in strafemc.properties)");
		if (!Files.isRegularFile(vpk())) throw new IOException("CS2 pak not found at " + vpk() + " (set cs2Path in strafemc.properties)");
		List<String> cmd = new ArrayList<>();
		cmd.add(exe.toString());
		cmd.addAll(args);
		Files.createDirectories(StrafeConfig.cacheDir());
		// each loader thread its own log: several conversions can run at once
		Path log = StrafeConfig.RUNTIME_DIR.resolve("vrf-last-" + Thread.currentThread().getName().replaceAll("[^A-Za-z0-9_-]", "_") + ".log");
		long t = System.nanoTime();
		try {
			VRF_SLOTS.acquire();
		} catch (InterruptedException e) {
			throw new IOException("interrupted", e);
		}
		Process p;
		try {
			p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
			if (!p.waitFor(10, TimeUnit.MINUTES)) {
				p.destroyForcibly();
				throw new IOException("VRF timed out");
			}
		} catch (InterruptedException e) {
			throw new IOException("interrupted", e);
		} finally {
			VRF_SLOTS.release();
		}
		StrafeMc.LOG.info("[strafemc] VRF {} -> exit {} in {} ms (log {})", args, p.exitValue(), (System.nanoTime() - t) / 1_000_000, log);
	}
}
