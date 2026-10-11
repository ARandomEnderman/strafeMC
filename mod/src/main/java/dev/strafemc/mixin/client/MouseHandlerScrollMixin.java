package dev.strafemc.mixin.client;

import dev.strafemc.client.movement.MovementController;
import dev.strafemc.config.StrafeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** bind mwheelup/mwheeldown +jump: each wheel notch in-game is a jump press (hotbar uses number keys). */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerScrollMixin {
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void strafemc$wheelJump(long window, double dx, double dy, CallbackInfo ci) {
		if (dy == 0) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.screen == null && mc.player != null && dev.strafemc.client.console.Binds.wheel(dy > 0)) {
			ci.cancel(); // an explicit "bind mwheelup/mwheeldown ..." wins
			return;
		}
		if (!StrafeConfig.scrollJump) return;
		if (mc.screen == null && MovementController.INSTANCE.active()) {
			MovementController.INSTANCE.queueWheelJump();
			ci.cancel();
		}
	}
}
