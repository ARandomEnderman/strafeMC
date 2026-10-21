package dev.strafemc.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The death screen waits for CS2's death camera (DeathCam) to look at the killer first. Minecraft opens it by calling
 * setScreen(null) every tick while you're dead (setScreen turns that into a new DeathScreen), so both are held.
 */
@Mixin(Minecraft.class)
public abstract class DeathScreenDelayMixin {
	@Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
	private void strafemc$deathCam(Screen screen, CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		boolean death = screen instanceof DeathScreen || screen == null && mc.level != null && mc.player != null && mc.player.isDeadOrDying();
		if (death && dev.strafemc.client.DeathCam.hold(screen)) ci.cancel();
	}
}
