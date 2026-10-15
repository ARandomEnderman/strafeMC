package dev.strafemc.setup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the ValveResourceFormat converter (Source2Viewer-CLI) over the player's own CS2 pak in batches: one process per
 * group of files instead of one per file. No Minecraft classes, so the standalone setup tool can use it too.
 */
public final class Vrf {
	/** Windows command lines are limited to 32767 characters; file lists are split well below that. */
	private static final int MAX_FILTER_CHARS = 8000;
	private static final Pattern HEADER = Pattern.compile("^\\[\\d+/\\d+\\] (\\S+)\\r?$", Pattern.MULTILINE);

	private final Path exe, vpk, logDir;

	public Vrf(Path exe, Path vpk, Path logDir) {
		this.exe = exe;
		this.vpk = vpk;
		this.logDir = logDir;
	}

	/** Every file in the pak (path -> size), optionally filtered by path prefixes ("a/,b/"). */
	public Map<String, Long> list(String filter) throws IOException {
		Path out = Files.createTempFile(logDir, "vpklist", ".txt");
		try {
			List<String> args = new ArrayList<>(List.of("-i", vpk.toString(), "-l"));
			if (filter != null) args.addAll(List.of("-f", filter));
			run(args, out);
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
	 * files the mod reads. filters: exact paths or folder prefixes; ext: extension filter or null.
	 */
	public void blocks(Collection<String> filters, String ext, String block, Path cache) throws IOException {
		for (String chunk : chunks(filters)) {
			try {
				blocksChunk(chunk, ext, block, cache);
			} catch (IOException e) {
				if (!chunk.contains(",")) {
					skip(chunk, e);
					continue;
				}
				for (String one : chunk.split(",")) { // one file the converter can't read shouldn't stop the rest
					try {
						blocksChunk(one, ext, block, cache);
					} catch (IOException e1) {
						skip(one, e1);
					}
				}
			}
		}
	}

	/** Leaves out what the converter can't read (a newer CS2 than it knows): logged, the rest goes on. */
	private void skip(String what, IOException e) {
		System.err.println("[strafemc] setup skipped " + what + ": " + e.getMessage());
		skipped.add(what);
	}

	/** What the converter couldn't read this run (empty: everything converted). */
	public final List<String> skipped = new ArrayList<>();

	private void blocksChunk(String chunk, String ext, String block, Path cache) throws IOException {
		{
			Path out = Files.createTempFile(logDir, "vrfblocks", ".txt");
			boolean ok = false;
			try {
				List<String> args = new ArrayList<>(List.of("-i", vpk.toString(), "-f", chunk, "-b", block));
				if (ext != null) args.addAll(List.of("-e", ext));
				run(args, out); // single-threaded on purpose: with --threads the printed blocks interleave
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
	}

	/** Decompiles matching files (sounds -> wav/mp3, textures -> png, scripts -> text) into outDir under their pak paths. */
	public void decompile(Collection<String> filters, String ext, Path outDir) throws IOException {
		for (String chunk : chunks(filters)) resilient(chunk, c -> {
			List<String> args = new ArrayList<>(List.of("-i", vpk.toString(), "-f", c, "-o", outDir.toString(), "-d", "--threads", "4"));
			if (ext != null) args.addAll(List.of("-e", ext));
			run(args, null);
		});
	}

	private interface Run {
		void run(String chunk) throws IOException;
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

	/** Exports matching models / animation clips to .glb files in outDir (with materials: their textures next to them). */
	public void glb(Collection<String> filters, String ext, Path outDir, boolean materials) throws IOException {
		Files.createDirectories(outDir); // the converter treats a missing output folder as a file name
		for (String chunk : chunks(filters)) resilient(chunk, c -> {
			List<String> args = new ArrayList<>(List.of("-i", vpk.toString(), "-f", c, "-o", outDir.toString(), "-d",
					"--gltf_export_format", "glb", "--gltf_export_animations", "--threads", "4"));
			if (materials) args.add("--gltf_export_materials");
			if (ext != null) args.addAll(List.of("-e", ext));
			run(args, null);
		});
	}

	/** Comma-joined filter lists short enough for one command line. */
	private static List<String> chunks(Collection<String> filters) {
		List<String> out = new ArrayList<>();
		StringBuilder b = new StringBuilder();
		for (String f : filters) {
			if (b.length() > 0 && b.length() + f.length() + 1 > MAX_FILTER_CHARS) {
				out.add(b.toString());
				b.setLength(0);
			}
			if (b.length() > 0) b.append(',');
			b.append(f);
		}
		if (b.length() > 0) out.add(b.toString());
		return out;
	}

	private void run(List<String> args, Path stdout) throws IOException {
		List<String> cmd = new ArrayList<>();
		cmd.add(exe.toString());
		cmd.addAll(args);
		Path log = stdout != null ? stdout : logDir.resolve("vrf-setup.log");
		ProcessBuilder pb = new ProcessBuilder(cmd).redirectOutput(log.toFile());
		if (stdout == null) pb.redirectErrorStream(true);
		else pb.redirectError(ProcessBuilder.Redirect.DISCARD); // captured output = the blocks only; errors would block a full pipe
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
	}
}
