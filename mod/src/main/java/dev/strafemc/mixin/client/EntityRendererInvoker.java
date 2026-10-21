package dev.strafemc.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** EntityRenderer's name tag drawing, for renderers that replace the model (AgentModelMixin). */
@Mixin(EntityRenderer.class)
public interface EntityRendererInvoker {
	@Invoker("submitNameDisplay")
	void strafemc$submitNameDisplay(EntityRenderState state, PoseStack ps, SubmitNodeCollector out, CameraRenderState cam);
}
