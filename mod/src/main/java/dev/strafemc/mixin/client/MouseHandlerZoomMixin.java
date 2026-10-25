package dev.strafemc.mixin.client;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Scoped: mouse turning slows with the zoom (CS2's zoom_sensitivity_ratio x zoomed FOV / 90). */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerZoomMixin {
	/** The death camera holds the view (CS2's freeze cam can't be turned). */
	@org.spongepowered.asm.mixin.injection.Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
	private void strafemc$deathCam(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (dev.strafemc.client.DeathCam.locked()) ci.cancel();
	}

	@ModifyArg(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"), index = 0)
	private double strafemc$zoomX(double dx) {
		if (dev.strafemc.client.RadioWheel.open()) { // the chat wheel: the mouse moves its cursor, not the view
			dev.strafemc.client.RadioWheel.mouse(dx, 0);
			return 0;
		}
		return dx * dev.strafemc.client.weapon.GunClient.INSTANCE.zoomSensitivity();
	}

	@ModifyArg(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"), index = 1)
	private double strafemc$zoomY(double dy) {
		if (dev.strafemc.client.RadioWheel.open()) {
			dev.strafemc.client.RadioWheel.mouse(0, dy);
			return 0;
		}
		return dy * dev.strafemc.client.weapon.GunClient.INSTANCE.zoomSensitivity();
	}
}
