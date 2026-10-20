package dev.strafemc.client.impact;

import com.mojang.blaze3d.platform.NativeImage;
import dev.strafemc.StrafeMc;
import dev.strafemc.data.AssetCache;
import dev.strafemc.data.Kv3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CS2 bullet-hole decals from the copy: scripts/decalgroups.vdata (weighted materials per group) and each decal material
 * (csgo_projected_decals: g_tColor, g_tAmbientOcclusion, DecalWorldWidth/Height + DecalSizeVariance in Hammer units).
 * The projected-decal shader's parallax/normal shading isn't available in Minecraft; the colour is shaded with the
 * decal's own ambient occlusion map, which carries the crater.
 */
public final class DecalLibrary {
	public record Option(String material, double probability) {}

	public record Material(Identifier texture, double width, double height, double sizeVariance) {}

	private static volatile Map<String, List<Option>> groups = Map.of();
	private static final Map<String, Material> MATERIALS = new ConcurrentHashMap<>();
	private static final Map<String, Boolean> LOADING = new ConcurrentHashMap<>();
	private static final Map<String, NativeImage> PENDING = new ConcurrentHashMap<>();

	private DecalLibrary() {}

	@SuppressWarnings("unchecked")
	public static void load() throws Exception {
		Map<String, Object> root = Kv3.parseObject(Files.readString(AssetCache.decompiled("scripts/decalgroups.vdata_c")));
		Map<String, List<Option>> out = new ConcurrentHashMap<>();
		for (var e : root.entrySet()) {
			if (!(e.getValue() instanceof Map<?, ?> g) || !(g.get("m_vecOptions") instanceof List<?> opts)) continue;
			List<Option> l = new ArrayList<>();
			for (Object o : opts)
				if (o instanceof Map<?, ?> m && m.get("m_hMaterial") != null)
					l.add(new Option(m.get("m_hMaterial").toString(), m.get("m_flProbability") instanceof Number n ? n.doubleValue() : 1));
			if (!l.isEmpty()) out.put(e.getKey(), l);
		}
		groups = out;
		StrafeMc.LOG.info("[strafemc] CS2 decal groups: {}", out.size());
	}

	/** Weighted pick of a material in the group (m_flProbability), or null. */
	public static String pick(String group, Random rng) {
		List<Option> l = group == null ? null : groups.get(group);
		if (l == null || l.isEmpty()) return null;
		double total = 0;
		for (Option o : l) total += o.probability();
		double r = rng.nextDouble() * total;
		for (Option o : l) if ((r -= o.probability()) <= 0) return o.material();
		return l.get(l.size() - 1).material();
	}

	/** Loads every material of a decal group in the background (startup), registering on first render use. */
	public static void prefetchGroup(String group) {
		List<Option> l = group == null ? null : groups.get(group);
		if (l == null) return;
		for (Option o : l) if (!MATERIALS.containsKey(o.material()) && LOADING.putIfAbsent(o.material(), true) == null) prepare(o.material());
	}

	/** Material name of the drawn stand-in hole for players without CS2 files. */
	public static final String PLACEHOLDER = "#placeholder";

	/** The material if ready (render thread); starts loading it in the background otherwise. */
	public static Material material(String vmat) {
		Material m = MATERIALS.get(vmat);
		if (m != null) return m;
		if (PLACEHOLDER.equals(vmat)) { // a dark round hole with a soft rim, drawn here (nothing from CS2)
			java.awt.image.BufferedImage b = new java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
			for (int y = 0; y < 32; y++)
				for (int x = 0; x < 32; x++) {
					double r = Math.hypot(x - 15.5, y - 15.5) / 15.5;
					int a = r < 0.45 ? 235 : r < 1 ? (int) (235 * (1 - r) / 0.55 * 0.6) : 0;
					int c = r < 0.45 ? 0x141414 : 0x3C3A36;
					b.setRGB(x, y, a << 24 | c);
				}
			try {
				java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
				javax.imageio.ImageIO.write(b, "png", bytes);
				SIZES.put(PLACEHOLDER, new double[] {4, 4, 0.6});
				return register(PLACEHOLDER, NativeImage.read(new java.io.ByteArrayInputStream(bytes.toByteArray())));
			} catch (java.io.IOException e) {
				return null;
			}
		}
		NativeImage img = PENDING.remove(vmat);
		if (img != null) return register(vmat, img);
		if (LOADING.putIfAbsent(vmat, true) == null) {
			dev.strafemc.client.Loaders.run(() -> prepare(vmat));
		}
		return null;
	}

