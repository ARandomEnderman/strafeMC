package dev.strafemc.client.sound;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;

/**
 * One CS2 sound played through Minecraft's engine. Distance falloff comes from the CS2 event's own curve (volume is
 * computed by the caller), so MC's attenuation is off; the position still gives stereo panning.
 */
public final class CsSoundInstance extends AbstractSoundInstance implements net.minecraft.client.resources.sounds.TickableSoundInstance {
	/** Entity the sound moves with (weapon sounds come from the gun); null = fixed where it was played. */
	private final net.minecraft.world.entity.Entity follow;
	private final double offsetUp;

	public CsSoundInstance(Identifier id, float volume, float pitch, double x, double y, double z) {
		this(id, volume, pitch, x, y, z, null, 0);
	}

	public CsSoundInstance(Identifier id, float volume, float pitch, double x, double y, double z, net.minecraft.world.entity.Entity follow,
						   double offsetUp) {
		super(id, SoundSource.PLAYERS, RandomSource.create());
		this.volume = volume;
		this.pitch = pitch;
		this.x = x;
		this.y = y;
		this.z = z;
		this.attenuation = Attenuation.NONE;
		this.relative = false;
		this.follow = follow;
		this.offsetUp = offsetUp;
	}

	/** Listener-relative at the head: the local player's own weapon sounds (centred, never one-sided while moving). */
	public static CsSoundInstance own(Identifier id, float volume, float pitch) {
		// its stereo variant: with Directional Audio on, it goes straight to both ears (direct channels), unfiltered
		if (id.getNamespace().equals(CsAudio.NAMESPACE) && !id.getPath().startsWith("stereo/"))
			id = Identifier.fromNamespaceAndPath(id.getNamespace(), "stereo/" + id.getPath());
		CsSoundInstance s = new CsSoundInstance(id, volume, pitch, 0, 0, 0, null, 0);
		s.relative = true;
		return s;
	}

	@Override
	public boolean isStopped() {
		return false; // ends with its sound
	}

	@Override
	public void tick() {
		if (follow == null || follow.isRemoved()) return;
		x = follow.getX();
		y = follow.getEyeY() + offsetUp;
		z = follow.getZ();
	}

	@Override
	public WeighedSoundEvents resolve(SoundManager manager) {
		this.sound = new Sound(identifier, ConstantFloat.of(1f), ConstantFloat.of(1f), 1, Sound.Type.FILE, false, false, 16);
		WeighedSoundEvents w = new WeighedSoundEvents(identifier, null);
		w.addSound(sound);
		return w;
	}
}
