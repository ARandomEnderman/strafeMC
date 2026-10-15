package dev.strafemc.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.OptionsRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** F1 (hide GUI) keeps the CS2 viewmodel on screen, as CS2 keeps the arms when its HUD is hidden. */
@Mixin(GameRenderer.class)
public abstract class GameRendererHandMixin {
	@Redirect(method = "renderItemInHand", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/state/OptionsRenderState;hideGui:Z"))
	private boolean strafemc$keepViewmodel(OptionsRenderState state) {
		var p = Minecraft.getInstance().player;
		if (p != null && p.getMainHandItem().getItem() instanceof dev.strafemc.weapon.GunItem) return false;
		return state.hideGui;
	}
}
