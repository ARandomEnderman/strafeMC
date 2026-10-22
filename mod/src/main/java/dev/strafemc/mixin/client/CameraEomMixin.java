package dev.strafemc.mixin.client;

import dev.strafemc.client.viewmodel.EomScene;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The end of match scene (EomScene) takes the camera: CS2's end_of_match_camera path and its field of view. */
@Mixin(Camera.class)
public abstract class CameraEomMixin {
	@Shadow
	private boolean detached;

	@Shadow
	protected abstract void setPosition(Vec3 pos);

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void strafemc$eomCamera(float partial, CallbackInfo ci) {
		if (!EomScene.active()) return;
		Object[] c = EomScene.camera();
		setPosition((Vec3) c[0]);
		setRotation((float) c[1], 0);
		detached = true; // your own body shows, standing where you were
	}

	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void strafemc$eomFov(float partial, CallbackInfoReturnable<Float> cir) {
		if (EomScene.active()) cir.setReturnValue(EomScene.fov());
	}
}
