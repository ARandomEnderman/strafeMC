package dev.strafemc.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The renderer's own pose steps, so ClientHitbox poses a mob's model exactly as it's drawn. */
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererInvoker {
	@Invoker("setupRotations")
	void strafemc$setupRotations(LivingEntityRenderState state, PoseStack ps, float bodyRot, float scale);

	@Invoker("scale")
	void strafemc$scale(LivingEntityRenderState state, PoseStack ps);
}
