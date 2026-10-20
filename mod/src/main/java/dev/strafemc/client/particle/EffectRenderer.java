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
 * renderers (MIT). Additive blend modes use our own SRC_ALPHA, ONE type (ParticleBlend), alpha blend
 * uses translucent emissive; particles are full-bright like CS2's unlit spritecards.
 */
public final class EffectRenderer {
	private static final int FULL_BRIGHT = 0xF000F0;
	private static final Map<Map<String, Object>, Info> INFOS = new IdentityHashMap<>();

	private EffectRenderer() {}

	/** Parsed renderer definition. */
	static final class Info {
		final boolean sprites, trails, projected, orientToNormal, additive, bloomOnly, waterOnly, worldZ, cpNormal, refract;
		final String texture, material;
		/** The texture's colour is looked up in a colour gradient (baked into the texture). */
		final boolean graded;
		final float animationRate, lengthScale, minLength, maxLength, lengthFadeIn, constrainRatio, forwardShift;
		final String animationType;
		final boolean animateInFps;
		/** CS2's lighting of alpha-blended layers, as one gain (1: drawn as is); subtracted breakup textures' blend amounts. */
		final Num selfIllum, diffuse, desaturation;
		final boolean lit;
		final List<Num> erode = new ArrayList<>();
		final Num radiusScale, alphaScale, overbright, headTaper, tailTaper, headAlpha, tailAlpha, minSize, maxSize, startFade, endFade;
		final Vec colorScale, headColor, tailColor;
		final int prevSource;

