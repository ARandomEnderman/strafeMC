package dev.strafemc.mixin.client;

import dev.strafemc.client.weapon.GunClient;
import dev.strafemc.weapon.GunItem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * CS2 recoil on the first-person camera: the view shows aim punch x0.45 plus the view punch kick, without touching the
 * player's real rotation (bullets use eye angles + full aim punch, see GunClient).
 */
@Mixin(Camera.class)
public abstract class CameraRecoilMixin {
	private static boolean strafemc$active() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && mc.options.getCameraType().isFirstPerson() && mc.player.getMainHandItem().getItem() instanceof GunItem;
	}

	@ModifyArg(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"), index = 0)
	private float strafemc$recoilYaw(float yaw) {
		return strafemc$active() ? yaw + (float) GunClient.INSTANCE.viewOffset()[1] : yaw;
	}

	@ModifyArg(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"), index = 1)
	private float strafemc$recoilPitch(float pitch) {
		return strafemc$active() ? pitch + (float) GunClient.INSTANCE.viewOffset()[0] : pitch;
	}
}
