package dev.strafemc.mixin;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No advancement (Minecraft's or strafeMC's) is granted while the server has them off (ServerRules.advancements). */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsOffMixin {
	@Inject(method = "award", at = @At("HEAD"), cancellable = true)
	private void strafemc$off(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
		if (!dev.strafemc.server.ServerRules.advancements()) cir.setReturnValue(false);
	}
}
