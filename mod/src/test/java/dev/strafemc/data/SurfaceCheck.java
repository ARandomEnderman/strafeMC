package dev.strafemc.data;

/** Resolves the CS2 surfaces strafeMC maps blocks to: decal group, impact sound, effect, penetration. */
public final class SurfaceCheck {
	public static void main(String[] a) throws Exception {
		Surfaces.load();
		dev.strafemc.client.impact.DecalLibrary.load();
		for (String n : new String[] {"default", "concrete", "rock", "brick", "tile", "Wood", "Wood_Ladder", "metal", "metalgrate", "chain", "glass",
				"dirt", "mud", "grass", "foliage", "sand", "gravel", "snow", "ice", "carpet", "slipperyslime"}) {
			Surfaces.Surface s = Surfaces.get(n);
			System.out.printf("%-14s decal=%-20s sound=%-24s pen=%.2f/%.2f effect=%s pick=%s%n", n, s.decalGroup(), s.bulletImpactSound(),
					s.penetrationDistance(), s.penetrationDamage(), s.impactEffect(),
					dev.strafemc.client.impact.DecalLibrary.pick(s.decalGroup(), new java.util.Random(1)));
		}
	}
}
