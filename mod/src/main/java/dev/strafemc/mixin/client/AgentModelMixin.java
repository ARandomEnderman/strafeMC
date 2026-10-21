package dev.strafemc.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Players and bots drawn as CS2 agents (AgentModels, when turned on): the agent instead of the model, the name tag as before. */
@Mixin(LivingEntityRenderer.class)
public abstract class AgentModelMixin {
	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At("HEAD"), cancellable = true)
	private void strafemc$agent(LivingEntityRenderState state, PoseStack ps, SubmitNodeCollector out, CameraRenderState cam, CallbackInfo ci) {
		if (!(state instanceof AvatarRenderState s) || !dev.strafemc.client.viewmodel.AgentModels.submit(s, ps, out)) return;
		((EntityRendererInvoker) this).strafemc$submitNameDisplay(state, ps, out, cam);
		ci.cancel();
	}
}
