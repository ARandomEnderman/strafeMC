package dev.strafemc.client.particle;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws Source 2 particle systems with Minecraft's submit API: C_OP_RenderSprites (camera-facing quads with sprite-sheet
 * frames) and C_OP_RenderTrails (ribbons along the motion, length = trail length x speed). Geometry follows VRF's
 * renderers (MIT). Additive blend modes use Minecraft's additive "eyes" type (colour premultiplied by alpha), alpha blend
 * uses translucent emissive; particles are full-bright like CS2's unlit spritecards.
 */
public final class EffectRenderer {
	private static final int FULL_BRIGHT = 0xF000F0;
	private static final Map<Map<String, Object>, Info> INFOS = new IdentityHashMap<>();

	private EffectRenderer() {}

	/** Parsed renderer definition. */
	static final class Info {
		final boolean sprites, trails, additive, bloomOnly, worldZ, cpNormal;
		final String texture;
		final float animationRate, lengthScale, minLength, maxLength, lengthFadeIn, constrainRatio, forwardShift;
		final String animationType;
		final boolean animateInFps;
		final Num radiusScale, alphaScale, overbright, headTaper, tailTaper, headAlpha, tailAlpha, minSize, maxSize;
		final Vec colorScale, headColor, tailColor;
		final int prevSource;

		Info(Pd p) {
			String cls = p.str("_class", "");
			sprites = cls.equals("C_OP_RenderSprites");
			trails = cls.equals("C_OP_RenderTrails");
			String blend = p.str("m_nOutputBlendMode", "PARTICLE_OUTPUT_BLEND_MODE_ALPHA");
			additive = blend.contains("ADD") || blend.contains("LIGHTEN");
			bloomOnly = p.b("m_bOnlyRenderInEffectsBloomPass", false); // drawn only into the bloom buffer: not a visible layer
			String orient = p.str("m_nOrientationType", "PARTICLE_ORIENTATION_SCREEN_ALIGNED");
			worldZ = orient.equals("PARTICLE_ORIENTATION_WORLD_Z_ALIGNED");
			// particle normals aren't simulated (C_INIT_NormalAlignToCP missing): impact layers align to CP0's normal (flagged)
			cpNormal = orient.equals("PARTICLE_ORIENTATION_ALIGN_TO_PARTICLE_NORMAL");
			String tex = null;
			for (Map<String, Object> t : p.array("m_vecTexturesInput")) {
				Pd tp = p.nested(t);
				if (!tp.b("m_bEnabled", true)) continue;
				tex = tp.str("m_hTexture", null);
				// alpha-only channel (smoke: shape in A, colour from a second zoomed texture): use the shape with white RGB
				if (tex != null && tp.str("m_nTextureChannels", "").equals("SPRITECARD_TEXTURE_CHANNEL_MIX_A")) tex = tex + ParticleTextures.ALPHA_ONLY;
				if (tex != null) break;
			}
			if (tex == null) tex = p.str("m_hTexture", null);
			texture = tex;
			animationRate = p.f("m_flAnimationRate", 0.1f);
			animationType = p.behaviorVersion >= 10 || trails ? p.str("m_nAnimationType", "ANIMATION_TYPE_FIXED_RATE") : "ANIMATION_TYPE_FIXED_RATE";
			animateInFps = p.b("m_bAnimateInFPS", false);
			radiusScale = p.num("m_flRadiusScale", new Num.Literal(1));
			alphaScale = p.num("m_flAlphaScale", new Num.Literal(1));
			overbright = p.num("m_flOverbrightFactor", new Num.Literal(1));
			colorScale = p.vecInput("m_vecColorScale", Vec.literal(new Vector3f(1)));
			lengthScale = p.f("m_flLengthScale", 1);
			minLength = p.f("m_flMinLength", 0);
			maxLength = p.f("m_flMaxLength", 2000);
			lengthFadeIn = p.f("m_flLengthFadeInTime", 0);
			constrainRatio = p.f("m_flConstrainRadiusToLengthRatio", 1);
			forwardShift = p.f("m_flForwardShift", 0);
			headTaper = p.num("m_flRadiusHeadTaper", new Num.Literal(1));
			tailTaper = p.num("m_flRadiusTaper", new Num.Literal(1));
			headAlpha = p.num("m_flHeadAlphaScale", new Num.Literal(1));
			tailAlpha = p.num("m_flTailAlphaScale", new Num.Literal(1));
			headColor = p.vecInput("m_vecHeadColorScale", Vec.literal(new Vector3f(1)));
			tailColor = p.vecInput("m_vecTailColorScale", Vec.literal(new Vector3f(1)));
			prevSource = p.field("m_nPrevPntSource", Particle.POSITION_PREVIOUS);
			// screen-relative size limits (x camera distance): sprites 0..5000, trails 0..2000 (VRF defaults)
			minSize = p.num("m_flMinSize", new Num.Literal(0));
			maxSize = p.num("m_flMaxSize", new Num.Literal(sprites ? 5000 : 2000));
		}
	}

