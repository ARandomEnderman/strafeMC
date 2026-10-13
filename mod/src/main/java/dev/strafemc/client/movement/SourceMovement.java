package dev.strafemc.client.movement;

/**
 * Port of Source/CS player movement (FullWalkMove, LadderMove, FullNoClipMove) at a fixed tick.
 * Units: Hammer units and seconds. Axes follow Minecraft (x, y = up, z); yaw/pitch are Minecraft angles.
 * Every tunable number comes from {@link Params} (live server cvars / weapon VData / engine probe);
 * the few values the engine does not expose are isolated in {@link Unexposed} and flagged.
 */
public final class SourceMovement {
	/** Values the CS2 engine does not expose as cvars or data; Source SDK reference values, to be verified. */
	public static final class Unexposed {
		/** CS_PLAYER_SPEED_DUCK_MODIFIER. */
		public static final double DUCK_SPEED_MODIFIER = 0.34;
		/** CS_PLAYER_SPEED_WALK_MODIFIER. */
		public static final double WALK_SPEED_MODIFIER = 0.52;
		/** NON_JUMP_VELOCITY: rising faster than this means not on ground. */
		public static final double NON_JUMP_VELOCITY = 140.0;
		/** Ground probe distance in CategorizePosition. */
		public static final double GROUND_PROBE = 2.0;
		/** Seconds for the view to move between stand and duck eye heights. */
		public static final double DUCK_VIEW_TIME = 0.125;
		/** MAX_CLIMB_SPEED (scaled by sv_ladder_scale_speed). */
		public static final double MAX_CLIMB_SPEED = 200.0;
		/** Speed pushed off a ladder by jumping. */
		public static final double LADDER_JUMP_SPEED = 270.0;
		/** WaterMove: wish speed scale underwater (Source). */
		public static final double WATER_SPEED_SCALE = 0.8;
		/** WaterMove: sink speed with no input (Source). */
		public static final double WATER_SINK_SPEED = 60.0;
		/** ClipVelocity STOP_EPSILON. */
		public static final double STOP_EPSILON = 0.1;

		private Unexposed() {}
	}

	/** Engine-sourced player facts (CS2 bot pawn probe, 2026-10-10): hull mins/maxs and m_vecViewOffset. */
	public static final double HULL_HALF_WIDTH = 16.0, HULL_STAND = 72.0, HULL_DUCK = 54.0;
	public static final double EYE_STAND = 63.16, EYE_DUCK = 45.16;

	/** All live tunables for one step. */
	public static final class Params {
		public double accelerate, airAccelerate, airMaxWishspeed, friction, stopSpeed, maxSpeed, gravity,
				jumpImpulse, stepSize, maxVelocity, timeBetweenDucks, bhopTimeWindow, stepMoveVelMin, jumpSpamPenalty,
				standableNormal, ladderScaleSpeed, ladderAngle, ladderDampen,
				noclipSpeed, noclipAccelerate, noclipFriction, noclipSpeedScaleOnShift, waterAccelerate, waterFriction;
		public boolean autoBhop, enableBhop, subtickViewAngles = true;
		/** sv_legacy_jump: CS2's pre-2026 jump (full sv_jump_impulse every time, no landing penalty, no bhop window). */
		public boolean legacyJump;
		/** Active weapon m_flMaxSpeed (knife when nothing is held). */
		public double weaponMaxSpeed;
	}

	public static final class Input {
		public double forward, side; // -1..1
		public boolean jumpHeld, jumpImpulse, duck, walk;
		public float yawDeg, pitchDeg;
		/**
		 * View yaws sampled during this tick (one per rendered frame). CS2 takes sub-tick view angles into account during
		 * movement (sv_subtick_movement_view_angles), so air acceleration runs once per sample with a slice of the tick.
		 */
	}

	/** Collision world in Hammer units. */
	public interface World {
		HullTrace.Result trace(double x, double y, double z, double hw, double h, double dx, double dy, double dz);

		boolean free(double x, double y, double z, double hw, double h);

		/**
		 * Outward unit normal of a ladder the hull touches, or null. volume=false: within 2 units of the ladder plate itself
		 * (walking up to it from the front); volume=true: anywhere in the ladder's block (stepping onto it from above).
		 */
		double[] ladder(double x, double y, double z, double hw, double h, boolean volume);

