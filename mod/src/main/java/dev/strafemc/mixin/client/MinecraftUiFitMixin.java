package dev.strafemc.mixin.client;

import com.mojang.blaze3d.platform.Window;
import dev.strafemc.client.ui.UiFit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lowers the GUI scale while a strafeMC menu that wouldn't fit is open (UiFit), and puts it back afterwards. */
@Mixin(Minecraft.class)
public abstract class MinecraftUiFitMixin {
	@Shadow
	public Screen screen;
	@Shadow
	@Final
	private Window window;

	@Shadow
	public abstract void resizeGui();

	@ModifyArg(method = "resizeGui", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;setGuiScale(I)V"))
	private int strafemc$fitScale(int scale) {
		return UiFit.scale(scale, screen);
	}

	/** A new screen: its scale before it lays itself out. */
	@Inject(method = "setScreen", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;getGuiScaledWidth()I", ordinal = 0))
	private void strafemc$fitNewScreen(Screen ignored, CallbackInfo ci) {
		int want = UiFit.scale(UiFit.chosen((Minecraft) (Object) this), screen);
		if (window.getGuiScale() != want) window.setGuiScale(want);
	}

	/** Closed (or replaced by a screen that didn't lay out): back to the player's scale. */
	@Inject(method = "setScreen", at = @At("TAIL"))
	private void strafemc$restoreScale(Screen ignored, CallbackInfo ci) {
		if (window.getGuiScale() != UiFit.scale(UiFit.chosen((Minecraft) (Object) this), screen)) resizeGui();
	}
}