		Info(Pd p) {
			String cls = p.str("_class", "");
			sprites = cls.equals("C_OP_RenderSprites");
			trails = cls.equals("C_OP_RenderTrails");
			// C_OP_RenderProjected: decals laid on surfaces (the fire's scorch), drawn as quads flat on the particle's plane
			projected = cls.equals("C_OP_RenderProjected");
			orientToNormal = p.b("m_bOrientToNormal", false);
			List<Map<String, Object>> mats = p.array("m_vecProjectedMaterials");
			material = mats.isEmpty() ? null : p.nested(mats.get(0)).str("m_hMaterial", null);
			String blend = p.str("m_nOutputBlendMode", "PARTICLE_OUTPUT_BLEND_MODE_ALPHA");
			// m_flAddSelfAmount 1: the spritecard adds the layer instead of blending it (an alpha layer that is additive)
			float addSelf = p.num("m_flAddSelfAmount", new Num.Literal(0)).next(PState.DEFAULT);
			additive = blend.contains("ADD") || blend.contains("LIGHTEN") || addSelf >= 1;
			// drawn only into the bloom buffer (ours too), or only into water's reflection/refraction (never here)
			bloomOnly = p.b("m_bOnlyRenderInEffectsBloomPass", false);
			waterOnly = p.b("m_bOnlyRenderInEffectsWaterPass", false);
			String orient = p.str("m_nOrientationType", "PARTICLE_ORIENTATION_SCREEN_ALIGNED");
			worldZ = orient.equals("PARTICLE_ORIENTATION_WORLD_Z_ALIGNED");
			// particle normals aren't simulated (C_INIT_NormalAlignToCP missing): impact layers align to CP0's normal (flagged)
			cpNormal = orient.equals("PARTICLE_ORIENTATION_ALIGN_TO_PARTICLE_NORMAL");
			String tex = null;
			float[] gradient = null;
			float gradientBlend = 1;
			for (Map<String, Object> t : p.array("m_vecTexturesInput")) {
				Pd tp = p.nested(t);
				if (!tp.b("m_bEnabled", true)) continue;
				if (tp.b("m_bReplaceTextureWithGradient", false)) { // a colour lookup over the first texture's brightness
					List<Map<String, Object>> stops = tp.nested(tp.raw("m_Gradient")).array("m_Stops");
					// m_flTextureBlend: how much of the looked-up colour replaces the texture's own (the flames keep half)
					gradientBlend = Math.max(0, Math.min(1, tp.num("m_flTextureBlend", new Num.Literal(1)).next(PState.DEFAULT)));
					gradient = new float[stops.size() * 4];
					for (int k = 0; k < stops.size(); k++) {
						Pd st = tp.nested(stops.get(k));
						float[] col = st.floats("m_Color");
						gradient[k * 4] = st.f("m_flPosition", 0);
						for (int c = 0; c < 3; c++) gradient[k * 4 + 1 + c] = col.length > c ? col[c] : 0;
					}
					continue;
				}
				if (tex != null && tp.str("m_nTextureBlendMode", "").equals("SPRITECARD_TEXTURE_BLEND_SUBTRACT"))
					erode.add(tp.num("m_flTextureBlend", new Num.Literal(1)));
				if (tex != null) continue;
				tex = tp.str("m_hTexture", null);
				// alpha-only channel (smoke: shape in A, colour from a second zoomed texture): use the shape with white RGB
				// alpha-only channel (smoke: shape in A) / ..._RGBALPHA (brightness is the transparency: glows on opaque black)
				tex = ParticleTextures.variant(tex, tp.str("m_nTextureChannels", ""));
			}
			if (tex == null) tex = p.str("m_hTexture", null);
			// alpha-blended layers take their alpha from the looked-up colour's brightness (dark = clear), as CS2's lookup can
			if (gradient != null) tex = ParticleTextures.withGradient(tex, gradient, gradientBlend) + (additive ? "" : ParticleTextures.GRADIENT_ALPHA);
			graded = gradient != null && tex != null;
			// heat shimmer: the texture is a normal map that bends the picture behind it, not something to draw (not done yet)
			refract = p.b("m_bRefract", false) || tex != null && (tex.contains("_normal") || tex.contains("/warp"));
			texture = tex;
			animationRate = p.f("m_flAnimationRate", 0.1f);
			animationType = p.behaviorVersion >= 10 || trails ? p.str("m_nAnimationType", "ANIMATION_TYPE_FIXED_RATE") : "ANIMATION_TYPE_FIXED_RATE";
			animateInFps = p.b("m_bAnimateInFPS", false);
			radiusScale = p.num("m_flRadiusScale", new Num.Literal(1));
			alphaScale = p.num("m_flAlphaScale", new Num.Literal(1));
			overbright = p.num("m_flOverbrightFactor", new Num.Literal(1));
			colorScale = p.vecInput("m_vecColorScale", Vec.literal(new Vector3f(1)));
			selfIllum = p.num("m_flSelfIllumAmount", new Num.Literal(0));
			diffuse = p.num("m_flDiffuseAmount", new Num.Literal(1));
			desaturation = p.num("m_flDesaturation", new Num.Literal(0));
			// CS2 lights alpha-blended layers (diffuse x the sun + self-illumination); layers it darkens with a colour scale
			// (the HE smoke: 64 / 255) count on that light, so drawn full-bright they came out near black
			lit = !additive && p.has("m_vecColorScale") && darkLiteral(colorScale);
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
			// sprites fade out as they grow from m_flStartFadeSize to m_flEndFadeSize of the screen (up close)
			startFade = p.num("m_flStartFadeSize", new Num.Literal(10));
			endFade = p.num("m_flEndFadeSize", new Num.Literal(20));
			minSize = p.num("m_flMinSize", new Num.Literal(0));
			maxSize = p.num("m_flMaxSize", new Num.Literal(sprites ? 5000 : 2000));
		}
	}

	/** A constant colour scale below white (a non-constant one needs a particle: not counted). */
	private static boolean darkLiteral(Vec v) {
		try {
			Vector3f c = v.next(null, null);
			return Math.max(c.x(), Math.max(c.y(), c.z())) < 0.99f;
		} catch (RuntimeException e) {
			return false;
		}
	}

	/**
	 * FLAGGED: the sun's strength for lit layers (CS2's HDR light isn't in its files); 2 puts the HE smoke (colour scale
	 * 0.25) at a mid grey.
	 */
	private static final float SUN = 2;

