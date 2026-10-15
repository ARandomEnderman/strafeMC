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
 * Smoke is CS2's voxel volume (weapon.SmokeVolume, the same fill the server runs) drawn as one soft puff per voxel
 * (CS2 raymarches it); fire is CS2's per-flame effect. Durations match weapon.GrenadeZones.
 */
public final class GrenadeClient {
	private GrenadeClient() {}

	private record Smoke(dev.strafemc.weapon.SmokeVolume volume, long start, boolean t) {}

	/**
	 * CS2's team smoke colours (smoke_grenade_ct_color 75 127 155, smoke_grenade_t_color 180 129 50) and how much of
	 * them tints the grey (FLAGGED: ours, CS2's smoke shader mixes it its own way).
	 */
	private static final float[] CT_TINT = {75 / 255f, 127 / 255f, 155 / 255f}, T_TINT = {180 / 255f, 129 / 255f, 50 / 255f};
	private static final float TINT = 0.3f;

	/**
	 * HE grenades blow a hole in smoke that fills back in (CS2 keeps recent HE blasts per smoke on its client).
	 * FLAGGED: radius and timing are ours: 450 units (3x the first 150), clear for 1 s, refilled from the
	 * edge by 3 s (user-tuned).
	 */
	private record Blast(Vec3 at, long time) {}

	private static final List<Blast> BLASTS = new ArrayList<>();
	private static final double BLAST_RADIUS = 450, BLAST_CLEAR = 1.0, BLAST_REFILLED = 3.0, BLAST_EDGE = 20;

	/** How much of the smoke at p survives recent HE blasts (0 inside a fresh hole, 1 untouched). */
	private static double blastKeep(Vec3 p, long now) {
		double keep = 1, upb = StrafeConfig.unitsPerBlock;
		List<Blast> blasts;
		synchronized (BLASTS) {
			if (BLASTS.isEmpty()) return 1;
			blasts = new ArrayList<>(BLASTS);
		}
		for (Blast b : blasts) {
			double t = (now - b.time) / 1e9;
			double r = BLAST_RADIUS * (1 - smoothstep(BLAST_CLEAR, BLAST_REFILLED, t)); // the hole shrinks as it refills
			if (r <= 0) continue;
			double d = p.distanceTo(b.at) * upb;
			keep = Math.min(keep, Math.max(0, Math.min(1, (d - r) / BLAST_EDGE + 1)));
		}
		return keep;
	}

	private static final class Fire {
		final Vec3 at;
		final long until;
		final boolean incendiary;
		final String key = "flame" + FLAME_IDS.incrementAndGet();

		Fire(Vec3 at, long until, boolean incendiary) {
			this.at = at;
			this.until = until;
			this.incendiary = incendiary;
		}
	}

	/** A burning fire's centre, for its looping sound (Inferno.Loop is a few seconds long: replayed while it burns). */
	private static final class FireSound {
		final Vec3 at;
		long nextLoop;

		FireSound(Vec3 at) {
			this.at = at;
		}
	}

	private static final java.util.concurrent.atomic.AtomicLong FLAME_IDS = new java.util.concurrent.atomic.AtomicLong();
	private static final List<FireSound> FIRE_SOUNDS = new ArrayList<>();
	private static final double LOOP_EVERY = 2.0;

	private static final List<Smoke> SMOKES = new ArrayList<>();
	private static final List<Fire> FIRES = new ArrayList<>();
	/** CS2's smoke timing: smoothstep in over 0.1..1.5 s, full until 17 s, gone at 22 s. */
	private static final double SMOKE_IN0 = 0.1, SMOKE_IN1 = 1.5, SMOKE_OUT0 = 17, SMOKE_GONE = 22;
	/** CS2's inferno_flame_lifetime (7 s, incendiary 5.5 s); the server sends each flame as it spreads. */
	private static final double FLAME_SECONDS = 7, FLAME_SECONDS_INC = 5.5, FLAME_REACH = 21;
	private static Identifier puffTexture;

