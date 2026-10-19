package dev.strafemc.client.viewmodel;

import com.mojang.blaze3d.platform.NativeImage;
import dev.strafemc.data.AssetCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A gun seen from the side the way CS2 places stickers and charms: its mesh drawn in its second UV set (TEXCOORD_1, CS2's
 * sticker space: x 0-1 along the gun), coloured from its finish. For each pixel it keeps the model-space point there, so
 * a charm placed on the picture hangs from that point. Also the gun's sticker slots from its CS2 material (where each
 * sits, its size, its default turn). Built in the background; null until ready.
 */
public final class SideView {
	public static final int W = 512;

	/** A sticker slot: centre (u, v) in sticker space, its size (1 / scale of it) and its turn (radians), as CS2's material has it. */
	public record Slot(double u, double v, double scale, double rotation) {}

	public final Identifier texture;
	/** The same picture with what CS2's sticker mask doesn't allow dimmed (shown while placing a sticker). */
	public final Identifier allowedTexture;
	/** Per pixel: whether a sticker may sit there (the gun's sticker mask, g_tStickerWepInputs red). */
	private final boolean[] allowed;
	public final int h;
	/** The sticker-space v at the picture's top and bottom (u runs 0-1 across). */
	public final double v0, v1;
	/** Model-space (glb, metres) point under each pixel, NaN where the gun isn't. */
	private final float[] pos;
	public final List<Slot> slots;
	/** Where the charm hangs by default (the model's keychain point), in sticker space, or null. */
	public double[] keychain;

	private SideView(Identifier texture, Identifier allowedTexture, boolean[] allowed, int h, double v0, double v1, float[] pos, List<Slot> slots) {
		this.texture = texture;
		this.allowedTexture = allowedTexture;
		this.allowed = allowed;
		this.h = h;
		this.v0 = v0;
		this.v1 = v1;
		this.pos = pos;
		this.slots = slots;
	}

	/** The model-space point under sticker-space (u, v), the nearest covered one within a few pixels; null if none. */
	public float[] pointAt(double u, double v) {
		int px = (int) Math.round(u * W), py = (int) Math.round((v - v0) / (v1 - v0) * h);
		for (int r = 0; r <= 6; r++)
			for (int dy = -r; dy <= r; dy++)
				for (int dx = -r; dx <= r; dx++) {
					int x = px + dx, y = py + dy;
					if (x < 0 || y < 0 || x >= W || y >= h) continue;
					int o = 3 * (y * W + x);
					if (!Float.isNaN(pos[o])) return new float[] {pos[o], pos[o + 1], pos[o + 2]};
				}
		return null;
	}

	/** Whether a sticker may be placed at sticker-space (u, v): on the gun, where CS2's sticker mask allows it. */
	public boolean allowedAt(double u, double v) {
		int x = (int) Math.floor(u * W), y = (int) Math.floor((v - v0) / (v1 - v0) * h);
		return x >= 0 && y >= 0 && x < W && y < h && allowed[y * W + x];
	}

	/** Sticker space (u, v) of the pixel nearest to a model-space point (the charm's default: its keychain attachment). */
	public double[] uvOf(float x, float y, float z) {
		double best = Double.MAX_VALUE;
		double[] out = null;
		for (int i = 0; i < W * h; i++) {
			int o = 3 * i;
			if (Float.isNaN(pos[o])) continue;
			double d = (pos[o] - x) * (pos[o] - x) + (pos[o + 1] - y) * (pos[o + 1] - y) + (pos[o + 2] - z) * (pos[o + 2] - z);
			if (d < best) {
				best = d;
				out = new double[] {(i % W + 0.5) / W, v0 + (i / W + 0.5) / h * (v1 - v0)};
			}
		}
		return out;
	}

