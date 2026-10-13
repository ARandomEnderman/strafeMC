package dev.strafemc.client.particle;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Simulates a surface impact effect (CP0/1 at the origin facing +Z, CP4 = tint) and prints every live particle per system. */
public final class ImpactCheck {
	public static void main(String[] a) {
		String vpcf = a.length > 0 ? a[0] : "particles/impact_fx/impact_wood.vpcf";
		PSystem sys = PSystem.load(vpcf);
		sys.controlPoint(0).set(new Vector3f(), new Quaternionf(), sys.state);
		sys.controlPoint(1).set(new Vector3f(), new Quaternionf(), sys.state);
		sys.controlPoint(4).set(new Vector3f(0.56f, 0.45f, 0.29f), null, sys.state);
		int frames = a.length > 1 ? Integer.parseInt(a[1]) : 20;
		for (int f = 0; f < frames; f++) {
			sys.update(1 / 60f);
			if (f < 6 || f % (frames > 20 ? 20 : 4) == 0) {
				System.out.println("-- frame " + f);
				dump(sys, "");
			}
		}
	}

	static void dump(PSystem s, String ind) {
		if (s.count() > 0) {
			StringBuilder b = new StringBuilder();
			for (int i = 0; i < Math.min(4, s.count()); i++) {
				Particle p = s.particle(i);
				b.append(String.format("%n%s   r=%.2f a=%.2f col=%.2f,%.2f,%.2f seq=%d rot=%.2f", ind, p.radius, p.alpha, p.color.x, p.color.y, p.color.z, p.sequence, p.rotation.z));
			}
			System.out.printf("%s%s: %d particles%s%n", ind, s.name.substring(s.name.lastIndexOf('/') + 1), s.count(), b);
		}
		for (PSystem c : s.children) if (c.childEnabled) dump(c, ind + "  ");
	}
}
