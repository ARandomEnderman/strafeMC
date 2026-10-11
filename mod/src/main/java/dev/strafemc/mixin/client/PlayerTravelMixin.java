package dev.strafemc.mixin.client;

import dev.strafemc.client.movement.MovementController;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla walk/sprint/sneak physics are removed for the local player; Source movement moves it instead. */
@Mixin(Player.class)
public abstract class PlayerTravelMixin {
	@Inject(method = "travel", at = @At("HEAD"), cancellable = true)
	private void strafemc$noVanillaTravel(Vec3 input, CallbackInfo ci) {
		if (MovementController.INSTANCE.controls((Player) (Object) this)) ci.cancel();
	}
}
