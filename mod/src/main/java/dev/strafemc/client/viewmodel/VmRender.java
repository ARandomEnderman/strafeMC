package dev.strafemc.client.viewmodel;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The viewmodel's own render type: vanilla's entity setup with our shaders (strafemc:core/viewmodel), which light the
 * CS2 viewmodel per pixel: diffuse from the scene's light, and a stand-in environment reflection (sky, ground, a sun
 * highlight) with Fresnel, metal reflecting in its own colour. The texture's alpha carries CS2's metalness.
 * FLAGGED: our lighting model, not CS2's shader; tuned by eye.
 */
public final class VmRender {
	private static final Identifier ID = Identifier.fromNamespaceAndPath("strafemc", "pipeline/viewmodel");
	private static RenderPipeline pipeline;
	private static final Map<Identifier, RenderType> TYPES = new ConcurrentHashMap<>();

	private VmRender() {}

	private static RenderPipeline pipeline() {
		if (pipeline == null)
			pipeline = RenderPipeline.builder(dev.strafemc.mixin.client.RenderPipelinesAccessor.strafemc$entitySnippet()).withLocation(ID)
					.withVertexShader(Identifier.fromNamespaceAndPath("strafemc", "core/viewmodel"))
					.withFragmentShader(Identifier.fromNamespaceAndPath("strafemc", "core/viewmodel")).withSampler("Sampler1").withCull(false).build();
		return pipeline;
	}

	/** The opaque viewmodel render type for a texture (alpha = metalness). */
	public static RenderType opaque(Identifier texture) {
		return TYPES.computeIfAbsent(texture, t -> dev.strafemc.mixin.client.RenderTypeInvoker.strafemc$create("strafemc_viewmodel",
				RenderSetup.builder(pipeline()).withTexture("Sampler0", t).useLightmap().useOverlay().createRenderSetup()));
	}
}
