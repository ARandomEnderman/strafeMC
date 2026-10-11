package dev.strafemc.mixin.client;

import dev.strafemc.client.movement.MovementController;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** CS keeps the FOV fixed: no Minecraft speed/sprint/effect FOV zoom while Source movement drives the player. */
@Mixin(AbstractClientPlayer.class)
public abstract class PlayerFovMixin {
	@Inject(method = "getFieldOfViewModifier", at = @At("HEAD"), cancellable = true)
	private void strafemc$fixedFov(boolean firstPerson, float partial, CallbackInfoReturnable<Float> cir) {
		if (MovementController.INSTANCE.controls((AbstractClientPlayer) (Object) this)) cir.setReturnValue(1.0f);
	}
}
