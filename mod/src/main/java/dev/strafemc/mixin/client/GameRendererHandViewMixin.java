package dev.strafemc.mixin.client;

import dev.strafemc.client.viewmodel.Viewmodel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import dev.strafemc.weapon.GunItem;
import dev.strafemc.weapon.MagazineItem;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hand pass: no walking bob while a CS item is held (CS viewmodels don't bob like Minecraft's hand). Also captures the camera rotation the hand pass starts from, so the CS2 viewmodel can be drawn in pure camera space. */
@Mixin(GameRenderer.class)
public abstract class GameRendererHandViewMixin {
	@Inject(method = "renderItemInHand", at = @At("HEAD"))
	private void strafemc$handView(CameraRenderState camera, float partial, Matrix4fc view, CallbackInfo ci) {
		Viewmodel.HAND_VIEW.set(view);
	}

	@Redirect(method = "renderItemInHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V"))
	private void strafemc$noHandBob(GameRenderer self, CameraRenderState camera, PoseStack ps) {
		var p = Minecraft.getInstance().player;
		if (p != null && (p.getMainHandItem().getItem() instanceof GunItem || p.getMainHandItem().getItem() instanceof MagazineItem)) return;
		((GameRendererInvoker) self).strafemc$bobView(camera, ps);
	}
}
