package dev.strafemc.config;

import dev.strafemc.StrafeMc;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * User-tunable settings in strafemc.properties, in strafeMC's folder (see runtimeDir): a "strafemc" folder inside the
 * Minecraft profile for new installs; older setups keep their existing folder.
 */
public final class StrafeConfig {
	public static final Path RUNTIME_DIR = runtimeDir();

	/**
	 * strafeMC's folder (settings, converted CS2 files): "strafemc" inside the Minecraft profile. Setups made before that
	 * (a %USERPROFILE%\\strafemc with a settings file) keep using their folder; -Dstrafemc.runtime=... overrides both.
	 */
	private static Path runtimeDir() {
		String prop = System.getProperty("strafemc.runtime");
		if (prop != null) return Path.of(prop);
		Path legacy = Path.of(System.getProperty("user.home"), "strafemc");
		if (Files.exists(legacy.resolve("strafemc.properties"))) return legacy;
		return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("strafemc");
	}

	private static final Path FILE = RUNTIME_DIR.resolve("strafemc.properties");

	/** The player chose "don't ask again" on the setup screen. */
	public static boolean setupNeverAsk = false;
	/** Advanced: start CS2's dedicated server from the player's CS2 folder when they open their own world. */
	public static boolean hostServer = false;
	/** The "run a CS2 server with your worlds?" question was answered (asked on the first singleplayer world). */
	public static boolean hostAsked = false;

	/** Master switch for Source movement. */
	public static boolean movementEnabled = true;
	/**
	 * Hammer units per Minecraft block. 40 makes the engine's 72-unit standing hull 1.8 blocks, exactly Minecraft's
	 * player height, and the 32-unit hull width 0.8 blocks (still fits 1-wide gaps). Jump apex 57 u = 1.43 blocks, so
	 * 1-block ledges are reachable as in vanilla; sv_stepsize 18 u = 0.45 blocks, so slabs/stairs need a jump, like
	 * an 18+ unit ledge in CS.
	 */
	public static double unitsPerBlock = 40.0;
	/** Minecraft HP per CS HP. 100 CS HP = 20 MC HP. */
	public static double hpScale = 0.2;
	/** Bullet-hole size relative to CS2's DecalWorldWidth/Height (user asked for 0.6; CS2 itself = 1.0). */
	public static double bulletHoleScale = 0.6;
	/** Mouse wheel triggers +jump (CS-style bind) instead of hotbar scrolling. */
	public static boolean scrollJump = true;
	/** Show horizontal speed (units/s) on the action bar, like cl_showpos. */
	public static boolean showSpeed = true;
	/** Double-tapping jump toggles creative flight while Source movement is active (off: bhop spam never starts flying). */
	public static boolean doubleTapFly = false;
	/** Volume of CS2 movement sounds (footsteps, jump, landing); CS2's own relative levels are kept. */
	public static double movementVolume = 0.6;
	/**
	 * Extra scale for landing sounds. The landing recordings are ~4x louder (RMS) than footstep recordings and CS2's
	 * client-side mix group evens that out in ways the files don't describe; 0.3 puts them next to the footsteps.
	 */
	public static double landVolume = 0.3;
	/** viewmodel_fov (CS2 client cvar; default 68, allowed 54-68 - Source/CS2 values, not readable from the server). */
	/** CS2's viewmodel_fov: 60..68, default 60. */
	public static double viewmodelFov = 60;
	/** CS2's viewmodel_offset_x / _y / _z (units: right, forward, up); defaults 1, 1, -1. Unlimited (CS2 clamps x -2..2.5, y and z -2..2). */
	public static double viewmodelOffsetX = 1, viewmodelOffsetY = 1, viewmodelOffsetZ = -1;
	/**
	 * CS2's grenade throw crosshair (client cvars, CS2 defaults): cl_grenadecrosshair_<explosive|flash|smoke|fire|decoy>
	 * on, cl_grenadecrosshairdelay_* 2 s (0..5), cl_grenadecrosshair_tickscaling 1.1 (0.5..2), cl_grenadecrosshair_ticklabels on.
	 */
	public static boolean[] grenadeCrosshair = {true, true, true, true, true};
	public static double[] grenadeCrosshairDelay = {2, 2, 2, 2, 2};
	public static double grenadeCrosshairTickScaling = 1.1;
	public static boolean grenadeCrosshairTickLabels = true;
	/** KZ-style jump stats after each jump (cl_jumpstats). */
	public static boolean jumpStats = true;
	/** VRF glTF axes -> camera space for the viewmodel (clip/model space is y-up, z forward, x left -> camera x=-x, y=y, z=-z). */
	public static String vmAxes = "-x,y,-z";
	/** Speed + CS2 link state above the hotbar (cl_showspeed). showSpeed is cl_showpos. */
	public static boolean showSpeedMeter = true;
	/** Speed graph above the hotbar (cl_velgraph). */
	public static boolean velGraph = false;
	/** Your CS side, "ct" or "t" (console quick menu, strafemc_team): footstep set and smoke colour, later your agent. */
	public static String team = "ct";
	/** CS2's cl_righthand: the viewmodel in the right hand (false: mirrored into the left). switchhands flips it. */
	public static boolean rightHand = true;
	/** H was bound to switchhands once (like CS2's default), so a removed or changed H bind stays as the player left it. */
	public static boolean switchHandsBound = false;
	/** Show the raw CS2 server log in the console (otherwise only replies to your own commands). */
	public static boolean showServerLog = false;
	/** Verbose movement/bridge logging. */
	public static boolean debug = true;

