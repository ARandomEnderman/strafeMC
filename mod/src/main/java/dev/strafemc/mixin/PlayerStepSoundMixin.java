package dev.strafemc.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Players with Source movement make CS2's footsteps (their client sends them, the server passes them on), so the
 * server doesn't also play Minecraft's step sounds for them to everyone else.
 */
@Mixin(Entity.class)
public abstract class PlayerStepSoundMixin {
	private boolean strafemc$csSteps() {
		return (Object) this instanceof ServerPlayer p && dev.strafemc.server.StrafeSettings.sourceMovement(p);
	}

	@Inject(method = "walkingStepSound", at = @At("HEAD"), cancellable = true)
	private void strafemc$walking(CallbackInfo ci) {
		if (strafemc$csSteps()) ci.cancel();
	}

	@Inject(method = "playCombinationStepSounds", at = @At("HEAD"), cancellable = true)
	private void strafemc$combination(CallbackInfo ci) {
		if (strafemc$csSteps()) ci.cancel();
	}

	@Inject(method = "playMuffledStepSound", at = @At("HEAD"), cancellable = true)
	private void strafemc$muffled(CallbackInfo ci) {
		if (strafemc$csSteps()) ci.cancel();
	}
}
