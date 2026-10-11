package dev.strafemc.mixin.client;

import dev.strafemc.client.movement.MovementController;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft nudges the player out of blocks it thinks they overlap. Source movement keeps the hull 1/32 unit off the
 * floor (DIST_EPSILON), so under an exactly-fitting ceiling MC's 1.5-block crouch box overlaps by a hair and gets
 * shoved sideways. Source collision already keeps the hull out of blocks, so skip MC's push while it is active.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerPushOutMixin {
	@Inject(method = "moveTowardsClosestSpace(DD)V", at = @At("HEAD"), cancellable = true)
	private void strafemc$noPushOut(double x, double z, CallbackInfo ci) {
		if (MovementController.INSTANCE.controls((LocalPlayer) (Object) this)) ci.cancel();
	}
}