		/** Source water level: 0 dry, 1 feet, 2 waist, 3 eyes under water. */
		default int waterLevel(double x, double y, double z, double h) {
			return 0;
		}

		/** Ground under the hull: {surfaceFriction, speedFactor, jumpFactor, bouncy(1/0)}. */
		default double[] surface(double x, double y, double z, double hw) {
			return NORMAL_SURFACE;
		}

		/** Blocks the hull is stuck in (cobweb, powder snow, berry bush): {horizontal, vertical} speed multiplier, or null. */
		default double[] stuck(double x, double y, double z, double hw, double h) {
			return null;
		}
	}

	public static final double[] NORMAL_SURFACE = {1, 1, 1, 0};

	public double x, y, z, vx, vy, vz;
	/** Current ground surface: Source m_surfaceFriction, MC speed/jump factor, slime. */
	private double[] surf = NORMAL_SURFACE;
	public boolean onGround, ducked, onLadder, noclip;
	/** Source water level of the last step (0..3). */
	public int waterLevel;
	public double eye = EYE_STAND;
	public double time;
	/** Events from the last step, for logging/sounds. */
	public boolean jumpedThisStep, landedThisStep;
	public double landSpeed;
	/**
	 * CS2's 2026 jump ("jump impulse mul" in cl_showpos): landing sets it to 1 - fall speed x LAND_PENALTY, it recovers
	 * MUL_RECOVERY per second (in the air too), and a jump's take-off speed is it x (sv_jump_impulse - gravity / 256).
	 * Measured from CS2 (cl_showpos, host_timescale 0.25): standing jump apex 55.83 u; landings from 298.9 / 262.3 /
	 * 257.8 u/s gave 0.851 / 0.869 / 0.871; recovery 0.61 per s; held-space hops 0.655 s apart. FLAGGED: fitted
	 * constants (the same numbers as sv_staminalandcost / 100 and sv_staminarecoveryrate / 100), and a landing while
	 * still penalised keeps the lower value.
	 */
	public double jumpMul = 1;
	static final double LAND_PENALTY = 0.0005, MUL_RECOVERY = 0.6;
	/** True when the last jump came from sv_autobunnyhopping (held), not a timed press. */
	public boolean jumpedAuto;
	/** Source m_flFallVelocity: downward speed recorded before moving (the move itself may clip vy to 0 on impact). */
	private double fallVelocity;

	private boolean jumpWasHeld;
	private double lastJumpPress = -1e9, prevJumpPress = -1e9, landTime = -1e9, lastDuckPress = -1e9;
	private boolean duckWasHeld;

	public double hullHeight() {
		return ducked ? HULL_DUCK : HULL_STAND;
	}

	public double horizontalSpeed() {
		return Math.hypot(vx, vz);
	}

	public void step(Input in, Params p, World w, double dt) {
		time += dt;
		jumpedThisStep = landedThisStep = false;

		if (noclip) {
			noclipMove(in, p, dt);
			return;
		}

		boolean wasDucked = ducked, airborne = !onGround;
		updateDuck(in, p, w);
		boolean unduckLanding = false;
		if (airborne && wasDucked && !ducked && vy <= 0) {
			// Source FinishUnDuck -> CategorizePosition: lowering the feet may touch the floor this very tick.
			// Jumping on that tick never registers a landing: the jumpbug (no fall damage, no landing sound).
			categorize(p, w);
			unduckLanding = onGround;
		}

		double maxSpeed = Math.min(p.maxSpeed, p.weaponMaxSpeed);
		surf = onGround ? w.surface(x, y, z, HULL_HALF_WIDTH) : NORMAL_SURFACE;
		double[] stuck = w.stuck(x, y, z, HULL_HALF_WIDTH, hullHeight());

		double yaw = Math.toRadians(in.yawDeg);
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);
		double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
		double wx = fx * in.forward + rx * in.side;
		double wz = fz * in.forward + rz * in.side;
		double wl = Math.hypot(wx, wz);
		double wishSpeed = 0;
		if (wl > 1e-6) {
			wx /= wl;
			wz /= wl;
			wishSpeed = maxSpeed * Math.min(1.0, wl); // cl_forwardspeed/sidespeed exceed maxspeed -> clamps to maxspeed
		}
		if (ducked && onGround) wishSpeed *= Unexposed.DUCK_SPEED_MODIFIER;
		else if (in.walk) wishSpeed *= Unexposed.WALK_SPEED_MODIFIER;
		if (onGround) wishSpeed *= surf[1]; // soul sand / honey: only while touching the ground, so bhopping keeps speed
		if (stuck != null) wishSpeed *= stuck[0];

