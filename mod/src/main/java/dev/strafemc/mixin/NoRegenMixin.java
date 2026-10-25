package dev.strafemc.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In a game mode and on a game mode server health only comes back with a new life, as in CS2: no hunger, no natural
 * regeneration. The food bar stays full so sprinting keeps working.
 */
@Mixin(FoodData.class)
public abstract class NoRegenMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void strafemc$noRegen(ServerPlayer p, CallbackInfo ci) {
		if (!dev.strafemc.mode.GameModes.playing(p) && !dev.strafemc.server.ServerRules.modeServer()) return;
		FoodData f = (FoodData) (Object) this;
		f.setFoodLevel(20);
		f.setSaturation(0);
		ci.cancel();
	}
}
