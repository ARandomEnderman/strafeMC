package dev.strafemc.client.sound;

import dev.strafemc.StrafeMc;
import dev.strafemc.bridge.Cs2Bridge;
import dev.strafemc.client.movement.SourceMovement;
import dev.strafemc.config.StrafeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Movement sounds for the local player: footsteps, jump, landing, ladder steps.
 * Uses the real CS2 sound events (files, volume, pitch, distance curve) with CS2's rules:
 * footstep_audible_threshold (walking/crouching are silent), sv_min_jump_landing_sound, sv_footsteps,
 * and a timed perfect bunnyhop makes no landing or jump sound (autobhop does).
 */
public final class Footsteps {
	/** Not exposed by CS2 (footsteps come from animation events): units travelled per step at run speed. Flagged. */
	public static final double STRIDE_UNITS = 75.0;

	private static final Map<SoundType, String> SURFACES = new IdentityHashMap<>();

	static {
		SURFACES.put(SoundType.STONE, "Concrete");
		SURFACES.put(SoundType.DEEPSLATE, "Concrete");
		SURFACES.put(SoundType.WOOD, "Wood");
		SURFACES.put(SoundType.BAMBOO_WOOD, "Wood");
		SURFACES.put(SoundType.CHERRY_WOOD, "Wood");
		SURFACES.put(SoundType.NETHER_WOOD, "Wood");
		SURFACES.put(SoundType.GRAVEL, "Gravel");
		SURFACES.put(SoundType.GRASS, "Grass");
		SURFACES.put(SoundType.SAND, "Sand");
		SURFACES.put(SoundType.SNOW, "Snow");
		SURFACES.put(SoundType.POWDER_SNOW, "Snow");
		SURFACES.put(SoundType.GLASS, "Glass");
		SURFACES.put(SoundType.WOOL, "Carpet");
		SURFACES.put(SoundType.METAL, "SolidMetal");
		SURFACES.put(SoundType.COPPER, "SolidMetal");
		SURFACES.put(SoundType.CHAIN, "ChainLink");
		SURFACES.put(SoundType.LADDER, "Ladder");
		SURFACES.put(SoundType.SLIME_BLOCK, "Slime");
		SURFACES.put(SoundType.HONEY_BLOCK, "Slime");
		SURFACES.put(SoundType.MUD, "Mud");
		SURFACES.put(SoundType.ROOTED_DIRT, "Dirt");
	}

	private double distSinceStep;
	/** Landing sound held back one step: if a timed perfect bhop follows, CS plays neither landing nor jump sound. */
	private double[] pendingLand;

	/** Called after every sim step (dt = that step's frametime: sub-tick steps are shorter than a tick) with its events. */
	public void afterStep(SourceMovement sim, double maxSpeed, double units, double dt) {
		var mc = Minecraft.getInstance();
		var p = mc.player;
		if (p == null || cv("sv_footsteps", 1) == 0) return;
		double fx = sim.x, fy = sim.y, fz = sim.z;

		boolean perfectTimedBhop = sim.jumpedThisStep && pendingLand != null && !sim.jumpedAuto;
		if (pendingLand != null) {
			if (!perfectTimedBhop) land(pendingLand[0], pendingLand[1], pendingLand[2], units);
			pendingLand = null;
		}
		if (sim.jumpedThisStep && !perfectTimedBhop) play("Default.WalkJump", fx, fy, fz, units);
		if (sim.landedThisStep && sim.landSpeed >= cv("sv_min_jump_landing_sound", 260)) pendingLand = new double[] {fx, fy, fz};

		boolean moving = sim.onLadder ? Math.abs(sim.vy) > 1 : (sim.onGround && sim.horizontalSpeed() > 1);
		if (!moving) return; // the stride counter keeps its progress: tapping A/D still adds up to steps
		double speed = sim.onLadder ? Math.abs(sim.vy) : sim.horizontalSpeed();
		distSinceStep += speed * dt;
		if (distSinceStep < STRIDE_UNITS) return;
		distSinceStep -= STRIDE_UNITS;
		// CS2: steps are only audible above footstep_audible_threshold x max speed, so walking and crouching are silent
		if (!sim.onLadder && speed < cv("footstep_audible_threshold", 0.55) * maxSpeed) return;
		step(sim.onLadder ? "Ladder" : null, fx, fy, fz, units);
	}

	private void step(String forceSurface, double x, double y, double z, double units) {
		BlockState under = below(x, y, z, units);
		String surf = forceSurface != null ? forceSurface : SURFACES.getOrDefault(under.getSoundType(), "Default");
		String team = "CT_"; // follows the agent's team once agents exist
		if (!play(team + surf + ".StepLeft", x, y, z, units)) play(team + "Default.StepLeft", x, y, z, units);
	}

	private void land(double x, double y, double z, double units) {
		String surf = SURFACES.getOrDefault(below(x, y, z, units).getSoundType(), "Default");
		if (!play("Land_" + surf + ".StepLeft", x, y, z, units, StrafeConfig.landVolume))
			play("Land_Default.StepLeft", x, y, z, units, StrafeConfig.landVolume);
	}

	private boolean play(String event, double x, double y, double z, double units) {
		return play(event, x, y, z, units, 1.0);
	}

	private boolean play(String event, double x, double y, double z, double units, double gain) {
		if (!CsAudio.ready()) return !dev.strafemc.client.Placeholders.active(); // no CS2 files: vanilla footsteps instead
		CsSoundEvents.Event e = CsSoundEvents.get(event);
		if (e == null) return false;
		var mc = Minecraft.getInstance();
		var cam = mc.gameRenderer.getMainCamera().position();
		double sy = y + e.offsetUp(); // position_offset is z-up in Hammer units
		double dist = Math.sqrt(sq(cam.x * units - x) + sq(cam.y * units - sy) + sq(cam.z * units - z));
		float vol = (float) (e.volume() * e.volumeAt(dist) * StrafeConfig.movementVolume * gain);
		if (vol <= 0.001f) return true;
		mc.getSoundManager().play(new CsSoundInstance(CsAudio.id(e.pickFile()), Math.min(1f, vol), (float) e.randomPitch(),
				x / units, sy / units, z / units));
		if (StrafeConfig.debug) StrafeMc.LOG.info("[strafemc] sound {} vol={} dist={}u", event, String.format("%.2f", vol), String.format("%.0f", dist));
		return true;
	}

	private static BlockState below(double x, double y, double z, double units) {
		var mc = Minecraft.getInstance();
		BlockState s = mc.level.getBlockState(BlockPos.containing(x / units, (y - 1) / units, z / units));
		return s.isAir() ? Blocks.STONE.defaultBlockState() : s;
	}

	private static double cv(String n, double d) {
		String v = dev.strafemc.client.ClientGameplay.cvar(n);
		if (v == null) return d;
		return switch (v) {
			case "true" -> 1;
			case "false" -> 0;
			default -> {
				try {
					yield Double.parseDouble(v);
				} catch (NumberFormatException e) {
					yield d;
				}
			}
		};
	}

	private static double sq(double v) {
		return v * v;
	}
}
