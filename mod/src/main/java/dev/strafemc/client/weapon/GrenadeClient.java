package dev.strafemc.client.weapon;

import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.Grenade;
import dev.strafemc.weapon.GrenadeEffects;
import net.minecraft.world.phys.Vec3;

/** Client side of grenades going off: CS2's effect and sound where the server says. */
public final class GrenadeClient {
	private GrenadeClient() {}

	public static void onEffect(StrafePayloads.GrenadeFx m) {
		Grenade type = Grenade.values()[Math.max(0, Math.min(Grenade.values().length - 1, m.kind()))];
		Vec3 at = new Vec3(m.x(), m.y(), m.z());
		switch (type) {
			case HE -> {
				dev.strafemc.client.particle.Effects.at(GrenadeEffects.HE_EFFECT, at, new Vec3(0, 1, 0));
				dev.strafemc.client.sound.CsSounds.play("BaseGrenade.Explode", at.x, at.y, at.z, 1.0);
			}
			default -> {}
		}
	}
}