	private static Info info(PSystem s, Map<String, Object> def) {
		return INFOS.computeIfAbsent(def, d -> new Info(new Pd(d, s.behaviorVersion)));
	}

	/**
	 * Submits a system tree. toRender maps simulation space (Source units/axes) to the pose's space; camRender is the camera
	 * position and camRight/camUp the screen axes, all in render space.
	 */
	public static void submit(PSystem root, Matrix4f toRender, Vector3f camRender, Vector3f camRight, Vector3f camUp, PoseStack ps,
							  SubmitNodeCollector out, java.util.function.Supplier<Matrix4f> drawPose) {
		List<PSystem> all = new ArrayList<>();
		collect(root, all);
		float unit = toRender.getScale(new Vector3f()).x;
		for (PSystem s : all) {
			if (s.count() == 0 || Effects.hidden(s.name)) continue;
			for (Map<String, Object> def : s.renderers) {
				Info in = info(s, def);
				if (!in.sprites && !in.trails || in.bloomOnly) continue;
				ParticleTextures.Tex tex = ParticleTextures.get(in.texture);
				if (tex == null) continue;
				RenderType type = in.additive ? RenderTypes.eyes(tex.id()) : RenderTypes.entityTranslucentEmissive(tex.id());
				List<float[]> quads = new ArrayList<>(); // 4 corners x (x,y,z,u,v,r,g,b,a)
				for (int i = 0; i < s.count(); i++) {
					Particle p = s.particle(i);
					if (in.sprites) sprite(s, in, tex, p, toRender, unit, camRender, camRight, camUp, quads);
					else trail(s, in, tex, p, toRender, unit, camRender, quads);
				}
				if (quads.isEmpty()) continue;
				boolean add = in.additive;
				// m_flOverbrightFactor > 1 (muzzle flash 3-4): vertex colours cap at 1, so additive layers are drawn that many times
				int passes = add ? Math.max(1, Math.min(8, (int) Math.ceil(in.overbright.next(s.state)))) : 1;
				if (passes > 1) for (float[] q : quads) for (int c = 0; c < 4; c++) for (int k = 5; k < 8; k++) q[c * 9 + k] /= passes;
				out.submitCustomGeometry(ps, type, (given, vc) -> {
					PoseStack.Pose pose = given;
					if (drawPose != null) { // viewmodel space: the pose is only known when the features are drawn
						pose = new PoseStack.Pose();
						pose.pose().set(drawPose.get());
						pose.normal().set(pose.pose()).invert().transpose();
					}
					for (int k = 0; k < passes; k++)
						for (float[] q : quads)
							for (int c = 0; c < 4; c++) vertex(vc, pose, q, c * 9, add);
				});
			}
		}
	}

