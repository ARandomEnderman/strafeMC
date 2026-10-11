package dev.strafemc.mixin.client;

import dev.strafemc.client.movement.MovementController;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No vanilla jump (or sprint-jump boost) for the local player; jumping is Source's CheckJumpButton. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityJumpMixin {
	@Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)
	private void strafemc$noVanillaJump(CallbackInfo ci) {
		if (MovementController.INSTANCE.controls((LivingEntity) (Object) this)) ci.cancel();
	}
}
