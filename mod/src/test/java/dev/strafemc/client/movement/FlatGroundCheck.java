package dev.strafemc.client.movement;

import java.util.ArrayList;
import java.util.List;

/**
 * Headless checks of {@link SourceMovement} + {@link HullTrace} on synthetic worlds (no Minecraft needed).
 * Run: gradlew flatGroundCheck. Uses the live cvar values cached by the bridge (printed first).
 */
public final class FlatGroundCheck {
	static final double DT = 1.0 / 64.0, B = 40.0; // 40 units per block

	/** World made of solids; optional ladder plane. */
	static final class TestWorld implements SourceMovement.World {
		final List<HullTrace.Solid> solids = new ArrayList<>();
		double[] ladderN;
		double ladderX0 = Double.NaN, ladderX1;

		TestWorld floor() {
			solids.add(HullTrace.Solid.box(-100000, -B, -100000, 100000, 0, 100000));
			return this;
		}

		@Override
		public HullTrace.Result trace(double x, double y, double z, double hw, double h, double dx, double dy, double dz) {
			return HullTrace.trace(x, y, z, hw, h, dx, dy, dz, solids);
		}

		@Override
		public boolean free(double x, double y, double z, double hw, double h) {
			return !HullTrace.overlaps(x, y, z, hw, h, solids);
		}

		@Override
		public double[] ladder(double x, double y, double z, double hw, double h, boolean volume) {
			return ladderN != null && x + hw + 2 >= ladderX0 && x - hw - 2 <= ladderX1 ? ladderN : null;
		}
	}

	public static void main(String[] a) throws Exception {
		var cv = new com.google.gson.Gson().fromJson(java.nio.file.Files.readString(java.nio.file.Path.of(a[0])), java.util.Map.class);
		SourceMovement.Params p = params(cv);
		System.out.printf("cvars: accel=%s airaccel=%s airwish=%s friction=%s stop=%s grav=%s jump=%s standable=%s ladderscale=%s%n",
				p.accelerate, p.airAccelerate, p.airMaxWishspeed, p.friction, p.stopSpeed, p.gravity, p.jumpImpulse, p.standableNormal, p.ladderScaleSpeed);
		run(p);
	}

	static SourceMovement.Params params(java.util.Map<?, ?> cv) {
		SourceMovement.Params p = new SourceMovement.Params();
		p.accelerate = d(cv, "sv_accelerate");
		p.airAccelerate = d(cv, "sv_airaccelerate");
		p.airMaxWishspeed = d(cv, "sv_air_max_wishspeed");
		p.friction = d(cv, "sv_friction");
		p.stopSpeed = d(cv, "sv_stopspeed");
		p.maxSpeed = d(cv, "sv_maxspeed");
		p.gravity = d(cv, "sv_gravity");
		p.jumpImpulse = d(cv, "sv_jump_impulse");
		p.stepSize = d(cv, "sv_stepsize");
		p.maxVelocity = d(cv, "sv_maxvelocity");
		p.timeBetweenDucks = d(cv, "sv_timebetweenducks");
		p.standableNormal = d(cv, "sv_standable_normal");
		p.ladderScaleSpeed = d(cv, "sv_ladder_scale_speed");
		p.ladderAngle = d(cv, "sv_ladder_angle");
		p.ladderDampen = d(cv, "sv_ladder_dampen");
		p.bhopTimeWindow = 0.0078125;
		p.stepMoveVelMin = 64;
		p.jumpSpamPenalty = 0.015625;
		p.weaponMaxSpeed = 250; // knife m_flMaxSpeed from weapons.vdata
		return p;
	}

