package dev.strafemc.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No camera tilt when hurt while holding a CS weapon (CS2 has none; a burst of hits threw the view around). */
@Mixin(GameRenderer.class)
public abstract class GameRendererHurtTiltMixin {
	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
	private void strafemc$noHurtTilt(CameraRenderState camera, PoseStack ps, CallbackInfo ci) {
		var p = Minecraft.getInstance().player;
		if (p != null && p.isAlive() && p.getMainHandItem().getItem() instanceof dev.strafemc.weapon.GunItem) ci.cancel();
	}
}