		waterLevel = w.waterLevel(x, y, z, hullHeight());
		if (waterLevel >= 2) {
			waterMove(in, p, w, maxSpeed, dt);
			return;
		}

		double[] ladderN = w.ladder(x, y, z, HULL_HALF_WIDTH, hullHeight(), true);
		double[] ladderPlate = ladderN == null ? null : w.ladder(x, y, z, HULL_HALF_WIDTH, hullHeight(), false);
		// Source: you only get onto a ladder by pushing into it; once climbing (off the ground) you stay on it
		// until you strafe off its side, jump off, or walk away from it on the floor.
		boolean pushingInto = ladderPlate != null && wl > 1e-6 && (wx * ladderPlate[0] + wz * ladderPlate[2]) < -0.1;
		// Walking off a ledge into a ladder volume also grabs it; facing away from the wall then climbs down (Source).
		boolean airborneWithInput = ladderN != null && !onGround && wl > 1e-6 && vy <= 0;
		onLadder = ladderN != null && (pushingInto || airborneWithInput || (onLadder && !onGround));
		if (onLadder) {
			ladderMove(in, p, w, ladderN, dt);
			return;
		}

		if (!p.legacyJump) jumpMul = Math.min(1, jumpMul + MUL_RECOVERY * dt);
		vy -= p.gravity * 0.5 * dt; // StartGravity
		if (!onGround) fallVelocity = -vy;

		checkJump(in, p, maxSpeed, dt);
		if (unduckLanding && !jumpedThisStep) { // un-ducked onto the floor without jumping: a normal landing
			landedThisStep = true;
			landSpeed = fallVelocity;
			landTime = time;
			if (!p.legacyJump) jumpMul = Math.max(0, Math.min(jumpMul, 1 - Math.max(0, landSpeed) * LAND_PENALTY));
		}

		if (onGround) {
			vy = 0;
			friction(p.friction, p.stopSpeed, dt);
		}
		checkVelocity(p);

		if (onGround) walkMove(wx, wz, wishSpeed, p, w, dt);
		else airMove(in, wx, wz, wishSpeed, p, w, dt);

		boolean wasGround = onGround;
		categorize(p, w);
		if (!wasGround && onGround) {
			landedThisStep = true;
			landSpeed = fallVelocity;
			landTime = time;
			if (!p.legacyJump) jumpMul = Math.max(0, Math.min(jumpMul, 1 - Math.max(0, landSpeed) * LAND_PENALTY));
			double[] under = w.surface(x, y, z, HULL_HALF_WIDTH);
			if (under[3] > 0 && !ducked && landSpeed > Unexposed.NON_JUMP_VELOCITY) {
				vy = landSpeed; // slime: bounce back up like vanilla (crouching lands normally)
				onGround = false;
			}
		}
		if (stuck != null) { // cobweb etc.: MC drains momentum every tick while inside
			vx *= stuck[0];
			vz *= stuck[0];
			// MC scales one tick's movement by the vertical factor and then zeroes the velocity, so a stuck entity never
			// builds vertical speed: it moves at most one tick of gravity x factor (powder snow 1.5 = a slow sink, not
			// a speed-up). Multiplying vy by 1.5 every step instead made powder snow accelerate falls into lethal landings.
			double cap = p.gravity / 20 * stuck[1];
			vy = Math.max(-cap, Math.min(cap, vy * Math.min(1, stuck[1])));
		}
		checkVelocity(p);

		vy -= p.gravity * 0.5 * dt; // FinishGravity
		if (onGround) vy = 0;

