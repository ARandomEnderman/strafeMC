package dev.strafemc.mixin.client;

import dev.strafemc.client.movement.MovementController;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** CS has no sprint; the sprint key is +speed (walk) while Source movement is active. */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerSprintMixin {
	@Inject(method = "canStartSprinting", at = @At("HEAD"), cancellable = true)
	private void strafemc$noSprint(CallbackInfoReturnable<Boolean> cir) {
		if (MovementController.INSTANCE.controls((LocalPlayer) (Object) this)) cir.setReturnValue(false);
	}
}