	/** The trajectory preview: the path of the grenade being held charged (null = none). */
	private static volatile List<Vec3> preview;

	public static void preview(List<Vec3> path) {
		preview = path;
	}

	/** Flashbang: when the white-out started and how long it lasts (seconds). */
	private static volatile long flashStart;
	private static volatile float flashSeconds, flashHold;

	public static void onEffect(StrafePayloads.GrenadeFx m) {
		Vec3 at = new Vec3(m.x(), m.y(), m.z());
		Vec3 up = new Vec3(0, 1, 0);
		// the flight direction: molotov bursts splash forward (their effects' +X), not towards world east
		// (the effect's +X points back along the throw: its fireballs splash forward through -X)
		Vec3 fwd = Float.isNaN(m.heading()) ? null : new Vec3(-Math.cos(m.heading()), 0, -Math.sin(m.heading()));
		switch (m.kind()) {
			case GrenadeEffects.EV_HE -> {
				synchronized (BLASTS) {
					BLASTS.add(new Blast(at, System.nanoTime()));
				}
				dev.strafemc.client.particle.Effects.at(GrenadeEffects.HE_EFFECT, at, up);
				CsSounds.play("BaseGrenade.Explode", at.x, at.y, at.z, 1.0);
			}
			case GrenadeEffects.EV_FLASH -> {
				dev.strafemc.client.particle.Effects.at(GrenadeEffects.FLASH_EFFECT, at, up);
				CsSounds.play("Flashbang.Explode", at.x, at.y, at.z, 1.0);
			}
			case GrenadeEffects.EV_SMOKE, GrenadeEffects.EV_SMOKE_T -> {
				var level = Minecraft.getInstance().level;
				if (level != null) synchronized (SMOKES) {
					SMOKES.add(new Smoke(dev.strafemc.weapon.SmokeVolume.fill(level, at), System.nanoTime(), m.kind() == GrenadeEffects.EV_SMOKE_T));
				}
				CsSounds.play("BaseSmokeEffect.Sound", at.x, at.y, at.z, 1.0);
			}
			case GrenadeEffects.EV_FLAME, GrenadeEffects.EV_FLAME_INC -> {
				boolean inc = m.kind() == GrenadeEffects.EV_FLAME_INC;
				Fire f = new Fire(at, System.nanoTime() + (long) ((inc ? FLAME_SECONDS_INC : FLAME_SECONDS) * 1e9), inc);
				synchronized (FIRES) {
					FIRES.add(f);
				}
				dev.strafemc.client.particle.Effects.atHeld(f.key, inc ? GrenadeEffects.INCENDIARY_FLAME_EFFECT : GrenadeEffects.MOLOTOV_FLAME_EFFECT, at, up);
			}
			case GrenadeEffects.EV_FIRE, GrenadeEffects.EV_FIRE_INC -> {
				boolean inc = m.kind() == GrenadeEffects.EV_FIRE_INC;
				dev.strafemc.client.particle.Effects.at(inc ? GrenadeEffects.INCENDIARY_GROUND_EFFECT : GrenadeEffects.MOLOTOV_GROUND_EFFECT, at, up, fwd);
				CsSounds.play(inc ? "IncGrenade.Start" : "Molotov.Start", at.x, at.y, at.z, 1.0); // the burst (CS2's server plays these)
				CsSounds.play("Inferno.Fire.Ignite", at.x, at.y, at.z, 1.0);
				synchronized (FIRE_SOUNDS) {
					FIRE_SOUNDS.add(new FireSound(at));
				}
			}
			case GrenadeEffects.EV_FIRE_OUT, GrenadeEffects.EV_FIRE_END -> {
				double range = 160 / StrafeConfig.unitsPerBlock;
				synchronized (FIRES) {
					FIRES.removeIf(f -> {
						if (f.at.distanceToSqr(at) >= range * range) return false;
						dev.strafemc.client.particle.Effects.stopHeld(f.key);
						return true;
					});
				}
				synchronized (FIRE_SOUNDS) {
					FIRE_SOUNDS.removeIf(f -> f.at.distanceToSqr(at) < 0.01);
				}
				if (m.kind() == GrenadeEffects.EV_FIRE_OUT) dev.strafemc.client.particle.Effects.at(GrenadeEffects.EXTINGUISH_EFFECT, at, up);
				CsSounds.play(m.kind() == GrenadeEffects.EV_FIRE_OUT ? "Molotov.Extinguish" : "Inferno.FadeOut", at.x, at.y, at.z, 1.0);
			}
			case GrenadeEffects.EV_FIZZLE, GrenadeEffects.EV_FIZZLE_INC -> {
				dev.strafemc.client.particle.Effects.at(m.kind() == GrenadeEffects.EV_FIZZLE_INC ? GrenadeEffects.INCENDIARY_AIR_EFFECT
						: GrenadeEffects.MOLOTOV_AIR_EFFECT, at, up, fwd);
				// FLAGGED: CS2's "failed to start" sounds for both an air burst and landing in a smoke
				CsSounds.play(m.kind() == GrenadeEffects.EV_FIZZLE_INC ? "IncGrenade.StartFailed" : "Molotov.StartFailed", at.x, at.y, at.z, 1.0);
			}
			case GrenadeEffects.EV_DECOY_SHOT -> dev.strafemc.client.particle.Effects.at(GrenadeEffects.DECOY_SHOT_EFFECT, at, up);
			case GrenadeEffects.EV_DECOY_POP -> CsSounds.play("BaseGrenade.Explode", at.x, at.y, at.z, 0.4);
			default -> {}
		}
	}