	/**
	 * Only the HE explosion's layers so far: other effects (impact dust, molotov splashes) were tuned by eye without
	 * these, and some are meant to stay dark (impact_fx_hit_darken).
	 */
	private static boolean approximated(PSystem s) {
		return s.name != null && s.name.contains("explosion_hegrenade");
	}

	/** A lit layer's colour gain: our per-particle stand-in for CS2's per-pixel lighting. */
	private static float gain(PSystem s, Info in, Particle p) {
		return in.lit && approximated(s) ? in.selfIllum.next(p, s.state) + in.diffuse.next(p, s.state) * SUN : 1;
	}

	/** FLAGGED: subtracting a breakup texture (average ~0.5) eats that much of the alpha on average; as a uniform fade. */
	private static float erosion(PSystem s, Info in, Particle p) {
		if (!approximated(s)) return 1;
		float k = 1;
		for (Num n : in.erode) k *= 1 - 0.5f * Math.max(0, Math.min(1, n.next(p, s.state)));
		return k;
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
		submit(root, toRender, camRender, camRight, camUp, ps, out, null, drawPose);
	}

	/** Draws a system tree straight into buffers (after the translucent terrain: in front of water, depth-tested against it). */
	public static void draw(PSystem root, Matrix4f toRender, Vector3f camRender, Vector3f camRight, Vector3f camUp, PoseStack ps,
							net.minecraft.client.renderer.MultiBufferSource buffers) {
		submit(root, toRender, camRender, camRight, camUp, ps, null, buffers, null);
	}

	/** Which layers draw: decals (projected) are drawn for every effect first, then the rest over them. */
	static final int ALL = 0, DECALS = 1, NO_DECALS = 2;
	private static int layers = ALL;

	/** draw() limited to decals or to everything else. */
	public static void draw(PSystem root, Matrix4f toRender, Vector3f camRender, Vector3f camRight, Vector3f camUp, PoseStack ps,
							net.minecraft.client.renderer.MultiBufferSource buffers, boolean decals) {
		layers = decals ? DECALS : NO_DECALS;
		try {
			submit(root, toRender, camRender, camRight, camUp, ps, null, buffers, null);
		} finally {
			layers = ALL;
		}
	}

	private static void submit(PSystem root, Matrix4f toRender, Vector3f camRender, Vector3f camRight, Vector3f camUp, PoseStack ps,
							   SubmitNodeCollector out, net.minecraft.client.renderer.MultiBufferSource buffers, java.util.function.Supplier<Matrix4f> drawPose) {
		List<PSystem> all = new ArrayList<>();
		collect(root, all);
		readPixelSize();
		float unit = toRender.getScale(new Vector3f()).x;
		for (PSystem s : all) {
			if (s.count() == 0 || Effects.hidden(s.name)) continue;
			for (Map<String, Object> def : s.renderers) {
				Info in = info(s, def);
				if (in.projected) {
					if (layers != NO_DECALS) projected(s, in, toRender, unit, ps, out, buffers);
					continue;
				}
				if (layers == DECALS) continue;
				// layers CS2 draws only into its bloom pass go only into ours (world effects, additive)
				boolean bloom = buffers != null && in.additive && ParticleBloom.ready();
				if (!in.sprites && !in.trails || in.refract || in.waterOnly || in.bloomOnly && !bloom) continue;
				{
				ParticleTextures.Tex tex = ParticleTextures.get(in.texture);
				if (tex == null) continue;
				RenderType type = in.additive ? ParticleBlend.additive(tex.id()) : RenderTypes.entityTranslucentEmissive(tex.id());
				qn = 0; // quads go into QB: 4 corners x (x,y,z,u,v,r,g,b,a) each
				for (int i = 0; i < s.count(); i++) {
					Particle p = s.particle(i);
					if (in.sprites) sprite(s, in, tex, p, toRender, unit, camRender, camRight, camUp);
					else trail(s, in, tex, p, toRender, unit, camRender);
				}
				forceAlpha = -1;
				if (qn == 0) continue;
				if (bloom) { // the glow: into the bloom buffer at a quarter (overbright up to 4 fits)
					var bvc = buffers.getBuffer(ParticleBloom.type(tex.id()));
					for (int o = 0; o < qn * QUAD; o += QUAD)
						for (int c = 0; c < 4; c++) vertex(bvc, ps.last(), qb, o + c * 9, true, ParticleBloom.IN);
					ParticleBloom.markDrawn();
				}
				if (in.bloomOnly) continue;
				boolean add = in.additive;
				// m_flOverbrightFactor > 1 (fire 2-5, muzzle flash 3-4): the full value went into the bloom; on screen it is
				// compressed like CS2's tone mapping does (hue kept, easing to white) in one pass
				int passes = 1;
				if (add) for (int o = 0; o < qn * QUAD; o += QUAD) for (int c = 0; c < 4; c++) tone(qb, o + c * 9 + 5);
				// drawn now: straight from the shared buffer; submitted for later: its own copy
				float[] quads = buffers != null ? qb : java.util.Arrays.copyOf(qb, qn * QUAD);
				int count = qn;
				SubmitNodeCollector.CustomGeometryRenderer geometry = (given, vc) -> {
					PoseStack.Pose pose = given;
					if (drawPose != null) { // viewmodel space: the pose is only known when the features are drawn
						pose = new PoseStack.Pose();
						pose.pose().set(drawPose.get());
						pose.normal().set(pose.pose()).invert().transpose();
					}
					for (int k = 0; k < passes; k++)
						for (int o = 0; o < count * QUAD; o += QUAD)
							for (int c = 0; c < 4; c++) vertex(vc, pose, quads, o + c * 9, add);
				};
				if (buffers != null) geometry.render(ps.last(), buffers.getBuffer(type));
				else out.submitCustomGeometry(ps, type, geometry);
				}
			}
		}
	}

