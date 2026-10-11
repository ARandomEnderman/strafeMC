package dev.strafemc.client.particle;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

/** Rebuilds a sprite-sheet atlas from VRF's per-frame PNGs and writes it out for inspection. */
public final class AtlasCheck {
	public static void main(String[] a) throws Exception {
		String c = a[0], base = c.substring(0, c.length() - "vtex_c".length());
		List<ParticleTextures.Sequence> sheet = ParticleTextures.sheet(c);
		System.out.println(sheet.size() + " sequences, frames " + sheet.stream().map(s -> s.frames().size()).toList());
		Method m = ParticleTextures.class.getDeclaredMethod("atlas", String.class, String.class, List.class);
		m.setAccessible(true);
		BufferedImage img = (BufferedImage) m.invoke(null, c, base, sheet);
		ImageIO.write(img, "png", Path.of(a[1]).toFile());
		System.out.println(img.getWidth() + "x" + img.getHeight());
	}
}
