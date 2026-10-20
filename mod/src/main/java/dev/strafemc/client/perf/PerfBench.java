package dev.strafemc.client.perf;

import dev.strafemc.StrafeMc;
import dev.strafemc.client.console.ConsoleLog;
import dev.strafemc.net.StrafePayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Developer benchmark (console: strafemc_perf_bench): the same scene every time, measured with Perf, so changes can be
 * compared. Phases of PHASE ticks each: idle with an AK out, then 2048 bullet holes on the ground in front, then two
 * smokes, then impact effects every tick; each phase's last-second numbers go to the console and the game log.
 * Wants a flat area (the dev test world) and the player standing on it.
 */
public final class PerfBench {
	private static final int PHASE = 160; // 8 s: the averages settle over the last second
	private static final String[] ALL = {"idle", "bullet_holes", "smoke", "effects", "fire"};
	/** -Dstrafemc.perfBenchOnly=a,b runs only those phases. */
	private static final String[] PHASES = System.getProperty("strafemc.perfBenchOnly") != null
			? System.getProperty("strafemc.perfBenchOnly").split(",") : ALL;
	private static int phase = -1, ticks;
	private static final Random RNG = new Random(7);
	/** Quit the game when done (dev runs with -Dstrafemc.perfBench=1). */
	static boolean quitAfter;

	private PerfBench() {}

	public static boolean running() {
		return phase >= 0;
	}

	public static void startAndQuit() {
		quitAfter = true;
		start();
	}

