package dev.strafemc.client.particle;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Additive particle layers (CS2 PARTICLE_OUTPUT_BLEND_MODE_ADD): colour x alpha added to the scene (SRC_ALPHA, ONE).
 * Minecraft 26.1's "eyes" type, used before, blends normally, so glow textures on an opaque black background (e.g.
 * effects/yellowflare, the impact sparks) were drawn as black squares. Same entity shader as eyes, no depth writes.
 */
public final class ParticleBlend {
	private static final RenderPipeline ADD = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
			.withLocation("pipeline/strafemc_particle_add")
			.withVertexShader("core/entity")
			.withFragmentShader("core/entity")
			.withShaderDefine("EMISSIVE")
			.withShaderDefine("NO_OVERLAY")
			.withShaderDefine("NO_CARDINAL_LIGHTING")
			.withSampler("Sampler0")
			.withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
			.withCull(false)
			.withVertexFormat(DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS)
			.withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
			.build());
	/**
	 * Depth only, where the texture x vertex alpha is at least 0.5: the dense part of a smoke cloud. Translucent smoke
	 * writes no depth, so whatever is drawn after it (bullet holes, effects, clouds in their own later pass) used to
	 * paint over it; with its core in the depth buffer those are hidden behind it. Its soft edges still blend.
	 */
	private static final RenderPipeline DEPTH = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
			.withLocation("pipeline/strafemc_smoke_depth")
			.withVertexShader("core/entity")
			.withFragmentShader("core/entity")
			.withShaderDefine("ALPHA_CUTOUT", 0.5f)
			.withShaderDefine("EMISSIVE")
			.withShaderDefine("NO_OVERLAY")
			.withShaderDefine("NO_CARDINAL_LIGHTING")
			.withSampler("Sampler0")
			.withColorTargetState(new ColorTargetState(java.util.Optional.empty(), ColorTargetState.WRITE_NONE))
			.withCull(false)
			.withVertexFormat(DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS)
			.withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
			.build());
	/**
	 * Smoke puffs: normal translucent blending, lit by the world, without the 0.1 alpha cutout of Minecraft's
	 * entity_translucent (that cut each puff's soft edge off in a hard ring, visible as circles inside the smoke).
	 */
	private static final RenderPipeline SMOKE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
			.withLocation("pipeline/strafemc_smoke")
			.withVertexShader("core/entity")
			.withFragmentShader("core/entity")
			.withShaderDefine("NO_OVERLAY")
			.withShaderDefine("NO_CARDINAL_LIGHTING")
			.withSampler("Sampler0")
			.withSampler("Sampler2")
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.withCull(false)
			.withVertexFormat(DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS)
			.withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
			.build());
	private static final Map<Identifier, RenderType> TYPES = new ConcurrentHashMap<>(), DEPTH_TYPES = new ConcurrentHashMap<>(),
			SMOKE_TYPES = new ConcurrentHashMap<>();

	private ParticleBlend() {}

	/** Called at client start so the pipeline is registered with Minecraft's own (they're compiled up front). */
	public static void init() {}

	public static RenderType smoke(Identifier texture) {
		return SMOKE_TYPES.computeIfAbsent(texture, t -> RenderType.create("strafemc_smoke",
				RenderSetup.builder(SMOKE).withTexture("Sampler0", t).useLightmap().createRenderSetup()));
	}

	public static RenderType depthOnly(Identifier texture) {
		return DEPTH_TYPES.computeIfAbsent(texture, t -> RenderType.create("strafemc_smoke_depth",
				RenderSetup.builder(DEPTH).withTexture("Sampler0", t).createRenderSetup()));
	}

	static RenderType additive(Identifier texture) {
		return TYPES.computeIfAbsent(texture, t -> RenderType.create("strafemc_particle_add",
				RenderSetup.builder(ADD).withTexture("Sampler0", t).sortOnUpload().createRenderSetup()));
	}
}
