package dev.strafemc.setup;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Gets the open-source ValveResourceFormat converter (Source2Viewer-CLI): the official release zip from GitHub, a
 * pinned version, checked against the SHA-256 GitHub publishes for it before anything is unpacked or run.
 */
public final class ConverterDownload {
	public static final String VERSION = "20.1"; // 20.1: reads CS2 files since the 2026-10-15 update (KeyValues3 types 26 / 27)

	/** OS/arch -> release file, its size and SHA-256 (from GitHub's release data for 20.1). */
	public record Build(String file, long size, String sha256) {
		public String url() {
			return "https://github.com/ValveResourceFormat/ValveResourceFormat/releases/download/" + VERSION + "/" + file;
		}
	}

	private ConverterDownload() {}

	/** The build for this computer, or null if there is none. */
	public static Build forThisComputer() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT), arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		boolean arm = arch.contains("aarch64") || arch.contains("arm64");
		if (os.contains("win")) return arm ? new Build("cli-windows-arm64.zip", 50197095, "a5348023b3d881847c228ea5aad6f2d8eb65abb8cdeed7fa138ea6426ff9ee93")
				: new Build("cli-windows-x64.zip", 52773073, "67aaf8a67a85ca60fd09ff343e62103a4cdf3a2d177fc656231d7d734c5b3aa3");
		if (os.contains("mac")) return arm ? new Build("cli-macos-arm64.zip", 54131516, "3e3196a678a8f010c345b49bac67c63bc7af0ef02e4543bb7a80b7508c1eefc4")
				: new Build("cli-macos-x64.zip", 57074008, "8dec0b851998f299d0acd5730ad62641c8d5d38155f409ed176864889fa3ac72");
		if (arm) return new Build("cli-linux-arm64.zip", 48808492, "6fae01594f0c54c42a8aad2f3c2c8771b40a18195a907c5c686bcb5a94a67023");
		return new Build("cli-linux-x64.zip", 53094205, "29d25a6d6b62377fc5386c3e5145b0a2180a401a50041afe9494b3f503deaef5");
	}

	/**
	 * Whether a converter strafeMC downloaded itself is older than VERSION (asks it: --version), so the setup gets the
	 * current one; a converter the player set up is never replaced.
	 */
	public static boolean outdated(Path exe, Path toolsDir) {
		if (!exe.toAbsolutePath().startsWith(toolsDir.toAbsolutePath())) return false;
		try {
			Process p = new ProcessBuilder(exe.toString(), "--version").redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("Version:\\s*(\\d+)\\.(\\d+)").matcher(out);
			if (!m.find()) return true;
			String[] want = VERSION.split("\\.");
			int have = Integer.parseInt(m.group(1)) * 1000 + Integer.parseInt(m.group(2)), need = Integer.parseInt(want[0]) * 1000 + Integer.parseInt(want[1]);
			return have < need;
		} catch (Exception e) {
			return true;
		}
	}

	/** The converter's program file inside an unpacked build. */
	public static Path executable(Path dir) {
		Path win = dir.resolve("Source2Viewer-CLI.exe");
		return Files.exists(win) ? win : dir.resolve("Source2Viewer-CLI");
	}

	/** Downloads, verifies and unpacks the converter into dir; returns its program file. progress: (fraction, text). */
	public static Path install(Path dir, BiConsumer<Double, String> progress) throws IOException {
		Build b = forThisComputer();
		Files.createDirectories(dir);
		Path zip = dir.resolve(b.file() + ".part");
		try {
			HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
			HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(b.url())).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
			if (r.statusCode() != 200) throw new IOException("download failed: HTTP " + r.statusCode());
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			try (InputStream in = r.body(); var out = Files.newOutputStream(zip)) {
				byte[] buf = new byte[1 << 16];
				long done = 0;
				for (int n; (n = in.read(buf)) > 0; ) {
					out.write(buf, 0, n);
					sha.update(buf, 0, n);
					done += n;
					if (progress != null) progress.accept((double) done / b.size(), "Downloading the converter");
				}
			}
			String got = HexFormat.of().formatHex(sha.digest());
			if (!got.equals(b.sha256())) throw new IOException("the downloaded converter doesn't match its published checksum; nothing was installed");
			unzip(zip, dir);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted", e);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException(e);
		} finally {
			Files.deleteIfExists(zip);
		}
		Path exe = executable(dir);
		if (!exe.toString().endsWith(".exe")) exe.toFile().setExecutable(true);
		if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) macSign(dir, exe);
		return exe;
	}

	/**
	 * Apple Silicon Macs only run signed code: if the converter or its libraries aren't signed, they get the local
	 * ad-hoc signature (codesign -s -, the same a build on that Mac gets). Files we download are never quarantined
	 * (only browsers mark downloads), so Gatekeeper doesn't stop them.
	 */
	private static void macSign(Path dir, Path exe) {
		java.util.List<Path> files = new java.util.ArrayList<>(java.util.List.of(exe));
		try (var s = Files.walk(dir)) {
			s.filter(p -> p.toString().endsWith(".dylib")).forEach(files::add);
		} catch (IOException ignored) {
		}
		for (Path f : files) {
			try {
				if (run("codesign", "--verify", f.toString()) != 0) run("codesign", "--force", "--sign", "-", f.toString());
			} catch (Exception e) { // no codesign: an Intel Mac runs unsigned code anyway
				return;
			}
		}
	}

	private static int run(String... cmd) throws Exception {
		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
		return p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) ? p.exitValue() : -1;
	}

	private static void unzip(Path zip, Path dir) throws IOException {
		try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
			for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
				Path out = dir.resolve(e.getName()).normalize();
				if (!out.startsWith(dir.normalize())) throw new IOException("unexpected path in the converter zip: " + e.getName());
				if (e.isDirectory()) Files.createDirectories(out);
				else {
					Files.createDirectories(out.getParent());
					Files.copy(z, out, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}
}
