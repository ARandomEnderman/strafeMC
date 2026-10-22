package dev.strafemc.client.console;

import dev.strafemc.bridge.Cs2Bridge;
import dev.strafemc.client.movement.MovementController;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.net.StrafePayloads.NoclipRequest;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Console command dispatch. Local (Minecraft-side) commands are handled here; everything else goes to the
 * real CS2 server through the {@link ConsoleTransport}, so real cvars/commands work.
 */
public final class ConsoleCommands {
	public static ConsoleTransport transport = RelayTransport.INSTANCE;

	public record Local(String name, String help, Consumer<String[]> run) {}

	public static final Map<String, Local> LOCAL = new LinkedHashMap<>();
	/** Names that don't exist in CS2 but people type out of habit, mapped to the real cvar. */
	public static final Map<String, String> ALIASES = Map.of("mp_autobunnyhopping", "sv_autobunnyhopping");

	/** Movement cvars restored by strafemc_reset_movement. */
	public static final String[] MOVEMENT_CVARS = {"sv_accelerate", "sv_airaccelerate", "sv_air_max_wishspeed", "sv_friction",
			"sv_stopspeed", "sv_maxspeed", "sv_gravity", "sv_jump_impulse", "sv_stepsize", "sv_maxvelocity", "sv_autobunnyhopping",
			"sv_enablebunnyhopping", "sv_legacy_jump", "sv_timebetweenducks", "sv_ladder_scale_speed", "sv_ladder_angle", "sv_ladder_dampen", "host_timescale"};