	/**
	 * The C4's front (the face with the keypad, where CS2 puts its stickers; its mask also allows the sides): the
	 * area-weighted normal of the faces in the middle of its sticker space. Null for other weapons.
	 */
	static float[] front(String cls, java.util.List<VmModel.Part> parts) {
		if (!dev.strafemc.weapon.C4Item.CLS.equals(cls)) return null;
		double nx = 0, ny = 0, nz = 0;
		for (VmModel.Part p : parts) {
			if (p.uv1 == null || p.alphaMode.equals("BLEND")) continue;
			for (int t = 0; t + 2 < p.idx.length; t += 3) {
				int a = p.idx[t], b = p.idx[t + 1], c = p.idx[t + 2];
				double u = (p.uv1[a * 2] + p.uv1[b * 2] + p.uv1[c * 2]) / 3, v = (p.uv1[a * 2 + 1] + p.uv1[b * 2 + 1] + p.uv1[c * 2 + 1]) / 3;
				if (u < 0.3 || u > 0.7 || v < 0.2 || v > 0.6) continue;
				double ex = p.pos[b * 3] - p.pos[a * 3], ey = p.pos[b * 3 + 1] - p.pos[a * 3 + 1], ez = p.pos[b * 3 + 2] - p.pos[a * 3 + 2];
				double fx = p.pos[c * 3] - p.pos[a * 3], fy = p.pos[c * 3 + 1] - p.pos[a * 3 + 1], fz = p.pos[c * 3 + 2] - p.pos[a * 3 + 2];
				double area = 0.5 * Math.sqrt(Math.pow(ey * fz - ez * fy, 2) + Math.pow(ez * fx - ex * fz, 2) + Math.pow(ex * fy - ey * fx, 2));
				for (int k : new int[] {a, b, c}) {
					nx += p.nrm[k * 3] * area;
					ny += p.nrm[k * 3 + 1] * area;
					nz += p.nrm[k * 3 + 2] * area;
				}
			}
		}
		double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
		return l < 1e-9 ? null : new float[] {(float) (nx / l), (float) (ny / l), (float) (nz / l)};
	}

	/** Whether a surface with this normal faces the front (within about 45 degrees). */
	static boolean facesFront(float[] front, double nx, double ny, double nz) {
		double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
		return front == null || l > 1e-9 && (nx * front[0] + ny * front[1] + nz * front[2]) / l > 0.7;
	}

	private static final Map<String, SideView> READY = new ConcurrentHashMap<>();
	private static final java.util.Set<String> BUSY = ConcurrentHashMap.newKeySet();

	/** The side view of a gun in a finish (kit null: the plain gun); null while it's being made. */
	public static SideView of(String cls, String kit, int finish) {
		String key = cls + "#" + kit + "#" + finish;
		SideView s = READY.get(key);
		if (s != null || !BUSY.add(key)) return s;
		Thread t = new Thread(() -> {
			try {
				SideView v = build(cls, kit, finish);
				if (v != null) READY.put(key, v);
				else BUSY.remove(key);
			} catch (Exception e) {
				dev.strafemc.StrafeMc.LOG.warn("[strafemc] side view of {}: {}", cls, e.toString());
			}
		}, "strafemc-sideview");
		t.setDaemon(true);
		t.start();
		return null;
	}

