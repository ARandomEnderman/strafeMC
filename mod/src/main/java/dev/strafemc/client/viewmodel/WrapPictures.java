package dev.strafemc.client.viewmodel;

import com.mojang.blaze3d.platform.NativeImage;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.data.AssetCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import javax.imageio.ImageIO;
import org.joml.Matrix4f;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A picture of each wrap as it really is: its gun (or knife) in the wrap's finish at the wrap's own float and pattern,
 * with its stickers, posed as in its idle animation and seen from its left (the side stickers go on), like CS2's
 * inventory pictures (4:3, transparent). Drawn once on the CPU from the weapon's mesh and baked finish, kept in the cache and loaded as a GUI texture; made one at a
 * time in the background, so menus show CS2's stock picture until it's ready.
 */
public final class WrapPictures {
	public static final int W = 256, H = 192;
	private static final int SS = 2; // supersampling
	private static final Map<String, Identifier> READY = new ConcurrentHashMap<>();
	private static final Set<String> BUSY = ConcurrentHashMap.newKeySet();
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "strafemc-wrap-pictures");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});

	private WrapPictures() {}

	/** The picture of a wrap item (its own gun), or null while it's made (or when it can't be). */
	public static Identifier get(ItemStack wrap) {
		if (!(wrap.getItem() instanceof dev.strafemc.weapon.SkinWrapItem)) return null;
		var w = dev.strafemc.weapon.SkinWraps.get(dev.strafemc.weapon.SkinWrapItem.skin(wrap));
		if (w == null || w.kits().isEmpty()) return null;
		var e = w.kits().entrySet().iterator().next();
		return get(e.getKey(), e.getValue(), wrap);
	}

	/** The picture of a wrap on a given weapon (kit: the wrap's finish for it), or null while it's made. */
	public static Identifier get(String cls, String kit, ItemStack wrap) {
		if (cls == null || kit == null) return null;
		int finish = dev.strafemc.weapon.SkinWrapItem.finish(wrap);
		String[] stickers = dev.strafemc.weapon.StickerSlots.get(wrap);
		boolean any = false;
		for (String s : stickers) any |= s != null && !s.isEmpty();
		String key = cls + "|" + kit + "|" + finish + (any ? "|" + String.join(",", stickers) : "");
		Identifier id = READY.get(key);
		if (id != null || !BUSY.add(key)) return id;
		String[] st = any ? stickers : null;
		WORKER.execute(() -> {
			try {
				NativeImage img = picture(key, cls, kit, finish, st);
				if (img == null) return; // stays busy: no picture for it
				Identifier tex = Identifier.fromNamespaceAndPath("strafemc", "wrap_picture/" + Integer.toHexString(key.hashCode()));
				Minecraft.getInstance().execute(() -> {
					Minecraft.getInstance().getTextureManager().register(tex, new DynamicTexture(() -> "strafemc wrap picture " + key, img));
					READY.put(key, tex);
				});
			} catch (Throwable t) {
				dev.strafemc.StrafeMc.LOG.warn("[strafemc] wrap picture {}: {}", key, t.toString());
			}
		});
		return null;
	}

	private static Path file(String key) {
		return StrafeConfig.cacheDir().resolve("wrap_pictures/v2/" + Integer.toHexString(key.hashCode()) + "_" + Integer.toHexString(key.length()) + ".png");
	}

	private static NativeImage picture(String key, String cls, String kit, int finish, String[] stickers) throws Exception {
		Path out = file(key);
		BufferedImage img = Files.exists(out) ? ImageIO.read(out.toFile()) : null;
		if (img == null) {
			img = render(cls, kit, finish, stickers);
			if (img == null) return null;
			Files.createDirectories(out.getParent());
			Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
			ImageIO.write(img, "png", tmp.toFile());
			Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		NativeImage ni = new NativeImage(img.getWidth(), img.getHeight(), false);
		for (int y = 0; y < img.getHeight(); y++)
			for (int x = 0; x < img.getWidth(); x++) ni.setPixel(x, y, img.getRGB(x, y));
		return ni;
	}

	/** The weapon in its finish, side-on (the side the second UV set, CS2's sticker space, shows), shaded, fitted. */
	static BufferedImage render(String cls, String kit, int finish, String[] stickers) throws Exception {
		String[] w = Viewmodel.weaponModel(cls);
		if (w == null) return null;
		Glb glb = Glb.read(AssetCache.exportGlb(w[0], true));
		boolean legacy = WeaponSkinCache.legacy(kit) && VmModel.hasMesh(glb, ".body_legacy");
		VmModel m = VmModel.load(glb, legacy ? ".body_legacy" : w[1]);
		var paint = WeaponSkinCache.paintable(w[0], m.parts);
		java.util.List<VmModel.Part> paintable = new java.util.ArrayList<>();
		Path base = null;
		for (VmModel.Part p : m.parts)
			if (paint != null && p.texture != null && p.texture.getFileName().toString().startsWith(paint.color())) {
				paintable.add(p);
				base = p.texture;
			}
		BufferedImage finishImg = null;
		if (base != null) {
			Viewmodel.downscaled(base);
			Path tex = stickers != null ? WeaponSkinCache.ensureStickers(cls, paint.material(), kit, finish, paintable, base, stickers)
					: WeaponSkinCache.ensure(cls, paint.material(), kit, finish, paintable, base);
			if (tex != null) {
				Path px = tex.resolveSibling(tex.getFileName().toString().replace(".png", ".vm1024b.png"));
				if (Files.exists(px)) finishImg = ImageIO.read(px.toFile());
			}
		}
		// posed as in its idle animation's first frame (the bind pose leaves parts like magazines out of place), seen from
		// the gun's left: muzzle to the left, as CS2's pictures, the side stickers go on
		Matrix4f view = new Matrix4f().rotateY((float) (Math.PI / 2)).mul(Viewmodel.toView());
		Matrix4f[][] skin = new Matrix4f[0][];
		Matrix4f rigid = null;
		var entry = dev.strafemc.weapon.WeaponTable.get(cls);
		boolean knife = entry != null && entry.knife(); // knives are held turned in their idle: at rest they lie flat
		if (knife) view = new Matrix4f().rotateZ((float) (Math.PI / 2)).mul(view); // upright at rest: lie it down, blade to the left
		if (!knife) try {
			VmClip c = new VmClip("idle", Glb.read(AssetCache.exportGlb(dev.strafemc.weapon.GunServer.clipPath(cls, "idle"), false)));
			Matrix4f[] pose = c.pose(0);
			Matrix4f wpn = Viewmodel.mount(c, pose);
			skin = Viewmodel.skins(m, c, pose, wpn);
			Integer weaponNode = c.node("weapon");
			rigid = weaponNode != null ? new Matrix4f(wpn).mul(pose[weaponNode]) : null;
		} catch (Exception e) {
			dev.strafemc.StrafeMc.LOG.warn("[strafemc] wrap picture of {} unposed: {}", cls, e.toString());
		}
		java.util.Map<VmModel.Part, float[][]> posed = new java.util.HashMap<>();
		float[] min = {Float.MAX_VALUE, Float.MAX_VALUE}, max = {-Float.MAX_VALUE, -Float.MAX_VALUE};
		for (VmModel.Part p : m.parts) {
			if (p.alphaMode.equals("BLEND") || p.texture == null) continue;
			Matrix4f[] s = p.skin >= 0 && p.skin < skin.length ? skin[p.skin] : rigid != null ? new Matrix4f[] {rigid} : new Matrix4f[0];
			float[][] sk = Viewmodel.skinVertices(p, s, view);
			posed.put(p, sk);
			for (int i = 0; i + 2 < sk[0].length; i += 3) {
				min[0] = Math.min(min[0], sk[0][i]);
				max[0] = Math.max(max[0], sk[0][i]);
				min[1] = Math.min(min[1], sk[0][i + 1]);
				max[1] = Math.max(max[1], sk[0][i + 1]);
			}
		}
		if (posed.isEmpty()) return null;
		int rw = W * SS, rh = H * SS;
		double scale = Math.min(rw * 0.92 / Math.max(1e-6, max[0] - min[0]), rh * 0.86 / Math.max(1e-6, max[1] - min[1]));
		double cx = (min[0] + max[0]) / 2, cy = (min[1] + max[1]) / 2;
		// light (view space): from above, in front, a little from the left
		double[] light = {-0.3, 0.7, 0.65};
		double ll = Math.sqrt(light[0] * light[0] + light[1] * light[1] + light[2] * light[2]);
		for (int k = 0; k < 3; k++) light[k] /= ll;
		int[] col = new int[rw * rh];
		float[] depth = new float[rw * rh];
		java.util.Arrays.fill(depth, -Float.MAX_VALUE);
		for (VmModel.Part p : m.parts) {
			float[][] sk = posed.get(p);
			if (sk == null) continue;
			boolean mask = p.alphaMode.equals("MASK"), painted = paintable.contains(p) && finishImg != null;
			BufferedImage tex;
			try {
				if (!painted) Viewmodel.downscaled(p.texture);
				tex = painted ? finishImg : ImageIO.read(Viewmodel.downscaledPath(p.texture).toFile());
			} catch (Exception e) {
				tex = null;
			}
			if (tex == null) continue;
			float[] uv = p.uv, pp = sk[0], nn = sk[1];
			int n = pp.length / 3;
			double[] sx = new double[n], sy = new double[n], sd = new double[n];
			for (int i = 0; i < n; i++) {
				sx[i] = rw / 2.0 + (pp[i * 3] - cx) * scale;
				sy[i] = rh / 2.0 - (pp[i * 3 + 1] - cy) * scale;
				sd[i] = pp[i * 3 + 2]; // nearer the viewer: larger
			}
			for (int t = 0; t + 2 < p.idx.length; t += 3) {
				int a = p.idx[t], b = p.idx[t + 1], c = p.idx[t + 2];
				double area = (sx[b] - sx[a]) * (sy[c] - sy[a]) - (sx[c] - sx[a]) * (sy[b] - sy[a]);
				if (Math.abs(area) < 1e-9) continue;
				int x0 = Math.max(0, (int) Math.floor(Math.min(sx[a], Math.min(sx[b], sx[c])))), x1 = Math.min(rw - 1, (int) Math.ceil(Math.max(sx[a], Math.max(sx[b], sx[c]))));
				int y0 = Math.max(0, (int) Math.floor(Math.min(sy[a], Math.min(sy[b], sy[c])))), y1 = Math.min(rh - 1, (int) Math.ceil(Math.max(sy[a], Math.max(sy[b], sy[c]))));
				for (int y = y0; y <= y1; y++)
					for (int x = x0; x <= x1; x++) {
						double px = x + 0.5, py = y + 0.5;
						double wa = ((sx[b] - px) * (sy[c] - py) - (sx[c] - px) * (sy[b] - py)) / area, wb = ((sx[c] - px) * (sy[a] - py) - (sx[a] - px) * (sy[c] - py)) / area,
								wc = 1 - wa - wb;
						if (wa < -1e-4 || wb < -1e-4 || wc < -1e-4) continue;
						float d = (float) (wa * sd[a] + wb * sd[b] + wc * sd[c]);
						int i = y * rw + x;
						if (d <= depth[i]) continue;
						double tu = wa * uv[a * 2] + wb * uv[b * 2] + wc * uv[c * 2], tv = wa * uv[a * 2 + 1] + wb * uv[b * 2 + 1] + wc * uv[c * 2 + 1];
						int tc = GloveSkinBaker.sampleBilinear(tex, tu - Math.floor(tu), tv - Math.floor(tv));
						if (mask && (tc >>> 24) < 128) continue;
						double nnx = wa * nn[a * 3] + wb * nn[b * 3] + wc * nn[c * 3], nny = wa * nn[a * 3 + 1] + wb * nn[b * 3 + 1] + wc * nn[c * 3 + 1],
								nnz = wa * nn[a * 3 + 2] + wb * nn[b * 3 + 2] + wc * nn[c * 3 + 2];
						double nl = Math.sqrt(nnx * nnx + nny * nny + nnz * nnz);
						double lam = nl < 1e-6 ? 0.5 : Math.abs(nnx * light[0] + nny * light[1] + nnz * light[2]) / nl;
						double sh = 0.42 + 0.68 * lam; // FLAGGED ours: soft studio light
						depth[i] = d;
						col[i] = 0xFF000000 | ch(tc >> 16, sh) << 16 | ch(tc >> 8, sh) << 8 | ch(tc, sh);
					}
			}
		}
		// down to the picture, averaging the supersamples (colour weighted by coverage)
		BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
		boolean any = false;
		for (int y = 0; y < H; y++)
			for (int x = 0; x < W; x++) {
				int r = 0, g = 0, b = 0, a = 0;
				for (int j = 0; j < SS; j++)
					for (int k = 0; k < SS; k++) {
						int c = col[(y * SS + j) * rw + x * SS + k];
						if ((c >>> 24) == 0) continue;
						a++;
						r += c >> 16 & 255;
						g += c >> 8 & 255;
						b += c & 255;
					}
				if (a == 0) continue;
				any = true;
				out.setRGB(x, y, (a * 255 / (SS * SS)) << 24 | (r / a) << 16 | (g / a) << 8 | b / a);
			}
		return any ? out : null;
	}

	private static int ch(int c, double s) {
		return (int) Math.max(0, Math.min(255, Math.round((c & 255) * s)));
	}
}
