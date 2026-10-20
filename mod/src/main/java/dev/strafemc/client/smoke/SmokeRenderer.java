package dev.strafemc.client.smoke;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.strafemc.StrafeMc;
import dev.strafemc.config.StrafeConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

/**
 * Draws the smoke volumes the way CS2 does (strafemc:core/smoke_volume, a port of CS2's smoke_volume shader): one
 * full-screen pass marches every pixel's ray through the smokes' 32^3 voxel densities, at half resolution (CS2 marches
 * at a lower resolution too), then the result is laid over the scene. Drawn at the end of the main pass, after the
 * world and before the hand. Inputs per smoke come from SmokeVolumes: its densities (two frames to blend between,
 * each as a dilated and an exact value), its light, where it is and how far it has faded.
 */
public final class SmokeRenderer {
	private SmokeRenderer() {}

	public static final int MAX_SMOKES = 8, N = 32, TILE = 34, MAX_BLASTS = 5, MAX_TRACERS = 16;
	/** CS2 units per block. */
	static final float UPB = 40;
	private static final int ATLAS_W = N * TILE, ATLAS_H = MAX_SMOKES * TILE, NOISE_W = 16 * 130, NOISE_H = 8 * 130;
	private static final int UBO_SIZE = 64 + 16 * 7 + MAX_SMOKES * 16 * 6 + MAX_BLASTS * 16 + 32 + 16 + MAX_TRACERS * 16 * 3;