	private static SideView build(String cls, String kit, int finish) throws Exception {
		var w = Viewmodel.weaponModel(cls);
		if (w == null) return null;
		// a kit with use_legacy_model is on the legacy mesh (its own UV layout, which the finish is baked for), as in the viewmodel
		Glb glb = Glb.read(AssetCache.exportGlb(w[0], true));
		boolean legacy = kit != null && WeaponSkinCache.legacy(kit) && VmModel.hasMesh(glb, ".body_legacy");
		VmModel m = VmModel.load(glb, legacy ? ".body_legacy" : w[1]);
		var paint = WeaponSkinCache.stickerable(cls, w[0], m.parts);
		// the finish, where the gun wears one; each part's own texture elsewhere
		BufferedImage finishImg = null;
		if (kit != null && paint != null) {
			Path f = WeaponSkinCache.texture(cls, kit, finish);
			Path px = f.resolveSibling(f.getFileName().toString().replace(".png", ".vm1024b.png"));
			if (Files.exists(px)) finishImg = ImageIO.read(px.toFile());
		}
		double v0 = Double.MAX_VALUE, v1 = -Double.MAX_VALUE, nx = 0;
		for (VmModel.Part p : m.parts) {
			if (p.uv1 == null) continue;
			for (int i = 0; i + 1 < p.uv1.length; i += 2)
				if (p.uv1[i] >= 0 && p.uv1[i] <= 1) {
					v0 = Math.min(v0, p.uv1[i + 1]);
					v1 = Math.max(v1, p.uv1[i + 1]);
					nx += p.nrm[(i / 2) * 3];
				}
		}
		if (v0 >= v1) return null;
		double side = nx >= 0 ? 1 : -1; // which way the faces drawn in 0-1 look (the nearer side wins)
		// the Dual Berettas are two mirrored pistols side by side in sticker space: the left one (u < 0.5) is turned the
		// other way, so there the opposite face is its outer side
		boolean twin = cls.equals("weapon_elite");
		int h = Math.max(16, (int) Math.round((v1 - v0) * W));
		BufferedImage img = new BufferedImage(W, h, BufferedImage.TYPE_INT_ARGB);
		boolean[] allowed = new boolean[W * h];
		BufferedImage stickerMask = null; // where stickers may go, in the gun's texture space
		if (paint != null) {
			var mp = GloveSkinBaker.Params.parse(Files.readAllLines(AssetCache.dataBlock(paint.material() + "_c")));
			stickerMask = WeaponSkinCache.image(mp, "g_tStickerWepInputs");
		}
		float[] front = front(cls, m.parts); // the C4: stickers only on its front
		float[] pos = new float[W * h * 3], depth = new float[W * h];
		java.util.Arrays.fill(pos, Float.NaN);
		java.util.Arrays.fill(depth, -Float.MAX_VALUE);
		for (VmModel.Part p : m.parts) {
			if (p.uv1 == null) continue;
			BufferedImage tex = paint != null && finishImg != null && p.texture != null && p.texture.getFileName().toString().startsWith(paint.color()) ? finishImg
					: p.texture == null ? null : ImageIO.read(Viewmodel.downscaledPath(p.texture).toFile());
			float[] u1 = p.uv1, uv = p.uv;
			boolean paintPart = paint != null && p.texture != null && p.texture.getFileName().toString().startsWith(paint.color());
			for (int t = 0; t + 2 < p.idx.length; t += 3) {
				int a = p.idx[t], b = p.idx[t + 1], c = p.idx[t + 2];
				if (u1[a * 2] < -0.02 || u1[a * 2] > 1.02 || u1[b * 2] < -0.02 || u1[b * 2] > 1.02 || u1[c * 2] < -0.02 || u1[c * 2] > 1.02) continue;
				double ax = u1[a * 2] * W, ay = (u1[a * 2 + 1] - v0) / (v1 - v0) * h, bx = u1[b * 2] * W, by = (u1[b * 2 + 1] - v0) / (v1 - v0) * h,
						cx = u1[c * 2] * W, cy = (u1[c * 2 + 1] - v0) / (v1 - v0) * h;
				double area = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
				if (Math.abs(area) < 1e-9) continue;
				int x0 = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx)))), x1 = Math.min(W - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
				int y0 = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy)))), y1 = Math.min(h - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
				for (int y = y0; y <= y1; y++)
					for (int x = x0; x <= x1; x++) {
						double px = x + 0.5, py = y + 0.5;
						double wa = ((bx - px) * (cy - py) - (cx - px) * (by - py)) / area, wb = ((cx - px) * (ay - py) - (ax - px) * (cy - py)) / area, wc = 1 - wa - wb;
						if (wa < -0.001 || wb < -0.001 || wc < -0.001) continue;
						float px3 = (float) (wa * p.pos[a * 3] + wb * p.pos[b * 3] + wc * p.pos[c * 3]);
						float d = (float) (px3 * side * (twin && px < W / 2.0 ? -1 : 1));
						int i = y * W + x;
						if (d < depth[i]) continue;
						depth[i] = d;
						for (int k = 0; k < 3; k++) pos[3 * i + k] = (float) (wa * p.pos[a * 3 + k] + wb * p.pos[b * 3 + k] + wc * p.pos[c * 3 + k]);
						if (paintPart && stickerMask != null) { // the gun's painted body: its sticker mask says where stickers go
							double mu = wa * uv[a * 2] + wb * uv[b * 2] + wc * uv[c * 2], mv = wa * uv[a * 2 + 1] + wb * uv[b * 2 + 1] + wc * uv[c * 2 + 1];
							allowed[i] = (GloveSkinBaker.sample(stickerMask, mu - Math.floor(mu), mv - Math.floor(mv)) >> 16 & 255) > 127
									&& facesFront(front, wa * p.nrm[a * 3] + wb * p.nrm[b * 3] + wc * p.nrm[c * 3], wa * p.nrm[a * 3 + 1] + wb * p.nrm[b * 3 + 1] + wc * p.nrm[c * 3 + 1],
									wa * p.nrm[a * 3 + 2] + wb * p.nrm[b * 3 + 2] + wc * p.nrm[c * 3 + 2]);
						} else allowed[i] = paintPart && stickerMask == null && cls.equals(dev.strafemc.weapon.C4Item.CLS); // the C4 has no mask: anywhere on it
						int col = 0xFF808080;
						if (tex != null) {
							double tu = wa * uv[a * 2] + wb * uv[b * 2] + wc * uv[c * 2], tv = wa * uv[a * 2 + 1] + wb * uv[b * 2 + 1] + wc * uv[c * 2 + 1];
							col = 0xFF000000 | GloveSkinBaker.sample(tex, tu - Math.floor(tu), tv - Math.floor(tv));
						}
						img.setRGB(x, y, col);
					}
			}
		}
		// the material's sticker slots
		List<Slot> slots = new ArrayList<>();
		if (paint != null) {
			var wp = GloveSkinBaker.Params.parse(Files.readAllLines(AssetCache.dataBlock(paint.material() + "_c")));
			for (int i = 0; i < dev.strafemc.weapon.StickerSlots.MAX; i++) {
				double[] off = wp.v("g_vSticker" + i + "Offset", 0, 0), sc = wp.v("g_vSticker" + i + "Scale", 0, 0);
				if (sc[0] <= 0) {
					if (!cls.equals(dev.strafemc.weapon.C4Item.CLS) || i >= dev.strafemc.weapon.StickerSlots.C4_SLOTS.length) break;
					double[] c4 = dev.strafemc.weapon.StickerSlots.C4_SLOTS[i]; // the C4's places are ours
					slots.add(new Slot(c4[0], c4[1], dev.strafemc.weapon.StickerSlots.C4_SCALE, c4[2]));
					continue;
				}
				slots.add(new Slot(0.5 + off[0], 0.5 + (off.length > 1 ? off[1] : 0), sc[0], wp.f("g_flSticker" + i + "Rotation", 0)));
			}
		}
		NativeImage ni = new NativeImage(W, h, false), dim = new NativeImage(W, h, false);
		for (int y = 0; y < h; y++)
			for (int x = 0; x < W; x++) {
				int c = img.getRGB(x, y);
				ni.setPixel(x, y, c); // ARGB in, as the GUI expects
				int k = allowed[y * W + x] ? 255 : 90; // where a sticker can't go: dark
				dim.setPixel(x, y, c & 0xFF000000 | ((c >> 16 & 255) * k / 255) << 16 | ((c >> 8 & 255) * k / 255) << 8 | (c & 255) * k / 255);
			}
		Identifier id = Identifier.fromNamespaceAndPath("strafemc", "side_view/" + Integer.toHexString((cls + kit + finish).hashCode()));
		Identifier dimId = Identifier.fromNamespaceAndPath("strafemc", "side_view/" + Integer.toHexString((cls + kit + finish).hashCode()) + "_allowed");
		var result = new SideView(id, dimId, allowed, h, v0, v1, pos, slots);
		float[] kc = Viewmodel.restPoint(w[0], m, "keychain");
		if (kc != null) result.keychain = result.uvOf(kc[0], kc[1], kc[2]);
		Minecraft.getInstance().execute(() -> {
			Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "strafemc side view " + cls, ni));
			Minecraft.getInstance().getTextureManager().register(dimId, new DynamicTexture(() -> "strafemc side view " + cls + " (sticker area)", dim));
		});
		return result;
	}
}
