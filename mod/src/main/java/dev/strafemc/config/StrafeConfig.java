package dev.strafemc.config;

import dev.strafemc.StrafeMc;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * User-tunable settings. Lives OUTSIDE the Minecraft profile (rule: only the jar goes into the profile):
 * %USERPROFILE%\strafemc\strafemc.properties, override the directory with -Dstrafemc.runtime=...
 */
public final class StrafeConfig {
	public static final Path RUNTIME_DIR = Path.of(System.getProperty("strafemc.runtime",
			Path.of(System.getProperty("user.home"), "strafemc").toString()));
	private static final Path FILE = RUNTIME_DIR.resolve("strafemc.properties");
	/** Pre-rename location (cs2mc era); migrated once on first start. */
	private static final Path OLD_RUNTIME_DIR = Path.of(System.getProperty("user.home"), "cs2mc", "runtime");

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
	public static double viewmodelFov = 68;
	/** KZ-style jump stats after each jump (cl_jumpstats). */
	public static boolean jumpStats = true;
	/** VRF glTF axes -> camera space for the viewmodel (clip/model space is y-up, z forward, x left -> camera x=-x, y=y, z=-z). */
	public static String vmAxes = "-x,y,-z";
	/** Speed + CS2 link state above the hotbar (cl_showspeed). showSpeed is cl_showpos. */
	public static boolean showSpeedMeter = true;
	/** Speed graph above the hotbar (cl_velgraph). */
	public static boolean velGraph = false;
	/** Which CS2 footstep set to use: "ct" or "t". */
	public static String footstepTeam = "ct";
	/** Show the raw CS2 server log in the console (otherwise only replies to your own commands). */
	public static boolean showServerLog = false;
	/** Verbose movement/bridge logging. */
	public static boolean debug = true;

	public static int bridgePort = 29016;
	public static int netconPort = 29015;
	public static String cs2Path = "C:\\Program Files (x86)\\Steam\\steamapps\\common\\Counter-Strike Global Offensive - MINECRAFT INTEGRATION";
	/** launch-cs2.bat used by "Start CS2 server" (all safety checks live there). */
	public static String launcherPath = Path.of(System.getProperty("user.home"), "cs2mc", "launch-cs2.bat").toString();
	public static String vrfPath = Path.of(System.getProperty("user.home"), "cs2mc", "tools", "vrf", "Source2Viewer-CLI.exe").toString();

	private StrafeConfig() {}

	public static Path cacheDir() {
		return RUNTIME_DIR.resolve("cache");
	}

	/** Moves the old %USERPROFILE%\\cs2mc\\runtime (settings, binds, caches) to the strafeMC location once. */
	private static void migrate() {
		try {
			if (Files.exists(RUNTIME_DIR) || !Files.isDirectory(OLD_RUNTIME_DIR)) return;
			Files.createDirectories(RUNTIME_DIR.getParent());
			Files.move(OLD_RUNTIME_DIR, RUNTIME_DIR);
			Path oldCfg = RUNTIME_DIR.resolve("cs2mc.properties");
			if (Files.exists(oldCfg)) Files.move(oldCfg, FILE);
			StrafeMc.LOG.info("[strafemc] moved runtime files from {} to {}", OLD_RUNTIME_DIR, RUNTIME_DIR);
		} catch (IOException e) {
			StrafeMc.LOG.warn("[strafemc] could not migrate {}: {}", OLD_RUNTIME_DIR, e.toString());
		}
	}

	public static void load() {
		migrate();
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
			viewmodelFov = Double.parseDouble(p.getProperty("viewmodelFov", String.valueOf(viewmodelFov)));
			jumpStats = Boolean.parseBoolean(p.getProperty("jumpStats", String.valueOf(jumpStats)));
			vmAxes = p.getProperty("viewmodelAxes", vmAxes);
			showSpeedMeter = Boolean.parseBoolean(p.getProperty("showSpeedMeter", String.valueOf(showSpeedMeter)));
			velGraph = Boolean.parseBoolean(p.getProperty("velGraph", String.valueOf(velGraph)));
			footstepTeam = p.getProperty("footstepTeam", footstepTeam);
			showServerLog = Boolean.parseBoolean(p.getProperty("showServerLog", String.valueOf(showServerLog)));
			bridgePort = Integer.parseInt(p.getProperty("bridgePort", String.valueOf(bridgePort)));
			netconPort = Integer.parseInt(p.getProperty("netconPort", String.valueOf(netconPort)));
			cs2Path = p.getProperty("cs2Path", cs2Path);
			vrfPath = p.getProperty("vrfPath", vrfPath);
			launcherPath = p.getProperty("launcherPath", launcherPath);
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
		p.setProperty("jumpStats", String.valueOf(jumpStats));
		p.setProperty("viewmodelAxes", vmAxes);
		p.setProperty("showSpeedMeter", String.valueOf(showSpeedMeter));
		p.setProperty("velGraph", String.valueOf(velGraph));
		p.setProperty("footstepTeam", footstepTeam);
		p.setProperty("showServerLog", String.valueOf(showServerLog));
		p.setProperty("bridgePort", String.valueOf(bridgePort));
		p.setProperty("netconPort", String.valueOf(netconPort));
		p.setProperty("cs2Path", cs2Path);
		p.setProperty("vrfPath", vrfPath);
		p.setProperty("launcherPath", launcherPath);
		try (Writer w = Files.newBufferedWriter(FILE)) {
			p.store(w, "strafeMC settings (unitsPerBlock: Hammer units per block; hpScale: MC HP per CS HP)");
		}
	}
}
