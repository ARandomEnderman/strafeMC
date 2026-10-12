package dev.strafemc.mixin;

import dev.strafemc.server.Noclip;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Noclipping players are never "in a wall": no suffocation damage while flying through blocks. */
@Mixin(Entity.class)
public abstract class EntityInWallMixin {
	@Inject(method = "isInWall", at = @At("HEAD"), cancellable = true)
	private void strafemc$noclipNotInWall(CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof Player p && Noclip.active(p)) cir.setReturnValue(false);
	}
}
