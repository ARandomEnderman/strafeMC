package dev.strafemc.mixin.client;

import dev.strafemc.client.movement.MovementController;
import dev.strafemc.config.StrafeConfig;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * With cl_doubletapfly 0, two quick jump presses (bunnyhopping!) never start creative flight: the double-tap timer is
 * cleared every tick, so each press only ever counts as the first tap. Stopping flight by double-tap still works.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerDoubleTapFlyMixin {
	@Inject(method = "aiStep", at = @At("HEAD"))
	private void strafemc$noDoubleTapFly(CallbackInfo ci) {
		LocalPlayer self = (LocalPlayer) (Object) this;
		if (!StrafeConfig.doubleTapFly && MovementController.INSTANCE.controls(self) && !self.getAbilities().flying)
			((PlayerJumpTriggerAccessor) self).strafemc$setJumpTriggerTime(0);
	}
}
