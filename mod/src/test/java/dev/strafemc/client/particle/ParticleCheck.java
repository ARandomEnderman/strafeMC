package dev.strafemc.client.particle;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Simulates CS2 weapon effects headlessly and prints per-system particle states. */
public final class ParticleCheck {
	public static void main(String[] a) {
		for (String vpcf : (a.length > 0 ? a : new String[] {"particles/unified_weapon_fx/uweapon_muzflsh_ak47_fps.vpcf", "particles/weapons/cs_weapon_fx/weapon_tracers_assrifle.vpcf"})) {
			PSystem sys = PSystem.load(vpcf);
			sys.controlPoint(0).set(new Vector3f(), new Quaternionf(), null);
			sys.controlPoint(1).set(new Vector3f(2000, 0, 0), new Quaternionf(), null);
			System.out.println("== " + vpcf);
			for (int f = 0; f < 12; f++) {
				sys.update(1 / 60f);
				if (f % 3 == 0) dump(sys, "", f);
			}
		}
	}

	static void dump(PSystem s, String indent, int f) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < Math.min(2, s.count()); i++) {
			Particle p = s.particle(i);
			b.append(String.format(" [pos %.1f,%.1f,%.1f r=%.2f a=%.2f col=%.2f,%.2f,%.2f seq=%d life=%.3f age=%.3f trail=%.2f]", p.position.x, p.position.y, p.position.z,
					p.radius, p.alpha, p.color.x, p.color.y, p.color.z, p.sequence, p.lifetime, p.age, p.trailLength));
		}
		System.out.printf("%sframe %d %s: %d particles, enabled=%s, renderers=%d%s%n", indent, f, s.name.substring(s.name.lastIndexOf('/') + 1), s.count(), s.childEnabled, s.renderers.size(), b);
		for (PSystem c : s.children) dump(c, indent + "  ", f);
	}
}
