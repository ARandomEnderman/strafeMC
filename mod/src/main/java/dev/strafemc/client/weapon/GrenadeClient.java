package dev.strafemc.client.weapon;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.strafemc.client.sound.CsSounds;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.GrenadeEffects;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Client side of grenades going off: CS2's effects and sounds, the flashbang white-out, smoke clouds and fire patches.
 * Smoke and fire are drawn by strafeMC (CS2's are a voxel smoke and a spreading fire system): a cloud of soft puffs
 * about CS2's smoke size, and flames over the burning area. Sizes and durations match weapon.GrenadeZones.
 */
public final class GrenadeClient {
	private GrenadeClient() {}

	private record Smoke(Vec3 at, long start, float[] puffs) {}

	private record Fire(Vec3 at, long until, boolean incendiary) {}

	private static final List<Smoke> SMOKES = new ArrayList<>();
	private static final List<Fire> FIRES = new ArrayList<>();
	private static final double SMOKE_SECONDS = 18, SMOKE_FADE = 2, FIRE_SECONDS = 7;
	private static final int PUFFS = 70;
	private static Identifier puffTexture;

	/** The trajectory preview: the path of the grenade being held charged (null = none). */
	private static volatile List<Vec3> preview;

	public static void preview(List<Vec3> path) {
		preview = path;
	}

	/** Flashbang: when the white-out started and how long it lasts (seconds). */
	private static volatile long flashStart;
	private static volatile float flashSeconds;

	public static void onEffect(StrafePayloads.GrenadeFx m) {
		Vec3 at = new Vec3(m.x(), m.y(), m.z());
		Vec3 up = new Vec3(0, 1, 0);
		switch (m.kind()) {
			case GrenadeEffects.EV_HE -> {
				dev.strafemc.client.particle.Effects.at(GrenadeEffects.HE_EFFECT, at, up);
				CsSounds.play("BaseGrenade.Explode", at.x, at.y, at.z, 1.0);
			}
			case GrenadeEffects.EV_FLASH -> {
				dev.strafemc.client.particle.Effects.at(GrenadeEffects.FLASH_EFFECT, at, up);
				CsSounds.play("Flashbang.Explode", at.x, at.y, at.z, 1.0);
			}
			case GrenadeEffects.EV_SMOKE -> {
				synchronized (SMOKES) {
					SMOKES.add(new Smoke(at, System.nanoTime(), puffs(at)));
				}
				CsSounds.play("BaseSmokeEffect.Sound", at.x, at.y, at.z, 1.0);
			}
			case GrenadeEffects.EV_FIRE, GrenadeEffects.EV_FIRE_INC -> {
				boolean inc = m.kind() == GrenadeEffects.EV_FIRE_INC;
				synchronized (FIRES) {
					FIRES.add(new Fire(at, System.nanoTime() + (long) (FIRE_SECONDS * 1e9), inc));
				}
				dev.strafemc.client.particle.Effects.at(GrenadeEffects.MOLOTOV_GROUND_EFFECT, at, up);
				CsSounds.play(inc ? "IncGrenade.Start" : "Inferno.Fire.Ignite", at.x, at.y, at.z, 1.0);
				CsSounds.play("Inferno.Loop", at.x, at.y, at.z, 1.0);
			}
			case GrenadeEffects.EV_FIRE_OUT, GrenadeEffects.EV_FIRE_END -> {
				synchronized (FIRES) {
					FIRES.removeIf(f -> f.at.distanceToSqr(at) < 0.01);
				}
				CsSounds.play(m.kind() == GrenadeEffects.EV_FIRE_OUT ? "Molotov.Extinguish" : "Inferno.FadeOut", at.x, at.y, at.z, 1.0);
			}
			case GrenadeEffects.EV_FIZZLE, GrenadeEffects.EV_FIZZLE_INC -> {
				dev.strafemc.client.particle.Effects.at(m.kind() == GrenadeEffects.EV_FIZZLE_INC ? GrenadeEffects.INCENDIARY_AIR_EFFECT
						: GrenadeEffects.MOLOTOV_AIR_EFFECT, at, up);
				CsSounds.play("Molotov.Extinguish", at.x, at.y, at.z, 1.0);
			}
			case GrenadeEffects.EV_DECOY_POP -> CsSounds.play("BaseGrenade.Explode", at.x, at.y, at.z, 0.4);
			default -> {}
		}
	}

	public static void onFlashed(StrafePayloads.Flashed m) {
		flashStart = System.nanoTime();
		flashSeconds = m.seconds();
		var p = Minecraft.getInstance().player;
		if (p != null) CsSounds.play(m.seconds() > 3 ? "Flashbang.Ring.Long" : m.seconds() > 1.5 ? "Flashbang.Ring.Medium" : "Flashbang.Ring.Short",
				p.getX(), p.getEyeY(), p.getZ(), 1.0);
	}

	/** The white-out: full white for the first 40% of the flash, then fading (FLAGGED: CS-like shape). */
	public static void registerHud() {
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("strafemc", "flashbang"), (g, delta) -> {
			float secs = flashSeconds;
			if (secs <= 0) return;
			float t = (System.nanoTime() - flashStart) / 1e9f, hold = secs * 0.4f;
			float a = t < hold ? 1 : 1 - (t - hold) / (secs - hold);
			if (a <= 0) {
				flashSeconds = 0;
				return;
			}
			g.fill(0, 0, g.guiWidth(), g.guiHeight(), (int) (Math.min(1, a) * 255) << 24 | 0xFFFFFF);
		});
	}

	/** Every client tick: flames over burning areas; finished smokes and fires dropped. */
	public static void tick(Minecraft mc) {
		if (mc.level == null || mc.isPaused()) return;
		long now = System.nanoTime();
		double r = 120 / StrafeConfig.unitsPerBlock;
		Random rnd = new Random();
		synchronized (FIRES) {
			FIRES.removeIf(f -> now > f.until);
			for (Fire f : FIRES)
				for (int i = 0; i < 8; i++) {
					double a = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble()) * r;
					double x = f.at.x + Math.cos(a) * d, z = f.at.z + Math.sin(a) * d;
					mc.level.addParticle(i % 4 == 3 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.FLAME, x, f.at.y + 0.05, z, 0, 0.03 + rnd.nextDouble() * 0.04, 0);
				}
		}
		synchronized (SMOKES) {
			SMOKES.removeIf(s -> (now - s.start) / 1e9 > SMOKE_SECONDS + SMOKE_FADE);
		}
	}

	/** Puff centres (x, y, z offsets in blocks, size) filling CS2's smoke size: 144 units around, about 100 high. */
	private static float[] puffs(Vec3 at) {
		Random rnd = new Random(Double.doubleToLongBits(at.x * 31 + at.z));
		double u = StrafeConfig.unitsPerBlock, rad = 144 / u, height = 100 / u;
		float[] p = new float[PUFFS * 4];
		for (int i = 0; i < PUFFS; i++) {
			double a = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble()) * rad * 0.85;
			p[i * 4] = (float) (Math.cos(a) * d);
			p[i * 4 + 1] = (float) (rnd.nextDouble() * height);
			p[i * 4 + 2] = (float) (Math.sin(a) * d);
			p[i * 4 + 3] = (float) ((50 + rnd.nextDouble() * 30) / u);
		}
		return p;
	}

	/** A soft round puff, made once (no CS2 files needed). */
	private static Identifier puff() {
		if (puffTexture != null) return puffTexture;
		NativeImage img = new NativeImage(64, 64, true);
		for (int y = 0; y < 64; y++)
			for (int x = 0; x < 64; x++) {
				double dx = (x - 31.5) / 31.5, dy = (y - 31.5) / 31.5, d = Math.sqrt(dx * dx + dy * dy);
				double a = Math.max(0, 1 - d);
				a = a * a * (3 - 2 * a);
				img.setPixel(x, y, (int) (a * 255) << 24 | 0xFFFFFF);
			}
		puffTexture = Identifier.fromNamespaceAndPath("strafemc", "smoke_puff");
		Minecraft.getInstance().getTextureManager().register(puffTexture, new DynamicTexture(() -> "strafemc smoke puff", img));
		return puffTexture;
	}

	/** Draws the trajectory preview (dots, a bigger one where it ends) and the smoke clouds. */
	public static void render(LevelRenderContext ctx) {
		renderPreview(ctx);
		List<Smoke> smokes;
		synchronized (SMOKES) {
			if (SMOKES.isEmpty()) return;
			smokes = new ArrayList<>(SMOKES);
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		Camera cam = mc.gameRenderer.getMainCamera();
		Vec3 c = cam.position();
		Vector3f up = new Vector3f(cam.upVector()), right = new Vector3f(cam.forwardVector()).cross(up).normalize();
		long now = System.nanoTime();
		List<float[]> quads = new ArrayList<>();
		for (Smoke s : smokes) {
			double t = (now - s.start) / 1e9;
			double grow = Math.min(1, t / 1.0), fade = t > SMOKE_SECONDS ? Math.max(0, 1 - (t - SMOKE_SECONDS) / SMOKE_FADE) : 1;
			float alpha = (float) (0.85 * Math.min(grow * 1.5, 1) * fade);
			for (int i = 0; i < PUFFS; i++) {
				float x = (float) (s.at.x + s.puffs[i * 4] * grow - c.x), y = (float) (s.at.y + s.puffs[i * 4 + 1] * grow - c.y),
						z = (float) (s.at.z + s.puffs[i * 4 + 2] * grow - c.z), size = (float) (s.puffs[i * 4 + 3] * (0.6 + 0.4 * grow));
				int light = LevelRenderer.getLightCoords(mc.level, BlockPos.containing(x + c.x, y + c.y, z + c.z));
				float shade = 0.62f + (i % 5) * 0.025f;
				quads.add(new float[] {x, y, z, size, shade, alpha, Float.intBitsToFloat(light)});
			}
		}
		quads.sort(Comparator.comparingDouble((float[] q) -> -(q[0] * q[0] + q[1] * q[1] + q[2] * q[2])));
		PoseStack ps = ctx.poseStack();
		ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.entityTranslucent(puff()), (pose, vc) -> {
			for (float[] q : quads) {
				int light = Float.floatToRawIntBits(q[6]);
				int shade = (int) (q[4] * 255);
				int col = (int) (q[5] * 255) << 24 | shade << 16 | shade << 8 | shade;
				float sz = q[3];
				float[][] corners = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};
				for (float[] k : corners)
					vc.addVertex(pose, q[0] + (right.x * k[0] + up.x * k[1]) * sz, q[1] + (right.y * k[0] + up.y * k[1]) * sz,
									q[2] + (right.z * k[0] + up.z * k[1]) * sz)
							.setColor(col).setUv(k[2], k[3]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0, 1, 0);
			}
		});
	}

	private static void renderPreview(LevelRenderContext ctx) {
		List<Vec3> path = preview;
		Minecraft mc = Minecraft.getInstance();
		if (path == null || path.size() < 2 || mc.level == null) return;
		Camera cam = mc.gameRenderer.getMainCamera();
		Vec3 c = cam.position();
		Vector3f up = new Vector3f(cam.upVector()), right = new Vector3f(cam.forwardVector()).cross(up).normalize();
		ctx.submitNodeCollector().submitCustomGeometry(ctx.poseStack(), RenderTypes.entityTranslucentEmissive(puff()), (pose, vc) -> {
			for (int i = 3; i < path.size(); i += 3) dot(vc, pose, path.get(i).subtract(c), right, up, 0.035f, 0xC0FFFFFF);
			dot(vc, pose, path.get(path.size() - 1).subtract(c), right, up, 0.12f, 0xE0FFFFFF);
		});
	}

	private static void dot(com.mojang.blaze3d.vertex.VertexConsumer vc, PoseStack.Pose pose, Vec3 p, Vector3f right, Vector3f up, float sz, int col) {
		float[][] corners = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};
		for (float[] k : corners)
			vc.addVertex(pose, (float) p.x + (right.x * k[0] + up.x * k[1]) * sz, (float) p.y + (right.y * k[0] + up.y * k[1]) * sz,
							(float) p.z + (right.z * k[0] + up.z * k[1]) * sz)
					.setColor(col).setUv(k[2], k[3]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, 0, 1, 0);
	}

	public static void clear() {
		preview = null;
		synchronized (SMOKES) {
			SMOKES.clear();
		}
		synchronized (FIRES) {
			FIRES.clear();
		}
		flashSeconds = 0;
	}
}
