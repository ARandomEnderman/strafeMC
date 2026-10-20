package dev.strafemc.mixin.client;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The volumetric smoke gets its own frame pass after the clouds pass (just before the weather), so clouds drawn
 * behind a smoke don't show through it: drawn in the main pass, the smoke wrote no depth for the clouds to test against.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererSmokeMixin {
	@Shadow
	@Final
	private LevelTargetBundle targets;

	@Inject(method = "addWeatherPass", at = @At("HEAD"))
	private void strafemc$smokePass(FrameGraphBuilder frame, GpuBufferSlice fog, CallbackInfo ci) {
		if (!dev.strafemc.client.smoke.SmokeVolumes.any()) return;
		FramePass pass = frame.addPass("strafemc_smoke");
		targets.main = pass.readsAndWrites(targets.main);
		pass.executes(() -> {
			long t = dev.strafemc.client.perf.Perf.start();
			dev.strafemc.client.smoke.SmokeRenderer.render();
			dev.strafemc.client.perf.Perf.end("smoke", t);
		});
	}
}
