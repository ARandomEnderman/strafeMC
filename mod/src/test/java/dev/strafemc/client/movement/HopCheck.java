package dev.strafemc.client.movement;

/** Holding jump (autobhop) on flat ground: time between takeoffs, vs CS2's exact parabola 2 x impulse / gravity. */
public final class HopCheck {
	public static void main(String[] a) throws Exception {
		var cv = new com.google.gson.Gson().fromJson(java.nio.file.Files.readString(java.nio.file.Path.of(a[0])), java.util.Map.class);
		SourceMovement.Params p = FlatGroundCheck.params(cv);
		p.autoBhop = true;
		FlatGroundCheck.TestWorld w = new FlatGroundCheck.TestWorld().floor();
		double dt = a.length > 1 ? Double.parseDouble(a[1]) : 1 / 64.0;
		SourceMovement s = FlatGroundCheck.fresh();
		SourceMovement.Input in = new SourceMovement.Input();
		in.jumpHeld = true;
		double t = 0, first = -1, last = -1;
		int jumps = 0;
		for (int i = 0; i < (int) (20 / dt); i++) {
			s.step(in, p, w, dt);
			t += dt;
			if (s.jumpedThisStep) {
				if (first < 0) first = t;
				last = t;
				jumps++;
			}
		}
		double period = (last - first) / (jumps - 1);
		System.out.printf("dt %.5f: %d jumps, period %.4f s (CS2 exact %.4f s), ratio %.4f%n", dt, jumps, period, 2 * p.jumpImpulse / p.gravity,
				period / (2 * p.jumpImpulse / p.gravity));
	}
}