	/** A graded layer's particle alpha (its fade is in the texture), or -1. */
	private static float forceAlpha = -1;

	/** This renderer's quads (render thread only), QUAD floats each, and how many. */
	private static final int QUAD = 36;
	private static float[] qb = new float[QUAD * 256];
	private static int qn;

	/** Room for one more quad; returns its offset in qb. */
	private static int nextQuad() {
		if ((qn + 1) * QUAD > qb.length) qb = java.util.Arrays.copyOf(qb, qb.length * 2);
		return qn++ * QUAD;
	}

	private static void collect(PSystem s, List<PSystem> out) {
		out.add(s);
		for (PSystem c : s.children) if (c.childEnabled) collect(c, out);
	}

	private static void vertex(VertexConsumer vc, PoseStack.Pose pose, float[] q, int o, boolean additive) {
		vertex(vc, pose, q, o, additive, 1);
	}

	private static void vertex(VertexConsumer vc, PoseStack.Pose pose, float[] q, int o, boolean additive, float scale) {
		float a = Math.max(0, Math.min(1, q[o + 8]));
		float r = Math.max(0, Math.min(1, q[o + 5] * scale)), g = Math.max(0, Math.min(1, q[o + 6] * scale)), b = Math.max(0, Math.min(1, q[o + 7] * scale));
		int argb = (int) (a * 255) << 24 | (int) (r * 255) << 16 | (int) (g * 255) << 8 | (int) (b * 255);
		vc.addVertex(pose, q[o], q[o + 1], q[o + 2]).setColor(argb).setUv(q[o + 3], q[o + 4]).setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(FULL_BRIGHT).setNormal(pose, 0, 1, 0);
	}

	/** Sheet frame rect (u0, v0, u1, v1) for a particle, as VRF's GetSheetFrame. */
	private static final float[] FULL_FRAME = {0, 0, 1, 1};
	/** Scratch space for the sprite path (render thread only): the frame rect and the vectors of one quad. */
	private static final float[] UV = new float[4];
	private static final Vector3f C = new Vector3f(), AX = new Vector3f(), AY = new Vector3f(), T = new Vector3f(), COL = new Vector3f(),
			R = new Vector3f(), U = new Vector3f(), CORNER = new Vector3f();

