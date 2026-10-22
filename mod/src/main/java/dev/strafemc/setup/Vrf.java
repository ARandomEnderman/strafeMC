package dev.strafemc.setup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the ValveResourceFormat converter (Source2Viewer-CLI) over the player's own CS2 pak in batches: one process per
 * group of files instead of one per file, several at once. With the pak's file list (pak()), folder filters become
 * file lists split over as many processes as the CPU has room for; a shared pool of permits (one per CPU thread)
 * keeps setup steps running side by side from starting more work than the CPU has threads. No Minecraft classes, so
 * the standalone setup tool can use it too.
 */
public final class Vrf {
	/** Windows command lines are limited to 32767 characters; file lists are split below that. */
	private static final int MAX_FILTER_CHARS = 28000;
	private static final Pattern HEADER = Pattern.compile("^\\[\\d+/\\d+\\] (\\S+)\\r?$", Pattern.MULTILINE);
	/** CPU threads, and the permits every running converter holds one of per thread it uses. */
	static final int CORES = Math.max(2, Runtime.getRuntime().availableProcessors());
	private static final Semaphore CPU = new Semaphore(CORES, true);
	/** Threads per converter process on big jobs: a few processes with a few threads each kept the CPU busiest. */
	private static final int THREADS_EACH = Math.max(1, Math.min(4, CORES / 4));

	private final Path exe, vpk, logDir;
	/** The pak's files (path -> size), for turning folder filters into file lists; null: folders go as they are. */
	private volatile Map<String, Long> pak;

	public Vrf(Path exe, Path vpk, Path logDir) {
		this.exe = exe;
		this.vpk = vpk;
		this.logDir = logDir;
	}

	/** Lets folder filters be split into parallel batches. */
	public void pak(Map<String, Long> pak) {
		this.pak = pak;
	}

	/** Every file in the pak (path -> size), optionally filtered by path prefixes ("a/,b/"). */
	public Map<String, Long> list(String filter) throws IOException {
		Path out = Files.createTempFile(logDir, "vpklist", ".txt");
		try {
			List<String> args = new ArrayList<>(List.of("-i", vpk.toString(), "-l"));
			if (filter != null) args.addAll(List.of("-f", filter));
			run(args, out, 1);
			Map<String, Long> m = new LinkedHashMap<>();
			Pattern line = Pattern.compile("^(\\S+) CRC:\\S+ size:(\\d+)");
			for (String l : Files.readAllLines(out, StandardCharsets.UTF_8)) {
				Matcher mm = line.matcher(l.trim());
				if (mm.find()) m.put(mm.group(1), Long.parseLong(mm.group(2)));
			}
			return m;
		} finally {
			Files.deleteIfExists(out);
		}
	}

	/**
	 * One resource block (e.g. DATA, MDAT) of every matching file, each saved as cache/<path>.<block>.kv3 - the same
	 * files the mod reads. filters: exact paths or folder prefixes; ext: extension filter or null. Each process prints
	 * its blocks one after the other (single-threaded: with --threads they would interleave), so many run side by side.
	 */
	public void blocks(Collection<String> filters, String ext, String block, Path cache) throws IOException {
		List<String> files = expand(filters, ext);
		parallel(chunks(files, CORES * 2), 1, c -> blocksChunk(c, ext, block, cache));
	}

	/** Leaves out what the converter can't read (a newer CS2 than it knows): logged, the rest goes on. */
	private void skip(String what, IOException e) {
		System.err.println("[strafemc] setup skipped " + what + ": " + e.getMessage());
		skipped.add(what);
	}

	/** What the converter couldn't read this run (empty: everything converted). */
	public final List<String> skipped = Collections.synchronizedList(new ArrayList<>());

