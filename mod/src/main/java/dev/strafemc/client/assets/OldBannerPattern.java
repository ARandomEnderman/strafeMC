package dev.strafemc.client.assets;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Minecraft's old banner pattern item picture (until 1.21.1; newer versions drew new ones), the base of the skin wrap
 * pictures. It isn't shipped: it's read (read-only) from an older Minecraft client jar already on the player's PC, in
 * the usual launcher folders, and only used if its pixels are exactly that picture. Null if there's none: the wraps
 * then use strafeMC's own drawing.
 */
public final class OldBannerPattern {
	/** java.util.Arrays.hashCode of the picture's ARGB pixels (fully transparent pixels as 0). */
	private static final int PIXELS_HASH = 832542567;
	private static final String[] ENTRIES = {"assets/minecraft/textures/item/flower_banner_pattern.png", "assets/minecraft/textures/item/banner_pattern.png"};

	private OldBannerPattern() {}

	public static BufferedImage find() {
		String appdata = System.getenv("APPDATA"), home = System.getProperty("user.home");
		List<Path> roots = new ArrayList<>();
		if (appdata != null) {
			roots.add(Path.of(appdata, ".minecraft", "versions"));
			roots.add(Path.of(appdata, "ModrinthApp", "meta", "libraries", "net", "minecraft", "client"));
			roots.add(Path.of(appdata, "PrismLauncher", "libraries", "com", "mojang", "minecraft"));
		}
		if (home != null) {
			roots.add(Path.of(home, "curseforge", "minecraft", "Install", "versions"));
			roots.add(Path.of(home, ".minecraft", "versions"));
			roots.add(Path.of(home, "Library", "Application Support", "minecraft", "versions"));
			roots.add(Path.of(home, "Library", "Application Support", "ModrinthApp", "meta", "libraries", "net", "minecraft", "client"));
			roots.add(Path.of(home, "Library", "Application Support", "PrismLauncher", "libraries", "com", "mojang", "minecraft"));
		}
		for (Path root : roots) {
			if (!Files.isDirectory(root)) continue;
			List<Path> jars;
			try (Stream<Path> s = Files.walk(root, 3)) {
				jars = s.filter(p -> p.toString().endsWith(".jar")).toList();
			} catch (Exception e) {
				continue;
			}
			for (Path jar : jars) {
				BufferedImage img = read(jar);
				if (img != null) return img;
			}
		}
		return null;
	}

	private static BufferedImage read(Path jar) {
		try (ZipFile z = new ZipFile(jar.toFile())) {
			for (String name : ENTRIES) {
				var e = z.getEntry(name);
				if (e == null) continue;
				BufferedImage img;
				try (var in = z.getInputStream(e)) {
					img = ImageIO.read(in);
				}
				if (img != null && hash(img) == PIXELS_HASH) return img;
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	private static int hash(BufferedImage img) {
		int[] px = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
		for (int i = 0; i < px.length; i++) if ((px[i] >>> 24) == 0) px[i] = 0;
		return java.util.Arrays.hashCode(px);
	}
}
