package dev.strafemc.mixin.client;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Scoped: mouse turning slows with the zoom (CS2's zoom_sensitivity_ratio x zoomed FOV / 90). */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerZoomMixin {
	@ModifyArg(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"), index = 0)
	private double strafemc$zoomX(double dx) {
		return dx * dev.strafemc.client.weapon.GunClient.INSTANCE.zoomSensitivity();
	}

	@ModifyArg(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"), index = 1)
	private double strafemc$zoomY(double dy) {
		return dy * dev.strafemc.client.weapon.GunClient.INSTANCE.zoomSensitivity();
	}
}