	/** The particle's sheet frame into UV (or the whole texture); returns UV, or FULL_FRAME. */
	private static float[] frameInto(Info in, ParticleTextures.Tex tex, Particle p) {
		if (tex.sequences().isEmpty()) return FULL_FRAME;
		int si = p.sequence >= 0 && p.sequence < tex.sequences().size() ? p.sequence : 0;
		ParticleTextures.Sequence seq = tex.sequences().get(si);
		if (seq.frames().isEmpty()) return FULL_FRAME;
		int idx = frameIndex(in, seq, p);
		ParticleTextures.Frame f = seq.frames().get(idx);
		UV[0] = f.u0();
		UV[1] = f.v0();
		UV[2] = f.u1();
		UV[3] = f.v1();
		return UV;
	}

	private static int frameIndex(Info in, ParticleTextures.Sequence seq, Particle p) {
		int idx = 0;
		if (seq.frames().size() >= 2) {
			float total = seq.totalTime() > 0 ? seq.totalTime() : 1;
			float time = in.animationType.equals("ANIMATION_TYPE_FIT_LIFETIME") ? p.normalizedAge() : p.age;
			float passes = in.animationType.equals("ANIMATION_TYPE_MANUAL_FRAMES") ? p.manualFrame
					: in.animateInFps ? time * in.animationRate / total : time * in.animationRate;
			float pos = total * (seq.clamp() ? PMath.saturate(passes) : passes - (float) Math.floor(passes));
			idx = seq.frameAt(pos);
		}
		return idx;
	}

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

	/** World size of one screen pixel at a distance (vertical FOV from the options, window height). */
	private static float pixelSize(float dist) {
		return (float) (2 * dist * pxTan / pxHeight);
	}

	/** tan(vertical FOV / 2) and the window height for pixelSize, read once per draw. */
	private static double pxTan;
	private static int pxHeight = 1;

	private static void readPixelSize() {
		var mc = net.minecraft.client.Minecraft.getInstance();
		pxHeight = Math.max(1, mc.getWindow().getHeight());
		pxTan = Math.tan(Math.toRadians(mc.options.fov().get()) / 2);
	}

	private static void sprite(PSystem s, Info in, ParticleTextures.Tex tex, Particle p, Matrix4f m, float unit, Vector3f cam, Vector3f right, Vector3f up) {
		float half = p.radius * in.radiusScale.next(p, s.state) * unit;
		if (half <= 0) return;
		Vector3f c = m.transformPosition(C.set(p.position));
		float dist = c.distance(cam);
		// m_flMinSize / m_flMaxSize: the sprite's size limits in screen pixels (Source 2; default 5000 = no limit), e.g.
		// the HE explosion's anamorphic flash streak at most 150 px across
		float pxToWorld = pixelSize(dist);
		half = Math.min(Math.max(half, in.minSize.next(p, s.state) * 0.5f * pxToWorld), in.maxSize.next(p, s.state) * 0.5f * pxToWorld);
		float roll = p.rotation.z, cos = (float) Math.cos(roll), sin = (float) Math.sin(roll);
		if (in.cpNormal && s.particleNormals) { // flat on the particle's own normal (simulated: the ground fire's layers)
			planeAxes(p.normal, m);
			right = R;
			up = U;
		} else if (in.worldZ || in.cpNormal) { // flat in a plane: world XY, or the impact surface (CP0's local XY)
			org.joml.Quaternionf q = in.cpNormal ? s.controlPoint(0).rotationOrIdentity() : new org.joml.Quaternionf();
			right = m.transformDirection(q.transform(R.set(1, 0, 0))).normalize();
			up = m.transformDirection(q.transform(U.set(0, 1, 0))).normalize();
		}
		Vector3f ax = AX.set(right).mul(cos).add(T.set(up).mul(sin)).mul(half);
		Vector3f ay = AY.set(up).mul(cos).sub(T.set(right).mul(sin)).mul(half);
		float[] uv = frameInto(in, tex, p);
		Vector3f col = hdr(in, s, p, COL.set(p.color).mul(in.colorScale.next(p, s.state)).mul(in.overbright.next(p, s.state)).mul(gain(s, in, p)));
		float a = forceAlpha >= 0 ? forceAlpha : p.alpha * in.alphaScale.next(p, s.state) * erosion(s, in, p);
		float screen = 2 * half / Math.max(1e-4f, pixelSize(dist) * pxHeight), fs = in.startFade.next(p, s.state), fe = in.endFade.next(p, s.state);
		if (screen > fs) a *= fe > fs ? Math.max(0, 1 - (screen - fs) / (fe - fs)) : 0;
		if (a <= 0) return;
		int o = nextQuad();
		corner(qb, o, CORNER.set(c).sub(ax).sub(ay), uv[0], uv[3], col, a);
		corner(qb, o + 9, CORNER.set(c).add(ax).sub(ay), uv[2], uv[3], col, a);
		corner(qb, o + 18, CORNER.set(c).add(ax).add(ay), uv[2], uv[1], col, a);
		corner(qb, o + 27, CORNER.set(c).sub(ax).add(ay), uv[0], uv[1], col, a);
	}

