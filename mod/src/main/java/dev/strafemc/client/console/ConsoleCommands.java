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
	public static ConsoleTransport transport = NetconTransport.INSTANCE;

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
		local("strafemc_movement", "Source movement on (1) / vanilla movement (0).", a -> {
			StrafeConfig.movementEnabled = bool(a, !StrafeConfig.movementEnabled);
			saved("strafemc_movement", StrafeConfig.movementEnabled);
		});
		local("strafemc_reset_movement", "Restore every movement cvar to the CS2 server's default.", a -> {
			Map<String, String> d = Cs2Bridge.INSTANCE.defaults();
			if (d.isEmpty()) {
				ConsoleLog.error("No defaults received from the CS2 server yet (is it running with -Metamod?).");
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
		local("viewmodel_fov", "Field of view of the first-person hand / viewmodel (54-68, default 68).", a -> {
			if (a.length > 0) {
				try {
					StrafeConfig.viewmodelFov = Math.max(54, Math.min(68, Double.parseDouble(a[0])));
					StrafeConfig.save();
				} catch (Exception e) {
					ConsoleLog.error("viewmodel_fov: expected a number");
					return;
				}
			}
			ConsoleLog.add("viewmodel_fov = " + StrafeConfig.viewmodelFov, ConsoleLog.OK);
		});
		local("cl_footstepteam", "CS2 footstep set: ct or t.", a -> {
			if (a.length > 0 && (a[0].equalsIgnoreCase("t") || a[0].equalsIgnoreCase("ct"))) StrafeConfig.footstepTeam = a[0].toLowerCase();
			try {
				StrafeConfig.save();
			} catch (Exception ignored) {
			}
			ConsoleLog.add("cl_footstepteam = " + StrafeConfig.footstepTeam, ConsoleLog.OK);
		});
		local("strafemc_serverlog", "Show the raw CS2 server log in the console (1) or only replies to your commands (0).", a -> {
			StrafeConfig.showServerLog = bool(a, !StrafeConfig.showServerLog);
			saved("strafemc_serverlog", StrafeConfig.showServerLog);
		});
		local("strafemc_startserver", "Start the local CS2 server through launch-cs2.bat (Steam must be closed).", a -> ServerLauncher.start());
		local("strafemc_stopserver", "Ask the local CS2 server to quit.", a -> ServerLauncher.stop());
		local("kill", "Kill yourself (CS kill).", a -> action("kill", "", false));
		local("explode", "Explode (CS explode).", a -> action("explode", "", false));
		local("god", "Toggle godmode (needs sv_cheats 1 and operator).", a -> action("god", "", true));
		local("hurtme", "hurtme <CS damage>: damage yourself (needs sv_cheats 1).", a -> action("hurtme", a.length > 0 ? a[0] : "10", true));
		local("give", "give <weapon_ak47|mag_ak47> (needs sv_cheats 1).", a -> action("give", a.length > 0 ? a[0] : "", true));
		local("impulse", "impulse 101: all weapons + ammo (needs sv_cheats 1).", a -> action("impulse", a.length > 0 ? a[0] : "", true));
		local("thirdperson", "Third-person camera.", a -> Minecraft.getInstance().options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK));
		local("firstperson", "First-person camera.", a -> Minecraft.getInstance().options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
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
		String v = Cs2Bridge.INSTANCE.cvar("sv_cheats");
		return "1".equals(v) || "true".equals(v);
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
