package dev.strafemc.mixin.client;

import dev.strafemc.client.movement.MovementController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The MC server never simulates Source movement, so its copy of our velocity is ~zero. When it sends a velocity packet
 * (knockback, but also plain damage like fall damage), vanilla would REPLACE our momentum with it. With Source movement
 * active we ADD it as an impulse instead: knockback still launches you, fall damage no longer stops you.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientMotionPacketMixin {
	@Inject(method = "handleSetEntityMotion", at = @At("HEAD"), cancellable = true)
	private void strafemc$motionAsImpulse(ClientboundSetEntityMotionPacket packet, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (!mc.isSameThread() || mc.player == null || packet.id() != mc.player.getId()) return; // vanilla reschedules onto the main thread
		if (!MovementController.INSTANCE.controls(mc.player)) return;
		MovementController.INSTANCE.addImpulse(packet.movement());
		ci.cancel();
	}
}
