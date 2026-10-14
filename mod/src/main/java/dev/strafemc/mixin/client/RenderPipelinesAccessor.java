package dev.strafemc.mixin.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla's entity pipeline snippet (matrices, fog, light directions, samplers, entity vertex format) for our viewmodel pipeline. */
@Mixin(RenderPipelines.class)
public interface RenderPipelinesAccessor {
	@Accessor("ENTITY_SNIPPET")
	static RenderPipeline.Snippet strafemc$entitySnippet() {
		throw new AssertionError();
	}
}
