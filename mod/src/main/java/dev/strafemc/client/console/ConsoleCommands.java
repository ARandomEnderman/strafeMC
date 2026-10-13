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
			"sv_enablebunnyhopping", "sv_timebetweenducks", "sv_ladder_scale_speed", "sv_ladder_angle", "sv_ladder_dampen", "host_timescale"};

	static {
		local("clear", "Clear the console.", a -> ConsoleLog.clear());
		local("help", "List the Minecraft-side console commands.", a -> {
			ConsoleLog.info("Minecraft-side commands (everything else is sent to the CS2 server):");
			LOCAL.values().forEach(l -> ConsoleLog.add("  " + l.name + " - " + l.help, ConsoleLog.GRAY));
			ConsoleLog.add("  mp_autobunnyhopping - alias for sv_autobunnyhopping", ConsoleLog.GRAY);
		});
		local("noclip", "Toggle noclip (needs sv_cheats 1 and operator). Optional 0/1.", a -> {
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
		local("cl_scrolljump", "Mouse wheel = +jump (1) or hotbar scroll (0).", a -> {
			StrafeConfig.scrollJump = bool(a, !StrafeConfig.scrollJump);
			saved("cl_scrolljump", StrafeConfig.scrollJump);
		});
		local("cl_showpos", "CS-style name/pos/ang/vel readout in the top-left corner.", a -> {
			StrafeConfig.showSpeed = bool(a, !StrafeConfig.showSpeed);
			saved("cl_showpos", StrafeConfig.showSpeed);
		});
		local("cl_jumpstats", "KZ-style jump stats after each jump (distance, pre, max, strafes, sync).", a -> {
			StrafeConfig.jumpStats = bool(a, !StrafeConfig.jumpStats);
			saved("cl_jumpstats", StrafeConfig.jumpStats);
		});
		local("cl_velgraph", "Speed graph above the hotbar (last 3 s).", a -> {
			StrafeConfig.velGraph = bool(a, !StrafeConfig.velGraph);
			saved("cl_velgraph", StrafeConfig.velGraph);
		});
		local("strafemc_preset_save", "Save the current movement cvars as a named preset.", a -> {
			if (a.length == 0) {
				ConsoleLog.error("Usage: strafemc_preset_save <name>");
				return;
			}
			ConsoleLog.add(Presets.save(a[0]) ? "saved preset " + a[0] : "could not save preset " + a[0], ConsoleLog.OK);
		});
		local("strafemc_preset_load", "Load a saved preset (or 'default' for the CS2 defaults).", a -> {
			if (a.length == 0) {
				ConsoleLog.error("Usage: strafemc_preset_load <name>");
				return;
			}
			if (a[0].equalsIgnoreCase("default")) ConsoleCommands.execute("strafemc_reset_movement");
			else ConsoleLog.add(Presets.load(a[0]) ? "loaded preset " + a[0] : "no preset named " + a[0], ConsoleLog.OK);
		});
		local("strafemc_preset_delete", "Delete a saved preset.", a -> {
			if (a.length == 0) {
				ConsoleLog.error("Usage: strafemc_preset_delete <name>");
				return;
			}
			ConsoleLog.add(Presets.delete(a[0]) ? "deleted preset " + a[0] : "no preset named " + a[0], ConsoleLog.OK);
		});
		local("strafemc_presets", "List saved presets.", a -> {
			var all = Presets.names();
			ConsoleLog.info(all.isEmpty() ? "no saved presets" : "presets: " + String.join(", ", all));
		});
		local("cl_showspeed", "Speed + CS2 live/cached state above the hotbar.", a -> {
			StrafeConfig.showSpeedMeter = bool(a, !StrafeConfig.showSpeedMeter);
			saved("cl_showspeed", StrafeConfig.showSpeedMeter);
		});
		local("cl_doubletapfly", "Double-tap jump toggles creative flight while Source movement is on.", a -> {
			StrafeConfig.doubleTapFly = bool(a, !StrafeConfig.doubleTapFly);
			saved("cl_doubletapfly", StrafeConfig.doubleTapFly);
		});
		local("strafemc_movement_choice", "1: every player turns Source movement on/off for themselves; 0: operators decide for everyone (operators).", a -> {
			if (a.length == 0) {
				ConsoleLog.error("strafemc_movement_choice 1 (each player chooses) or 0 (operators decide)");
				return;
			}
			action("strafemc_movement_choice", bool(a, false) ? "1" : "0", false);
		});
		local("strafemc_movement", "Source movement on (1) / vanilla movement (0): your own if the server lets players choose, else for the whole server (operators).", a -> {
			boolean on = bool(a, !dev.strafemc.client.ClientGameplay.sourceMovement());
			action("strafemc_movement", on ? "1" : "0", false);
		});
		local("strafemc_knockback", "Gun/knife hits push targets back like Minecraft (1) or slow them like CS2 (0, default), for the whole server (operators).", a -> {
			if (a.length == 0) {
				ConsoleLog.error("strafemc_knockback 1 (Minecraft knockback) or 0 (CS2 tagging)");
				return;
			}
			action("strafemc_knockback", bool(a, false) ? "1" : "0", false);
		});
		local("strafemc_slabstep", "Step up slabs: half-block step height (1) or CS2's 18 units (0, default), for the whole server (operators).", a ->
				action("strafemc_slabstep", bool(a, !dev.strafemc.client.ClientGameplay.slabStep()) ? "1" : "0", false));
		local("strafemc_lagcomp", "Lag compensation for the whole server: shots are checked against what the shooter saw (1, default) or the present (0) (operators).", a -> {
			if (a.length == 0) {
				ConsoleLog.error("strafemc_lagcomp 1 (on) or 0 (off)");
				return;
			}
			action("strafemc_lagcomp", bool(a, true) ? "1" : "0", false);
		});
		local("strafemc_maxunlag", "How far back lag compensation may rewind, in milliseconds (default 300; CS2 uses 200) (operators).", a -> {
			if (a.length == 0) {
				ConsoleLog.error("strafemc_maxunlag <milliseconds>");
				return;
			}
			action("strafemc_maxunlag", a[0], false);
		});
		local("strafemc_reset_movement", "Restore every movement cvar to the CS2 server's default.", a -> {
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
		local("unbindall", "Remove every bind.", a -> {
			Binds.unbindAll();
			ConsoleLog.add("all binds removed", ConsoleLog.OK);
		});
		local("binds", "List your binds.", a -> {
			var all = Binds.all();
			if (all.isEmpty()) ConsoleLog.info("no binds");
			all.forEach((k, v) -> ConsoleLog.add("  " + k + " = \"" + v + "\"", ConsoleLog.GRAY));
		});
		local("cl_movementvolume", "Volume of CS2 footstep/jump/landing sounds, 0-1 (CS2's relative levels are kept).", a -> {
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
		local("cl_landvolume", "Extra volume scale for landing sounds, 0-1 (default 0.3).", a -> {
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
		viewmodelSetting("viewmodel_fov", "Field of view of the first-person weapon (60-68, default 60).", 60, 68, () -> StrafeConfig.viewmodelFov,
				v -> StrafeConfig.viewmodelFov = v);
		viewmodelSetting("viewmodel_offset_x", "Weapon position to the right (-2 to 2.5, default 1).", -2, 2.5, () -> StrafeConfig.viewmodelOffsetX,
				v -> StrafeConfig.viewmodelOffsetX = v);
		viewmodelSetting("viewmodel_offset_y", "Weapon position forward (-2 to 2, default 1).", -2, 2, () -> StrafeConfig.viewmodelOffsetY,
				v -> StrafeConfig.viewmodelOffsetY = v);
		viewmodelSetting("viewmodel_offset_z", "Weapon position up (-2 to 2, default -1).", -2, 2, () -> StrafeConfig.viewmodelOffsetZ,
				v -> StrafeConfig.viewmodelOffsetZ = v);
		for (int i = 0; i < 5; i++) { // CS2's grenade throw crosshair
			final int k = i;
			String kind = dev.strafemc.client.weapon.GrenadeCrosshair.KINDS[i];
			local("cl_grenadecrosshair_" + kind, "Is the grenade crosshair enabled (" + kind + ", default 1).", a -> {
				StrafeConfig.grenadeCrosshair[k] = bool(a, !StrafeConfig.grenadeCrosshair[k]);
				saved("cl_grenadecrosshair_" + kind, StrafeConfig.grenadeCrosshair[k]);
			});
			viewmodelSetting("cl_grenadecrosshairdelay_" + kind, "How long should the pin be pulled for before showing the grenade crosshair (0-5, default 2).",
					0, 5, () -> StrafeConfig.grenadeCrosshairDelay[k], v -> StrafeConfig.grenadeCrosshairDelay[k] = v);
		}
		viewmodelSetting("cl_grenadecrosshair_tickscaling", "Scale applied to grenade throw crosshair ticks (0.5-2, default 1.1).", 0.5, 2,
				() -> StrafeConfig.grenadeCrosshairTickScaling, v -> StrafeConfig.grenadeCrosshairTickScaling = v);
		local("cl_grenadecrosshair_ticklabels", "Draw labels on grenade throw crosshair ticks (default 1).", a -> {
			StrafeConfig.grenadeCrosshairTickLabels = bool(a, !StrafeConfig.grenadeCrosshairTickLabels);
			saved("cl_grenadecrosshair_ticklabels", StrafeConfig.grenadeCrosshairTickLabels);
		});
		local("strafemc_serverlog", "Show the raw CS2 server log in the console (1) or only replies to your commands (0).", a -> {
			StrafeConfig.showServerLog = bool(a, !StrafeConfig.showServerLog);
			saved("strafemc_serverlog", StrafeConfig.showServerLog);
		});
		local("strafemc_startserver", "Start the local CS2 server (optional host extra, if set up).", a -> {
			if (RelayTransport.remote()) ConsoleLog.error("Only the host runs this server's CS2 server.");
			else ServerLauncher.start();
		});
		local("strafemc_stopserver", "Ask the local CS2 server to quit.", a -> {
			if (RelayTransport.remote()) ConsoleLog.error("Only the host runs this server's CS2 server.");
			else ServerLauncher.stop();
		});
		local("kill", "Kill yourself (CS kill).", a -> action("kill", "", false));
		local("explode", "Explode (CS explode).", a -> action("explode", "", false));
		local("god", "Toggle godmode (needs sv_cheats 1 and operator).", a -> action("god", "", true));
		local("healme", "healme [CS HP]: heal yourself (default: fully). Needs sv_cheats 1 and operator.", a -> action("healme", a.length > 0 ? a[0] : "", true));
		local("getpos", "Print your position and view as a setpos/setang line (cl_showpos coordinates).", a -> {
			var p = Minecraft.getInstance().player;
			if (p == null) return;
			double u = StrafeConfig.unitsPerBlock, yaw = Math.toRadians(p.getYRot());
			double csYaw = Math.toDegrees(Math.atan2(-Math.cos(yaw), -Math.sin(yaw)));
			ConsoleLog.add(String.format(java.util.Locale.ROOT, "setpos %.2f %.2f %.2f;setang %.2f %.2f 0", p.getX() * u, -p.getZ() * u, p.getY() * u,
					p.getXRot(), csYaw), ConsoleLog.WHITE);
		});
		local("setpos", "setpos <x> <y> <z>: teleport (cl_showpos coordinates). Needs sv_cheats 1 and operator.", a -> {
			if (a.length < 3) {
				ConsoleLog.error("usage: setpos <x> <y> <z>");
				return;
			}
			action("setpos", a[0] + " " + a[1] + " " + a[2], true);
		});
		local("setang", "setang <pitch> <yaw> [roll]: set your view (CS angles). Needs sv_cheats 1.", a -> {
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
		local("hurtme", "hurtme <CS damage>: damage yourself (needs sv_cheats 1).", a -> action("hurtme", a.length > 0 ? a[0] : "10", true));
		local("give", "give <weapon_ak47|mag_ak47> (needs sv_cheats 1).", a -> action("give", a.length > 0 ? a[0] : "", true));
		local("impulse", "impulse 101: all weapons + ammo (needs sv_cheats 1).", a -> action("impulse", a.length > 0 ? a[0] : "", true));
		local("thirdperson", "Third-person camera.", a -> Minecraft.getInstance().options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK));
		local("firstperson", "First-person camera.", a -> Minecraft.getInstance().options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
		local("strafemc_fx_list", "List the layers of the last bullet impact effect (for strafemc_fx_hide).", a -> {
			var layers = dev.strafemc.client.particle.Effects.lastImpactLayers();
			if (layers.isEmpty()) ConsoleLog.add("No impact effect yet: shoot a block first.", ConsoleLog.WARN);
			for (String l : layers) ConsoleLog.add(l, ConsoleLog.WHITE);
		});
		local("strafemc_fx_hide", "strafemc_fx_hide <layer name or part of it> | clear: hide particle layers to find a broken one.", a -> {
			if (a.length == 0) {
				ConsoleLog.add("hidden: " + dev.strafemc.client.particle.Effects.hiddenNames(), ConsoleLog.WHITE);
				return;
			}
			dev.strafemc.client.particle.Effects.hide(a[0]);
			ConsoleLog.add("hidden: " + dev.strafemc.client.particle.Effects.hiddenNames(), ConsoleLog.OK);
		});
		local("strafemc_relearn", "Reload the CS2 command list used for autocomplete.", a -> NetconTransport.INSTANCE.learnCommands());
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
