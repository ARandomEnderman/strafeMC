package dev.strafemc.client.movement;

import dev.strafemc.StrafeMc;
import dev.strafemc.bridge.Cs2Bridge;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.data.WeaponData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives {@link SourceMovement} for the local player at a fixed 64 Hz from the render loop, replacing vanilla
 * player movement entirely while active. Rendered position is interpolated between the last two sim states.
 */
public final class MovementController {
	public static final MovementController INSTANCE = new MovementController();
	public static final double TICK = 1.0 / 64.0;

	private final SourceMovement sim = new SourceMovement();
	private final dev.strafemc.client.sound.Footsteps footsteps = new dev.strafemc.client.sound.Footsteps();
	private final SourceMovement.Params params = new SourceMovement.Params();
	private final SourceMovement.Input input = new SourceMovement.Input();
	private final AtomicInteger wheelJumps = new AtomicInteger();
	/** View yaws seen since the last sim step (one per frame), for CS2 sub-tick view angles. */
	private final float[] yawSamples = new float[32];
	private int yawCount;
	private float lastStepYaw = Float.NaN;
	/**
	 * Minimum view-angle samples per tick for sub-tick air acceleration. CS2 takes sub-tick view angles into account
	 * (sv_subtick_movement_view_angles) but doesn't expose how many; frame samples are used when there are more.
	 */
	private static final int MIN_SUBTICK_SAMPLES = 4;

	private boolean active;
	private long lastNs;
	private double acc;
	private double px, py, pz; // previous sim position (units) for interpolation
	private Vec3 lastWritten;
	/** Velocity (blocks/tick) we last handed to Minecraft; anything MC adds on top is an outside push. */
	private Vec3 lastVelWritten;
	private String inactiveReason = "";
	private long lastHudMs, lastLogMs;
	private int stepsSinceLog;
	private double peakSpeed;

	private MovementController() {}

	public boolean controls(Entity e) {
		return active && e == Minecraft.getInstance().player;
	}

	public boolean active() {
		return active;
	}

	/** Eye height in blocks while active (camera follows the Source view offset). */
	public float eyeHeightBlocks() {
		return (float) (sim.eye / StrafeConfig.unitsPerBlock);
	}

	public boolean onGround() {
		return !active || sim.onGround;
	}

	public boolean onLadder() {
		return active && sim.onLadder;
	}

	public boolean ducked() {
		return active && sim.ducked;
	}

	public double horizontalSpeed() {
		return active ? sim.horizontalSpeed() : 0;
	}

	public boolean noclip() {
		return sim.noclip;
	}

	/** Applied when the MC server answers a noclip request. */
	public void setNoclip(boolean on) {
		sim.noclip = on; // like Source, leaving noclip keeps your current velocity
	}

	public void queueWheelJump() {
		wheelJumps.incrementAndGet();
	}