	private static final RenderPipeline MARCH = RenderPipelines.register(RenderPipeline.builder().withLocation("pipeline/strafemc_smoke_volume")
			.withVertexShader("core/screenquad").withFragmentShader(Identifier.fromNamespaceAndPath("strafemc", "core/smoke_volume"))
			.withSampler("DepthSampler").withSampler("NoiseSampler").withSampler("DensitySampler").withSampler("LightSampler")
			.withSampler("BlueNoiseSampler").withSampler("LightmapSampler").withUniform("SmokeInfo", UniformType.UNIFORM_BUFFER)
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
			.withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES).build());
	private static final RenderPipeline COMPOSITE = RenderPipelines.register(RenderPipeline.builder().withLocation("pipeline/strafemc_smoke_composite")
			.withVertexShader("core/screenquad").withFragmentShader(Identifier.fromNamespaceAndPath("strafemc", "core/smoke_composite"))
			.withSampler("InSampler").withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
			.withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES).build());

	private static GpuTexture density, light, noise, blueNoise;
	private static GpuTextureView densityView, lightView, noiseView, blueNoiseView;
	private static GpuBuffer ubo;
	private static TextureTarget target;
	private static CompletableFuture<byte[][]> noiseLoad;
	private static boolean failed;
	private static long frame;
	private static final long T0 = System.nanoTime();
	private static final ByteBuffer SLOT = ByteBuffer.allocateDirect(ATLAS_W * TILE * 4).order(ByteOrder.nativeOrder());
	private static final ByteBuffer UPLOAD = ByteBuffer.allocateDirect(UBO_SIZE).order(ByteOrder.nativeOrder());

	/** Called at client start so the pipelines are registered with Minecraft's own. */
	public static void init() {}

	/** Seconds on the smoke clock (shader time, blast and tracer times). */
	public static float now() {
		return (float) ((System.nanoTime() - T0) / 1e9 % 3600.0);
	}

	/** A position in CS2 units and axes (x, y = -z, z = up), relative to the camera. */
	static Vector3f toCs(Vec3 p, Vec3 cam) {
		return new Vector3f((float) ((p.x - cam.x) * UPB), (float) (-(p.z - cam.z) * UPB), (float) ((p.y - cam.y) * UPB));
	}

	static Vector3f dirToCs(org.joml.Vector3fc d) {
		return new Vector3f(d.x(), -d.z(), d.y());
	}

	public static void render() {
		if (failed) return;
		List<SmokeVolumes.Drawn> smokes = SmokeVolumes.drawn();
		if (smokes.isEmpty()) return;
		try {
			if (!ready()) return;
			draw(smokes);
		} catch (RuntimeException | LinkageError e) {
			failed = true;
			StrafeMc.LOG.error("[strafemc] volumetric smoke failed, smokes won't be drawn this session", e);
		}
	}

	/** Textures made once the noise is loaded (read from the setup's cache on a worker thread). */
	private static boolean ready() {
		if (noise != null) return true;
		if (noiseLoad == null) noiseLoad = CompletableFuture.supplyAsync(SmokeRenderer::loadNoise);
		if (!noiseLoad.isDone()) return false;
		byte[][] data = noiseLoad.join();
		if (data == null) {
			failed = true;
			StrafeMc.LOG.warn("[strafemc] smoke noise not in the cache (run the setup): volumetric smoke is off");
			return false;
		}
		var device = RenderSystem.getDevice();
		int use = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
		noise = device.createTexture(() -> "strafemc smoke noise", use, TextureFormat.RGBA8, NOISE_W, NOISE_H, 1, 1);
		blueNoise = device.createTexture(() -> "strafemc smoke blue noise", use, TextureFormat.RGBA8, 256, 256, 1, 1);
		density = device.createTexture(() -> "strafemc smoke density", use, TextureFormat.RGBA8, ATLAS_W, ATLAS_H, 1, 1);
		light = device.createTexture(() -> "strafemc smoke light", use, TextureFormat.RGBA8, ATLAS_W, ATLAS_H, 1, 1);
		var enc = device.createCommandEncoder();
		enc.writeToTexture(noise, direct(data[0]), NativeImage.Format.RGBA, 0, 0, 0, 0, NOISE_W, NOISE_H);
		enc.writeToTexture(blueNoise, direct(data[1]), NativeImage.Format.RGBA, 0, 0, 0, 0, 256, 256);
		enc.writeToTexture(density, direct(new byte[ATLAS_W * ATLAS_H * 4]), NativeImage.Format.RGBA, 0, 0, 0, 0, ATLAS_W, ATLAS_H);
		enc.writeToTexture(light, direct(new byte[ATLAS_W * ATLAS_H * 4]), NativeImage.Format.RGBA, 0, 0, 0, 0, ATLAS_W, ATLAS_H);
		noiseView = device.createTextureView(noise);
		blueNoiseView = device.createTextureView(blueNoise);
		densityView = device.createTextureView(density);
		lightView = device.createTextureView(light);
		ubo = device.createBuffer(() -> "strafemc smoke info", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE);
		return true;
	}

	private static ByteBuffer direct(byte[] b) {
		ByteBuffer bb = ByteBuffer.allocateDirect(b.length).order(ByteOrder.nativeOrder());
		bb.put(b).flip();
		return bb;
	}

	/**
	 * CS2's smoke noise (materials/dev/noise/worley_perlin_z0000, 128 slices of 128x128) packed into 16 x 8 tiles with a
	 * 1-texel wrapped border, and the 256x256 blue noise; null if the setup hasn't converted them.
	 */
	private static byte[][] loadNoise() {
		try {
			Path dir = StrafeConfig.cacheDir().resolve("materials/dev/noise");
			byte[] atlas = new byte[NOISE_W * NOISE_H * 4];
			for (int z = 0; z < 128; z++) {
				Path f = dir.resolve(String.format("worley_perlin_z0000_tga_37c9ebc1_z%03d.png", z));
				if (!Files.isRegularFile(f)) return null;
				BufferedImage im = ImageIO.read(f.toFile());
				int tx = (z % 16) * 130, ty = (z / 16) * 130;
				for (int y = -1; y <= 128; y++)
					for (int x = -1; x <= 128; x++) {
						int argb = im.getRGB(Math.floorMod(x, 128), Math.floorMod(y, 128));
						int o = ((ty + y + 1) * NOISE_W + tx + x + 1) * 4;
						atlas[o] = (byte) (argb >> 16);
						atlas[o + 1] = (byte) (argb >> 8);
						atlas[o + 2] = (byte) argb;
						atlas[o + 3] = (byte) (argb >>> 24);
					}
			}
			byte[] blue = new byte[256 * 256 * 4];
			Path bf = StrafeConfig.cacheDir().resolve("textures/dev/blue_noise_256.png");
			if (Files.isRegularFile(bf)) {
				BufferedImage im = ImageIO.read(bf.toFile());
				for (int y = 0; y < 256; y++)
					for (int x = 0; x < 256; x++) {
						int argb = im.getRGB(x, y), o = (y * 256 + x) * 4;
						blue[o] = (byte) (argb >> 16);
						blue[o + 1] = (byte) (argb >> 8);
						blue[o + 2] = (byte) argb;
						blue[o + 3] = (byte) (argb >>> 24);
					}
			} else { // FLAGGED: without CS2's blue noise, white noise jitters the rays
				java.util.Random r = new java.util.Random(1);
				r.nextBytes(blue);
			}
			return new byte[][] {atlas, blue};
		} catch (Exception e) {
			StrafeMc.LOG.warn("[strafemc] couldn't read the smoke noise", e);
			return null;
		}
	}

	/** One slot's 32 slices of 32x32 voxels into an atlas row, each slice with a 1-texel clamped border. */
	private static void upload(GpuTexture tex, int slot, byte[] voxels) {
		ByteBuffer b = SLOT;
		b.clear();
		for (int y = 0; y < TILE; y++)
			for (int z = 0; z < N; z++)
				for (int x = 0; x < TILE; x++) {
					int vx = Math.clamp(x - 1, 0, N - 1), vy = Math.clamp(y - 1, 0, N - 1);
					int o = ((vx + N * (vy + N * z))) * 4;
					b.put(voxels[o]).put(voxels[o + 1]).put(voxels[o + 2]).put(voxels[o + 3]);
				}
		b.flip();
		RenderSystem.getDevice().createCommandEncoder().writeToTexture(tex, b, NativeImage.Format.RGBA, 0, 0, 0, slot * TILE, ATLAS_W, TILE);
	}

	private static void draw(List<SmokeVolumes.Drawn> smokes) {
		Minecraft mc = Minecraft.getInstance();
		var device = RenderSystem.getDevice();
		var enc = device.createCommandEncoder();
		var main = mc.getMainRenderTarget();
		int w = Math.max(1, main.width / 2), h = Math.max(1, main.height / 2);
		if (target == null) target = new TextureTarget("strafemc smoke", w, h, false);
		else if (target.width != w || target.height != h) target.resize(w, h);

		Camera cam = mc.gameRenderer.getMainCamera();
		Vec3 c = cam.position();
		float time = now();
		int count = Math.min(MAX_SMOKES, smokes.size());
		for (int i = 0; i < count; i++) {
			SmokeVolumes.Drawn s = smokes.get(i);
			if (s.densityDirty()) upload(density, s.slot(), s.density());
			if (s.lightDirty()) upload(light, s.slot(), s.light());
		}

		// clip -> camera-relative blocks -> CS2 units and axes
		Matrix4f clipToMc = cam.getViewRotationProjectionMatrix(new Matrix4f()).invert();
		Matrix4f toCs = new Matrix4f(UPB, 0, 0, 0, 0, 0, UPB, 0, 0, -UPB, 0, 0, 0, 0, 0, 1);
		Matrix4f clipToCs = toCs.mul(clipToMc, new Matrix4f());
		Vector3f right = dirToCs(new Vector3f(cam.leftVector()).negate()), up = dirToCs(cam.upVector()), fwd = dirToCs(cam.forwardVector());

		ByteBuffer u = UPLOAD;
		u.clear();
		Std140Builder b = Std140Builder.intoBuffer(u);
		b.putMat4f(clipToCs);
		b.putVec4(right.x, right.y, right.z, 0).putVec4(up.x, up.y, up.z, 0).putVec4(fwd.x, fwd.y, fwd.z, 0);
		List<SmokeVolumes.Blast> blasts = SmokeVolumes.blasts();
		List<SmokeVolumes.Tracer> tracers = SmokeVolumes.tracers();
		int nb = Math.min(MAX_BLASTS, blasts.size()), nt = Math.min(MAX_TRACERS, tracers.size());
		b.putVec4(time, frame++ % 1024, count, nb);
		SmokeVolumes.Sun sun = SmokeVolumes.sun(mc);
		Vector3f sd = dirToCs(sun.dir());
		b.putVec4(sd.x, sd.y, sd.z, 0);
		b.putVec4(sun.r(), sun.g(), sun.b(), nt);
		SmokeVolumes.Blast flash = SmokeVolumes.lastFlash();
		Vector3f fp = flash == null ? new Vector3f() : toCs(flash.at(), c);
		b.putVec4(fp.x, fp.y, fp.z, flash == null ? -1000 : flash.time());
		for (int k = 0; k < 6; k++)
			for (int i = 0; i < MAX_SMOKES; i++) {
				if (i >= count) {
					b.putVec4(0, 0, 0, 0);
					continue;
				}
				SmokeVolumes.Drawn s = smokes.get(i);
				switch (k) {
					case 0 -> {
						Vector3f p = toCs(s.center(), c);
						b.putVec4(p.x, p.y, p.z, s.slot());
					}
					case 1 -> {
						Vector3f p = toCs(s.min(), c), q = toCs(s.max(), c); // y flips: min/max swap
						b.putVec4(Math.min(p.x, q.x), Math.min(p.y, q.y), Math.min(p.z, q.z), 0);
					}
					case 2 -> {
						Vector3f p = toCs(s.min(), c), q = toCs(s.max(), c);
						b.putVec4(Math.max(p.x, q.x), Math.max(p.y, q.y), Math.max(p.z, q.z), 0);
					}
					case 3 -> b.putVec4(s.age(), s.frameBlend(), s.slot(), i);
					case 4 -> b.putVec4(s.tint()[0], s.tint()[1], s.tint()[2], 0);
					default -> b.putVec4(s.densityScale(), 0, s.tintAmount(), s.shrink());
				}
			}
		int[] masks = new int[8];
		for (int k = 0; k < MAX_BLASTS; k++) {
			if (k >= nb) {
				b.putVec4(0, 0, -1e6f, -1000);
				continue;
			}
			SmokeVolumes.Blast bl = blasts.get(k);
			Vector3f p = toCs(bl.at(), c);
			b.putVec4(p.x, p.y, p.z, bl.time());
			for (int i = 0; i < count; i++) if (smokes.get(i).center().distanceTo(bl.at()) * UPB < 250 + 640) masks[k] |= 1 << i;
		}
		b.putVec4(masks[0], masks[1], masks[2], masks[3]).putVec4(masks[4], 0, 0, 0);
		b.putVec4(SmokeVolumes.LIGHT_SCALE, device.isZZeroToOne() ? 1 : 0, 0, 0);
		for (int k = 0; k < 3; k++)
			for (int i = 0; i < MAX_TRACERS; i++) {
				if (i >= nt) {
					b.putVec4(0, 0, 0, 1000);
					continue;
				}
				// CS2's client: the bullet's end travels from the start over 0.1 s, the whole line rises 10 units a
				// second (FLAGGED: kept from overshooting the impact; thickness 1, CS2's per-shot value not traced)
				SmokeVolumes.Tracer t = tracers.get(i);
				float age = time - t.time(), along = Math.min(1, age * 10), rise = age * 10;
				Vector3f p = toCs(k == 1 ? t.from().lerp(t.to(), along) : t.from(), c);
				switch (k) {
					case 0 -> b.putVec4(p.x, p.y, p.z + rise, age);
					case 1 -> b.putVec4(p.x, p.y, p.z + rise, t.glow() ? 1 : 0);
					default -> b.putVec4(1, 0, 0, 0);
				}
			}
		u.position(0);
		u.limit(UBO_SIZE);
		enc.writeToBuffer(ubo.slice(), u);

		var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		enc.clearColorTexture(target.getColorTexture(), 0);
		try (RenderPass pass = enc.createRenderPass(() -> "strafemc smoke march", target.getColorTextureView(), OptionalInt.empty())) {
			pass.setPipeline(MARCH);
			pass.setUniform("SmokeInfo", ubo);
			pass.bindTexture("DepthSampler", main.getDepthTextureView(), nearest);
			pass.bindTexture("NoiseSampler", noiseView, linear);
			pass.bindTexture("DensitySampler", densityView, linear);
			pass.bindTexture("LightSampler", lightView, linear);
			pass.bindTexture("BlueNoiseSampler", blueNoiseView, nearest);
			pass.bindTexture("LightmapSampler", mc.gameRenderer.lightmap(), linear);
			pass.draw(0, 3);
		}
		GpuTextureView out = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : main.getColorTextureView();
		try (RenderPass pass = enc.createRenderPass(() -> "strafemc smoke composite", out, OptionalInt.empty())) {
			pass.setPipeline(COMPOSITE);
			pass.bindTexture("InSampler", target.getColorTextureView(), linear);
			pass.draw(0, 3);
		}
	}
}
