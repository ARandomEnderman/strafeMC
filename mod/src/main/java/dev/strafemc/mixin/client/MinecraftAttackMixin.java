package dev.strafemc.mixin.client;

import dev.strafemc.client.weapon.GunClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While a CS gun is held, left click fires it (GunClient) instead of hitting entities or breaking blocks. */
@Mixin(Minecraft.class)
public abstract class MinecraftAttackMixin {
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void strafemc$gunStart(CallbackInfoReturnable<Boolean> cir) {
		if (GunClient.holdingGun(((Minecraft) (Object) this).player)) cir.setReturnValue(false);
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void strafemc$knifeUse(CallbackInfo ci) { // right click stabs (GunClient) while a CS knife is held
		var p = ((Minecraft) (Object) this).player;
		if (p != null && (p.getMainHandItem().getItem() instanceof dev.strafemc.weapon.KnifeItem
				|| p.getMainHandItem().getItem() instanceof dev.strafemc.weapon.GrenadeItem)) ci.cancel(); // right click: stab / underhand throw
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void strafemc$gunContinue(boolean attacking, CallbackInfo ci) {
		if (GunClient.holdingGun(((Minecraft) (Object) this).player)) ci.cancel();
	}
}
