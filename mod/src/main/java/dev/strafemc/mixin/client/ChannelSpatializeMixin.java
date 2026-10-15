package dev.strafemc.mixin.client;

import com.mojang.blaze3d.audio.Channel;
import org.lwjgl.openal.AL10;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Directional Audio (HRTF) on the sounds around you, not on your own: listener-relative sounds (your own gun, steps,
 * the UI) play unfiltered (OpenAL Soft's AL_SOURCE_SPATIALIZE_SOFT off), everything placed in the world keeps HRTF's
 * direction cues. Channels are reused, so every other sound is set back to the default.
 */
@Mixin(Channel.class)
public abstract class ChannelSpatializeMixin {
	private static final int AL_SOURCE_SPATIALIZE_SOFT = 0x1214, AL_AUTO_SOFT = 0x0002, AL_DIRECT_CHANNELS_SOFT = 0x1033;
	private static Boolean strafemc$supported;

	@Shadow
	@Final
	private int source;

	@Inject(method = "setRelative", at = @At("TAIL"))
	private void strafemc$spatialize(boolean relative, CallbackInfo ci) {
		if (strafemc$supported == null) strafemc$supported = AL10.alIsExtensionPresent("AL_SOFT_source_spatialize");
		if (!strafemc$supported) return;
		AL10.alSourcei(source, AL_SOURCE_SPATIALIZE_SOFT, relative ? AL10.AL_FALSE : AL_AUTO_SOFT);
		// a stereo sound (your own CS sounds' stereo variant) goes straight to the ears: no HRTF at all
		AL10.alSourcei(source, AL_DIRECT_CHANNELS_SOFT, relative ? AL10.AL_TRUE : AL10.AL_FALSE);
		AL10.alGetError(); // never leave an error behind for Minecraft's own checks
	}
}
