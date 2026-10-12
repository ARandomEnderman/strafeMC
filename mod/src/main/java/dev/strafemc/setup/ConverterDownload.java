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
	public static final String VERSION = "20.0";

	/** OS/arch -> release file, its size and SHA-256 (from GitHub's release data for 20.0). */
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
		if (os.contains("win")) return arm ? new Build("cli-windows-arm64.zip", 50169478, "df2a52372fee1ce8284abd2cd8a09f65e7259778290b5facfcfa07ef75f4ea1a")
				: new Build("cli-windows-x64.zip", 52735867, "d32ab327b8bbb42a2528866afb03bb582bdb779d0005488da32b90292afd3ff5");
		if (os.contains("mac")) return arm ? new Build("cli-macos-arm64.zip", 54097964, "fa3fc51ab8ed8c96899a64cf8977c2c4810342605868f37044e85a412ff4e0cd")
				: new Build("cli-macos-x64.zip", 57035976, "9b430e8233fa498c34c00cf5cf4da48d854c8f255b1208557861802d9e16a19c");
		if (arm) return new Build("cli-linux-arm64.zip", 48777281, "adcfeac823e25ebdaebe0b6c4b3132546afa3978d23fca37bb522da1fe5cb2e5");
		return new Build("cli-linux-x64.zip", 53059004, "3e8af47cd6ce52e8068904f2aa1dda23c56a6b96a8310b25090f0711cda76a8a");
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
		return exe;
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