	static void run(SourceMovement.Params p) {

		TestWorld flat = new TestWorld().floor();

		// 1. run forward from rest
		SourceMovement s = fresh();
		SourceMovement.Input in = new SourceMovement.Input();
		in.forward = 1;
		double t90 = -1;
		for (int i = 0; i < 128; i++) {
			s.step(in, p, flat, DT);
			if (t90 < 0 && s.horizontalSpeed() >= 0.9 * 250) t90 = s.time;
		}
		System.out.printf("run:    top speed %.2f u/s (knife max 250), 90%% after %.3f s, y=%.3f ground=%s%n", s.horizontalSpeed(), t90, s.y, s.onGround);

		// 2. stop
		in.forward = 0;
		int ticks = 0;
		while (s.horizontalSpeed() > 0 && ticks < 640) {
			s.step(in, p, flat, DT);
			ticks++;
		}
		System.out.printf("stop:   250 -> 0 in %.3f s%n", ticks * DT);

		// 3. standing jump
		s = fresh();
		in = new SourceMovement.Input();
		in.jumpHeld = true;
		double apex = 0;
		int air = 0;
		s.step(in, p, flat, DT);
		in.jumpHeld = false;
		while (!s.onGround && air < 640) {
			s.step(in, p, flat, DT);
			apex = Math.max(apex, s.y);
			air++;
		}
		System.out.printf("jump:   apex %.2f u, airtime %.3f s%n", apex, (air + 1) * DT);

		// 4. air strafe
		s = fresh();
		s.vz = 250;
		in = new SourceMovement.Input();
		in.jumpHeld = true;
		s.step(in, p, flat, DT);
		in.jumpHeld = false;
		in.side = -1;
		float yaw = 0;
		double start = s.horizontalSpeed();
		while (!s.onGround) {
			yaw -= 4.0f;
			in.yawDeg = yaw;
			s.step(in, p, flat, DT);
		}
		System.out.printf("strafe: %.1f -> %.1f u/s over one jump%n", start, s.horizontalSpeed());

		// 4b. CS2 sub-tick steps: same strafe as 3 slices per tick (~190 fps), each with its own view; airaccelerate 12 vs 100
		for (float turn : new float[] {4f, 8f, 12f})
		for (double aa : new double[] {12, 100}) {
			double keep = p.airAccelerate;
			p.airAccelerate = aa;
			s = fresh();
			s.vz = 250;
			in = new SourceMovement.Input();
			in.jumpHeld = true;
			s.step(in, p, flat, DT);
			in.jumpHeld = false;
			in.side = -1;
			float yw = 0;
			while (!s.onGround) {
				for (int k = 1; k <= 3; k++) {
					in.yawDeg = yw - turn * k / 3;
					s.step(in, p, flat, DT / 3);
				}
				yw -= turn;
			}
			System.out.printf("subtick: turn %4.1f deg/tick  airaccelerate %4.0f -> %.1f u/s after one strafed jump%n", turn, aa, s.horizontalSpeed());
			p.airAccelerate = keep;
		}

		// 5. stairs as ramps: 5 stairs rising toward +x (yaw -90 = facing +x)
		TestWorld stairs = new TestWorld().floor();
		for (int i = 0; i < 5; i++) stairs.solids.add(HullTrace.Solid.ramp((2 + i) * B, i * B, -B, B, 1, 0));
		for (int i = 0; i < 5; i++) stairs.solids.add(HullTrace.Solid.box((2 + i + 1) * B, i * B, -B, (2 + i + 2) * B, (i + 1) * B, 0)); // supports under the next stair
		stairs.solids.add(HullTrace.Solid.box(7 * B, 0, -B, 12 * B, 5 * B, 0)); // landing at the top
		s = fresh();
		s.z = -B / 2;
		in = new SourceMovement.Input();
		in.forward = 1;
		in.yawDeg = -90;
		double minSpeedOnRamp = 1e9;
		for (int i = 0; i < 200 && s.x < 9 * B; i++) {
			s.step(in, p, stairs, DT);
			if (s.x > 3 * B && s.x < 6 * B) minSpeedOnRamp = Math.min(minSpeedOnRamp, s.horizontalSpeed());
		}
		System.out.printf("stairs: reached x=%.0f y=%.1f (top = %.0f) ground=%s, min speed on ramp %.1f u/s%n", s.x, s.y, 5 * B, s.onGround, minSpeedOnRamp);

		// 5b. surf ramp: 1 deep x 2 high (63.4 deg) rising toward +x, a long row along z; land on it moving along z
		TestWorld surf = new TestWorld().floor();
		for (int j = 0; j < 20; j++) // ramps placed diagonally (1 over, 2 up) = one tall continuous 63.4 degree slope
			for (int k = -50; k < 50; k++) surf.solids.add(HullTrace.Solid.ramp((2 + j) * B, 2 * j * B, k * B, B, B, 2 * B, 1, 0));
		double keepAa = p.airAccelerate;
		p.airAccelerate = 150; // surf servers
		s = fresh();
		s.onGround = false;
		s.x = 12 * B - 40; // hull just off the slope, about 20 blocks up
		s.y = 20 * B;
		s.vz = 400;
		in = new SourceMovement.Input();
		in.side = -1; // facing +z, "left" points to +x: strafe into the ramp
		in.yawDeg = 0;
		boolean everGround = false;
		double minY = 1e9;
		for (int i = 0; i < 128; i++) {
			s.step(in, p, surf, DT);
			everGround |= s.onGround;
			minY = Math.min(minY, s.y);
		}
		System.out.printf("surf:   after 2 s: along-ramp speed %.1f u/s (start 400), y=%.0f (start %.0f, min %.0f), ever on ground=%s%n", s.vz, s.y, 20 * B, minY, everGround);
		// 5c. glide with no input at the default airaccelerate: along-ramp speed must be conserved (no friction in air).
		// Per-block prisms (seams every block, like the old McWorld) vs one merged prism per row.
		for (boolean merged : new boolean[] {false, true}) {
			TestWorld g = new TestWorld().floor();
			for (int j = 0; j < 20; j++)
				if (merged) g.solids.add(HullTrace.Solid.ramp((2 + j) * B, 2 * j * B, -50 * B, 100 * B, B, 2 * B, 1, 0));
				else for (int k = -50; k < 50; k++) g.solids.add(HullTrace.Solid.ramp((2 + j) * B, 2 * j * B, k * B, B, B, 2 * B, 1, 0));
			p.airAccelerate = keepAa;
			s = fresh();
			s.onGround = false;
			s.x = 12 * B - 40;
			s.y = 20 * B;
			s.vz = 600;
			in = new SourceMovement.Input();
			in.yawDeg = 0;
			double worstDrop = 0, prev = 600;
			for (int i = 0; i < 64; i++) {
				s.step(in, p, g, DT);
				worstDrop = Math.max(worstDrop, prev - s.vz);
				prev = s.vz;
			}
			System.out.printf("glide %s: after 1 s vz %.1f (start 600), worst single-tick loss %.1f, y=%.0f%n", merged ? "merged " : "per-blk", s.vz, worstDrop, s.y);
		}
		// 5d. surf UP: fly into the ramp diagonally (into it + along it) low on a tall slope; Source's ClipVelocity turns the
		// into-ramp part into upward speed, so you should climb.
		{
			TestWorld g = new TestWorld().floor();
			for (int j = 0; j < 20; j++) g.solids.add(HullTrace.Solid.ramp((2 + j) * B, 2 * j * B, -50 * B, 100 * B, B, 2 * B, 1, 0));
			p.airAccelerate = keepAa;
			s = fresh();
			s.onGround = false;
			s.x = 4 * B - 20; // hull just in front of the slope foot at y = 4 blocks
			s.y = 4 * B + 1;
			s.vx = 500; // into the ramp
			s.vz = 300; // along it
			s.vy = 0;
			in = new SourceMovement.Input();
			in.yawDeg = 0;
			double maxY = s.y, maxVy = -1e9;
			StringBuilder tr = new StringBuilder();
			for (int i = 0; i < 64; i++) {
				s.step(in, p, g, DT);
				maxY = Math.max(maxY, s.y);
				maxVy = Math.max(maxVy, s.vy);
				if (i < 12) tr.append(String.format(" [x%.0f y%.0f vx%.0f vy%.0f]", s.x, s.y, s.vx, s.vy));
			}
			System.out.printf("surf up: start y %.0f, max y %.0f, max vy %.1f%n  %s%n", 4.0 * B, maxY, maxVy, tr);
		}
		// 5e. surfing along at 600 u/s, holding the strafe key toward the ramp: height gained in 1 s by sv_airaccelerate
		for (int side : new int[] {-1, 1}) for (double aa : new double[] {12, 150}) {
			TestWorld g = new TestWorld().floor();
			for (int j = 0; j < 20; j++) g.solids.add(HullTrace.Solid.ramp((2 + j) * B, 2 * j * B, -50 * B, 100 * B, B, 2 * B, 1, 0));
			p.airAccelerate = aa;
			s = fresh();
			s.onGround = false;
			s.x = 12 * B - 20;
			s.y = 20 * B + 1;
			s.vz = 600;
			in = new SourceMovement.Input();
			in.side = side;
			in.yawDeg = 0;
			double maxY = s.y;
			for (int i = 0; i < 64; i++) {
				s.step(in, p, g, DT);
				maxY = Math.max(maxY, s.y);
			}
			System.out.printf("surf climb side %d airaccel %3.0f: x %.0f height gained %.0f u (%.1f blocks) in 1 s, speed now %.0f%n", side, aa, s.x, maxY - (20 * B + 1), (maxY - 20 * B) / B, s.horizontalSpeed());
		}
		// 5f. climbing with a perfect mouse-turn strafe (wishdir kept perpendicular to velocity, toward the ramp) by ramp angle
		for (double aa5 : new double[] {12, 150})
		for (double[] r : new double[][] {{1, 2}, {2, 3}, {1, 1.5}}) { // run, rise in blocks: 63.4, 56.3, 56.3 degrees
			TestWorld g = new TestWorld().floor();
			for (int j = 0; j < 40; j++) g.solids.add(HullTrace.Solid.ramp((2 + j * r[0]) * B, j * r[1] * B, -500 * B, 1000 * B, r[0] * B, r[1] * B, 1, 0));
			p.airAccelerate = aa5;
			s = fresh();
			s.onGround = false;
			double y0 = 20 * B + 1;
			s.y = y0;
			s.x = (2 + 20 * r[0] / r[1]) * B - 20; // in front of the slope at y0
			s.vz = 600;
			in = new SourceMovement.Input();
			in.side = -1;
			double maxY = y0;
			for (int i = 0; i < 128; i++) {
				in.yawDeg = (float) Math.toDegrees(Math.atan2(-s.vx, s.vz));
				s.step(in, p, g, DT);
				maxY = Math.max(maxY, s.y);
			}
			System.out.printf("surf climb airaccel %3.0f, %.1f deg (run %.1f rise %.1f): +%.1f blocks in 2 s, speed %.0f%n", aa5,
					Math.toDegrees(Math.atan2(r[1], r[0])), r[0], r[1], (maxY - y0) / B, Math.sqrt(s.vx * s.vx + s.vy * s.vy + s.vz * s.vz));
		}
		p.airAccelerate = keepAa;

		// 6. slab (half block = 20 u > sv_stepsize 18): can't walk up, jumping works
		TestWorld slab = new TestWorld().floor();
		slab.solids.add(HullTrace.Solid.box(2 * B, 0, -1000, 4 * B, B / 2, 1000));
		s = fresh();
		in = new SourceMovement.Input();
		in.forward = 1;
		in.yawDeg = -90;
		for (int i = 0; i < 64; i++) s.step(in, p, slab, DT);
		System.out.printf("slab:   walking: x=%.1f y=%.1f (blocked at x=%.0f, y stays 0)%n", s.x, s.y, 2 * B - 16);

		// 7. ladder on a wall at x = 2B facing -x: W vs W + strafe at 45 degrees
		for (int mode = 0; mode < 2; mode++) {
			TestWorld lad = new TestWorld().floor();
			lad.solids.add(HullTrace.Solid.box(2 * B, 0, -1000, 3 * B, 20 * B, 1000));
			lad.ladderN = new double[] {-1, 0, 0};
			lad.ladderX0 = 2 * B - 4;
			lad.ladderX1 = 2 * B;
			s = fresh();
			s.x = 2 * B - 16 - 1;
			in = new SourceMovement.Input();
			in.forward = 1;
			in.yawDeg = mode == 0 ? -90 : -45; // mode 1: looking 45 degrees right of the ladder
			in.side = mode == 0 ? 0 : -1; // ...and strafing left, so the wish direction points into the ladder
			double y0 = s.y;
			for (int i = 0; i < 64; i++) s.step(in, p, lad, DT);
			System.out.printf("ladder: %s climbs %.1f u/s (onLadder=%s)%n", mode == 0 ? "W only      " : "W+A diagonal", (s.y - y0), s.onLadder);
		}
	}

	static SourceMovement fresh() {
		SourceMovement s = new SourceMovement();
		s.onGround = true;
		return s;
	}

	static double d(java.util.Map<?, ?> m, String k) {
		Object v = m.get(k);
		if (v == null) throw new IllegalStateException("missing cvar " + k);
		return switch (v.toString()) {
			case "true" -> 1;
			case "false" -> 0;
			default -> Double.parseDouble(v.toString());
		};
	}
}
