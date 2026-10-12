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
	private static final Map<Identifier, RenderType> TYPES = new ConcurrentHashMap<>();

	private ParticleBlend() {}

	/** Called at client start so the pipeline is registered with Minecraft's own (they're compiled up front). */
	public static void init() {}

	static RenderType additive(Identifier texture) {
		return TYPES.computeIfAbsent(texture, t -> RenderType.create("strafemc_particle_add",
				RenderSetup.builder(ADD).withTexture("Sampler0", t).sortOnUpload().createRenderSetup()));
	}
}
