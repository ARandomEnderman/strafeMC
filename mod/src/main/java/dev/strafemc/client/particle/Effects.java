package dev.strafemc.client.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.strafemc.StrafeMc;
import dev.strafemc.config.StrafeConfig;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Live CS2 particle effects. Two simulation spaces, both in Source units/axes (x forward, y left, z up):
 * VIEW (attached to the camera; viewmodel effects like the muzzle flash, drawn in the hand pass) and WORLD
 * (Minecraft world mapped to x = east, y = north, z = up; tracers and impacts, drawn with the level).
 */
public final class Effects {
	private static final class Instance {
		final PSystem sys;
		final String attachment; // VIEW effects follow this viewmodel attachment
		double age;

		Instance(PSystem sys, String attachment) {
			this.sys = sys;
			this.attachment = attachment;
		}
	}

	private static final List<Instance> VIEW = new ArrayList<>(), WORLD = new ArrayList<>();
	private static final double MAX_AGE = 5;
	private static long lastView, lastWorld;

	private Effects() {}

	private static float u() {
		return (float) StrafeConfig.unitsPerBlock;
	}

	// ------------------------------------------------------------------ spawning

	/** A viewmodel effect (e.g. the muzzle flash) at a weapon attachment. */
	public static void view(String vpcf, String attachment) {
		PSystem s = load(vpcf);
		if (s == null) return;
		synchronized (VIEW) {
			VIEW.add(new Instance(s, attachment));
		}
	}

	/** A world effect between two points (tracers: CP0 start, CP1 end). */
	public static void tracer(String vpcf, Vec3 from, Vec3 to) {
		PSystem s = load(vpcf);
		if (s == null) return;
		Vector3f a = toSim(from), b = toSim(to);
		Quaternionf rot = PMath.forwardToRotation(new Vector3f(b).sub(a));
		s.controlPoint(0).set(a, rot, s.state);
		s.controlPoint(1).set(b, rot, s.state);
		synchronized (WORLD) {
			WORLD.add(new Instance(s, null));
		}
	}

	/**
	 * A world effect at a point on a surface. CS2's impact effects launch along their control point's local +Z
	 * (m_LocalCoordinateSystemSpeed [0, 0, 40..140]), so the control point's Z axis is the surface normal.
	 */
	public static void at(String vpcf, Vec3 pos, Vec3 normal) {
		at(vpcf, pos, normal, null);
	}

	/** tint: the hit material's colour (0..1), passed in control point 4 as CS2's impact effects read it (chips take its colour). */
	public static void at(String vpcf, Vec3 pos, Vec3 normal, Vector3f tint) {
		PSystem s = load(vpcf);
		if (s == null) return;
		Vector3f z = new Vector3f((float) normal.x, (float) -normal.z, (float) normal.y).normalize();
		Vector3f ref = Math.abs(z.z) > 0.9f ? new Vector3f(1, 0, 0) : new Vector3f(0, 0, 1);
		Vector3f x = new Vector3f(ref).sub(new Vector3f(z).mul(ref.dot(z))).normalize(), y = new Vector3f(z).cross(x);
		Quaternionf rot = new Quaternionf().setFromNormalized(new Matrix3f(x, y, z));
		Vector3f at = toSim(pos);
		s.controlPoint(0).set(at, rot, s.state);
		s.controlPoint(1).set(at, rot, s.state); // second point of impact effects: the hit point too (flagged)
		if (tint != null) s.controlPoint(4).set(tint, null, s.state);
		synchronized (WORLD) {
			WORLD.add(new Instance(s, null));
		}
	}

	private static final java.util.Set<String> READY = java.util.concurrent.ConcurrentHashMap.newKeySet(),
			WARMING = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** Loads an effect's definition tree in the background (VRF runs per file); spawning waits until it's cached. */
	public static void warm(String vpcf) {
		if (vpcf == null || vpcf.isEmpty() || READY.contains(vpcf) || !WARMING.add(vpcf)) return;
		Thread t = new Thread(() -> {
			try {
				PSystem s = PSystem.load(vpcf);
				if (s != null) {
					prefetchTextures(s);
					READY.add(vpcf);
				}
			} catch (Exception e) {
				StrafeMc.LOG.warn("[strafemc] effect {} failed: {}", vpcf, e.toString());
			}
		}, "strafemc-effect");
		t.setDaemon(true);
		t.start();
	}

	private static void prefetchTextures(PSystem s) {
		for (java.util.Map<String, Object> r : s.renderers) {
			Object t = r.get("m_hTexture");
			if (r.get("m_vecTexturesInput") instanceof java.util.List<?> l)
				for (Object o : l) if (o instanceof java.util.Map<?, ?> m && m.get("m_hTexture") != null)
					ParticleTextures.prefetch(m.get("m_hTexture") + ("SPRITECARD_TEXTURE_CHANNEL_MIX_A".equals(String.valueOf(m.get("m_nTextureChannels"))) ? ParticleTextures.ALPHA_ONLY : ""));
			if (t != null) ParticleTextures.prefetch(t.toString());
		}
		for (PSystem c : s.children) prefetchTextures(c);
	}

