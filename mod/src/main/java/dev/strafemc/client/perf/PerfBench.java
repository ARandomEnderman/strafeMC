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
		mc.options.pauseOnLostFocus = false; // the bench runs in a window in the background
		if (mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen) mc.setScreen(null);
		mc.player.setXRot(45);
		mc.player.setYRot(0);
		if (PHASES[phase].equals("effects")) for (int i = 0; i < 6; i++) effect(mc);
		++ticks;
		if (PHASES[phase].equals("armsrace")) armsRace(mc, ticks);
		if (PHASES[phase].equals("modes")) modes(mc, ticks);
		if (PHASES[phase].equals("rounds")) rounds(mc, ticks);
		if (PHASES[phase].equals("botplant")) botPlant(mc, ticks);
		if (PHASES[phase].equals("zones")) zones(mc, ticks);
		if (PHASES[phase].equals("c4")) { // close over the planted bomb, then from its side
			mc.player.getAbilities().flying = true;
			mc.player.setDeltaMovement(Vec3.ZERO);
			boolean side = ticks >= 80;
			double eye = mc.player.getEyeHeight(); // spectating: the eyes may go anywhere
			mc.player.setPos(c4At.x + (side ? 0.8 : 0), c4At.y + (side ? 0.3 : 0.75) - eye, c4At.z - (side ? 0 : 0.15));
			mc.player.setYRot(side ? 90 : 0);
			mc.player.setXRot(side ? 18 : 66);
			mc.options.fov().set(40); // zoomed in on it
			if (ticks == 70) shot(mc, "c4_top");
			if (ticks == 150) shot(mc, "c4_side");
			if (ticks == PHASE - 1) { // as it was: field of view, game mode, standing on the ground by the bomb
				mc.options.fov().set(70);
				var server = mc.getSingleplayerServer();
				java.util.UUID id = mc.player.getUUID();
				net.minecraft.world.level.GameType mode = c4Mode == null || c4Mode == net.minecraft.world.level.GameType.SPECTATOR
						? net.minecraft.world.level.GameType.CREATIVE : c4Mode;
				Vec3 back = c4At.add(0, 0, -2);
				if (server != null) server.execute(() -> {
					var p = server.getPlayerList().getPlayer(id);
					if (p == null) return;
					p.setGameMode(mode);
					p.teleportTo(back.x, back.y, back.z);
				});
			}
		}
		if (PHASES[phase].equals("fire")) {
			if (ticks < 104) {
				mc.player.setPos(3.5, fireY, fireZ);
				mc.player.setXRot(28);
			} else { // from above, looking down on the molotov
				mc.player.getAbilities().flying = true;
				mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
				mc.player.setPos(3.5, fireY + 6, fireZ + 1);
				mc.player.setYRot(0);
				mc.player.setXRot(62);
			}
			if (ticks == 112) shot(mc, "fire_top");
			if (ticks >= 90 && ticks < 100) mc.player.setYRot(-58); // the incendiary
			if (ticks == 96) shot(mc, "fire_incendiary");
			if (ticks == 10 || ticks == 30 || ticks == 60 || ticks == 100 || ticks == 135) shot(mc, "fire_" + ticks);
			// single layers: everything but one hidden (FX_ONLY), a frame later each
			if (ticks >= 70 && ticks < 70 + 2 * FX_ONLY.length) {
				int k = (ticks - 70) / 2;
				if ((ticks - 70) % 2 == 0) dev.strafemc.client.particle.Effects.only(FX_ONLY[k]);
				else shot(mc, "fire_only_" + FX_ONLY[k]);
			}
			if (ticks == 70 + 2 * FX_ONLY.length) dev.strafemc.client.particle.Effects.only(null);
			if (ticks == 8) for (String l : dev.strafemc.client.particle.Effects.layerCounts()) if (l.contains("sprays") || l.contains("   p ")) log(l);
			if (ticks == 60) {
				for (String l : dev.strafemc.client.particle.Effects.layerCounts()) log(l);
				for (String l : dev.strafemc.client.weapon.InfernoClient.describe()) log(l);
			}
		}
		if (ticks < (PHASES[phase].equals("botplant") ? 1400 : PHASES[phase].equals("zones") ? 520 : PHASE)) return;
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
			case "c4" -> c4(mc);
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
	private static double fireZ, fireY;
	private static final String[] FX_ONLY = {"groundfire_outline", "main_center", "main_fancy", "snapshot_child_base", "projected", "fire01",
			"climbingoutline", "filler_replicator"};

	private static void fires(Minecraft mc) {
		var server = mc.getSingleplayerServer();
		if (server == null) return;
		// a fixed spot: the player 9 blocks back from the fires, old test walls cleared
		mc.player.setPos(3.5, mc.player.getY(), 0.5);
		BlockPos g = ground(mc);
		Perf.toggle(false);
		fireZ = 0.5;
		fireY = mc.player.getY();
		var dim = mc.player.level().dimension();
		server.execute(() -> {
			var level = server.getLevel(dim);
			if (level == null) return;
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "kill @e[type=!player]");
			for (int x = -20; x <= 20; x++)
				for (int z = -10; z <= 30; z++)
					for (int y = 1; y <= 4; y++) level.setBlockAndUpdate(g.offset(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
			for (int x = 10; x <= 17; x++)
				for (int y = 1; y <= 3; y++) level.setBlockAndUpdate(g.offset(x, y, 9), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
			dev.strafemc.weapon.GrenadeZones.fire(level, new Vec3(g.getX() + 0.5, g.getY() + 1, g.getZ() + 6.5), null, new Vec3(0, 0, 8), Float.NaN, false);
			dev.strafemc.weapon.GrenadeZones.fire(level, new Vec3(g.getX() + 13.5, g.getY() + 1, g.getZ() + 7.5), null, new Vec3(0, 0, 8), Float.NaN, true);
		});
	}

	/**
	 * Arms Race: the mode on with a bot on the other side, kills handed to the rules (warmup skipped), the player killed
	 * and respawned, then the mode off; the log shows the inventory at each step and shots show the HUD.
	 */
	private static void armsRace(Minecraft mc, int t) {
		var server = mc.getSingleplayerServer();
		if (server == null) return;
		java.util.UUID id = mc.player.getUUID();
		server.execute(() -> {
			var p = server.getPlayerList().getPlayer(id);
			if (p == null) return;
			switch (t) {
				case 2 -> {
					p.getInventory().setItem(5, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 7));
					log("arms race: before " + inv(p));
					dev.strafemc.weapon.CsTeam.set(p, false);
					dev.strafemc.mode.GameModes.set("armsrace");
					dev.strafemc.weapon.Bots.add(true);
					log("arms race: started " + inv(p));
				}
				case 10 -> dev.strafemc.mode.GameModes.skipWarmup();
				case 20, 22, 24, 26, 28 -> {
					var bot = server.overworld().getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(net.minecraft.world.entity.LivingEntity.class),
							e -> dev.strafemc.weapon.Bots.isBot(e)).stream().findFirst().orElse(null);
					if (bot != null) dev.strafemc.mode.GameModes.kill(p, bot, "ak47", null);
					log("arms race: kill " + t + " bot " + (bot != null) + " " + inv(p));
				}
				case 40 -> p.kill(server.overworld());
				case 70 -> log("arms race: respawned alive " + p.isAlive() + " " + inv(p));
				case 140 -> {
					dev.strafemc.mode.GameModes.set("none");
					dev.strafemc.weapon.Bots.kickAll();
					log("arms race: stopped " + inv(p));
				}
				default -> {}
			}
		});
		if (t == 1) Perf.toggle(false); // the overlay would cover the mode's HUD
		if (t == 30 || t == 75) shot(mc, "armsrace_" + t);
	}

	/** Every game mode in turn with a bot: kills handed to the rules, a purchase, a death; logs and a HUD shot each. */
	private static void modes(Minecraft mc, int t) {
		var server = mc.getSingleplayerServer();
		if (server == null) return;
		if (t == 1) Perf.toggle(false);
		java.util.UUID id = mc.player.getUUID();
		server.execute(() -> {
			var p = server.getPlayerList().getPlayer(id);
			if (p == null) return;
			var bot = server.overworld().getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(net.minecraft.world.entity.LivingEntity.class),
					e -> dev.strafemc.weapon.Bots.isBot(e)).stream().findFirst().orElse(null);
			switch (t) {
				case 2 -> {
					dev.strafemc.weapon.CsTeam.set(p, false);
					dev.strafemc.mode.GameModes.set("armsrace");
					dev.strafemc.weapon.Bots.add(true);
				}
				case 8 -> dev.strafemc.mode.GameModes.skipWarmup();
				case 12, 14, 16 -> {
					if (bot != null) dev.strafemc.mode.GameModes.kill(bot, p, "g3sg1", null);
					log("modes: armsrace bot kill, bot holds " + (bot == null ? "-" : bot.getMainHandItem().getItem()));
				}
				case 24 -> log("modes: armsrace bot now holds " + (bot == null ? "-" : bot.getMainHandItem().getItem()));
				case 30 -> {
					dev.strafemc.mode.GameModes.set("deathmatch");
					log("modes: deathmatch " + inv(p));
				}
				case 34 -> dev.strafemc.mode.GameModes.skipWarmup();
				case 36 -> {
					dev.strafemc.weapon.BuyMenu.buy(p, "ak47");
					dev.strafemc.weapon.BuyMenu.buy(p, "hegrenade");
					log("modes: bought ak47 + hegrenade " + inv(p));
				}
				case 38, 40 -> {
					if (bot != null) dev.strafemc.mode.GameModes.kill(p, bot, t == 38 ? "ak47" : "knife", null);
				}
				case 42 -> p.kill(server.overworld());
				case 70 -> log("modes: deathmatch respawned " + inv(p));
				case 90 -> dev.strafemc.mode.GameModes.set("teamdeathmatch");
				case 94 -> dev.strafemc.mode.GameModes.skipWarmup();
				case 96, 98 -> {
					if (bot != null) dev.strafemc.mode.GameModes.kill(p, bot, "ak47", null);
				}
				case 140 -> {
					dev.strafemc.mode.GameModes.set("none");
					dev.strafemc.weapon.Bots.kickAll();
					log("modes: stopped " + inv(p));
				}
				default -> {}
			}
		});
		if (t == 26) shot(mc, "modes_armsrace");
		if (t == 75) shot(mc, "modes_deathmatch");
		if (t == 110) shot(mc, "modes_teamdeathmatch");
	}

	/**
	 * Competitive: T and CT spawn blocks and a bomb site built in front, the player a T, a CT bot; the warmup and freeze
	 * skipped, a purchase with too little money and one with enough, the bomb planted at the site, the bot killed: the
	 * log shows the money at each step and how the round ended.
	 */
	private static void rounds(Minecraft mc, int t) {
		var server = mc.getSingleplayerServer();
		if (server == null) return;
		if (t == 1) Perf.toggle(false);
		java.util.UUID id = mc.player.getUUID();
		server.execute(() -> {
			var p = server.getPlayerList().getPlayer(id);
			if (p == null) return;
			var level = p.level();
			BlockPos g = p.blockPosition().below();
			var bot = level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(net.minecraft.world.entity.LivingEntity.class),
					e -> dev.strafemc.weapon.Bots.isBot(e)).stream().findFirst().orElse(null);
			switch (t) {
				case 2 -> {
					var sp = dev.strafemc.weapon.WeaponItems.SPAWN_POINT.defaultBlockState();
					level.setBlockAndUpdate(g.offset(0, 0, 3), sp.setValue(dev.strafemc.world.SpawnBlock.SIDE, dev.strafemc.world.SpawnBlock.Side.T));
					level.setBlockAndUpdate(g.offset(12, 0, 3), sp.setValue(dev.strafemc.world.SpawnBlock.SIDE, dev.strafemc.world.SpawnBlock.Side.CT));
					level.setBlockAndUpdate(g.offset(6, 0, 8), dev.strafemc.weapon.WeaponItems.BOMB_SITE.defaultBlockState());
					dev.strafemc.weapon.CsTeam.set(p, true);
					dev.strafemc.mode.GameModes.set("competitive");
					dev.strafemc.weapon.Bots.add(false);
				}
				case 6 -> dev.strafemc.mode.GameModes.skipWarmup();
				case 10 -> log("rounds: round 1 " + p.blockPosition().toShortString() + " " + inv(p) + " " + state(p));
				case 12 -> {
					dev.strafemc.weapon.BuyMenu.buy(p, "ak47");
					dev.strafemc.weapon.BuyMenu.buy(p, "kevlar");
					log("rounds: bought kevlar (ak47 refused) " + inv(p) + " armor " + dev.strafemc.weapon.CsArmor.of(p) + " " + state(p));
				}
				case 14 -> dev.strafemc.mode.GameModes.skipWarmup(); // freeze over
				case 20 -> { // to the bomb site with the C4 out
					var inv = p.getInventory();
					for (int i = 0; i < 9; i++) if (inv.getItem(i).is(dev.strafemc.weapon.WeaponItems.C4)) inv.setSelectedSlot(i);
					p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(inv.getSelectedSlot()));
					p.teleportTo(g.getX() + 7.5, g.getY() + 1, g.getZ() + 8.5);
				}
				case 24 -> {
					dev.strafemc.weapon.C4Item.start(p);
					log("rounds: planting, holding " + p.getMainHandItem().getItem() + " onGround " + p.onGround());
				}
				case 90 -> {
					dev.strafemc.weapon.C4Item.plant(p, false);
					log("rounds: planted? " + state(p));
				}
				case 100 -> {
					if (bot != null) {
						dev.strafemc.mode.GameModes.kill(p, bot, "glock", null);
						bot.kill((net.minecraft.server.level.ServerLevel) level);
					}
				}
				case 104 -> log("rounds: after the kill " + state(p));
				case 150 -> {
					dev.strafemc.mode.GameModes.set("none");
					dev.strafemc.weapon.Bots.kickAll();
					log("rounds: stopped " + inv(p));
				}
				default -> {}
			}
		});
		if (t == 30) shot(mc, "rounds_live");
		if (t == 95) shot(mc, "rounds_bomb");
		if (t == 110) shot(mc, "rounds_over");
	}

	/**
	 * Bots and the bomb: a T spawn with a bomb site 6 blocks off, the CT spawn 40 blocks away in a stone box with the
	 * player (a CT) in it; a T bot gets the bomb, walks to the site and plants; the log shows the round's events.
	 */
	private static void botPlant(Minecraft mc, int t) {
		var server = mc.getSingleplayerServer();
		if (server == null) return;
		if (t == 1) Perf.toggle(false);
		java.util.UUID id = mc.player.getUUID();
		server.execute(() -> {
			var p = server.getPlayerList().getPlayer(id);
			if (p == null) return;
			var level = p.level();
			switch (t) {
				case 2 -> {
					BlockPos g = p.blockPosition().below();
					botBase = g;
					var sp = dev.strafemc.weapon.WeaponItems.SPAWN_POINT.defaultBlockState();
					level.setBlockAndUpdate(g.offset(0, 0, 0), sp.setValue(dev.strafemc.world.SpawnBlock.SIDE, dev.strafemc.world.SpawnBlock.Side.T));
					level.setBlockAndUpdate(g.offset(6, 0, 0), dev.strafemc.weapon.WeaponItems.BOMB_SITE.defaultBlockState());
					BlockPos ct = g.offset(40, 0, 0);
					for (int x = -2; x <= 2; x++)
						for (int z = -2; z <= 2; z++)
							for (int y = 1; y <= 4; y++)
								if (Math.abs(x) == 2 || Math.abs(z) == 2 || y == 4) level.setBlockAndUpdate(ct.offset(x, y, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
					level.setBlockAndUpdate(ct, sp.setValue(dev.strafemc.world.SpawnBlock.SIDE, dev.strafemc.world.SpawnBlock.Side.CT));
					dev.strafemc.weapon.CsTeam.set(p, false);
					dev.strafemc.mode.GameModes.set("competitive");
					dev.strafemc.mode.GameModes.joinTeam(p, "ct");
					dev.strafemc.weapon.Bots.add(true);
				}
				case 6 -> dev.strafemc.mode.GameModes.skipWarmup();
				case 30 -> {
					dev.strafemc.mode.GameModes.skipWarmup(); // freeze over
					log("botplant: live " + state(p));
				}
				default -> {}
			}
			if (t > 30 && t % 40 == 0) {
				var bot = level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(net.minecraft.world.entity.LivingEntity.class),
						e -> dev.strafemc.weapon.Bots.isBot(e)).stream().findFirst().orElse(null);
				var bombs = level.getEntitiesOfClass(dev.strafemc.weapon.PlantedC4.class, new net.minecraft.world.phys.AABB(botBase).inflate(60));
				log("botplant: t=" + t + " bot " + (bot == null ? "-" : bot.blockPosition().toShortString() + " " + bot.getMainHandItem().getItem())
						+ " bombs " + bombs.size() + " " + state(p).replaceAll("\\\"players\\\".*", ""));
			}
			if (t == 1390) {
				dev.strafemc.mode.GameModes.set("none");
				dev.strafemc.weapon.Bots.kickAll();
			}
		});
	}

	private static BlockPos botBase = BlockPos.ZERO;

	/**
	 * Zones: a bomb site A and both buy zones drawn with the Zone Tool's code, shown while the tool is held; then
	 * Competitive with a T bot that plants inside the drawn site, the team menu on joining, and the Tab scoreboard.
	 */
	private static void zones(Minecraft mc, int t) {
		var server = mc.getSingleplayerServer();
		if (server == null) return;
		if (t == 1) Perf.toggle(false);
		java.util.UUID id = mc.player.getUUID();
		if (t == 4) { // look along the zones
			mc.player.setYRot(-90);
			mc.player.setXRot(30);
		}
		if (t == 12) shot(mc, "zones_tool");
		if (t == 40) shot(mc, "zones_teammenu");
		if (t == 44 && mc.screen != null) mc.setScreen(null);
		if (t == 300) mc.options.keyPlayerList.setDown(true);
		if (t == 304) shot(mc, "zones_scoreboard");
		if (t == 306) mc.options.keyPlayerList.setDown(false);
		if (t == 360) shot(mc, "zones_hud");
		server.execute(() -> {
			var p = server.getPlayerList().getPlayer(id);
			if (p == null) return;
			var level = p.level();
			switch (t) {
				case 2 -> {
					BlockPos g = p.blockPosition().below();
					botBase = g;
					var sp = dev.strafemc.weapon.WeaponItems.SPAWN_POINT.defaultBlockState();
					level.setBlockAndUpdate(g.offset(3, 0, 0), sp.setValue(dev.strafemc.world.SpawnBlock.SIDE, dev.strafemc.world.SpawnBlock.Side.T));
					BlockPos ct = g.offset(40, 0, 0);
					for (int x = -2; x <= 2; x++)
						for (int z = -2; z <= 2; z++)
							for (int y = 1; y <= 4; y++)
								if (Math.abs(x) == 2 || Math.abs(z) == 2 || y == 4) level.setBlockAndUpdate(ct.offset(x, y, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
					level.setBlockAndUpdate(ct, sp.setValue(dev.strafemc.world.SpawnBlock.SIDE, dev.strafemc.world.SpawnBlock.Side.CT));
					dev.strafemc.mode.Zones.clear();
					dev.strafemc.mode.Zones.add(p, dev.strafemc.mode.Zones.Type.SITE_A, g.offset(8, 0, -3), g.offset(13, 0, 3));
					dev.strafemc.mode.Zones.add(p, dev.strafemc.mode.Zones.Type.BUY_T, g.offset(1, 0, -2), g.offset(5, 0, 2));
					dev.strafemc.mode.Zones.add(p, dev.strafemc.mode.Zones.Type.BUY_CT, g.offset(37, 0, -3), g.offset(43, 0, 3));
					p.getInventory().setItem(p.getInventory().getSelectedSlot(), new net.minecraft.world.item.ItemStack(dev.strafemc.weapon.WeaponItems.ZONE_TOOL));
				}
				case 20 -> {
					dev.strafemc.weapon.CsTeam.set(p, false);
					dev.strafemc.mode.GameModes.set("competitive");
					dev.strafemc.mode.GameModes.joinTeam(p, "ct");
					dev.strafemc.weapon.Bots.add(true);
				}
				case 50 -> dev.strafemc.mode.GameModes.skipWarmup();
				case 60 -> {
					dev.strafemc.mode.GameModes.skipWarmup(); // freeze over
					log("zones: live " + state(p).replaceAll("\"players\".*", ""));
				}
				default -> {}
			}
			if (t > 60 && t % 60 == 0) {
				var bombs = level.getEntitiesOfClass(dev.strafemc.weapon.PlantedC4.class, new net.minecraft.world.phys.AABB(botBase).inflate(60));
				log("zones: t=" + t + " bombs " + bombs.size() + (bombs.isEmpty() ? "" : " at " + bombs.get(0).blockPosition().toShortString()) + " "
						+ state(p).replaceAll("\"players\".*", ""));
			}
			if (t == 510) {
				dev.strafemc.mode.GameModes.set("none");
				dev.strafemc.weapon.Bots.kickAll();
			}
		});
	}

	private static String state(net.minecraft.server.level.ServerPlayer p) {
		return dev.strafemc.mode.GameModes.stateOf(p);
	}

	private static String inv(net.minecraft.server.level.ServerPlayer p) {
		StringBuilder b = new StringBuilder();
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++)
			if (!inv.getItem(i).isEmpty()) b.append(i).append('=').append(inv.getItem(i).getItem()).append('x').append(inv.getItem(i).getCount()).append(' ');
		return b.toString();
	}

	private static Vec3 c4At = Vec3.ZERO;
	private static net.minecraft.world.level.GameType c4Mode;

	/** A planted C4 with three stickers and a charm, in front of the player. */
	private static void c4(Minecraft mc) {
		var server = mc.getSingleplayerServer();
		if (server == null) return;
		Perf.toggle(false); // the overlay would cover it
		int x = mc.player.getBlockX(), z = mc.player.getBlockZ() + 2; // on the ground wherever the player is (they may be spectating)
		c4At = new Vec3(x + 0.5, mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z), z + 0.5);
		java.util.UUID id = mc.player.getUUID();
		var dim = mc.player.level().dimension();
		server.execute(() -> {
			var level = server.getLevel(dim);
			var p = server.getPlayerList().getPlayer(id);
			if (level == null || p == null) return;
			c4Mode = p.gameMode.getGameModeForPlayer();
			p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
			var item = new net.minecraft.world.item.ItemStack(dev.strafemc.weapon.WeaponItems.C4);
			item.set(dev.strafemc.weapon.StickerSlots.STICKERS, java.util.List.of("std_aces_high_holo", "", "std_fearsome_holo", "", "std_vigilance_holo"));
			var charms = dev.strafemc.weapon.Charms.all();
			if (!charms.isEmpty()) item.set(dev.strafemc.weapon.StickerSlots.CHARM, charms.get(0).id());
			var bomb = new dev.strafemc.weapon.PlantedC4(level, p, item);
			bomb.setPos(c4At);
			bomb.setYRot(0);
			level.addFreshEntity(bomb);
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
