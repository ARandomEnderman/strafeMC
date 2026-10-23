package dev.strafemc.mixin.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Screen effects on the world, after it's drawn and before the HUD, every frame whether the HUD shows or not (F1 must
 * not take them away): the death camera's colour and the flashbang's afterimage and white-out.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererLevelEffectsMixin {
	@Inject(method = "renderLevel", at = @At("TAIL"))
	private void strafemc$afterLevel(DeltaTracker delta, CallbackInfo ci) {
		dev.strafemc.client.DeathCam.afterLevel();
		dev.strafemc.client.weapon.GrenadeClient.afterLevel();
	}
}