		updateEye(dt);
	}

	private void updateEye(double dt) {
		double target = ducked ? EYE_DUCK : EYE_STAND;
		double rate = (EYE_STAND - EYE_DUCK) / Unexposed.DUCK_VIEW_TIME * dt;
		eye = eye < target ? Math.min(target, eye + rate) : Math.max(target, eye - rate);
	}

	// ---------------------------------------------------------------- jump / friction / accel

	private void checkJump(Input in, Params p, double maxSpeed, double dt) {
		boolean edge = (in.jumpHeld && !jumpWasHeld) || in.jumpImpulse;
		jumpWasHeld = in.jumpHeld;
		if (edge) {
			if (time - prevJumpPress <= p.jumpSpamPenalty) edge = false; // sv_jump_spam_penalty_time
			prevJumpPress = time;
			if (edge) lastJumpPress = time;
		}
		if (!onGround) return;

		boolean timed = edge;
		boolean want = edge || (in.jumpHeld && p.autoBhop);
		// sv_bhop_time_window: a press shortly before landing still counts as a jump on landing
		if (!want && !p.legacyJump && time - landTime <= dt + 1e-9 && lastJumpPress <= landTime && landTime - lastJumpPress <= p.bhopTimeWindow + dt) {
			want = true;
			timed = true;
		}
		if (!want) return;

		if (!p.enableBhop) {
			// sv_enablebunnyhopping 0: "jump speed may not exceed 1.1x max speed"
			double cap = 1.1 * maxSpeed, hs = horizontalSpeed();
			if (hs > cap && hs > 0) {
				vx *= cap / hs;
				vz *= cap / hs;
			}
		}
		double impulse = p.legacyJump ? p.jumpImpulse : jumpMul * (p.jumpImpulse - p.gravity / 256);
		vy = impulse * surf[2]; // honey: MC jump factor
		vy -= p.gravity * 0.5 * dt; // FinishGravity inside CheckJumpButton
		onGround = false;
		lastJumpPress = -1e9;
		jumpedThisStep = true;
		jumpedAuto = !timed;
	}

	private void friction(double friction, double stopSpeed, double dt) {
		double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
		if (speed < 0.1) return;
		double control = Math.max(speed, stopSpeed);
		double drop = control * friction * surf[0] * dt;
		double ns = Math.max(0, speed - drop) / speed;
		vx *= ns;
		vy *= ns;
		vz *= ns;
	}

	private void accelerate(double wx, double wy, double wz, double wishSpeed, double accel, double dt) {
		double cur = vx * wx + vy * wy + vz * wz;
		double add = wishSpeed - cur;
		if (add <= 0) return;
		double as = Math.min(accel * dt * wishSpeed * surf[0], add);
		vx += as * wx;
		vy += as * wy;
		vz += as * wz;
	}

	/**
	 * CS2 AirAccelerate: addspeed = min(wishspeed, sv_air_max_wishspeed) - current speed along wishdir; accelspeed =
	 * sv_airaccelerate * wishspeed (uncapped: what makes air strafing work) * frametime. Applied as in CS2: if half of
	 * accelspeed already covers addspeed, all of it now; else half now and the rest of min(accelspeed, addspeed) after
	 * the move (returned as {vx, vz} to add then). Same total as classic Source; the position integrates with half.
	 */
	private double[] airAccelerate(double wx, double wz, double wishSpeed, double accel, double cap, double dt) {
		double wishSpd = Math.min(wishSpeed, cap); // sv_air_max_wishspeed
		double cur = vx * wx + vz * wz;
		double add = wishSpd - cur;
		if (add <= 0 || dt <= 0) return new double[2];
		double as = accel * wishSpeed * dt, now, later;
		if (as * 0.5 > add) {
			now = add;
			later = 0;
		} else {
			now = as * 0.5;
			later = Math.min(as, add) - now;
		}
		vx += now * wx;
		vz += now * wz;
		return new double[] {later * wx, later * wz};
	}

	private void checkVelocity(Params p) {
		double m = p.maxVelocity; // per axis
		vx = Math.max(-m, Math.min(m, vx));
		vy = Math.max(-m, Math.min(m, vy));
		vz = Math.max(-m, Math.min(m, vz));
	}

	// ---------------------------------------------------------------- ground / air

	private void walkMove(double wx, double wz, double wishSpeed, Params p, World w, double dt) {
		vy = 0;
		accelerate(wx, 0, wz, wishSpeed, p.accelerate, dt);
		vy = 0;
		if (horizontalSpeed() < 1.0) {
			vx = vz = 0;
			return;
		}
		HullTrace.Result t = w.trace(x, y, z, HULL_HALF_WIDTH, hullHeight(), vx * dt, 0, vz * dt);
		if (!t.hit) {
			x = t.ex;
			z = t.ez;
			stayOnGround(p, w);
			return;
		}
		stepMove(p, w, dt);
		stayOnGround(p, w);
	}

	private void airMove(Input in, double wx, double wz, double wishSpeed, Params p, World w, double dt) {
		// CS2 air acceleration: half of the acceleration before the move, the rest after it
		double[] later = airAccelerate(wx, wz, wishSpeed, p.airAccelerate, p.airMaxWishspeed, dt);
		tryPlayerMove(w, dt);
		vx += later[0];
		vz += later[1];
	}

	/** Source TryPlayerMove: up to 4 bumps, clipping velocity against every plane hit (overbounce 1). */
	/** Debug sink for collision events in the air (surf diagnosis); null = off. */
	public java.util.function.Consumer<String> collisionLog;

	private void log(String fmt, Object... a) {
		if (collisionLog != null && !onGround) collisionLog.accept(String.format(java.util.Locale.ROOT, fmt, a));
	}

	private void tryPlayerMove(World w, double dt) {
		double[][] planes = new double[5][];
		int numPlanes = 0;
		double ovx = vx, ovy = vy, ovz = vz; // original_velocity
		double pvx = vx, pvy = vy, pvz = vz; // primal_velocity
		double timeLeft = dt, allFraction = 0;
		double h = hullHeight();
		for (int bump = 0; bump < 4; bump++) {
			if (vx == 0 && vy == 0 && vz == 0) break;
			HullTrace.Result t = w.trace(x, y, z, HULL_HALF_WIDTH, h, vx * timeLeft, vy * timeLeft, vz * timeLeft);
			allFraction += t.fraction;
			if (t.startSolid) {
				log("airmove STARTSOLID at (%.2f %.2f %.2f) v(%.1f %.1f %.1f) -> 0", x, y, z, vx, vy, vz);
				vx = vy = vz = 0;
				return;
			}
			if (t.fraction > 0) {
				x = t.ex;
				y = t.ey;
				z = t.ez;
				ovx = vx;
				ovy = vy;
				ovz = vz;
				numPlanes = 0;
			}
			if (!t.hit) break;
			timeLeft -= timeLeft * t.fraction;
			if (numPlanes >= planes.length) {
				vx = vy = vz = 0;
				break;
			}
			planes[numPlanes++] = new double[] {t.nx, t.ny, t.nz};
			double bvx = vx, bvy = vy, bvz = vz;

			if (numPlanes == 1 && !onGround) {
				double[] c = clip(ovx, ovy, ovz, planes[0]);
				vx = ovx = c[0];
				vy = ovy = c[1];
				vz = ovz = c[2];
			} else {
				int i;
				for (i = 0; i < numPlanes; i++) {
					double[] c = clip(ovx, ovy, ovz, planes[i]);
					vx = c[0];
					vy = c[1];
					vz = c[2];
					int j;
					for (j = 0; j < numPlanes; j++) {
						if (j != i && vx * planes[j][0] + vy * planes[j][1] + vz * planes[j][2] < 0) break;
					}
					if (j == numPlanes) break;
				}
				if (i == numPlanes) {
					if (numPlanes != 2) {
						vx = vy = vz = 0;
						break;
					}
					double[] a = planes[0], b = planes[1];
					double dx = a[1] * b[2] - a[2] * b[1], dy = a[2] * b[0] - a[0] * b[2], dz = a[0] * b[1] - a[1] * b[0];
					double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
					if (l < 1e-9) {
						vx = vy = vz = 0;
						break;
					}
					dx /= l;
					dy /= l;
					dz /= l;
					double d = dx * vx + dy * vy + dz * vz;
					vx = dx * d;
					vy = dy * d;
					vz = dz * d;
				}
				if (vx * pvx + vy * pvy + vz * pvz <= 0) {
					log("airmove bump %d: velocity reversed vs primal -> 0", bump);
					vx = vy = vz = 0;
					break;
				}
			}
			log("airmove bump %d frac %.3f planes %d n(%.3f %.3f %.3f) v(%.1f %.1f %.1f) -> (%.1f %.1f %.1f) pos(%.1f %.1f %.1f)",
					bump, t.fraction, numPlanes, t.nx, t.ny, t.nz, bvx, bvy, bvz, vx, vy, vz, x, y, z);
		}
		if (allFraction == 0) {
			if (vx != 0 || vy != 0 || vz != 0) log("airmove allFraction 0: stuck, v(%.1f %.1f %.1f) -> 0", vx, vy, vz);
			vx = vy = vz = 0;
		}
	}

	/** ClipVelocity with overbounce 1 (sv_bounce 0). */
	private static double[] clip(double ix, double iy, double iz, double[] n) {
		double backoff = ix * n[0] + iy * n[1] + iz * n[2];
		double ox = ix - n[0] * backoff, oy = iy - n[1] * backoff, oz = iz - n[2] * backoff;
		if (Math.abs(ox) < Unexposed.STOP_EPSILON) ox = 0;
		if (Math.abs(oy) < Unexposed.STOP_EPSILON) oy = 0;
		if (Math.abs(oz) < Unexposed.STOP_EPSILON) oz = 0;
		double adjust = ox * n[0] + oy * n[1] + oz * n[2];
		if (adjust < 0) {
			ox -= n[0] * adjust;
			oy -= n[1] * adjust;
			oz -= n[2] * adjust;
		}
		return new double[] {ox, oy, oz};
	}

	/** Source StepMove: compare a plain slide with a slide raised by sv_stepsize, keep whichever got farther. */
	private void stepMove(Params p, World w, double dt) {
		double sx = x, sy = y, sz = z, svx = vx, svy = vy, svz = vz;
		double h = hullHeight();

		tryPlayerMove(w, dt);
		double downX = x, downY = y, downZ = z, downVx = vx, downVy = vy, downVz = vz;
		if (Math.hypot(svx, svz) < p.stepMoveVelMin) return; // sv_step_move_vel_min

		x = sx;
		y = sy;
		z = sz;
		vx = svx;
		vy = svy;
		vz = svz;
		HullTrace.Result up = w.trace(x, y, z, HULL_HALF_WIDTH, h, 0, p.stepSize + HullTrace.DIST_EPSILON, 0);
		if (!up.startSolid) y = up.ey;
		tryPlayerMove(w, dt);
		HullTrace.Result down = w.trace(x, y, z, HULL_HALF_WIDTH, h, 0, -(p.stepSize + HullTrace.DIST_EPSILON), 0);
		if (!down.hit || down.ny < p.standableNormal) {
			x = downX; y = downY; z = downZ; vx = downVx; vy = downVy; vz = downVz;
			return;
		}
		if (!down.startSolid) y = down.ey;
		double downDist = sq(downX - sx) + sq(downZ - sz);
		double upDist = sq(x - sx) + sq(z - sz);
		if (downDist > upDist) {
			x = downX; y = downY; z = downZ; vx = downVx; vy = downVy; vz = downVz;
		} else {
			vy = downVy;
		}
	}

	/** Source StayOnGround: glue to the ground over small descents (ramps, steps down). */
	private void stayOnGround(Params p, World w) {
		double h = hullHeight();
		HullTrace.Result up = w.trace(x, y, z, HULL_HALF_WIDTH, h, 0, 2, 0);
		double sy = up.ey;
		HullTrace.Result down = w.trace(x, sy, z, HULL_HALF_WIDTH, h, 0, (y - p.stepSize) - sy, 0);
		if (down.hit && down.fraction > 0 && !down.startSolid && down.ny >= p.standableNormal) {
			if (Math.abs(y - down.ey) > 0.5 * (1.0 / 32.0)) y = down.ey;
		}
	}

	private void categorize(Params p, World w) {
		if (vy > Unexposed.NON_JUMP_VELOCITY) {
			onGround = false;
			return;
		}
		HullTrace.Result t = w.trace(x, y, z, HULL_HALF_WIDTH, hullHeight(), 0, -Unexposed.GROUND_PROBE, 0);
		if (t.hit && t.ny >= p.standableNormal) {
			onGround = true;
			if (!t.startSolid) y = t.ey;
		} else onGround = false;
	}

	// ---------------------------------------------------------------- ladder

	/** Source/CS:GO LadderMove: pushing into the ladder becomes climbing; diagonal input climbs faster. */
	private void ladderMove(Input in, Params p, World w, double[] n, double dt) {
		onGround = false;
		if (in.jumpHeld && !jumpWasHeld) {
			jumpWasHeld = true;
			onLadder = false;
			vx = n[0] * Unexposed.LADDER_JUMP_SPEED;
			vy = n[1] * Unexposed.LADDER_JUMP_SPEED;
			vz = n[2] * Unexposed.LADDER_JUMP_SPEED;
			tryPlayerMove(w, dt);
			return;
		}
		jumpWasHeld = in.jumpHeld;
		double climb = Unexposed.MAX_CLIMB_SPEED * p.ladderScaleSpeed;
		double fwd = in.forward * climb, right = in.side * climb;
		if (fwd == 0 && right == 0) {
			vx = vy = vz = 0;
		} else {
			double yaw = Math.toRadians(in.yawDeg), pitch = Math.toRadians(in.pitchDeg);
			double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
			double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
			double velx = fx * fwd + rx * right, vely = fy * fwd, velz = fz * fwd + rz * right;
			// perp = normalize(up x n), the horizontal direction along the ladder
			double px = n[2], pz = -n[0];
			double pl = Math.hypot(px, pz);
			px /= pl;
			pz /= pl;
			double normal = velx * n[0] + vely * n[1] + velz * n[2]; // velocity into the ladder face
			double cx = n[0] * normal, cy = n[1] * normal, cz = n[2] * normal;
			double lx = velx - cx, ly = vely - cy, lz = velz - cz; // lateral
			// tmp = n x perp (perp has no y component): the "up the ladder" direction
			double[] tmp = {n[1] * pz, n[2] * px - n[0] * pz, -n[1] * px};
			double tmpDist = tmp[0] * lx + tmp[1] * ly + tmp[2] * lz;
			double perpDist = px * lx + pz * lz;
			// CS:GO: damp sideways movement unless pushing into the ladder at a steep enough angle (sv_ladder_angle/dampen)
			double ax = px * perpDist + cx, ay = cy, az = pz * perpDist + cz;
			double al = Math.sqrt(ax * ax + ay * ay + az * az);
			if (al > 1e-9) {
				double angleDot = (ax * n[0] + ay * n[1] + az * n[2]) / al;
				if (angleDot < p.ladderAngle) {
					lx = tmp[0] * tmpDist + px * p.ladderDampen * perpDist;
					ly = tmp[1] * tmpDist;
					lz = tmp[2] * tmpDist + pz * p.ladderDampen * perpDist;
				}
			}
			vx = lx - normal * tmp[0];
			vy = ly - normal * tmp[1];
			vz = lz - normal * tmp[2];
			HullTrace.Result g = w.trace(x, y, z, HULL_HALF_WIDTH, hullHeight(), 0, -Unexposed.GROUND_PROBE, 0);
			boolean onFloor = g.hit && g.ny >= p.standableNormal;
			if (onFloor && normal > 0) { // on the floor moving away from the ladder
				vx += n[0] * Unexposed.MAX_CLIMB_SPEED;
				vz += n[2] * Unexposed.MAX_CLIMB_SPEED;
			}
		}
		tryPlayerMove(w, dt);
		updateEye(dt);
	}

	// ---------------------------------------------------------------- water

	/** Source WaterMove: swim along the view (pitch included), jump swims up, idle sinks; sv_waterfriction / sv_wateraccelerate. */
	private void waterMove(Input in, Params p, World w, double maxSpeed, double dt) {
		onGround = false;
		onLadder = false;
		double yaw = Math.toRadians(in.yawDeg), pitch = Math.toRadians(in.pitchDeg);
		double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
		double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
		double wx = (fx * in.forward + rx * in.side) * maxSpeed;
		double wy = fy * in.forward * maxSpeed;
		double wz = (fz * in.forward + rz * in.side) * maxSpeed;
		if (in.jumpHeld) wy += maxSpeed;
		else if (in.forward == 0 && in.side == 0) wy -= Unexposed.WATER_SINK_SPEED;
		double wishSpeed = Math.sqrt(wx * wx + wy * wy + wz * wz);
		if (wishSpeed > maxSpeed) {
			double k = maxSpeed / wishSpeed;
			wx *= k;
			wy *= k;
			wz *= k;
			wishSpeed = maxSpeed;
		}
		wishSpeed *= Unexposed.WATER_SPEED_SCALE;

		double speed = Math.sqrt(vx * vx + vy * vy + vz * vz), newSpeed = 0;
		if (speed > 0) {
			newSpeed = Math.max(0, speed - dt * speed * p.waterFriction * surf[0]);
			vx *= newSpeed / speed;
			vy *= newSpeed / speed;
			vz *= newSpeed / speed;
		}
		if (wishSpeed >= 0.1) {
			double add = wishSpeed - newSpeed;
			if (add > 0) {
				double l = Math.sqrt(wx * wx + wy * wy + wz * wz);
				double as = Math.min(p.waterAccelerate * wishSpeed * dt * surf[0], add);
				vx += as * wx / l;
				vy += as * wy / l;
				vz += as * wz / l;
			}
		}
		checkVelocity(p);
		HullTrace.Result t = w.trace(x, y, z, HULL_HALF_WIDTH, hullHeight(), vx * dt, vy * dt, vz * dt);
		if (!t.hit) {
			x = t.ex;
			y = t.ey;
			z = t.ez;
		} else {
			stepMove(p, w, dt);
		}
		updateEye(dt);
	}

	// ---------------------------------------------------------------- noclip

	/** Source FullNoClipMove with sv_noclip* cvars: fly along the view, no collision. */
	private void noclipMove(Input in, Params p, double dt) {
		onGround = false;
		surf = NORMAL_SURFACE;
		double yaw = Math.toRadians(in.yawDeg), pitch = Math.toRadians(in.pitchDeg);
		double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
		double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
		double up = (in.jumpHeld ? 1 : 0) - (in.duck ? 1 : 0);
		double wx = fx * in.forward + rx * in.side, wy = fy * in.forward + up, wz = fz * in.forward + rz * in.side;
		double wl = Math.sqrt(wx * wx + wy * wy + wz * wz);
		double wishSpeed = 0;
		if (wl > 1e-6) {
			wx /= wl;
			wy /= wl;
			wz /= wl;
			wishSpeed = p.noclipSpeed;
			if (in.walk) wishSpeed *= p.noclipSpeedScaleOnShift;
		}
		friction(p.noclipFriction, p.stopSpeed, dt);
		if (wishSpeed > 0) accelerate(wx, wy, wz, wishSpeed, p.noclipAccelerate, dt);
		x += vx * dt;
		y += vy * dt;
		z += vz * dt;
		updateEye(dt);
	}

	// ---------------------------------------------------------------- duck

	private void updateDuck(Input in, Params p, World w) {
		boolean press = in.duck && !duckWasHeld;
		duckWasHeld = in.duck;
		if (press) {
			if (time - lastDuckPress < p.timeBetweenDucks && !ducked) return; // sv_timebetweenducks
			lastDuckPress = time;
		}
		double diff = HULL_STAND - HULL_DUCK;
		if (in.duck && !ducked) {
			if (!onGround) {
				y += diff; // in air the hull shrinks from below: feet come up, head stays
				eye = Math.max(EYE_DUCK, eye - diff);
			}
			ducked = true;
		} else if (!in.duck && ducked) {
			if (onGround) {
				if (w.free(x, y, z, HULL_HALF_WIDTH, HULL_STAND)) ducked = false;
			} else if (w.free(x, y - diff, z, HULL_HALF_WIDTH, HULL_STAND)) {
				y -= diff;
				eye = Math.min(EYE_STAND, eye + diff);
				ducked = false;
			} else if (w.free(x, y, z, HULL_HALF_WIDTH, HULL_STAND)) {
				ducked = false;
			}
		}
	}

	private static double sq(double v) {
		return v * v;
	}
}