	private static void collect(PSystem s, List<PSystem> out) {
		out.add(s);
		for (PSystem c : s.children) if (c.childEnabled) collect(c, out);
	}

	private static void vertex(VertexConsumer vc, PoseStack.Pose pose, float[] q, int o, boolean additive) {
		float a = Math.max(0, Math.min(1, q[o + 8]));
		float r = Math.max(0, Math.min(1, q[o + 5])), g = Math.max(0, Math.min(1, q[o + 6])), b = Math.max(0, Math.min(1, q[o + 7]));
		if (additive) { // ONE, ONE blending: premultiply
			r *= a;
			g *= a;
			b *= a;
		}
		int argb = (int) (a * 255) << 24 | (int) (r * 255) << 16 | (int) (g * 255) << 8 | (int) (b * 255);
		vc.addVertex(pose, q[o], q[o + 1], q[o + 2]).setColor(argb).setUv(q[o + 3], q[o + 4]).setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(FULL_BRIGHT).setNormal(pose, 0, 1, 0);
	}

	/** Sheet frame rect (u0, v0, u1, v1) for a particle, as VRF's GetSheetFrame. */
	private static float[] frame(Info in, ParticleTextures.Tex tex, Particle p) {
		if (tex.sequences().isEmpty()) return new float[] {0, 0, 1, 1};
		int si = p.sequence >= 0 && p.sequence < tex.sequences().size() ? p.sequence : 0;
		ParticleTextures.Sequence seq = tex.sequences().get(si);
		if (seq.frames().isEmpty()) return new float[] {0, 0, 1, 1};
		int idx = 0;
		if (seq.frames().size() >= 2) {
			float total = seq.totalTime() > 0 ? seq.totalTime() : 1;
			float time = in.animationType.equals("ANIMATION_TYPE_FIT_LIFETIME") ? p.normalizedAge() : p.age;
			float passes = in.animationType.equals("ANIMATION_TYPE_MANUAL_FRAMES") ? p.manualFrame
					: in.animateInFps ? time * in.animationRate / total : time * in.animationRate;
			float pos = total * (seq.clamp() ? PMath.saturate(passes) : passes - (float) Math.floor(passes));
			idx = seq.frameAt(pos);
		}
		ParticleTextures.Frame f = seq.frames().get(idx);
		return new float[] {f.u0(), f.v0(), f.u1(), f.v1()};
	}

	private static void sprite(PSystem s, Info in, ParticleTextures.Tex tex, Particle p, Matrix4f m, float unit, Vector3f cam, Vector3f right, Vector3f up,
							   List<float[]> quads) {
		float half = p.radius * in.radiusScale.next(p, s.state) * unit;
		if (half <= 0) return;
		Vector3f c = m.transformPosition(new Vector3f(p.position));
		float dist = c.distance(cam);
		half = Math.min(Math.max(half, in.minSize.next(p, s.state) * dist), in.maxSize.next(p, s.state) * dist);
		float roll = p.rotation.z, cos = (float) Math.cos(roll), sin = (float) Math.sin(roll);
		if (in.worldZ || in.cpNormal) { // flat in a plane: world XY, or the impact surface (CP0's local XY)
			org.joml.Quaternionf q = in.cpNormal ? s.controlPoint(0).rotationOrIdentity() : new org.joml.Quaternionf();
			right = m.transformDirection(q.transform(new Vector3f(1, 0, 0))).normalize();
			up = m.transformDirection(q.transform(new Vector3f(0, 1, 0))).normalize();
		}
		Vector3f ax = new Vector3f(right).mul(cos).add(new Vector3f(up).mul(sin)).mul(half);
		Vector3f ay = new Vector3f(up).mul(cos).sub(new Vector3f(right).mul(sin)).mul(half);
		float[] uv = frame(in, tex, p);
		Vector3f col = new Vector3f(p.color).mul(in.colorScale.next(p, s.state)).mul(in.overbright.next(p, s.state));
		float a = p.alpha * in.alphaScale.next(p, s.state);
		float[] q = new float[36];
		corner(q, 0, new Vector3f(c).sub(ax).sub(ay), uv[0], uv[3], col, a);
		corner(q, 9, new Vector3f(c).add(ax).sub(ay), uv[2], uv[3], col, a);
		corner(q, 18, new Vector3f(c).add(ax).add(ay), uv[2], uv[1], col, a);
		corner(q, 27, new Vector3f(c).sub(ax).add(ay), uv[0], uv[1], col, a);
		quads.add(q);
	}

