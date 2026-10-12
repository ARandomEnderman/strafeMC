package dev.strafemc.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.strafemc.config.StrafeConfig;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * viewmodel_fov: the first-person hand/held item (and later the CS viewmodel) is drawn with its own fixed FOV, like CS.
 * 26.1 builds the hand projection in renderLevel from CameraRenderState.hudFov, so the value is replaced where it's read.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererHandFovMixin {
	@ModifyExpressionValue(method = "renderLevel", at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/renderer/state/level/CameraRenderState;hudFov:F"))
	private float strafemc$viewmodelFov(float original) {
		return dev.strafemc.client.ClientGameplay.sourceMovement() ? (float) StrafeConfig.viewmodelFov : original;
	}
}
