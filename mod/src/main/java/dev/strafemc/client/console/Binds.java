package dev.strafemc.client.console;

import dev.strafemc.StrafeMc;
import dev.strafemc.config.StrafeConfig;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Source-style key binds (`bind f "sv_cheats 1"`, `bind mwheeldown +jump`). Stored in the runtime dir (binds.properties).
 * "+jump", "+duck" and "+speed" are held movement inputs; anything else runs as a console command on key press
 * (and "+foo" also runs "-foo" on release, like Source).
 */
public final class Binds {
	private static final Path FILE = StrafeConfig.RUNTIME_DIR.resolve("binds.properties");
	private static final Map<String, String> BINDS = new TreeMap<>();
	private static final Map<String, Boolean> WAS_DOWN = new HashMap<>();
	public static volatile boolean holdJump, holdDuck, holdSpeed;

	private static final Map<String, Integer> KEYS = new HashMap<>();

	static {
		for (char c = 'a'; c <= 'z'; c++) KEYS.put(String.valueOf(c), GLFW.GLFW_KEY_A + (c - 'a'));
		for (char c = '0'; c <= '9'; c++) KEYS.put(String.valueOf(c), GLFW.GLFW_KEY_0 + (c - '0'));
		for (int i = 1; i <= 12; i++) KEYS.put("f" + i, GLFW.GLFW_KEY_F1 + i - 1);
		for (int i = 0; i <= 9; i++) KEYS.put("kp_" + i, GLFW.GLFW_KEY_KP_0 + i);
		Object[][] named = {{"space", GLFW.GLFW_KEY_SPACE}, {"tab", GLFW.GLFW_KEY_TAB}, {"enter", GLFW.GLFW_KEY_ENTER},
				{"shift", GLFW.GLFW_KEY_LEFT_SHIFT}, {"rshift", GLFW.GLFW_KEY_RIGHT_SHIFT}, {"ctrl", GLFW.GLFW_KEY_LEFT_CONTROL},
				{"rctrl", GLFW.GLFW_KEY_RIGHT_CONTROL}, {"alt", GLFW.GLFW_KEY_LEFT_ALT}, {"ralt", GLFW.GLFW_KEY_RIGHT_ALT},
				{"capslock", GLFW.GLFW_KEY_CAPS_LOCK}, {"backspace", GLFW.GLFW_KEY_BACKSPACE}, {"uparrow", GLFW.GLFW_KEY_UP},
				{"downarrow", GLFW.GLFW_KEY_DOWN}, {"leftarrow", GLFW.GLFW_KEY_LEFT}, {"rightarrow", GLFW.GLFW_KEY_RIGHT},
				{"ins", GLFW.GLFW_KEY_INSERT}, {"del", GLFW.GLFW_KEY_DELETE}, {"home", GLFW.GLFW_KEY_HOME}, {"end", GLFW.GLFW_KEY_END},
				{"pgup", GLFW.GLFW_KEY_PAGE_UP}, {"pgdn", GLFW.GLFW_KEY_PAGE_DOWN}, {"kp_enter", GLFW.GLFW_KEY_KP_ENTER},
				{"kp_plus", GLFW.GLFW_KEY_KP_ADD}, {"kp_minus", GLFW.GLFW_KEY_KP_SUBTRACT}, {"kp_multiply", GLFW.GLFW_KEY_KP_MULTIPLY},
				{"kp_slash", GLFW.GLFW_KEY_KP_DIVIDE}, {"kp_del", GLFW.GLFW_KEY_KP_DECIMAL}, {"semicolon", GLFW.GLFW_KEY_SEMICOLON},
				{"'", GLFW.GLFW_KEY_APOSTROPHE}, {",", GLFW.GLFW_KEY_COMMA}, {".", GLFW.GLFW_KEY_PERIOD}, {"/", GLFW.GLFW_KEY_SLASH},
				{"\\", GLFW.GLFW_KEY_BACKSLASH}, {"-", GLFW.GLFW_KEY_MINUS}, {"=", GLFW.GLFW_KEY_EQUAL}, {"[", GLFW.GLFW_KEY_LEFT_BRACKET},
				{"]", GLFW.GLFW_KEY_RIGHT_BRACKET}};
		for (Object[] n : named) KEYS.put((String) n[0], (Integer) n[1]);
	}

