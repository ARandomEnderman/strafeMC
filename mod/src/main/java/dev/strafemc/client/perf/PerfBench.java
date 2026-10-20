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
	private static final String[] PHASES = {"idle", "bullet_holes", "smoke", "effects"};
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
		log("perf bench: " + PHASE / 20 + " s per phase " + String.join(", ", PHASES));
	}

	public static void tick(Minecraft mc) {
		if (!running() || mc.player == null) return;
		mc.player.setXRot(45);
		mc.player.setYRot(0);
		if (phase == 3) for (int i = 0; i < 6; i++) effect(mc);
		if (++ticks < PHASE) return;
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
		switch (PHASES[phase]) {
			case "bullet_holes" -> holes(mc, 2048);
			case "smoke" -> {
				BlockPos g = ground(mc);
				for (int k = 0; k < 2; k++)
					dev.strafemc.client.weapon.GrenadeClient.onEffect(new StrafePayloads.GrenadeFx(k == 0 ? dev.strafemc.weapon.GrenadeEffects.EV_SMOKE
							: dev.strafemc.weapon.GrenadeEffects.EV_SMOKE_T, g.getX() - 4 + 8 * k, g.getY() + 1, g.getZ() + 10, 0));
			}
			default -> {}
		}
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