	private static PSystem load(String vpcf) {
		if (vpcf == null || vpcf.isEmpty()) return null;
		if (!READY.contains(vpcf)) { // never block the game on VRF: the first use only starts loading
			warm(vpcf);
			return null;
		}
		try {
			return PSystem.load(vpcf);
		} catch (Exception e) {
			StrafeMc.LOG.warn("[strafemc] effect {} failed: {}", vpcf, e.toString());
			return null;
		}
	}

	/** Minecraft world (blocks) to WORLD simulation space (Source units, z up). */
	static Vector3f toSim(Vec3 p) {
		float u = u();
		return new Vector3f((float) p.x * u, (float) -p.z * u, (float) p.y * u);
	}

	// ------------------------------------------------------------------ view effects

	/** Simulation VIEW space -> camera space (blocks): cam = (-y, z, -x) / u. */
	private static Matrix4f viewToCamera() {
		float k = 1 / u();
		return new Matrix4f(0, 0, -k, 0, -k, 0, 0, 0, 0, k, 0, 0, 0, 0, 0, 1);
	}

	/** Called from the viewmodel's hand-pass render with its camera pose (draw-time pose = inverse(model-view) * cam). */
	public static void renderView(PoseStack ps, SubmitNodeCollector out, Matrix4f cam) {
		long now = System.nanoTime();
		float dt = lastView == 0 ? 0 : Math.min(0.1f, (now - lastView) / 1e9f);
		lastView = now;
		Matrix4f toCam = viewToCamera(), fromCam = new Matrix4f(toCam).invert();
		synchronized (VIEW) {
			for (Iterator<Instance> it = VIEW.iterator(); it.hasNext(); ) {
				Instance in = it.next();
				Matrix4f a = dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.attachmentTransforms.get(in.attachment);
				if (a != null) {
					Vector3f pos = fromCam.transformPosition(a.getTranslation(new Vector3f()));
					Vector3f fwd = fromCam.transformDirection(a.transformDirection(new Vector3f(1, 0, 0))).normalize();
					Vector3f left = fromCam.transformDirection(a.transformDirection(new Vector3f(0, 1, 0))).normalize();
					Vector3f up = new Vector3f(fwd).cross(left).normalize();
					left = new Vector3f(up).cross(fwd).normalize();
					in.sys.controlPoint(0).set(pos, new Quaternionf().setFromNormalized(new Matrix3f(fwd, left, up)), in.sys.state);
				}
				in.sys.update(dt);
				in.age += dt;
				if (in.age > MAX_AGE || (in.age > 0.05 && in.sys.finished())) it.remove();
			}
			for (Instance in : VIEW)
				EffectRenderer.submit(in.sys, toCam, new Vector3f(), new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), ps, out,
						() -> new Matrix4f(RenderSystem.getModelViewMatrix()).invert().mul(cam));
		}
	}

	// ------------------------------------------------------------------ world effects

	public static void renderWorld(LevelRenderContext ctx) {
		long now = System.nanoTime();
		float dt = lastWorld == 0 ? 0 : Math.min(0.1f, (now - lastWorld) / 1e9f);
		lastWorld = now;
		synchronized (WORLD) {
			if (WORLD.isEmpty()) return;
			Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
			Vec3 c = camera.position();
			float k = 1 / u();
			// WORLD sim -> camera-relative world (blocks): (x/u - cx, z/u - cy, -y/u - cz)
			Matrix4f toRender = new Matrix4f(k, 0, 0, 0, 0, 0, -k, 0, 0, k, 0, 0, (float) -c.x, (float) -c.y, (float) -c.z, 1);
			Vector3f look = new Vector3f(camera.forwardVector()), up = new Vector3f(camera.upVector()), right = new Vector3f(look).cross(up).normalize();
			for (Iterator<Instance> it = WORLD.iterator(); it.hasNext(); ) {
				Instance in = it.next();
				in.sys.setCameraPosition(toSim(c));
				in.sys.controlPoint(5).set(toSim(c), null, in.sys.state); // detail-by-distance input of impact effects: the viewer (flagged)
				in.sys.update(dt);
				in.age += dt;
				if (in.age > MAX_AGE || (in.age > 0.05 && in.sys.finished())) it.remove();
			}
			for (Instance in : WORLD) EffectRenderer.submit(in.sys, toRender, new Vector3f(), right, up, ctx.poseStack(), ctx.submitNodeCollector(), null);
		}
	}

	public static void clear() {
		synchronized (VIEW) {
			VIEW.clear();
		}
		synchronized (WORLD) {
			WORLD.clear();
		}
	}
}
