package dev.strafemc.data;

import dev.strafemc.config.StrafeConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * What a dedicated server needs from CS2 (it has no CS2 install to convert from): weapons.vdata and the other scripts,
 * the surface properties, and the animation data (reload and draw timings, inspect graphs), packed from this PC's
 * converted cache into one zip whose contents go into the server's folder as strafemc/cache. About 20 MB.
 */
public final class ServerData {
	private ServerData() {}

	/** Writes <runtime>/strafemc-server-data.zip; returns it and the number of files. */
	public static Result export() throws IOException {
		Path cache = StrafeConfig.cacheDir(), out = StrafeConfig.RUNTIME_DIR.resolve("strafemc-server-data.zip");
		int n = 0;
		try (OutputStream os = Files.newOutputStream(out); ZipOutputStream zip = new ZipOutputStream(os)) {
			for (String dir : List.of("scripts", "surfaceproperties", "animation")) {
				Path d = cache.resolve(dir);
				if (!Files.isDirectory(d)) continue;
				List<Path> files;
				try (Stream<Path> s = Files.walk(d)) {
					files = s.filter(Files::isRegularFile).filter(f -> !dir.equals("animation") || f.getFileName().toString().endsWith(".kv3")).toList();
				}
				for (Path f : files) {
					zip.putNextEntry(new ZipEntry("strafemc/cache/" + cache.relativize(f).toString().replace('\\', '/')));
					Files.copy(f, zip);
					zip.closeEntry();
					n++;
				}
			}
		}
		return new Result(out, n);
	}

	public record Result(Path zip, int files) {}
}
