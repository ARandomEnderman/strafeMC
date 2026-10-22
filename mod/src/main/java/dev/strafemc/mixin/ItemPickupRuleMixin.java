package dev.strafemc.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Round modes, as CS2: only Terrorists pick up the bomb, only Counter-Terrorists a defuse kit. */
@Mixin(ItemEntity.class)
public abstract class ItemPickupRuleMixin {
	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void strafemc$sideItems(Player p, CallbackInfo ci) {
		if (!dev.strafemc.mode.GameModes.mayPickUp(p, ((ItemEntity) (Object) this).getItem())) ci.cancel();
	}
}