	private static final Map<String, double[]> SIZES = new ConcurrentHashMap<>();

	private static Material register(String vmat, NativeImage img) {
		Identifier id = Identifier.fromNamespaceAndPath("strafemc", "decal/" + Integer.toHexString(vmat.hashCode()));
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "strafemc decal " + vmat, img));
		double[] s = SIZES.getOrDefault(vmat, new double[] {6, 6, 0});
		Material m = new Material(id, s[0], s[1], s[2]);
		MATERIALS.put(vmat, m);
		return m;
	}

	@SuppressWarnings("unchecked")
	private static void prepare(String vmat) {
		try {
			String c = vmat.endsWith("_c") ? vmat : vmat + "_c";
			String text = Files.readString(AssetCache.dataBlock(c));
			int hdr = text.indexOf("-->");
			Map<String, Object> d = Kv3.parseObject(text.substring(text.indexOf('{', hdr < 0 ? 0 : hdr + 3)));
			String color = null, ao = null;
			double w = 6, h = 6, var = 0;
			if (d.get("m_textureParams") instanceof List<?> tp)
				for (Object o : tp)
					if (o instanceof Map<?, ?> m) {
						String n = String.valueOf(m.get("m_name")), v = String.valueOf(m.get("m_pValue"));
						if (n.equals("g_tColor")) color = v;
						else if (n.equals("g_tAmbientOcclusion")) ao = v;
					}
			if (d.get("m_floatAttributes") instanceof List<?> fa)
				for (Object o : fa)
					if (o instanceof Map<?, ?> m && m.get("m_flValue") instanceof Number v) {
						switch (String.valueOf(m.get("m_name"))) {
							case "DecalWorldWidth" -> w = v.doubleValue();
							case "DecalWorldHeight" -> h = v.doubleValue();
							case "DecalSizeVariance" -> var = v.doubleValue();
							default -> {
							}
						}
					}
			if (color == null) throw new IllegalStateException("no g_tColor");
			BufferedImage col = ImageIO.read(png(color).toFile());
			BufferedImage occ = ao != null ? ImageIO.read(png(ao).toFile()) : null;
			int W = col.getWidth(), H = col.getHeight();
			BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
			for (int y = 0; y < H; y++)
				for (int x = 0; x < W; x++) {
					int p = col.getRGB(x, y);
					double a = occ == null ? 1 : (occ.getRGB(x * occ.getWidth() / W, y * occ.getHeight() / H) >> 16 & 255) / 255.0;
					int r = (int) ((p >> 16 & 255) * a), g = (int) ((p >> 8 & 255) * a), b = (int) ((p & 255) * a);
					out.setRGB(x, y, (p & 0xff000000) | r << 16 | g << 8 | b);
				}
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			ImageIO.write(out, "png", bytes);
			SIZES.put(vmat, new double[] {w, h, var});
			PENDING.put(vmat, NativeImage.read(new ByteArrayInputStream(bytes.toByteArray())));
		} catch (Exception e) {
			StrafeMc.LOG.warn("[strafemc] decal {} failed: {}", vmat, e.toString());
		}
	}

	/** A vtex resource ("....vtex") decompiled to PNG in the cache. */
	private static Path png(String vtex) throws Exception {
		String c = vtex.endsWith("_c") ? vtex : vtex + "_c";
		Path expected = dev.strafemc.config.StrafeConfig.cacheDir().resolve(vtex.replaceAll("\\.vtex$", ".png"));
		AssetCache.ensureDecompiled(c, expected);
		return expected;
	}
}