	public static void onFlashed(StrafePayloads.Flashed m) {
		flashStart = System.nanoTime();
		flashHold = m.hold();
		flashSeconds = m.fade();
		var p = Minecraft.getInstance().player;
		if (p != null) CsSounds.play(m.hold() > 3 ? "Flashbang.Ring.Long" : m.hold() > 1.5 ? "Flashbang.Ring.Medium" : "Flashbang.Ring.Short",
				p.getX(), p.getEyeY(), p.getZ(), 1.0);
	}

	/**
	 * The white-out, CS2's client curve: with `left` the time left of the flash duration (the server's fade time), fully
	 * white while more than 3 s are left, then (left / 3)^2 over the last 3 s. (CS2 also blends a frozen frame from the
	 * flash moment that fades linearly over the whole duration; not done yet.)
	 */
	public static void registerHud() {
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("strafemc", "grenade_crosshair"),
				(g, delta) -> GrenadeCrosshair.render(g));
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("strafemc", "flashbang"), (g, delta) -> {
			float secs = flashSeconds;
			if (secs <= 0) return;
			float left = secs - (System.nanoTime() - flashStart) / 1e9f;
			float a = left >= 3 ? 1 : left > 0 ? (left / 3) * (left / 3) : 0;
			if (a <= 0) {
				flashSeconds = 0;
				return;
			}
			g.fill(0, 0, g.guiWidth(), g.guiHeight(), (int) (Math.min(1, a) * 255) << 24 | 0xFFFFFF);
		});
	}

	/** Every client tick: flames over burning areas; finished smokes and fires dropped. */
	/** Decoys lying on the ground (entity id) with their sparks running. */
	private static final java.util.Set<Integer> DECOYS = new java.util.HashSet<>();

	/** CS2's sparks under a lying decoy: started when it settles, stopped when it's gone (it pops) or moves again. */
	private static void decoySparks(Minecraft mc) {
		java.util.Set<Integer> now = new java.util.HashSet<>();
		for (var e : mc.level.entitiesForRendering())
			if (e instanceof dev.strafemc.weapon.GrenadeEntity g && g.grenade() == dev.strafemc.weapon.Grenade.DECOY && g.resting()) {
				now.add(g.getId());
				if (DECOYS.add(g.getId()))
					dev.strafemc.client.particle.Effects.atHeld("decoy" + g.getId(), GrenadeEffects.DECOY_GROUND_EFFECT, g.position(), new Vec3(0, 1, 0));
			}
		DECOYS.removeIf(id -> {
			if (now.contains(id)) return false;
			dev.strafemc.client.particle.Effects.stopHeld("decoy" + id);
			return true;
		});
	}

	public static void tick(Minecraft mc) {
		if (mc.level == null || mc.isPaused()) return;
		decoySparks(mc);
		long now = System.nanoTime();
		double r = FLAME_REACH / StrafeConfig.unitsPerBlock;
		Random rnd = new Random();
		synchronized (FIRE_SOUNDS) {
			for (FireSound fs : FIRE_SOUNDS)
				if (now >= fs.nextLoop) {
					CsSounds.play("Inferno.Loop", fs.at.x, fs.at.y, fs.at.z, 1.0);
					fs.nextLoop = now + (long) (LOOP_EVERY * 1e9);
				}
		}
		synchronized (FIRES) {
			FIRES.removeIf(f -> {
				if (now <= f.until) return false;
				dev.strafemc.client.particle.Effects.stopHeld(f.key);
				return true;
			});
			if (dev.strafemc.client.Placeholders.active()) // no CS2 effects: Minecraft flames instead
				for (Fire f : FIRES)
					for (int i = 0; i < 2; i++) {
						double a = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble()) * r;
						double x = f.at.x + Math.cos(a) * d, z = f.at.z + Math.sin(a) * d;
						mc.level.addParticle(i % 4 == 3 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.FLAME, x, f.at.y + 0.05, z, 0, 0.03 + rnd.nextDouble() * 0.04, 0);
					}
		}
		synchronized (SMOKES) {
			SMOKES.removeIf(s -> (now - s.start) / 1e9 > SMOKE_GONE);
		}
		synchronized (BLASTS) {
			BLASTS.removeIf(b -> (now - b.time) / 1e9 > BLAST_REFILLED);
		}
	}

	/** CS2's smoothstep: 0 at e0, 1 at e1 (either order), smooth in between. */
	private static double smoothstep(double e0, double e1, double x) {
		double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
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
	}

	/**
	 * Smoke, drawn last in the main pass (END_MAIN) so the order is fixed: every puff back to front with normal blending
	 * (overlapping puffs build up to opaque), then the thick inside into the depth buffer, so clouds (their own later
	 * pass) don't draw through it. Drawn among the other submits, the depth pass could land before puffs behind it and
	 * hide them, leaving only the nearest puff of a smoke visible.
	 */
	public static void renderSmoke(LevelRenderContext ctx) {
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
			double t = (now - s.start) / 1e9, fade = smoothstep(SMOKE_GONE, SMOKE_OUT0, t);
			var v = s.volume;
			float[] tint = s.t ? T_TINT : CT_TINT;
			double vs = dev.strafemc.weapon.SmokeVolume.VOXEL / StrafeConfig.unitsPerBlock;
			for (int n = 0; n < v.cells.length; n++) {
				// grows out from the grenade in flood order over CS2's 0.1..1.5 s fade-in (FLAGGED: the order is ours)
				double at0 = SMOKE_IN0 + (SMOKE_IN1 - SMOKE_IN0) * v.steps[n] / Math.max(1, v.maxStep);
				double appear = smoothstep(at0, at0 + 0.35, t);
				if (appear <= 0) continue;
				int cell = v.cells[n];
				Vec3 p = v.center(cell);
				double dens = v.density(p) * blastKeep(p, now);
				if (dens <= 0) continue;
				int h = cell * 0x9E3779B1; // a fixed per-voxel wobble so the puffs don't sit on a grid
				float jx = ((h >>> 8 & 255) / 255f - 0.5f) * 0.3f, jy = ((h >>> 16 & 255) / 255f - 0.5f) * 0.3f, jz = ((h >>> 24) / 255f - 0.5f) * 0.3f;
				float x = (float) (p.x + jx * vs - c.x), y = (float) (p.y + jy * vs - c.y), z = (float) (p.z + jz * vs - c.z);
				float alpha = (float) (0.55 * dens * appear * fade);
				int cx = cell % dev.strafemc.weapon.SmokeVolume.N, cy = cell / dev.strafemc.weapon.SmokeVolume.N % dev.strafemc.weapon.SmokeVolume.N,
						cz = cell / (dev.strafemc.weapon.SmokeVolume.N * dev.strafemc.weapon.SmokeVolume.N);
				boolean inner = v.filled(cx + 1, cy, cz) && v.filled(cx - 1, cy, cz) && v.filled(cx, cy + 1, cz) && v.filled(cx, cy - 1, cz)
						&& v.filled(cx, cy, cz + 1) && v.filled(cx, cy, cz - 1);
				float depthAlpha = inner && dens > 0.9 ? (float) (appear * fade) : 0; // only the thick inside hides what's behind
				int light = LevelRenderer.getLightCoords(mc.level, BlockPos.containing(p));
				float shade = 0.64f + ((h >>> 4 & 7) - 3.5f) * 0.012f;
				float r = shade * (1 - TINT) + tint[0] * TINT, g = shade * (1 - TINT) + tint[1] * TINT, b = shade * (1 - TINT) + tint[2] * TINT;
				// edge voxels (next to a wall or the cloud's end) get small puffs: big ones would hang through the wall
				float size = (float) (vs * (inner ? 1.25 : 0.6));
				if (!inner) {
					x -= jx * vs;
					y -= jy * vs;
					z -= jz * vs;
				}
				quads.add(new float[] {x, y, z, size, r, alpha, Float.intBitsToFloat(light), g, b, depthAlpha});
			}
		}
		quads.sort(Comparator.comparingDouble((float[] q) -> -(q[0] * q[0] + q[1] * q[1] + q[2] * q[2])));
		var buffers = ctx.bufferSource();
		PoseStack.Pose pose = ctx.poseStack().last();
		float[][] corners = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};
		for (int pass = 0; pass < 2; pass++) {
			net.minecraft.client.renderer.rendertype.RenderType type = pass == 0 ? dev.strafemc.client.particle.ParticleBlend.smoke(puff())
					: dev.strafemc.client.particle.ParticleBlend.depthOnly(puff());
			com.mojang.blaze3d.vertex.VertexConsumer vc = buffers.getBuffer(type);
			for (float[] q : quads) {
				float a = pass == 1 ? q[9] : q[5];
				if (a <= 0) continue;
				int light = Float.floatToRawIntBits(q[6]);
				int col = (int) (a * 255) << 24 | (int) (q[4] * 255) << 16 | (int) (q[7] * 255) << 8 | (int) (q[8] * 255);
				float sz = q[3];
				for (float[] k : corners)
					vc.addVertex(pose, q[0] + (right.x * k[0] + up.x * k[1]) * sz, q[1] + (right.y * k[0] + up.y * k[1]) * sz,
									q[2] + (right.z * k[0] + up.z * k[1]) * sz)
							.setColor(col).setUv(k[2], k[3]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0, 1, 0);
			}
			buffers.endBatch(type);
		}
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
		DECOYS.clear();
		synchronized (FIRE_SOUNDS) {
			FIRE_SOUNDS.clear();
		}
		synchronized (SMOKES) {
			SMOKES.clear();
		}
		synchronized (FIRES) {
			FIRES.clear();
		}
		flashSeconds = 0;
	}
}
