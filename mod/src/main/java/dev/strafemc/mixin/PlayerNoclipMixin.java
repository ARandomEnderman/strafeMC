package dev.strafemc.mixin;

import dev.strafemc.server.Noclip;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Player.tick resets noPhysics to isSpectator(); keep it on for noclipping players (server accepts the moves, no fall damage). */
@Mixin(Player.class)
public abstract class PlayerNoclipMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void strafemc$noclip(CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (Noclip.active(self)) {
			self.noPhysics = true;
			self.resetFallDistance();
		} else if (Noclip.onStairs(self)) {
			self.noPhysics = true;
		}
	}
}
