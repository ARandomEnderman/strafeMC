package dev.strafemc.mixin.client;

import net.minecraft.client.gui.components.toasts.RecipeToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.components.toasts.TutorialToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While a game mode runs, Minecraft's recipe and tutorial pop-ups stay away (the mode's gear unlocks recipes as it's handed out). */
@Mixin(ToastManager.class)
public abstract class ToastManagerMixin {
	@Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
	private void strafemc$quietInModes(Toast toast, CallbackInfo ci) {
		if ((toast instanceof RecipeToast || toast instanceof TutorialToast) && dev.strafemc.client.ModeHud.active()) ci.cancel();
	}
}
