package dev.strafemc.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import dev.strafemc.client.StrafeSettingsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds "strafeMC..." as the last button of the Options screen's button grid. */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {
	protected OptionsScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/layouts/HeaderAndFooterLayout;addToFooter(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;"))
	private void strafemc$gridButton(CallbackInfo ci, @Local GridLayout.RowHelper grid) {
		Screen self = this;
		grid.addChild(Button.builder(Component.translatable("strafemc.options.button"), b -> minecraft.setScreen(new StrafeSettingsScreen(self))).build());
	}
}