	private Binds() {}

	/** Every key name bind accepts, sorted (for autocomplete). */
	public static java.util.List<String> keyNames() {
		java.util.List<String> l = new java.util.ArrayList<>(KEYS.keySet());
		for (int i = 1; i <= 5; i++) l.add("mouse" + i);
		l.add("mwheelup");
		l.add("mwheeldown");
		java.util.Collections.sort(l);
		return l;
	}

	public static boolean validKey(String k) {
		k = k.toLowerCase();
		return KEYS.containsKey(k) || k.matches("mouse[1-5]") || k.equals("mwheelup") || k.equals("mwheeldown");
	}

	public static Map<String, String> all() {
		synchronized (BINDS) {
			return new TreeMap<>(BINDS);
		}
	}

	public static String get(String key) {
		synchronized (BINDS) {
			return BINDS.get(key.toLowerCase());
		}
	}

	public static void bind(String key, String cmd) {
		synchronized (BINDS) {
			BINDS.put(key.toLowerCase(), cmd);
		}
		save();
	}

	public static boolean unbind(String key) {
		boolean had;
		synchronized (BINDS) {
			had = BINDS.remove(key.toLowerCase()) != null;
		}
		save();
		return had;
	}

	public static void unbindAll() {
		synchronized (BINDS) {
			BINDS.clear();
		}
		save();
	}

	/** Mouse wheel: returns true if a bind handled it. */
	public static boolean wheel(boolean up) {
		String cmd = get(up ? "mwheelup" : "mwheeldown");
		if (cmd == null) return false;
		if (cmd.trim().equals("+jump")) dev.strafemc.client.movement.MovementController.INSTANCE.queueWheelJump();
		else ConsoleCommands.execute(cmd.startsWith("+") ? cmd.substring(1) : cmd);
		return true;
	}

	/** Polled every frame while in-game with no screen open. */
	public static void poll(Minecraft mc) {
		boolean jump = false, duck = false, speed = false;
		long win = mc.getWindow().handle();
		for (var e : all().entrySet()) {
			String k = e.getKey(), cmd = e.getValue().trim();
			if (k.startsWith("mwheel")) continue;
			boolean down = mc.screen == null && isDown(win, k);
			boolean was = WAS_DOWN.getOrDefault(k, false);
			WAS_DOWN.put(k, down);
			switch (cmd) {
				case "+jump" -> jump |= down;
				case "+duck" -> duck |= down;
				case "+speed" -> speed |= down;
				default -> {
					if (down && !was) ConsoleCommands.execute(cmd);
					else if (!down && was && cmd.startsWith("+") && !cmd.contains(";")) ConsoleCommands.execute("-" + cmd.substring(1));
				}
			}
		}
		holdJump = jump;
		holdDuck = duck;
		holdSpeed = speed;
	}

	private static boolean isDown(long win, String k) {
		if (k.startsWith("mouse")) {
			int b = Integer.parseInt(k.substring(5)) - 1; // mouse1 = left
			return GLFW.glfwGetMouseButton(win, b) == GLFW.GLFW_PRESS;
		}
		Integer code = KEYS.get(k);
		return code != null && GLFW.glfwGetKey(win, code) == GLFW.GLFW_PRESS;
	}

	public static void load() {
		try {
			if (!Files.exists(FILE)) return;
			Properties p = new Properties();
			try (Reader r = Files.newBufferedReader(FILE)) {
				p.load(r);
			}
			synchronized (BINDS) {
				for (String k : p.stringPropertyNames()) BINDS.put(k, p.getProperty(k));
			}
			StrafeMc.LOG.info("[strafemc] {} binds loaded", BINDS.size());
		} catch (Exception e) {
			StrafeMc.LOG.warn("[strafemc] binds unreadable: {}", e.toString());
		}
	}

	private static void save() {
		try {
			Properties p = new Properties();
			p.putAll(all());
			try (Writer w = Files.newBufferedWriter(FILE)) {
				p.store(w, "strafeMC binds (CS key name = command)");
			}
		} catch (Exception e) {
			StrafeMc.LOG.warn("[strafemc] binds not saved: {}", e.toString());
		}
	}
}