	public void onFrame() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) {
			setInactive("no player");
			return;
		}
		String why = blockReason(p);
		if (why != null) {
			if (active) {
				// hand momentum back to vanilla (blocks per tick)
				double s = StrafeConfig.unitsPerBlock;
				p.setDeltaMovement(sim.vx / s / 20.0, sim.vy / s / 20.0, sim.vz / s / 20.0);
			}
			setInactive(why);
			return;
		}
		double s = StrafeConfig.unitsPerBlock;
		if (!active || lastWritten == null || p.position().distanceToSqr(lastWritten) > 1e-8) {
			if (active) StrafeMc.LOG.info("[strafemc] external position change ({} -> {}), resyncing sim", lastWritten, p.position());
			resync(p, s, !active);
			if (!active) StrafeMc.LOG.info("[strafemc] Source movement ACTIVE (unitsPerBlock={})", s);
			active = true;
			inactiveReason = "";
			announce(true);
		}
		absorbImpulses(p, s);
		long now = System.nanoTime();
		double frame = Math.min((now - lastNs) / 1e9, 0.25);
		lastNs = now;
		if (mc.isPaused()) return;
		acc += frame * Math.max(0, cvOr("host_timescale", 1)); // host_timescale prescales the clock

		try {
			readParams();
		} catch (RuntimeException e) {
			setInactive(e.getMessage());
			return;
		}
		readInput(mc, p);
		McWorld world = new McWorld(p, s);
		if (yawCount < yawSamples.length) yawSamples[yawCount++] = p.getYRot();
		while (acc >= TICK) {
			px = sim.x;
			py = sim.y;
			pz = sim.z;
			input.yawDeg = p.getYRot();
			input.pitchDeg = p.getXRot();
			input.subYaws = null; // plain Source air acceleration, once per tick
			yawCount = 0;
			sim.collisionLog = StrafeConfig.debug ? m -> StrafeMc.LOG.info("[strafemc] {}", m) : null;
			sim.step(input, params, world, TICK);
			footsteps.afterStep(sim, Math.min(params.maxSpeed, params.weaponMaxSpeed), s);
			dev.strafemc.client.JumpStats.INSTANCE.afterStep(sim, input.side);
			// fall damage: measured by the server (server/ServerFall), never reported from here
			input.jumpImpulse = false;
			acc -= TICK;
			stepsSinceLog++;
			peakSpeed = Math.max(peakSpeed, sim.horizontalSpeed());
			if (StrafeConfig.debug && sim.jumpedThisStep)
				StrafeMc.LOG.info("[strafemc] jump  hspeed={} vy={} t={}", f(sim.horizontalSpeed()), f(sim.vy), f(sim.time));
			if (StrafeConfig.debug && sim.landedThisStep)
				StrafeMc.LOG.info("[strafemc] land  hspeed={} fallspeed={} t={}", f(sim.horizontalSpeed()), f(sim.landSpeed), f(sim.time));
		}

		double a = acc / TICK;
		double rx = (px + (sim.x - px) * a) / s, ry = (py + (sim.y - py) * a) / s, rz = (pz + (sim.z - pz) * a) / s;
		if (sim.onGround) {
			// The Source hull rests DIST_EPSILON (1/32 u) above the floor. Report the exact floor height to Minecraft
			// (block heights are multiples of 1/64) so MC's own boxes don't poke into a ceiling that fits exactly.
			double snapped = Math.round(ry * 64.0) / 64.0;
			if (Math.abs(ry - snapped) < 0.002) ry = snapped;
		}
		p.setPos(rx, ry, rz);
		p.xo = p.xOld = rx;
		p.yo = p.yOld = ry;
		p.zo = p.zOld = rz;
		Vec3 vel = new Vec3(sim.vx / s / 20.0, sim.vy / s / 20.0, sim.vz / s / 20.0);
		p.setDeltaMovement(vel);
		lastVelWritten = vel;
		p.setOnGroundWithMovement(sim.onGround, vel);
		if (sim.onGround) p.resetFallDistance();
		if (p.isSprinting()) p.setSprinting(false);
		lastWritten = p.position();

		hud(mc, p);
		log();
	}

	/**
	 * Knockback, explosions (TNT rocket jumps), wind charges, riptide, bubble columns... all arrive as changes to MC's
	 * delta movement. Add them to the Source velocity so they carry like real momentum. Tiny MC rounding (< 0.02 b/t) is ignored.
	 */
	private void absorbImpulses(LocalPlayer p, double s) {
		if (lastVelWritten == null) return;
		Vec3 d = p.getDeltaMovement().subtract(lastVelWritten);
		if (d.lengthSqr() < 0.02 * 0.02) return;
		sim.vx += d.x * 20.0 * s;
		sim.vy += d.y * 20.0 * s;
		sim.vz += d.z * 20.0 * s;
		if (d.y * 20.0 * s > SourceMovement.Unexposed.NON_JUMP_VELOCITY) sim.onGround = false;
		lastVelWritten = p.getDeltaMovement();
		if (StrafeConfig.debug) StrafeMc.LOG.info("[strafemc] outside push ({}, {}, {}) u/s", f(d.x * 20 * s), f(d.y * 20 * s), f(d.z * 20 * s));
	}

	/** A server velocity packet (blocks/tick) added on top of the Source velocity; tiny ones (plain damage) are ignored. */
	public void addImpulse(Vec3 v) {
		if (v.lengthSqr() < 0.05 * 0.05) {
			if (StrafeConfig.debug) StrafeMc.LOG.info("[strafemc] ignored server velocity reset {}", v);
			return;
		}
		double s = StrafeConfig.unitsPerBlock;
		sim.vx += v.x * 20.0 * s;
		sim.vy += v.y * 20.0 * s;
		sim.vz += v.z * 20.0 * s;
		if (v.y * 20.0 * s > SourceMovement.Unexposed.NON_JUMP_VELOCITY) sim.onGround = false;
		if (StrafeConfig.debug) StrafeMc.LOG.info("[strafemc] server knockback ({}, {}, {}) u/s", f(v.x * 20 * s), f(v.y * 20 * s), f(v.z * 20 * s));
	}

	private void resync(LocalPlayer p, double s, boolean keepMomentum) {
		sim.x = px = p.getX() * s;
		sim.y = py = p.getY() * s;
		sim.z = pz = p.getZ() * s;
		Vec3 d = p.getDeltaMovement();
		if (keepMomentum) {
			sim.vx = d.x * s * 20.0;
			sim.vy = d.y * s * 20.0;
			sim.vz = d.z * s * 20.0;
		} else {
			sim.vx = sim.vy = sim.vz = 0;
		}
		sim.onGround = p.onGround();
		lastNs = System.nanoTime();
		acc = 0;
	}

	private void setInactive(String why) {
		if (active || !why.equals(inactiveReason)) StrafeMc.LOG.info("[strafemc] Source movement inactive: {}", why);
		if (active) announce(false);
		active = false;
		inactiveReason = why;
		lastWritten = null;
		lastVelWritten = null;
	}

	private static void announce(boolean on) {
		if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(dev.strafemc.net.StrafePayloads.SourceMovement.TYPE))
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.strafemc.net.StrafePayloads.SourceMovement(on));
	}

	private String blockReason(LocalPlayer p) {
		if (!dev.strafemc.client.ClientGameplay.sourceMovement()) return "turned off on this server (strafemc_movement)";
		if (!dev.strafemc.client.ClientGameplay.hasCvars()) return "no CS2 cvars yet (start CS2 via launch-cs2.bat -Metamod once)";
		if (!dev.strafemc.client.ClientGameplay.weaponsReady()) return "weapon data not loaded yet";
		if (p.isSpectator()) return "spectator";
		if (p.getAbilities().flying && !sim.noclip) return "flying";
		if (p.isPassenger()) return "riding";
		if (p.isFallFlying()) return "elytra";
		if (p.isInLava() && !sim.noclip) return "in lava";
		if (p.onClimbable() && !sim.noclip
				&& new McWorld(p, StrafeConfig.unitsPerBlock).ladder(p.getX() * StrafeConfig.unitsPerBlock, p.getY() * StrafeConfig.unitsPerBlock,
				p.getZ() * StrafeConfig.unitsPerBlock, SourceMovement.HULL_HALF_WIDTH, sim.hullHeight(), true) == null)
			return "climbing a non-ladder block (vanilla)";
		if (p.isSleeping()) return "sleeping";
		return null;
	}

	private void readParams() {
		params.accelerate = cv("sv_accelerate");
		params.airAccelerate = cv("sv_airaccelerate");
		params.airMaxWishspeed = cv("sv_air_max_wishspeed");
		params.friction = cv("sv_friction");
		params.stopSpeed = cv("sv_stopspeed");
		params.maxSpeed = cv("sv_maxspeed");
		params.gravity = cv("sv_gravity");
		params.jumpImpulse = cv("sv_jump_impulse");
		params.stepSize = cv("sv_stepsize");
		params.maxVelocity = cv("sv_maxvelocity");
		params.timeBetweenDucks = cv("sv_timebetweenducks");
		params.bhopTimeWindow = cvOr("sv_bhop_time_window", 0);
		params.stepMoveVelMin = cvOr("sv_step_move_vel_min", 0);
		params.jumpSpamPenalty = cvOr("sv_jump_spam_penalty_time", 0);
		params.standableNormal = cv("sv_standable_normal");
		params.ladderScaleSpeed = cv("sv_ladder_scale_speed");
		params.ladderAngle = cv("sv_ladder_angle");
		params.ladderDampen = cv("sv_ladder_dampen");
		params.noclipSpeed = cvOr("sv_noclipspeed", Double.NaN);
		params.noclipAccelerate = cvOr("sv_noclipaccelerate", Double.NaN);
		params.noclipFriction = cvOr("sv_noclipfriction", Double.NaN);
		params.noclipSpeedScaleOnShift = cvOr("sv_noclipspeedscaleonshift", Double.NaN);
		params.waterAccelerate = cv("sv_wateraccelerate");
		params.waterFriction = cv("sv_waterfriction");
		params.autoBhop = cv("sv_autobunnyhopping") != 0;
		params.subtickViewAngles = cvOr("sv_subtick_movement_view_angles", 1) != 0;
		params.enableBhop = cv("sv_enablebunnyhopping") != 0;
		// TODO(milestone 3): active CS weapon item; until then the player carries the knife.
		double gun = dev.strafemc.client.weapon.GunClient.heldMaxSpeed(Minecraft.getInstance().player);
		params.weaponMaxSpeed = Double.isNaN(gun) ? dev.strafemc.client.ClientGameplay.maxSpeed("weapon_knife", false) : gun; // held weapon's m_flMaxSpeed
	}

	private static double cv(String n) {
		String v = dev.strafemc.client.ClientGameplay.cvar(n);
		if (v == null) throw new IllegalStateException("cvar " + n + " never received from CS2");
		return parse(v);
	}

	private static double cvOr(String n, double missing) {
		String v = dev.strafemc.client.ClientGameplay.cvar(n);
		return v == null ? missing : parse(v);
	}

	private static double parse(String v) {
		return switch (v) {
			case "true" -> 1;
			case "false" -> 0;
			default -> Double.parseDouble(v);
		};
	}

	private void readInput(Minecraft mc, LocalPlayer p) {
		var o = mc.options;
		boolean ui = mc.screen == null;
		input.forward = ui ? (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0) : 0;
		input.side = ui ? (o.keyRight.isDown() ? 1 : 0) - (o.keyLeft.isDown() ? 1 : 0) : 0;
		dev.strafemc.client.console.Binds.poll(mc);
		input.jumpHeld = ui && (o.keyJump.isDown() || dev.strafemc.client.console.Binds.holdJump);
		input.duck = ui && (o.keyShift.isDown() || dev.strafemc.client.console.Binds.holdDuck);
		input.walk = ui && (o.keySprint.isDown() || dev.strafemc.client.console.Binds.holdSpeed);
		if (wheelJumps.getAndSet(0) > 0) input.jumpImpulse = true;
	}

	private void hud(Minecraft mc, LocalPlayer p) {
		if (!StrafeConfig.showSpeedMeter) return;
		long now = System.currentTimeMillis();
		if (now - lastHudMs < 100) return;
		lastHudMs = now;
		String link = Cs2Bridge.INSTANCE.connected() ? "§aCS2" : "§6CS2 cached";
		p.sendOverlayMessage(Component.literal(String.format(sim.onLadder || sim.noclip ? "§f%.0f §7u/s (3D)  %s%s" : "§f%.0f §7u/s  %s%s", sim.onLadder || sim.noclip ? Math.sqrt(sim.vx * sim.vx + sim.vy * sim.vy + sim.vz * sim.vz) : sim.horizontalSpeed(), link,
				sim.ducked ? " §7duck" : "")));
	}

	private void log() {
		long now = System.currentTimeMillis();
		if (!StrafeConfig.debug || now - lastLogMs < 1000) return;
		lastLogMs = now;
		StrafeMc.LOG.info("[strafemc] move {} steps/s  pos=({}, {}, {})u  v=({}, {}, {})  hspeed={} peak={}  ground={} duck={}  cvars v{} {}",
				stepsSinceLog, f(sim.x), f(sim.y), f(sim.z), f(sim.vx), f(sim.vy), f(sim.vz), f(sim.horizontalSpeed()),
				f(peakSpeed), sim.onGround, sim.ducked, Cs2Bridge.INSTANCE.version(), Cs2Bridge.INSTANCE.connected() ? "live" : "cached");
		stepsSinceLog = 0;
		peakSpeed = 0;
	}

	private static String f(double v) {
		return String.format("%.1f", v);
	}

}
