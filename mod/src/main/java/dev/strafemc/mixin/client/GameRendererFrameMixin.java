package dev.strafemc.mixin.client;

import dev.strafemc.client.movement.MovementController;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs the 64 Hz Source movement sim once per rendered frame, before the camera is set up. */
@Mixin(GameRenderer.class)
public abstract class GameRendererFrameMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void strafemc$frame(DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
		dev.strafemc.client.perf.Perf.frame();
		long t = dev.strafemc.client.perf.Perf.start();
		MovementController.INSTANCE.onFrame();
		dev.strafemc.client.perf.Perf.end("movement", t);
		t = dev.strafemc.client.perf.Perf.start();
		dev.strafemc.client.weapon.GunClient.INSTANCE.onFrame(net.minecraft.client.Minecraft.getInstance());
		dev.strafemc.client.perf.Perf.end("gun", t);
	}
}