	static {
		local("clear", "Clear the console.", a -> ConsoleLog.clear());
		local("help", "List commands.", a -> {
			ConsoleLog.info("Minecraft-side commands (everything else is sent to the CS2 server):");
			LOCAL.values().forEach(l -> ConsoleLog.add("  " + l.name + " - " + l.help, ConsoleLog.GRAY));
			ConsoleLog.add("  mp_autobunnyhopping - alias for sv_autobunnyhopping", ConsoleLog.GRAY);
		});
		local("noclip", "Fly through walls. Cheat.", a -> {
			if (!cheats()) {
				ConsoleLog.error("Can't use cheat command noclip in multiplayer, unless the server has sv_cheats set to 1.");
				return;
			}
			boolean want = bool(a, !MovementController.INSTANCE.noclip());
			var mc = Minecraft.getInstance();
			if (mc.getConnection() == null || !ClientPlayNetworking.canSend(NoclipRequest.TYPE)) {
				ConsoleLog.error("noclip: not in a world (or the Minecraft server doesn't have strafeMC)");
				return;
			}
			ClientPlayNetworking.send(new NoclipRequest(want));
		});
		local("strafemc_perf", "Show how long strafeMC's work takes each frame.", a -> {
			dev.strafemc.client.perf.Perf.toggle(bool(a, !dev.strafemc.client.perf.Perf.on));
			ConsoleLog.info("strafemc_perf " + (dev.strafemc.client.perf.Perf.on ? 1 : 0));
		});
		local("strafemc_server_data", "Pack what a dedicated server needs from CS2 into strafemc-server-data.zip (unzip it in the server's folder).", a -> {
			try {
				var r = dev.strafemc.data.ServerData.export();
				ConsoleLog.info("Wrote " + r.files() + " files to " + r.zip() + ". Unzip it into the server's folder (it makes strafemc/cache).");
			} catch (Exception e) {
				ConsoleLog.error("strafemc_server_data: " + e);
			}
		});
		local("strafemc_perf_bench", "Measure a fixed scene (bullet holes, smoke, effects) and log the numbers.", a -> dev.strafemc.client.perf.PerfBench.start());
		local("ui_sweep", "Screenshot every strafeMC menu at each GUI scale into ui-sweep (ui_sweep stop ends it).", a -> {
			if (a.length > 0 && a[0].equalsIgnoreCase("stop")) dev.strafemc.client.ui.UiSweep.stop();
			else dev.strafemc.client.ui.UiSweep.start();
		});
		local("cl_scrolljump", "Scroll to jump (1) or scroll the hotbar (0).", a -> {
			StrafeConfig.scrollJump = bool(a, !StrafeConfig.scrollJump);
			saved("cl_scrolljump", StrafeConfig.scrollJump);
		});
		local("cl_showpos", "Show your position, angles and speed.", a -> {
			StrafeConfig.showSpeed = bool(a, !StrafeConfig.showSpeed);
			saved("cl_showpos", StrafeConfig.showSpeed);
		});
		local("cl_jumpstats", "Show stats after each jump.", a -> {
			StrafeConfig.jumpStats = bool(a, !StrafeConfig.jumpStats);
			saved("cl_jumpstats", StrafeConfig.jumpStats);
		});
		local("cl_velgraph", "Show a speed graph.", a -> {
			StrafeConfig.velGraph = bool(a, !StrafeConfig.velGraph);
			saved("cl_velgraph", StrafeConfig.velGraph);
		});
		local("strafemc_preset_save", "Save your movement settings as a preset.", a -> {
			if (a.length == 0) {
				ConsoleLog.error("Usage: strafemc_preset_save <name>");
				return;
			}
			ConsoleLog.add(Presets.save(a[0]) ? "saved preset " + a[0] : "could not save preset " + a[0], ConsoleLog.OK);
		});
		local("strafemc_preset_load", "Load a preset ('default' for CS2's).", a -> {
			if (a.length == 0) {
				ConsoleLog.error("Usage: strafemc_preset_load <name>");
				return;
			}
			if (a[0].equalsIgnoreCase("default")) ConsoleCommands.execute("strafemc_reset_movement");
			else ConsoleLog.add(Presets.load(a[0]) ? "loaded preset " + a[0] : "no preset named " + a[0], ConsoleLog.OK);
		});
		local("strafemc_preset_delete", "Delete a preset.", a -> {
			if (a.length == 0) {
				ConsoleLog.error("Usage: strafemc_preset_delete <name>");
				return;
			}
			ConsoleLog.add(Presets.delete(a[0]) ? "deleted preset " + a[0] : "no preset named " + a[0], ConsoleLog.OK);
		});
		local("strafemc_presets", "List presets.", a -> {
			var all = Presets.names();
			ConsoleLog.info(all.isEmpty() ? "no saved presets" : "presets: " + String.join(", ", all));
		});
		local("cl_showspeed", "Show your speed.", a -> {
			StrafeConfig.showSpeedMeter = bool(a, !StrafeConfig.showSpeedMeter);
			saved("cl_showspeed", StrafeConfig.showSpeedMeter);
		});
		local("cl_doubletapfly", "Double-tap jump to fly in creative.", a -> {
			StrafeConfig.doubleTapFly = bool(a, !StrafeConfig.doubleTapFly);
			saved("cl_doubletapfly", StrafeConfig.doubleTapFly);
		});
		local("strafemc_movement_choice", "Let each player choose their movement (1) or not (0). Operators.", a -> {
			if (a.length == 0) {
				ConsoleLog.error("strafemc_movement_choice 1 (each player chooses) or 0 (operators decide)");
				return;
			}
			action("strafemc_movement_choice", bool(a, false) ? "1" : "0", false);
		});
		local("strafemc_movement", "CS2 movement (1) or Minecraft movement (0).", a -> {
			boolean on = bool(a, !dev.strafemc.client.ClientGameplay.sourceMovement());
			action("strafemc_movement", on ? "1" : "0", false);
		});
		local("strafemc_pattern", "Set the pattern (0-1000) of the skin you're holding. Cheat.", a -> {
			if (a.length == 0) {
				ConsoleLog.error("strafemc_pattern <0-1000>");
				return;
			}
			action("strafemc_pattern", a[0], true);
		});
		local("strafemc_float", "Set the float (0-1, or FN / MW / FT / WW / BS) of the skin you're holding. Cheat.", a -> {
			if (a.length == 0) {
				ConsoleLog.error("strafemc_float <0-1 | FN | MW | FT | WW | BS>");
				return;
			}
			action("strafemc_float", String.join(" ", a), true);
		});
		local("strafemc_drop", "Get a care package now. Cheat.", a -> action("strafemc_drop", "", true));
		local("drop_everyone", "Give every player a care package. Operators.", a -> action("drop_everyone", "", false));
		local("strafemc_stattrak", "Make the skin you're holding StatTrak with this many kills (off: plain). Cheat.",
				a -> action("strafemc_stattrak", String.join(" ", a), true));
		local("strafemc_c4_keypad", "Plant and defuse the C4 by typing its code on a keypad, quicker than holding (1), or just hold (0). Operators.",
				a -> action("strafemc_c4_keypad", a.length > 0 ? (bool(a, false) ? "1" : "0") : "1", false));
		local("strafemc_wear", "Wear on case drops (1) or all Factory New (0). Operators.",
				a -> action("strafemc_wear", a.length > 0 ? (bool(a, true) ? "1" : "0") : "1", false));
		for (String r : new String[] {"gold", "covert", "classified", "restricted", "milspec", "industrial", "consumer"})
			local("strafemc_wear_" + r, "Wear on " + r + " drops (1) or Factory New (0). Operators.",
					a -> action("strafemc_wear", r + " " + (a.length > 0 ? (bool(a, true) ? "1" : "0") : "1"), false));
		for (String cv : dev.strafemc.client.weapon.Crosshair.DEFAULTS.keySet())
			viewmodelSetting(cv, "Crosshair setting.", -1000, 1000,
					() -> dev.strafemc.client.weapon.Crosshair.get(cv), v -> StrafeConfig.crosshair.put(cv, v));
		local("apply_crosshair_code", "Use a CS2 crosshair code.", a -> {
			if (a.length == 0 || !dev.strafemc.client.weapon.Crosshair.apply(a[0])) ConsoleLog.error("apply_crosshair_code CSGO-xxxxx-xxxxx-xxxxx-xxxxx-xxxxx");
		});
		local("crosshair_editor", "Edit your crosshair.", a -> Minecraft.getInstance().execute(() ->
				Minecraft.getInstance().setScreen(dev.strafemc.client.StrafeSettingsScreen.at(Minecraft.getInstance().screen, "crosshair"))));
		local("bot_add", "Add a bot. Operators.", a -> action("bot_add", "", false));
		local("bot_add_t", "Add a T bot. Operators.", a -> action("bot_add_t", "", false));
		local("bot_add_ct", "Add a CT bot. Operators.", a -> action("bot_add_ct", "", false));
		local("bot_kick", "Remove all bots. Operators.", a -> action("bot_kick", "", false));
		local("bot_quota", "Number of bots. Operators.", a -> {
			if (a.length == 0) ConsoleLog.error("bot_quota <number>");
			else action("bot_quota", a[0], false);
		});
		local("bot_difficulty", "Bot skill, 1-10. Operators.", a -> {
			if (a.length == 0) ConsoleLog.error("bot_difficulty <1-10>");
			else action("bot_difficulty", a[0], false);
		});
		local("strafemc_bot_ffa", "Bots fight everyone (1) or the other team (0). Operators.",
				a -> action("strafemc_bot_ffa", a.length > 0 ? (bool(a, false) ? "1" : "0") : "1", false));
		local("strafemc_bot_skins", "Bot skins: random, or player names. Operators.",
				a -> action("strafemc_bot_skins", a.length == 0 ? "random" : String.join(" ", a), false));
		local("strafemc_knockback", "Hits knock back (1) or slow down like CS2 (0). Operators.", a -> {
			if (a.length == 0) {
				ConsoleLog.error("strafemc_knockback 1 (Minecraft knockback) or 0 (CS2 tagging)");
				return;
			}
			action("strafemc_knockback", bool(a, false) ? "1" : "0", false);
		});
		local("strafemc_slabstep", "Step up slabs (1) or not (0). Operators.", a ->
				action("strafemc_slabstep", bool(a, !dev.strafemc.client.ClientGameplay.slabStep()) ? "1" : "0", false));
		local("strafemc_lagcomp", "Lag compensation on (1) or off (0). Operators.", a -> {
			if (a.length == 0) {
				ConsoleLog.error("strafemc_lagcomp 1 (on) or 0 (off)");
				return;
			}
			action("strafemc_lagcomp", bool(a, true) ? "1" : "0", false);
		});
		local("strafemc_maxunlag", "Max lag compensation in ms. Operators.", a -> {
			if (a.length == 0) {
				ConsoleLog.error("strafemc_maxunlag <milliseconds>");
				return;
			}
			action("strafemc_maxunlag", a[0], false);
		});
		local("strafemc_reset_movement", "Reset movement settings to CS2's.", a -> {
			Map<String, String> d = Cs2Bridge.INSTANCE.defaults();
			if (d.isEmpty()) {
				ConsoleLog.error("No CS2 default values available.");
				return;
			}
			StringBuilder cmd = new StringBuilder();
			for (String n : MOVEMENT_CVARS) if (d.containsKey(n)) cmd.append(n).append(' ').append(d.get(n)).append(';');
			transport.send(cmd.toString());
			ConsoleLog.add("Movement cvars reset to server defaults.", ConsoleLog.OK);
		});
		local("bind", "bind <key> \"<command>\" (CS key names: f, mouse4, mwheeldown, kp_enter...). No command = show it.", a -> {
			if (a.length == 0) {
				ConsoleLog.error("Usage: bind <key> \"<command>\"");
				return;
			}
			String key = a[0].toLowerCase();
			if (!Binds.validKey(key)) {
				ConsoleLog.error("\"" + key + "\" isn't a valid key");
				return;
			}
			if (a.length == 1) {
				String cur = Binds.get(key);
				ConsoleLog.info(cur == null ? "\"" + key + "\" is not bound" : "\"" + key + "\" = \"" + cur + "\"");
				return;
			}
			String cmd = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)).replace("\"", "");
			Binds.bind(key, cmd);
			ConsoleLog.add("\"" + key + "\" = \"" + cmd + "\"", ConsoleLog.OK);
		});
		local("unbind", "unbind <key>", a -> {
			if (a.length == 0) {
				ConsoleLog.error("Usage: unbind <key>");
				return;
			}
			ConsoleLog.add(Binds.unbind(a[0]) ? "unbound \"" + a[0].toLowerCase() + "\"" : "\"" + a[0] + "\" was not bound", ConsoleLog.OK);
		});
		local("unbindall", "Remove all binds.", a -> {
			Binds.unbindAll();
			ConsoleLog.add("all binds removed", ConsoleLog.OK);
		});
		local("binds", "List binds.", a -> {
			var all = Binds.all();
			if (all.isEmpty()) ConsoleLog.info("no binds");
			all.forEach((k, v) -> ConsoleLog.add("  " + k + " = \"" + v + "\"", ConsoleLog.GRAY));
		});
		local("cl_movementvolume", "Footstep volume, 0-1.", a -> {
			if (a.length > 0) {
				try {
					StrafeConfig.movementVolume = Math.max(0, Math.min(1, Double.parseDouble(a[0])));
					StrafeConfig.save();
				} catch (Exception e) {
					ConsoleLog.error("cl_movementvolume: expected a number 0-1");
					return;
				}
			}
			ConsoleLog.add("cl_movementvolume = " + StrafeConfig.movementVolume, ConsoleLog.OK);
		});
		local("cl_landvolume", "Landing volume, 0-1.", a -> {
			if (a.length > 0) {
				try {
					StrafeConfig.landVolume = Math.max(0, Math.min(1, Double.parseDouble(a[0])));
					StrafeConfig.save();
				} catch (Exception e) {
					ConsoleLog.error("cl_landvolume: expected a number 0-1");
					return;
				}
			}
			ConsoleLog.add("cl_landvolume = " + StrafeConfig.landVolume, ConsoleLog.OK);
		});
		viewmodelSetting("zoom_sensitivity_ratio", "Scoped sensitivity.", 0.001, 100,
				() -> StrafeConfig.zoomSensitivityRatio, v -> StrafeConfig.zoomSensitivityRatio = v);
		viewmodelSetting("viewmodel_fov", "Weapon field of view, 60-68.", 60, 68, () -> StrafeConfig.viewmodelFov,
				v -> StrafeConfig.viewmodelFov = v);
		// offsets unlimited on request (CS2 clamps them to -2..2.5 / -2..2)
		viewmodelSetting("viewmodel_offset_x", "Weapon position: right.", -1e6, 1e6, () -> StrafeConfig.viewmodelOffsetX,
				v -> StrafeConfig.viewmodelOffsetX = v);
		viewmodelSetting("viewmodel_offset_y", "Weapon position: forward.", -1e6, 1e6, () -> StrafeConfig.viewmodelOffsetY,
				v -> StrafeConfig.viewmodelOffsetY = v);
		viewmodelSetting("viewmodel_offset_z", "Weapon position: up.", -1e6, 1e6, () -> StrafeConfig.viewmodelOffsetZ,
				v -> StrafeConfig.viewmodelOffsetZ = v);
		viewmodelSetting("viewmodel_sway_scale", "Weapon sway, 0-5.", 0, 5,
				() -> StrafeConfig.viewmodelSwayScale, v -> StrafeConfig.viewmodelSwayScale = v);
		for (int i = 0; i < 5; i++) { // CS2's grenade throw crosshair
			final int k = i;
			String kind = dev.strafemc.client.weapon.GrenadeCrosshair.KINDS[i];
			local("cl_grenadecrosshair_" + kind, "Grenade crosshair (" + kind + ").", a -> {
				StrafeConfig.grenadeCrosshair[k] = bool(a, !StrafeConfig.grenadeCrosshair[k]);
				saved("cl_grenadecrosshair_" + kind, StrafeConfig.grenadeCrosshair[k]);
			});
			viewmodelSetting("cl_grenadecrosshairdelay_" + kind, "Seconds before the grenade crosshair shows.",
					0, 5, () -> StrafeConfig.grenadeCrosshairDelay[k], v -> StrafeConfig.grenadeCrosshairDelay[k] = v);
		}
		viewmodelSetting("cl_grenadecrosshair_tickscaling", "Grenade crosshair tick size.", 0.5, 2,
				() -> StrafeConfig.grenadeCrosshairTickScaling, v -> StrafeConfig.grenadeCrosshairTickScaling = v);
		local("cl_grenadecrosshair_ticklabels", "Grenade crosshair tick labels.", a -> {
			StrafeConfig.grenadeCrosshairTickLabels = bool(a, !StrafeConfig.grenadeCrosshairTickLabels);
			saved("cl_grenadecrosshair_ticklabels", StrafeConfig.grenadeCrosshairTickLabels);
		});
		local("grenadecam", "Grenade practice: the red path of a charged grenade and a camera riding your thrown one (1) or off (0). Singleplayer, or sv_cheats 1.", a -> {
			StrafeConfig.grenadeCam = bool(a, !StrafeConfig.grenadeCam);
			saved("grenadecam", StrafeConfig.grenadeCam);
			if (StrafeConfig.grenadeCam && !dev.strafemc.client.weapon.GrenadeCam.allowed())
				ConsoleLog.add("grenadecam works in singleplayer, or on a server with sv_cheats 1.", ConsoleLog.WARN);
		});
		local("strafemc_agent_models", "Show players and bots as CS2 agents (1) or Minecraft players (0).", a -> {
			StrafeConfig.agentModels = bool(a, !StrafeConfig.agentModels);
			saved("strafemc_agent_models", StrafeConfig.agentModels);
		});
		local("cl_righthand", "Right hand (1) or left hand (0).", a -> {
			boolean was = StrafeConfig.rightHand;
			StrafeConfig.rightHand = bool(a, !StrafeConfig.rightHand);
			saved("cl_righthand", StrafeConfig.rightHand);
			if (was != StrafeConfig.rightHand) dev.strafemc.client.weapon.GunClient.redeploy();
		});
		local("strafemc_gpu_skinning", "GPU weapon animation (1) or CPU (0).", a -> {
			StrafeConfig.gpuSkinning = bool(a, !StrafeConfig.gpuSkinning);
			saved("strafemc_gpu_skinning", StrafeConfig.gpuSkinning);
		});
		local("switchhands", "Switch hands (while holding a CS weapon).", a -> {
			var pl = net.minecraft.client.Minecraft.getInstance().player;
			if (pl == null || !(pl.getMainHandItem().getItem() instanceof dev.strafemc.weapon.GunItem)) return; // nothing to switch
			StrafeConfig.rightHand = !StrafeConfig.rightHand;
			saved("cl_righthand", StrafeConfig.rightHand);
			dev.strafemc.client.weapon.GunClient.redeploy();
		});
		local("strafemc_team", "Your side: ct or t.", a -> {
			StrafeConfig.team = a.length > 0 ? (a[0].equalsIgnoreCase("t") ? "t" : "ct") : ("t".equals(StrafeConfig.team) ? "ct" : "t");
			try {
				StrafeConfig.save();
			} catch (Exception e) {
				ConsoleLog.error("could not save config: " + e.getMessage());
			}
			sendTeam();
			ConsoleLog.add("Team: " + StrafeConfig.team.toUpperCase(java.util.Locale.ROOT), ConsoleLog.OK);
		});
		local("strafemc_serverlog", "Show the CS2 server log.", a -> {
			StrafeConfig.showServerLog = bool(a, !StrafeConfig.showServerLog);
			saved("strafemc_serverlog", StrafeConfig.showServerLog);
		});
		local("strafemc_startserver", "Start the CS2 server.", a -> {
			if (RelayTransport.remote()) ConsoleLog.error("Only the host runs this server's CS2 server.");
			else ServerLauncher.start();
		});
		local("strafemc_stopserver", "Stop the CS2 server.", a -> {
			if (RelayTransport.remote()) ConsoleLog.error("Only the host runs this server's CS2 server.");
			else ServerLauncher.stop();
		});
		local("kill", "Kill yourself.", a -> action("kill", "", false));
		local("buy", "Buy an item.",
				a -> { if (a.length > 0) buy(a[0]); else ConsoleLog.error("buy <item>"); });
		local("sellbackall", "Refund everything you bought.",
				a -> dev.strafemc.client.BuyMenuScreen.refundAll());
		local("refund", "Refund an item.", a -> refund(a.length > 0 ? a[0] : "all"));
		local("strafemc_buymenu", "Who can buy: 0 nobody, 1 creative, 2 everyone. Operators.",
				a -> action("strafemc_buymenu", a.length > 0 ? a[0] : "", false));
		local("strafemc_killcards_time", "Kill card time in seconds (0 = until death). Operators.",
				a -> { if (a.length > 0) action("strafemc_killcards_time", a[0], false); else ConsoleLog.info("strafemc_killcards_time = " + dev.strafemc.client.ClientGameplay.killCardTime()); });
		local("strafemc_buytime", "Buy time in seconds (0 = no limit). Operators.",
				a -> { if (a.length > 0) action("strafemc_buytime", a[0], false); else ConsoleLog.info("strafemc_buytime = " + dev.strafemc.client.ClientGameplay.buyTime()); });
		local("strafemc_shop_refresh", "New shop offers now: yours, or a player's (strafemc_shop_refresh <player>). Operators.",
				a -> action("strafemc_shop_refresh", a.length > 0 ? a[0] : "", false));
		local("strafemc_buyzone", "Round modes: how far a side's buy zone reaches from its spawn blocks (blocks). Operators.",
				a -> action("strafemc_buyzone", a.length > 0 ? a[0] : "", false));
		local("strafemc_bombsite", "Round modes: how wide and deep a Bomb Site block's plant area is (blocks; 6 tall from the block's top). Operators.",
				a -> action("strafemc_bombsite", a.length > 0 ? a[0] : "", false));
		local("strafemc_overtime", "Round modes: a tie on the last round goes to overtime (1) or is a draw (0, CS2 Competitive). Operators.",
				a -> action("strafemc_overtime", a.length > 0 ? (bool(a, true) ? "1" : "0") : "", false));
		local("strafemc_gamemode", "The game mode: none (strafeMC survival), armsrace, deathmatch or teamdeathmatch. Operators.",
				a -> action("strafemc_gamemode", a.length > 0 ? a[0] : "", false));
		local("strafemc_spawnblocks", "Respawn on spawn blocks (1) or not (0). Operators.",
				a -> action("strafemc_spawnblocks", a.length > 0 ? (bool(a, true) ? "1" : "0") : "1", false));
		local("strafemc_drops", "Care packages on (1) or off (0). Operators.",
				a -> action("strafemc_drops", bool(a, !dev.strafemc.client.ClientGameplay.drops()) ? "1" : "0", false));
		local("mp_forcecamera", "Who the dead may watch in round modes: 0 anyone (and a free camera), 1 own team, 2 nobody; default: the mode's. Operators.",
				a -> action("mp_forcecamera", a.length > 0 ? a[0] : "", false));
		local("spec_freeze_time", "Seconds the death camera holds on your killer; default: the mode's. Operators.",
				a -> action("spec_freeze_time", a.length > 0 ? a[0] : "", false));
		local("mp_autoteambalance", "Round modes: move players to the smaller side when the next round starts (1) or not (0, default). Operators.",
				a -> action("mp_autoteambalance", a.length > 0 ? a[0] : "", false));
		local("mp_limitteams", "How many players one side may have over the other when joining (0: no limit; default 2). Operators.",
				a -> action("mp_limitteams", a.length > 0 ? a[0] : "", false));
		local("strafemc_gamemode_server", "Game mode server (1): everyone's main menu loadout; no loadout items, levels, drops, shop, upgrader, trade ups. Operators.",
				a -> action("strafemc_gamemode_server", a.length > 0 ? (bool(a, true) ? "1" : "0") : "", false));
		local("strafemc_loadout_keep", "Keep loadout on death (1) or drop it (0). Operators.",
				a -> action("strafemc_loadout_keep", bool(a, !dev.strafemc.client.ClientGameplay.loadoutKeep()) ? "1" : "0", false));
		local("loadout", "Open your loadout.", a -> dev.strafemc.client.LoadoutKey.open());
		local("sv_rethrow_last_grenade", "Rethrow your last grenade. Cheat.",
				a -> action("sv_rethrow_last_grenade", "", true));
		local("explode", "Explode.", a -> action("explode", "", false));
		local("god", "God mode. Cheat.", a -> action("god", "", true));
		local("healme", "Heal yourself. Cheat.", a -> action("healme", a.length > 0 ? a[0] : "", true));
		local("getpos", "Print your position.", a -> {
			var p = Minecraft.getInstance().player;
			if (p == null) return;
			double u = StrafeConfig.unitsPerBlock, yaw = Math.toRadians(p.getYRot());
			double csYaw = Math.toDegrees(Math.atan2(-Math.cos(yaw), -Math.sin(yaw)));
			ConsoleLog.add(String.format(java.util.Locale.ROOT, "setpos %.2f %.2f %.2f;setang %.2f %.2f 0", p.getX() * u, -p.getZ() * u, p.getY() * u,
					p.getXRot(), csYaw), ConsoleLog.WHITE);
		});
		local("setpos", "Teleport. Cheat.", a -> {
			if (a.length < 3) {
				ConsoleLog.error("usage: setpos <x> <y> <z>");
				return;
			}
			action("setpos", a[0] + " " + a[1] + " " + a[2], true);
		});
		local("setang", "Set your view angles. Cheat.", a -> {
			var p = Minecraft.getInstance().player;
			if (p == null || a.length < 2) {
				ConsoleLog.error("usage: setang <pitch> <yaw>");
				return;
			}
			if (!cheats()) {
				ConsoleLog.error("setang needs sv_cheats 1.");
				return;
			}
			try {
				double c = Math.toRadians(Double.parseDouble(a[1]));
				p.setXRot((float) Math.max(-90, Math.min(90, Double.parseDouble(a[0]))));
				p.setYRot((float) Math.toDegrees(Math.atan2(-Math.cos(c), -Math.sin(c)))); // inverse of cl_showpos' yaw
			} catch (NumberFormatException e) {
				ConsoleLog.error("setang: numbers please");
			}
		});
		local("hurtme", "Damage yourself. Cheat.", a -> action("hurtme", a.length > 0 ? a[0] : "10", true));
		local("give", "Give yourself an item. Cheat.", a -> action("give", a.length > 0 ? a[0] : "", true));
		local("impulse", "impulse 101: all weapons and ammo. Cheat.", a -> action("impulse", a.length > 0 ? a[0] : "", true));
		local("thirdperson", "Third-person camera.", a -> Minecraft.getInstance().options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK));
		local("firstperson", "First-person camera.", a -> Minecraft.getInstance().options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
		local("strafemc_fx_list", "List the last impact's effect layers.", a -> {
			var layers = dev.strafemc.client.particle.Effects.lastImpactLayers();
			if (layers.isEmpty()) ConsoleLog.add("No impact effect yet: shoot a block first.", ConsoleLog.WARN);
			for (String l : layers) ConsoleLog.add(l, ConsoleLog.WHITE);
		});
		local("strafemc_fx_hide", "Hide an effect layer (or clear).", a -> {
			if (a.length == 0) {
				ConsoleLog.add("hidden: " + dev.strafemc.client.particle.Effects.hiddenNames(), ConsoleLog.WHITE);
				return;
			}
			dev.strafemc.client.particle.Effects.hide(a[0]);
			ConsoleLog.add("hidden: " + dev.strafemc.client.particle.Effects.hiddenNames(), ConsoleLog.OK);
		});
		local("strafemc_relearn", "Reload the CS2 command list.", a -> NetconTransport.INSTANCE.learnCommands());
	}

	private ConsoleCommands() {}

	private static void local(String n, String h, Consumer<String[]> r) {
		LOCAL.put(n, new Local(n, h, r));
	}

	public static void execute(String line) {
		ConsoleLog.add("] " + line, ConsoleLog.ECHO);
		String trimmed = line.trim().toLowerCase();
		String[] parts = trimmed.startsWith("bind ") ? new String[] {line} : line.split(";"); // keep "bind f \"a; b\"" whole
		for (String part : parts) {
			String cmd = part.trim();
			if (cmd.isEmpty()) continue;
			String[] tok = cmd.split("\\s+");
			String name = tok[0].toLowerCase();
			String[] args = Arrays.copyOfRange(tok, 1, tok.length);
			if (ALIASES.containsKey(name)) {
				name = ALIASES.get(name);
				cmd = name + (args.length > 0 ? " " + String.join(" ", args) : "");
			}
			Local l = LOCAL.get(name);
			if (l != null) {
				try {
					l.run.accept(args);
				} catch (RuntimeException e) {
					ConsoleLog.error(name + ": " + e.getMessage());
				}
			} else {
				transport.send(cmd);
			}
		}
	}

	/** Sends a command without echoing it (quick-menu buttons echo their own description). */
	public static void quiet(String cmd) {
		ConsoleLog.add("] " + cmd, ConsoleLog.ECHO);
		transport.send(cmd);
	}

	/** Sends a world-changing console command to the MC server (which checks operator permission). */
	private static void action(String cmd, String arg, boolean needsCheats) {
		if (needsCheats && !cheats()) {
			ConsoleLog.error("Can't use cheat command " + cmd + " in multiplayer, unless the server has sv_cheats set to 1.");
			return;
		}
		if (!ClientPlayNetworking.canSend(dev.strafemc.net.StrafePayloads.Action.TYPE)) {
			ConsoleLog.error(cmd + ": not in a world");
			return;
		}
		ClientPlayNetworking.send(new dev.strafemc.net.StrafePayloads.Action(cmd, arg));
	}

	public static boolean cheats() {
		String v = dev.strafemc.client.ClientGameplay.cvar("sv_cheats"); // the server's, on someone else's server
		return "1".equals(v) || "true".equals(v);
	}

	/** A personal viewmodel setting: shown without a value, set and saved (clamped to CS2's range) with one. */
	private static void viewmodelSetting(String name, String help, double min, double max, java.util.function.DoubleSupplier get,
										 java.util.function.DoubleConsumer set) {
		local(name, help, a -> {
			if (a.length > 0) {
				try {
					set.accept(Math.max(min, Math.min(max, Double.parseDouble(a[0]))));
					StrafeConfig.save();
				} catch (Exception e) {
					ConsoleLog.error(name + ": expected a number");
					return;
				}
			}
			ConsoleLog.add(name + " = " + get.getAsDouble(), ConsoleLog.OK);
		});
	}

	/** Buys an item (the buy menu's buttons, the buy command). */
	public static void buy(String item) {
		action("buy", item, false);
	}

	/** Undoes a purchase made since spawning ("all": every one; CS2's refund / sellbackall). */
	public static void refund(String item) {
		action("refund", item, false);
	}

	/** Tells the server your side (for your smokes' colour). */
	public static void sendTeam() {
		if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(dev.strafemc.net.StrafePayloads.TeamChoice.TYPE))
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.strafemc.net.StrafePayloads.TeamChoice("t".equals(StrafeConfig.team)));
	}

	static boolean bool(String[] a, boolean toggled) {
		if (a.length == 0) return toggled;
		return !(a[0].equals("0") || a[0].equalsIgnoreCase("false") || a[0].equalsIgnoreCase("off"));
	}

	private static void saved(String n, boolean v) {
		try {
			StrafeConfig.save();
		} catch (Exception e) {
			ConsoleLog.error("could not save config: " + e.getMessage());
		}
		ConsoleLog.add(n + " = " + (v ? "1" : "0"), ConsoleLog.OK);
	}
}
