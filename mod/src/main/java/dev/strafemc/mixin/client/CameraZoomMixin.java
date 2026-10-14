package dev.strafemc.mixin.client;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** CS2 scopes: the world FOV narrows to the gun's zoom FOV (GunClient.zoomFov). */
@Mixin(Camera.class)
public abstract class CameraZoomMixin {
	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void strafemc$zoom(float partial, CallbackInfoReturnable<Float> cir) {
		cir.setReturnValue(dev.strafemc.client.weapon.GunClient.INSTANCE.zoomFov(cir.getReturnValueF()));
	}
}