	private void blocksChunk(String chunk, String ext, String block, Path cache) throws IOException {
		Path out = Files.createTempFile(logDir, "vrfblocks", ".txt");
		boolean ok = false;
		try {
			List<String> args = new ArrayList<>(List.of("-i", vpk.toString(), "-f", chunk, "-b", block));
			if (ext != null) args.addAll(List.of("-e", ext));
			run(args, out, 1);
			String text = Files.readString(out, StandardCharsets.UTF_8);
			Matcher m = HEADER.matcher(text);
			List<int[]> spans = new ArrayList<>();
			List<String> names = new ArrayList<>();
			while (m.find()) {
				spans.add(new int[] {m.start(), m.end()});
				names.add(m.group(1));
			}
			for (int i = 0; i < names.size(); i++) {
				int start = spans.get(i)[0], end = i + 1 < spans.size() ? spans.get(i + 1)[0] : text.length();
				Path dst = cache.resolve(names.get(i) + "." + block + ".kv3");
				Files.createDirectories(dst.getParent());
				Files.writeString(dst, text.substring(start, end), StandardCharsets.UTF_8);
			}
			ok = true;
		} finally {
			if (ok) Files.deleteIfExists(out); // a failed run's output stays: the error message points at it
		}
	}

	/** Decompiles matching files (sounds -> wav/mp3, textures -> png, scripts -> text) into outDir under their pak paths. */
	public void decompile(Collection<String> filters, String ext, Path outDir) throws IOException {
		List<String> files = expand(filters, ext);
		parallel(chunks(files, Math.max(1, CORES / THREADS_EACH) * 3), THREADS_EACH, c -> {
			List<String> args = new ArrayList<>(List.of("-i", vpk.toString(), "-f", c, "-o", outDir.toString(), "-d", "--threads",
					String.valueOf(THREADS_EACH)));
			if (ext != null) args.addAll(List.of("-e", ext));
			run(args, null, THREADS_EACH);
		});
	}

	/** Exports matching models / animation clips to .glb files in outDir (with materials: their textures next to them). */
	public void glb(Collection<String> filters, String ext, Path outDir, boolean materials) throws IOException {
		Files.createDirectories(outDir); // the converter treats a missing output folder as a file name
		List<String> files = expand(filters, ext);
		// a model's textures are written next to it: a folder's models stay in one process, so none write the same file
		parallel(byFolder(chunks(files, Math.max(1, CORES / THREADS_EACH) * 3)), THREADS_EACH, c -> {
			List<String> args = new ArrayList<>(List.of("-i", vpk.toString(), "-f", c, "-o", outDir.toString(), "-d",
					"--gltf_export_format", "glb", "--gltf_export_animations", "--threads", String.valueOf(THREADS_EACH)));
			if (materials) args.add("--gltf_export_materials");
			if (ext != null) args.addAll(List.of("-e", ext));
			run(args, null, THREADS_EACH);
		});
	}

	private interface Run {
		void run(String chunk) throws IOException;
	}

	/** Runs the batches side by side (each as resilient()); returns once all are done. */
	private void parallel(List<String> chunks, int threadsEach, Run r) throws IOException {
		if (chunks.isEmpty()) return;
		if (chunks.size() == 1) {
			resilient(chunks.get(0), r);
			return;
		}
		ExecutorService pool = Executors.newFixedThreadPool(Math.min(chunks.size(), Math.max(1, CORES / threadsEach)));
		try {
			List<Future<?>> done = new ArrayList<>();
			for (String c : chunks) done.add(pool.submit(() -> resilient(c, r)));
			for (Future<?> f : done) f.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted", e);
		} catch (java.util.concurrent.ExecutionException e) {
			throw new IOException(e.getCause());
		} finally {
			pool.shutdownNow();
		}
	}

	/** A batch; if it fails, entry by entry, skipping the ones that fail. */
	private void resilient(String chunk, Run r) {
		try {
			r.run(chunk);
		} catch (IOException e) {
			if (!chunk.contains(",")) {
				skip(chunk, e);
				return;
			}
			for (String one : chunk.split(","))
				try {
					r.run(one);
				} catch (IOException e1) {
					skip(one, e1);
				}
		}
	}

