package dev.strafemc.mixin;

import dev.strafemc.server.Noclip;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Players driven by Source movement take CS2 fall damage (see dev.strafemc.server.CsFallDamage) instead of vanilla's. */
@Mixin(Player.class)
public abstract class PlayerFallDamageMixin {
	@Inject(method = "causeFallDamage", at = @At("HEAD"), cancellable = true)
	private void strafemc$csFallDamage(double distance, float multiplier, DamageSource source, CallbackInfoReturnable<Boolean> cir) {
		if (Noclip.usesSource((Player) (Object) this)) cir.setReturnValue(false);
	}
}