	/** Tone curve for an additive colour (q[o..o+2]): as is up to KNEE, then easing to 1 (FLAGGED: our curve). */
	private static final float KNEE = 0.6f;

	private static void tone(float[] q, int o) {
		float m = Math.max(q[o], Math.max(q[o + 1], q[o + 2]));
		if (m <= KNEE) return;
		float g = KNEE + (1 - KNEE) * (1 - (float) Math.exp(-(m - KNEE) / (1 - KNEE)));
		float k = g / m;
		q[o] *= k;
		q[o + 1] *= k;
		q[o + 2] *= k;
	}

	/**
	 * CS2 draws effects in HDR: colours pushed past 1 by m_flOverbrightFactor come out of the tone mapping and bloom
	 * whiter, not just saturated. Additive layers here: m_flDesaturation towards the colour's luminance, then the part
	 * above 1 spread to the other channels (FLAGGED: our stand-in for the HDR pipeline).
	 */
	private static Vector3f hdr(Info in, PSystem s, Particle p, Vector3f c) {
		if (!in.additive) return c;
		float d = Math.max(0, Math.min(1, in.desaturation.next(p, s.state)));
		float l = 0.2125f * c.x + 0.7154f * c.y + 0.0721f * c.z;
		if (d > 0) c.lerp(new Vector3f(l), d);
		return c;
	}

	/** Two render-space axes (into R, U) spanning the plane with simulation-space normal n. */
	private static void planeAxes(Vector3f n, Matrix4f m) {
		Vector3f z = new Vector3f(n);
		if (z.lengthSquared() < 1e-8f) z.set(0, 0, 1);
		z.normalize();
		Vector3f ref = Math.abs(z.z) < 0.9f ? new Vector3f(0, 0, 1) : new Vector3f(1, 0, 0);
		Vector3f x = new Vector3f(ref).cross(z).normalize(), y = new Vector3f(z).cross(x);
		m.transformDirection(R.set(x)).normalize();
		m.transformDirection(U.set(y)).normalize();
	}