	/**
	 * Folder filters ("a/b/") as the pak's files under them with that extension (exact paths and anything without a
	 * pak list stay as they are), so a big folder can be split into batches.
	 */
	private List<String> expand(Collection<String> filters, String ext) {
		Map<String, Long> p = pak;
		List<String> out = new ArrayList<>();
		for (String f : filters) {
			if (p == null || !f.endsWith("/")) {
				out.add(f);
				continue;
			}
			int before = out.size();
			for (String k : p.keySet()) if (k.startsWith(f) && (ext == null || k.endsWith("." + ext))) out.add(k);
			if (out.size() == before) out.add(f); // nothing listed: let the converter look
		}
		return out;
	}

	/**
	 * Comma-joined filter lists short enough for one command line, and at least `parts` of them when there are that
	 * many files (so they can run side by side).
	 */
	private static List<String> chunks(List<String> filters, int parts) {
		// at least 8 per batch: one file alone makes the converter write it beside -o instead of under it
		int perChunk = Math.max(8, (int) Math.ceil(filters.size() / (double) Math.max(1, parts)));
		List<String> out = new ArrayList<>();
		StringBuilder b = new StringBuilder();
		int n = 0;
		for (String f : filters) {
			if (b.length() > 0 && (b.length() + f.length() + 1 > MAX_FILTER_CHARS || n >= perChunk)) {
				out.add(b.toString());
				b.setLength(0);
				n = 0;
			}
			if (b.length() > 0) b.append(',');
			b.append(f);
			n++;
		}
		if (b.length() > 0) out.add(b.toString());
		if (out.size() > 1 && !out.get(out.size() - 1).contains(",")) // a lone file left over joins the batch before it
			out.set(out.size() - 2, out.get(out.size() - 2) + "," + out.remove(out.size() - 1));
		return out;
	}

	/** Batches regrouped so no folder is split between two of them (each folder's files move to its first batch). */
	private static List<String> byFolder(List<String> chunks) {
		Map<String, Integer> home = new java.util.HashMap<>();
		List<List<String>> out = new ArrayList<>();
		for (String c : chunks) {
			List<String> mine = new ArrayList<>();
			out.add(mine);
			for (String f : c.split(",")) {
				String dir = f.contains("/") ? f.substring(0, f.lastIndexOf('/')) : "";
				Integer h = home.putIfAbsent(dir, out.size() - 1);
				(h == null ? mine : out.get(h)).add(f);
			}
		}
		List<String> joined = new ArrayList<>();
		for (List<String> l : out) if (!l.isEmpty()) joined.add(String.join(",", l));
		return joined;
	}

	private static final java.util.concurrent.atomic.AtomicInteger RUNS = new java.util.concurrent.atomic.AtomicInteger();

	/** One converter process holding `threads` CPU permits; its log is kept only when it fails. */
	private void run(List<String> args, Path stdout, int threads) throws IOException {
		List<String> cmd = new ArrayList<>();
		cmd.add(exe.toString());
		cmd.addAll(args);
		Path log = stdout != null ? stdout : logDir.resolve("vrf-setup-" + RUNS.incrementAndGet() + ".log");
		ProcessBuilder pb = new ProcessBuilder(cmd).redirectOutput(log.toFile());
		if (stdout == null) pb.redirectErrorStream(true);
		else pb.redirectError(ProcessBuilder.Redirect.DISCARD); // captured output = the blocks only; errors would block a full pipe
		int permits = Math.min(CORES, Math.max(1, threads));
		try {
			CPU.acquire(permits);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted", e);
		}
		boolean ok = false;
		try {
			Process p = pb.start();
			try {
				if (!p.waitFor(30, TimeUnit.MINUTES)) {
					p.destroyForcibly();
					throw new IOException("the converter took longer than 30 minutes");
				}
			} catch (InterruptedException e) {
				p.destroyForcibly();
				Thread.currentThread().interrupt();
				throw new IOException("interrupted", e);
			}
			if (p.exitValue() != 0) throw new IOException("the converter failed (exit " + p.exitValue() + "), see " + log);
			ok = true;
		} finally {
			CPU.release(permits);
			if (ok && stdout == null) Files.deleteIfExists(log);
		}
	}
}
