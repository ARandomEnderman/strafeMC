package dev.strafemc.mixin.client;

import dev.strafemc.client.movement.MovementController;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Camera height follows the engine's view offset (stand/duck) instead of Minecraft's pose eye height. */
@Mixin(Entity.class)
public abstract class EntityEyeHeightMixin {
	@Inject(method = "getEyeHeight()F", at = @At("HEAD"), cancellable = true)
	private void strafemc$sourceEye(CallbackInfoReturnable<Float> cir) {
		if (MovementController.INSTANCE.controls((Entity) (Object) this)) cir.setReturnValue(MovementController.INSTANCE.eyeHeightBlocks());
	}
}