	/**
	 * C_OP_RenderProjected: each particle's material laid flat on its plane (its normal when m_bOrientToNormal, else
	 * the ground), radius across, rolled, nudged off the surface. CS2 projects it onto the geometry below; flat quads
	 * are our stand-in (FLAGGED).
	 */
	private static void projected(PSystem s, Info in, Matrix4f m, float unit, PoseStack ps, SubmitNodeCollector out,
								  net.minecraft.client.renderer.MultiBufferSource buffers) {
		if (in.material == null) return;
		dev.strafemc.client.impact.DecalLibrary.Material mat = dev.strafemc.client.impact.DecalLibrary.material(in.material);
		if (mat == null) return;
		qn = 0;
		Vector3f ground = new Vector3f(0, 0, 1);
		for (int i = 0; i < s.count(); i++) {
			Particle p = s.particle(i);
			float half = p.radius * in.radiusScale.next(p, s.state) * unit;
			if (half <= 0) continue;
			Vector3f n = in.orientToNormal ? p.normal : ground;
			planeAxes(n, m);
			Vector3f lift = m.transformDirection(new Vector3f(n).normalize()).normalize().mul(0.02f + (i % 16) * 0.001f);
			Vector3f c = m.transformPosition(C.set(p.position)).add(lift);
			float roll = p.rotation.z, cos = (float) Math.cos(roll), sin = (float) Math.sin(roll);
			Vector3f ax = AX.set(R).mul(cos).add(T.set(U).mul(sin)).mul(half);
			Vector3f ay = AY.set(U).mul(cos).sub(T.set(R).mul(sin)).mul(half);
			Vector3f col = COL.set(p.color).mul(in.colorScale.next(p, s.state));
			float a = p.alpha * in.alphaScale.next(p, s.state);
			int o = nextQuad();
			corner(qb, o, CORNER.set(c).sub(ax).sub(ay), 0, 1, col, a);
			corner(qb, o + 9, CORNER.set(c).add(ax).sub(ay), 1, 1, col, a);
			corner(qb, o + 18, CORNER.set(c).add(ax).add(ay), 1, 0, col, a);
			corner(qb, o + 27, CORNER.set(c).sub(ax).add(ay), 0, 0, col, a);
		}
		if (qn == 0) return;
		float[] quads = buffers != null ? qb : java.util.Arrays.copyOf(qb, qn * QUAD);
		int count = qn;
		SubmitNodeCollector.CustomGeometryRenderer geometry = (pose, vc) -> {
			for (int o = 0; o < count * QUAD; o += QUAD)
				for (int k = 0; k < 4; k++) vertex(vc, pose, quads, o + k * 9, false);
		};
		RenderType type = ParticleBlend.smoke(mat.texture()); // no depth writes: overlapping decals don't fight
		if (buffers != null) {
			geometry.render(ps.last(), buffers.getBuffer(type));

		} else out.submitCustomGeometry(ps, type, geometry);
	}

	private static void trail(PSystem s, Info in, ParticleTextures.Tex tex, Particle p, Matrix4f m, float unit, Vector3f cam) {
		Vector3f prev = p.vector(in.prevSource);
		Vector3f diff = new Vector3f(prev).sub(p.position);
		if (diff.lengthSquared() == 0) return;
		float dt = s.frameTime() > 0 ? s.frameTime() : 1 / 64f;
		// the previous position moves every frame (length = speed x trail time); any other source (a spawn point kept in a
		// scratch vector, e.g. the HE explosion's smoke blasts) is a distance already
		float len = in.lengthScale * p.trailLength * diff.length() / (in.prevSource == Particle.POSITION_PREVIOUS ? dt : 1);
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
		Vector3f tint = hdr(in, s, p, new Vector3f(p.color).mul(in.colorScale.next(p, s.state)).mul(in.overbright.next(p, s.state)).mul(gain(s, in, p)));
		float a = forceAlpha >= 0 ? forceAlpha * p.alphaAlternate : p.alpha * p.alphaAlternate * in.alphaScale.next(p, s.state) * erosion(s, in, p);
		Vector3f hc = new Vector3f(tint).mul(in.headColor.next(p, s.state)), tc = new Vector3f(tint).mul(in.tailColor.next(p, s.state));
		float ha = a * in.headAlpha.next(p, s.state), ta = a * in.tailAlpha.next(p, s.state);
		int o = nextQuad();
		corner(qb, o, new Vector3f(head).sub(new Vector3f(width).mul(hw)), uv[0], uv[1], hc, ha);
		corner(qb, o + 9, new Vector3f(head).add(new Vector3f(width).mul(hw)), uv[2], uv[1], hc, ha);
		corner(qb, o + 18, new Vector3f(tail).add(new Vector3f(width).mul(tw)), uv[2], uv[3], tc, ta);
		corner(qb, o + 27, new Vector3f(tail).sub(new Vector3f(width).mul(tw)), uv[0], uv[3], tc, ta);
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