	public static int bridgePort = 29016;
	public static int netconPort = 29015;
	/** The player's CS2 folder (contains game\\csgo\\pak01_dir.vpk); empty until set up. */
	public static String cs2Path = "";
	/** launch-cs2.bat used by "Start CS2 server" (all safety checks live there). */
	public static String launcherPath = "";
	/** The ValveResourceFormat converter (Source2Viewer-CLI); empty until set up. */
	public static String vrfPath = "";

	private StrafeConfig() {}

	public static Path cacheDir() {
		return RUNTIME_DIR.resolve("cache");
	}

	public static void load() {
		try {
			Files.createDirectories(RUNTIME_DIR);
			Properties p = new Properties();
			if (Files.exists(FILE)) {
				try (Reader r = Files.newBufferedReader(FILE)) {
					p.load(r);
				}
			}
			movementEnabled = Boolean.parseBoolean(p.getProperty("movementEnabled", String.valueOf(movementEnabled)));
			unitsPerBlock = Double.parseDouble(p.getProperty("unitsPerBlock", String.valueOf(unitsPerBlock)));
			bulletHoleScale = Double.parseDouble(p.getProperty("bulletHoleScale", String.valueOf(bulletHoleScale)));
			hpScale = Double.parseDouble(p.getProperty("hpScale", String.valueOf(hpScale)));
			scrollJump = Boolean.parseBoolean(p.getProperty("scrollJump", String.valueOf(scrollJump)));
			showSpeed = Boolean.parseBoolean(p.getProperty("showSpeed", String.valueOf(showSpeed)));
			debug = Boolean.parseBoolean(p.getProperty("debug", String.valueOf(debug)));
			doubleTapFly = Boolean.parseBoolean(p.getProperty("doubleTapFly", String.valueOf(doubleTapFly)));
			movementVolume = Double.parseDouble(p.getProperty("movementVolume", String.valueOf(movementVolume)));
			landVolume = Double.parseDouble(p.getProperty("landVolume", String.valueOf(landVolume)));
			viewmodelFov = Math.max(60, Math.min(68, Double.parseDouble(p.getProperty("viewmodelFov", String.valueOf(viewmodelFov)))));
			viewmodelOffsetX = Double.parseDouble(p.getProperty("viewmodelOffsetX", String.valueOf(viewmodelOffsetX)));
			viewmodelOffsetY = Double.parseDouble(p.getProperty("viewmodelOffsetY", String.valueOf(viewmodelOffsetY)));
			viewmodelOffsetZ = Double.parseDouble(p.getProperty("viewmodelOffsetZ", String.valueOf(viewmodelOffsetZ)));
			jumpStats = Boolean.parseBoolean(p.getProperty("jumpStats", String.valueOf(jumpStats)));
			team = "t".equalsIgnoreCase(p.getProperty("team", team)) ? "t" : "ct";
			rightHand = Boolean.parseBoolean(p.getProperty("rightHand", String.valueOf(rightHand)));
			switchHandsBound = Boolean.parseBoolean(p.getProperty("switchHandsBound", String.valueOf(switchHandsBound)));
			for (int i = 0; i < 5; i++) {
				String k = dev.strafemc.client.weapon.GrenadeCrosshair.KINDS[i];
				grenadeCrosshair[i] = Boolean.parseBoolean(p.getProperty("grenadeCrosshair." + k, String.valueOf(grenadeCrosshair[i])));
				grenadeCrosshairDelay[i] = Math.max(0, Math.min(5, Double.parseDouble(p.getProperty("grenadeCrosshairDelay." + k, String.valueOf(grenadeCrosshairDelay[i])))));
			}
			grenadeCrosshairTickScaling = Math.max(0.5, Math.min(2, Double.parseDouble(p.getProperty("grenadeCrosshairTickScaling", String.valueOf(grenadeCrosshairTickScaling)))));
			grenadeCrosshairTickLabels = Boolean.parseBoolean(p.getProperty("grenadeCrosshairTickLabels", String.valueOf(grenadeCrosshairTickLabels)));
			vmAxes = p.getProperty("viewmodelAxes", vmAxes);
			showSpeedMeter = Boolean.parseBoolean(p.getProperty("showSpeedMeter", String.valueOf(showSpeedMeter)));
			velGraph = Boolean.parseBoolean(p.getProperty("velGraph", String.valueOf(velGraph)));
			showServerLog = Boolean.parseBoolean(p.getProperty("showServerLog", String.valueOf(showServerLog)));
			bridgePort = Integer.parseInt(p.getProperty("bridgePort", String.valueOf(bridgePort)));
			netconPort = Integer.parseInt(p.getProperty("netconPort", String.valueOf(netconPort)));
			cs2Path = p.getProperty("cs2Path", cs2Path);
			vrfPath = p.getProperty("vrfPath", vrfPath);
			launcherPath = p.getProperty("launcherPath", launcherPath);
			setupNeverAsk = Boolean.parseBoolean(p.getProperty("setupNeverAsk", String.valueOf(setupNeverAsk)));
			hostServer = Boolean.parseBoolean(p.getProperty("hostServer", String.valueOf(hostServer)));
			hostAsked = Boolean.parseBoolean(p.getProperty("hostAsked", String.valueOf(hostAsked)));
			save();
			StrafeMc.LOG.info("[strafemc] config {} : unitsPerBlock={} hpScale={} scrollJump={} debug={}", FILE, unitsPerBlock, hpScale, scrollJump, debug);
		} catch (IOException | RuntimeException e) {
			StrafeMc.LOG.error("[strafemc] config load failed, using defaults", e);
		}
	}

