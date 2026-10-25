package dev.strafemc.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** CS2: "You cannot drop weapons during warmup" (round modes): the drop key does nothing then, the client's guess undone. */
@Mixin(ServerPlayer.class)
public abstract class WarmupDropMixin {
	@Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true)
	private void strafemc$warmupDrop(boolean all, CallbackInfo ci) {
		ServerPlayer p = (ServerPlayer) (Object) this;
		if (dev.strafemc.mode.GameModes.mayDrop(p, p.getMainHandItem())) return;
		ci.cancel();
		p.containerMenu.sendAllDataToRemote(); // the client already took it out of the slot: put it back there
		dev.strafemc.server.Toasts.warn(p, "You cannot drop weapons during warmup");
	}
}
