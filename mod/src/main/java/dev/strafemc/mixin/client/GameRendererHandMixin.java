package dev.strafemc.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.OptionsRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.level.GameType;

/**
 * F1 (hide GUI) keeps the CS2 viewmodel on screen, as CS2 keeps the arms when its HUD is hidden; and while dead in a
 * game mode, watching a player who holds a CS weapon, their viewmodel is drawn (Minecraft draws no hands for spectators).
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererHandMixin {
	@Redirect(method = "renderItemInHand", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/state/OptionsRenderState;hideGui:Z"))
	private boolean strafemc$keepViewmodel(OptionsRenderState state) {
		var p = Minecraft.getInstance().player;
		if (p != null && p.getMainHandItem().getItem() instanceof dev.strafemc.weapon.GunItem) return false;
		return state.hideGui;
	}

	@Redirect(method = "renderItemInHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;getPlayerMode()Lnet/minecraft/world/level/GameType;"))
	private GameType strafemc$watchedViewmodel(MultiPlayerGameMode mode) {
		GameType real = mode.getPlayerMode();
		var h = dev.strafemc.client.viewmodel.Viewmodel.holder();
		if (real == GameType.SPECTATOR && dev.strafemc.client.viewmodel.Viewmodel.watching() && h.getMainHandItem().getItem() instanceof dev.strafemc.weapon.GunItem)
			return GameType.ADVENTURE;
		return real;
	}
}