	private static void trail(PSystem s, Info in, ParticleTextures.Tex tex, Particle p, Matrix4f m, float unit, Vector3f cam, List<float[]> quads) {
		Vector3f prev = p.vector(in.prevSource);
		Vector3f diff = new Vector3f(prev).sub(p.position);
		if (diff.lengthSquared() == 0) return;
		float dt = s.frameTime() > 0 ? s.frameTime() : 1 / 64f;
		float len = in.lengthScale * p.trailLength * diff.length() / dt;
		len *= Math.min(1, p.age / Math.max(in.lengthFadeIn, 1e-9f));
		len = Math.max(in.minLength, Math.min(in.maxLength, len));
		if (len == 0) return;
		float radius = Math.min(p.radius * in.radiusScale.next(p, s.state), in.constrainRatio * len);
		// render space
		Vector3f pos = m.transformPosition(new Vector3f(p.position));
		Vector3f dir = m.transformDirection(new Vector3f(diff)).normalize();
		float lenR = len * unit, rR = radius * unit;
		Vector3f planeN = new Vector3f(cam).sub(pos);
		Vector3f width = new Vector3f(dir).cross(planeN);
		if (width.lengthSquared() < 1e-12f) width = new Vector3f(dir).cross(Math.abs(dir.y) < 0.999f ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0));
		width.normalize();
		Vector3f center = new Vector3f(pos).add(new Vector3f(dir).mul(lenR * (0.5f - in.forwardShift)));
		Vector3f head = new Vector3f(center).sub(new Vector3f(dir).mul(lenR * 0.5f)), tail = new Vector3f(center).add(new Vector3f(dir).mul(lenR * 0.5f));
		float hw = rR * in.headTaper.next(p, s.state), tw = rR * in.tailTaper.next(p, s.state);
		float[] uv = frame(in, tex, p);
		Vector3f tint = new Vector3f(p.color).mul(in.colorScale.next(p, s.state)).mul(in.overbright.next(p, s.state));
		float a = p.alpha * p.alphaAlternate * in.alphaScale.next(p, s.state);
		Vector3f hc = new Vector3f(tint).mul(in.headColor.next(p, s.state)), tc = new Vector3f(tint).mul(in.tailColor.next(p, s.state));
		float ha = a * in.headAlpha.next(p, s.state), ta = a * in.tailAlpha.next(p, s.state);
		float[] q = new float[36];
		corner(q, 0, new Vector3f(head).sub(new Vector3f(width).mul(hw)), uv[0], uv[1], hc, ha);
		corner(q, 9, new Vector3f(head).add(new Vector3f(width).mul(hw)), uv[2], uv[1], hc, ha);
		corner(q, 18, new Vector3f(tail).add(new Vector3f(width).mul(tw)), uv[2], uv[3], tc, ta);
		corner(q, 27, new Vector3f(tail).sub(new Vector3f(width).mul(tw)), uv[0], uv[3], tc, ta);
		quads.add(q);
	}

	private static void corner(float[] q, int o, Vector3f p, float u, float v, Vector3f c, float a) {
		q[o] = p.x;
		q[o + 1] = p.y;
		q[o + 2] = p.z;
		q[o + 3] = u;
		q[o + 4] = v;
		q[o + 5] = c.x;
		q[o + 6] = c.y;
		q[o + 7] = c.z;
		q[o + 8] = a;
	}
}