	public static void save() throws IOException {
		Properties p = new Properties();
		p.setProperty("movementEnabled", String.valueOf(movementEnabled));
		p.setProperty("unitsPerBlock", String.valueOf(unitsPerBlock));
		p.setProperty("hpScale", String.valueOf(hpScale));
		p.setProperty("scrollJump", String.valueOf(scrollJump));
		p.setProperty("showSpeed", String.valueOf(showSpeed));
		p.setProperty("debug", String.valueOf(debug));
		p.setProperty("bulletHoleScale", String.valueOf(bulletHoleScale));
		p.setProperty("doubleTapFly", String.valueOf(doubleTapFly));
		p.setProperty("movementVolume", String.valueOf(movementVolume));
		p.setProperty("landVolume", String.valueOf(landVolume));
		p.setProperty("viewmodelFov", String.valueOf(viewmodelFov));
		p.setProperty("viewmodelOffsetX", String.valueOf(viewmodelOffsetX));
		p.setProperty("viewmodelOffsetY", String.valueOf(viewmodelOffsetY));
		p.setProperty("viewmodelOffsetZ", String.valueOf(viewmodelOffsetZ));
		p.setProperty("jumpStats", String.valueOf(jumpStats));
		p.setProperty("team", team);
		p.setProperty("rightHand", String.valueOf(rightHand));
		p.setProperty("switchHandsBound", String.valueOf(switchHandsBound));
		for (int i = 0; i < 5; i++) {
			String k = dev.strafemc.client.weapon.GrenadeCrosshair.KINDS[i];
			p.setProperty("grenadeCrosshair." + k, String.valueOf(grenadeCrosshair[i]));
			p.setProperty("grenadeCrosshairDelay." + k, String.valueOf(grenadeCrosshairDelay[i]));
		}
		p.setProperty("grenadeCrosshairTickScaling", String.valueOf(grenadeCrosshairTickScaling));
		p.setProperty("grenadeCrosshairTickLabels", String.valueOf(grenadeCrosshairTickLabels));
		p.setProperty("viewmodelAxes", vmAxes);
		p.setProperty("showSpeedMeter", String.valueOf(showSpeedMeter));
		p.setProperty("velGraph", String.valueOf(velGraph));
		p.setProperty("showServerLog", String.valueOf(showServerLog));
		p.setProperty("bridgePort", String.valueOf(bridgePort));
		p.setProperty("netconPort", String.valueOf(netconPort));
		p.setProperty("cs2Path", cs2Path);
		p.setProperty("vrfPath", vrfPath);
		p.setProperty("launcherPath", launcherPath);
		p.setProperty("setupNeverAsk", String.valueOf(setupNeverAsk));
		p.setProperty("hostServer", String.valueOf(hostServer));
		p.setProperty("hostAsked", String.valueOf(hostAsked));
		try (Writer w = Files.newBufferedWriter(FILE)) {
			p.store(w, "strafeMC settings (unitsPerBlock: Hammer units per block; hpScale: MC HP per CS HP)");
		}
	}
}
