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
 * Client side of grenades going off: CS2's effects and sounds, the flashbang white-out, smoke and fire patches. Smoke is
 * CS2's simulated voxel volume (weapon.SmokeVolume, stepped by client.smoke.SmokeVolumes) drawn by
 * client.smoke.SmokeRenderer; fire is CS2's ground fire effect, shaped by InfernoClient from the server's flames.
 */
public final class GrenadeClient {
	private GrenadeClient() {}

	/** A burning fire's centre, for its looping sound (Inferno.Loop is a few seconds long: replayed while it burns). */
	private static final class FireSound {
		final Vec3 at;
		long nextLoop;

		FireSound(Vec3 at) {
			this.at = at;
		}
	}

	private static final List<FireSound> FIRE_SOUNDS = new ArrayList<>();
	private static final double LOOP_EVERY = 2.0;

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
				dev.strafemc.client.smoke.SmokeVolumes.blast(at);
				dev.strafemc.client.particle.Effects.at(GrenadeEffects.HE_EFFECT, at, up);
				CsSounds.play("BaseGrenade.Explode", at.x, at.y, at.z, 1.0);
			}
			case GrenadeEffects.EV_C4 -> {
				dev.strafemc.client.particle.Effects.at(GrenadeEffects.C4_EFFECT, at, up);
				CsSounds.play("c4.explode", at.x, at.y, at.z, 1.0);
			}
			case GrenadeEffects.EV_C4_BEEP, GrenadeEffects.EV_C4_BEEP_B, GrenadeEffects.EV_C4_BEEP_10, GrenadeEffects.EV_C4_BEEP_B_10 -> {
				var bomb = dev.strafemc.client.viewmodel.PlantedC4Renderer.bombAt(at);
				dev.strafemc.client.particle.Effects.at(GrenadeEffects.C4_LIGHT_EFFECT, dev.strafemc.client.viewmodel.PlantedC4Renderer.ledAt(bomb, at), up);
				boolean b = m.kind() == GrenadeEffects.EV_C4_BEEP_B || m.kind() == GrenadeEffects.EV_C4_BEEP_B_10;
				boolean last11 = m.kind() == GrenadeEffects.EV_C4_BEEP_10 || m.kind() == GrenadeEffects.EV_C4_BEEP_B_10;
				// CS2: heard further as the time runs out (public.distance_multiplier), the _10sec beep on top near the end
				double reach = Math.min(1, 0.2 + 0.8 * (bomb == null ? 1 : Math.max(0, Math.min(1, bomb.fractionLeft()))));
				CsSounds.playScaled("C4.PlantSound" + (b ? "B" : ""), at.x, at.y, at.z, 1.0, reach);
				if (last11) CsSounds.playScaled("C4.PlantSound" + (b ? "B" : "") + "_10sec", at.x, at.y, at.z, 1.0, reach);
				dev.strafemc.client.viewmodel.PlantedC4Renderer.beeped(at); // its LED blinks
			}
			case GrenadeEffects.EV_C4_TRIP -> {
				var bomb = dev.strafemc.client.viewmodel.PlantedC4Renderer.bombAt(at);
				dev.strafemc.client.particle.Effects.at(GrenadeEffects.C4_TRIGGER_EFFECT, dev.strafemc.client.viewmodel.PlantedC4Renderer.ledAt(bomb, at), up);
				CsSounds.play("C4.ExplodeTriggerTrip", at.x, at.y, at.z, 1.0);
			}
			case GrenadeEffects.EV_FLASH -> {
				dev.strafemc.client.smoke.SmokeVolumes.flash(at);
				dev.strafemc.client.particle.Effects.at(GrenadeEffects.FLASH_EFFECT, at, up);
				CsSounds.play("Flashbang.Explode", at.x, at.y, at.z, 1.0);
			}
			case GrenadeEffects.EV_SMOKE, GrenadeEffects.EV_SMOKE_T -> {
				var level = Minecraft.getInstance().level;
				if (level != null) dev.strafemc.client.smoke.SmokeVolumes.add(dev.strafemc.weapon.SmokeVolume.create(level, at), m.kind() == GrenadeEffects.EV_SMOKE_T);
				CsSounds.play("BaseSmokeEffect.Sound", at.x, at.y, at.z, 1.0);
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
		Random rnd = new Random();
		synchronized (FIRE_SOUNDS) {
			for (FireSound fs : FIRE_SOUNDS)
				if (now >= fs.nextLoop) {
					CsSounds.play("Inferno.Loop", fs.at.x, fs.at.y, fs.at.z, 1.0);
					fs.nextLoop = now + (long) (LOOP_EVERY * 1e9);
				}
		}
		InfernoClient.tick();
		if (dev.strafemc.client.Placeholders.active()) // no CS2 effects: Minecraft flames over the fires instead
			for (Vec3 at : InfernoClient.flames()) {
				double a = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble()) * 1.2;
				mc.level.addParticle(rnd.nextInt(4) == 3 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.FLAME, at.x + Math.cos(a) * d, at.y + 0.05,
						at.z + Math.sin(a) * d, 0, 0.03 + rnd.nextDouble() * 0.04, 0);
			}
		dev.strafemc.client.smoke.SmokeVolumes.tick();
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
		dev.strafemc.client.smoke.SmokeVolumes.clear();
		InfernoClient.clear();
		flashSeconds = 0;
	}
}
