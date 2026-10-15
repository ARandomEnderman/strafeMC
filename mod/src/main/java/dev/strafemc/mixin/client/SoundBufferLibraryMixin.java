package dev.strafemc.mixin.client;

import com.mojang.blaze3d.audio.SoundBuffer;
import dev.strafemc.client.sound.CsAudio;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/** strafemc_cs:* sounds are decoded CS2 audio from the local cache, not resource-pack .ogg files. */
@Mixin(SoundBufferLibrary.class)
public abstract class SoundBufferLibraryMixin {
	@Inject(method = "getCompleteBuffer", at = @At("HEAD"), cancellable = true)
	private void strafemc$csAudio(Identifier path, CallbackInfoReturnable<CompletableFuture<SoundBuffer>> cir) {
		if (CsAudio.NAMESPACE.equals(path.getNamespace())) cir.setReturnValue(CsAudio.buffer(path));
	}

	/** The engine is restarting: CS2 sounds go with Minecraft's own and are decoded again for the new one. */
	@Inject(method = "clear", at = @At("HEAD"))
	private void strafemc$clearCs(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		CsAudio.clear();
	}
}