	public static void start() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || running()) return;
		Perf.toggle(true);
		dev.strafemc.client.impact.BulletHoles.clear();
		equipGun(mc);
		var server = mc.getSingleplayerServer(); // the same light every run: noon
		if (server != null)
			server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set noon"));
		mc.player.setXRot(45); // looking down at the field
		mc.player.setYRot(0);
		phase = 0;
		ticks = 0;
		begin(mc);
		log("perf bench: " + PHASE / 20 + " s per phase " + String.join(", ", PHASES));
	}

	public static void tick(Minecraft mc) {
		if (!running() || mc.player == null) return;
		mc.player.setXRot(45);
		mc.player.setYRot(0);
		if (PHASES[phase].equals("effects")) for (int i = 0; i < 6; i++) effect(mc);
		++ticks;
		if (PHASES[phase].equals("fire")) {
			mc.player.setPos(mc.player.getX(), mc.player.getY(), fireZ);
			mc.player.setXRot(30);
			if (ticks == 10 || ticks == 30 || ticks == 60 || ticks == 100 || ticks == 135) shot(mc, "fire_" + ticks);
			// single layers: everything but one hidden (FX_ONLY), a frame later each
			if (ticks >= 70 && ticks < 70 + 2 * FX_ONLY.length) {
				int k = (ticks - 70) / 2;
				if ((ticks - 70) % 2 == 0) dev.strafemc.client.particle.Effects.only(FX_ONLY[k]);
				else shot(mc, "fire_only_" + FX_ONLY[k]);
			}
			if (ticks == 70 + 2 * FX_ONLY.length) dev.strafemc.client.particle.Effects.only(null);
			if (ticks == 60) {
				for (String l : dev.strafemc.client.particle.Effects.layerCounts()) log(l);
				for (String l : dev.strafemc.client.weapon.InfernoClient.describe()) log(l);
			}
		}
		if (ticks < PHASE) return;
		log("== " + PHASES[phase]);
		shot(mc, PHASES[phase]);
		for (String l : Perf.lines()) log(l);
		ticks = 0;
		if (++phase >= PHASES.length) {
			phase = -1;
			log("perf bench done");
			if (quitAfter) mc.stop();
			return;
		}
		begin(mc);
	}

	private static void begin(Minecraft mc) {
		switch (PHASES[phase]) {
			case "bullet_holes" -> holes(mc, 2048);
			case "fire" -> fires(mc);
			case "smoke" -> {
				BlockPos g = ground(mc);
				for (int k = 0; k < 2; k++)
					dev.strafemc.client.weapon.GrenadeClient.onEffect(new StrafePayloads.GrenadeFx(k == 0 ? dev.strafemc.weapon.GrenadeEffects.EV_SMOKE
							: dev.strafemc.weapon.GrenadeEffects.EV_SMOKE_T, g.getX() - 4 + 8 * k, g.getY() + 1, g.getZ() + 10, 0));
			}
			default -> {}
		}
	}

	/**
	 * A molotov fire on open ground (thrown forward) and an incendiary fire against a stone wall, started by the
	 * integrated server as real grenades would.
	 */
	private static double fireZ;
	private static final String[] FX_ONLY = {"groundfire_outline", "main_center", "main_fancy", "snapshot_child_base", "projected", "fire01",
			"climbingoutline", "filler_replicator"};

	private static void fires(Minecraft mc) {
		var server = mc.getSingleplayerServer();
		if (server == null) return;
		// a fixed spot: the player 9 blocks back from the fires, old test walls cleared
		mc.player.setPos(3.5, mc.player.getY(), -2.5);
		BlockPos g = ground(mc);
		Perf.toggle(false);
		fireZ = -2.5;
		var dim = mc.player.level().dimension();
		server.execute(() -> {
			var level = server.getLevel(dim);
			if (level == null) return;
			for (int x = -20; x <= 20; x++)
				for (int z = -10; z <= 30; z++)
					for (int y = 1; y <= 4; y++) level.setBlockAndUpdate(g.offset(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
			for (int x = 3; x <= 10; x++)
				for (int y = 1; y <= 3; y++) level.setBlockAndUpdate(g.offset(x, y, 11), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
			dev.strafemc.weapon.GrenadeZones.fire(level, new Vec3(g.getX() - 3.5, g.getY() + 1, g.getZ() + 7.5), null, new Vec3(0, 0, 8), Float.NaN, false);
			dev.strafemc.weapon.GrenadeZones.fire(level, new Vec3(g.getX() + 6.5, g.getY() + 1, g.getZ() + 9.5), null, new Vec3(0, 0, 8), Float.NaN, true);
		});
	}

	/** A picture of the phase's scene (runtime folder, perf-bench/) to compare looks before and after a change. */
	private static void shot(Minecraft mc, String name) {
		java.nio.file.Path dir = dev.strafemc.config.StrafeConfig.RUNTIME_DIR.resolve("perf-bench");
		try {
			java.nio.file.Files.createDirectories(dir);
		} catch (Exception e) {
			return;
		}
		java.nio.file.Path out = dir.resolve(name + ".png");
		net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(), img -> {
			try (img) {
				img.writeToFile(out);
			} catch (Exception ignored) {
			}
		});
	}

	private static void log(String s) {
		ConsoleLog.info(s);
		StrafeMc.LOG.info("[strafemc] {}", s);
	}

	/** The block the player stands on. */
	private static BlockPos ground(Minecraft mc) {
		return mc.player.blockPosition().below();
	}

	/** Bullet holes on the ground in a 12 x 8 area in front of the player, as server impacts would place them. */
	private static void holes(Minecraft mc, int n) {
		BlockPos g = ground(mc);
		for (int i = 0; i < n; i++) {
			int bx = g.getX() - 6 + RNG.nextInt(12), bz = g.getZ() + 1 + RNG.nextInt(8);
			BlockPos b = new BlockPos(bx, g.getY(), bz);
			dev.strafemc.client.impact.BulletHoles.onImpact(new StrafePayloads.BulletImpact(bx + RNG.nextDouble(), g.getY() + 1.0, bz + RNG.nextDouble(),
					Direction.UP.get3DDataValue(), b.asLong(), "concrete", RNG.nextInt(), 0, 1, 0, false));
		}
	}

	private static void effect(Minecraft mc) {
		BlockPos g = ground(mc);
		var s = dev.strafemc.data.Surfaces.get("concrete");
		if (s == null || s.impactEffect() == null || s.impactEffect().isEmpty()) return;
		dev.strafemc.client.particle.Effects.at(s.impactEffect(), new Vec3(g.getX() - 8 + RNG.nextDouble() * 16, g.getY() + 1.0, g.getZ() + 4 + RNG.nextDouble() * 16),
				new Vec3(0, 1, 0));
	}

	/** An AK-47 in the selected slot (through the integrated server), so the viewmodel draws. */
	private static void equipGun(Minecraft mc) {
		var server = mc.getSingleplayerServer();
		if (server == null) return;
		java.util.UUID id = mc.player.getUUID();
		server.execute(() -> {
			var p = server.getPlayerList().getPlayer(id);
			if (p != null) p.getInventory().setItem(p.getInventory().getSelectedSlot(), new net.minecraft.world.item.ItemStack(dev.strafemc.weapon.WeaponItems.AK47));
		});
	}
}
