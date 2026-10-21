package dev.strafemc.client.console;

import dev.strafemc.bridge.Cs2Bridge;
import dev.strafemc.client.movement.MovementController;
import dev.strafemc.config.StrafeConfig;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Source-style developer console. Output = live CS2 server log (netcon) + local messages; input goes to
 * {@link ConsoleCommands}. The gear in the title bar opens a quick menu of toggles.
 */
public final class ConsoleScreen extends Screen {
	/** Kept between game sessions (the newest HISTORY_MAX commands), like CS2's console. */
	private static final java.nio.file.Path HISTORY_FILE = StrafeConfig.RUNTIME_DIR.resolve("console-history.txt");
	private static final int HISTORY_MAX = 200;
	private static final List<String> HISTORY = loadHistory();
	private static boolean menuOpen;

	private static final int BG = 0xE0202020, TITLE = 0xF0303030, BORDER = 0xFF5A5A5A, LOG_BG = 0xC0101010, SEL = 0xFF3C5A8C;

	private EditBox input;
	private Button submit, gear;
	private final List<MenuItem> items = new ArrayList<>();
	private int historyPos = -1, scroll, sel = -1;
	private int wx, wy, ww, wh;
	private List<String[]> suggestions = List.of();

	/** One quick-menu row: its name, its value, its control, an optional back-to-default button, the cvar or setting it edits. */
	private record MenuItem(String name, Supplier<String> value, Button button, Button reset, String cvar, boolean numeric, boolean header) {}

	private static final int ROW_H = 18, ROW_GAP = 2, CTRL_W = 80;
	/** strafeMC menu colours (StrafeSettingsScreen). */
	private static final int MENU_BG = dev.strafemc.client.StrafeSettingsScreen.BG, MENU_LINE = 0x30FFFFFF, ACCENT = 0xFFE0C060;
	/** Quick-menu width: 236 GUI px, shrunk on small screens / large GUI scales so the log keeps at least half the window. */
	private int menuW = 236;
	private int menuScroll;
	private EditBox menuEdit;
	private String editingCvar;

	/** Opens the quick menu with the console (the menu sweep pictures it). */
	public static void showMenu() {
		menuOpen = true;
	}

	public ConsoleScreen() {
		super(Component.literal("Console"));
	}

	@Override
	protected void init() {
		ww = (int) (width * 0.84);
		wh = (int) (height * 0.72);
		wx = (width - ww) / 2;
		wy = (int) (height * 0.05);
		menuW = Math.max(150, Math.min(236, ww / 2 - 10));

		String keep = input == null ? "" : input.getValue();
		input = new EditBox(font, wx + 6, wy + wh - 22, ww - 74, 16, Component.literal("command"));
		input.setMaxLength(512);
		input.setValue(keep);
		input.setResponder(v -> {
			if (v.contains("`")) input.setValue(v.replace("`", ""));
			updateSuggestions();
		});
		addRenderableWidget(input);
		setInitialFocus(input);
		submit = addRenderableWidget(Button.builder(Component.literal("Submit"), b -> submit()).bounds(wx + ww - 64, wy + wh - 23, 58, 18).build());
		gear = addRenderableWidget(Button.builder(Component.literal("⚙"), b -> {
			menuOpen = !menuOpen;
			layoutMenu();
		}).bounds(wx + ww - 20, wy + 1, 18, 14).build());
		gear.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Quick settings")));

		items.clear();
		// the same sections and look as the strafeMC menus: a gold header per section, each row a name and its control
		header("You");
		row("Team", () -> "t".equals(StrafeConfig.team) ? "T" : "CT", () -> ConsoleCommands.execute("strafemc_team"));
		row("Movement", () -> dev.strafemc.client.ClientGameplay.sourceMovement() ? "CS2" : "Minecraft", () -> ConsoleCommands.execute("strafemc_movement"));
		row("Scroll wheel", () -> StrafeConfig.scrollJump ? "Jump" : "Hotbar", () -> ConsoleCommands.execute("cl_scrolljump"));
		row("Double-tap to fly", () -> onOff(StrafeConfig.doubleTapFly), () -> ConsoleCommands.execute("cl_doubletapfly"));
		row("Right-handed", () -> onOff(StrafeConfig.rightHand), () -> ConsoleCommands.execute("cl_righthand " + (StrafeConfig.rightHand ? 0 : 1)));
		row("Show position", () -> onOff(StrafeConfig.showSpeed), () -> ConsoleCommands.execute("cl_showpos"));
		row("Speed meter", () -> onOff(StrafeConfig.showSpeedMeter), () -> ConsoleCommands.execute("cl_showspeed"));
		row("Jump stats", () -> onOff(StrafeConfig.jumpStats), () -> ConsoleCommands.execute("cl_jumpstats"));
		row("Velocity graph", () -> onOff(StrafeConfig.velGraph), () -> ConsoleCommands.execute("cl_velgraph"));
		row("Performance overlay", () -> onOff(dev.strafemc.client.perf.Perf.on), () -> ConsoleCommands.execute("strafemc_perf"));
		row("CS2 server log", () -> onOff(StrafeConfig.showServerLog), () -> ConsoleCommands.execute("strafemc_serverlog"));
		row("Crosshair", () -> "Edit...", () -> minecraft.setScreen(dev.strafemc.client.StrafeSettingsScreen.at(this, "crosshair")));
		row("Movement presets", () -> "Open...", () -> minecraft.setScreen(new PresetScreen(this)));

		header("Server");
		row("Buy menu", () -> capital(dev.strafemc.weapon.BuyMenu.modeName(dev.strafemc.client.ClientGameplay.buyMode())), () -> ConsoleCommands.execute("strafemc_buymenu"));
		row("Buy time", () -> dev.strafemc.client.ClientGameplay.buyTime() == 0 ? "No limit" : dev.strafemc.client.ClientGameplay.buyTime() + " s",
				() -> cycle("strafemc_buytime", dev.strafemc.client.ClientGameplay.buyTime(), 0, 10, 20, 45, 90));
		row("Kill card time", () -> dev.strafemc.client.ClientGameplay.killCardTime() == 0 ? "Until death" : dev.strafemc.client.ClientGameplay.killCardTime() + " s",
				() -> cycle("strafemc_killcards_time", dev.strafemc.client.ClientGameplay.killCardTime(), 0, 3, 5, 10, 30));
		row("Loadout on death", () -> dev.strafemc.client.ClientGameplay.loadoutKeep() ? "Kept" : "Dropped", () -> ConsoleCommands.execute("strafemc_loadout_keep"));
		row("Care packages", () -> onOff(dev.strafemc.client.ClientGameplay.drops()),
				() -> ConsoleCommands.execute("strafemc_drops " + (dev.strafemc.client.ClientGameplay.drops() ? 0 : 1)));
		setting("Wear on drops", "strafemc_wear", "On", "Factory New");
		row("Game mode", () -> {
			double v = dev.strafemc.client.ClientGameplay.setting("strafemc_gamemode");
			var modes = dev.strafemc.mode.GameModes.MODES;
			return Double.isNaN(v) || v < 0 || v >= modes.size() ? "?" : dev.strafemc.mode.GameModes.NAMES.get(modes.get((int) v));
		}, () -> {
			double v = dev.strafemc.client.ClientGameplay.setting("strafemc_gamemode");
			var modes = dev.strafemc.mode.GameModes.MODES;
			ConsoleCommands.execute("strafemc_gamemode " + modes.get(Double.isNaN(v) ? 1 : ((int) v + 1) % modes.size()));
		});
		setting("Overtime", "strafemc_overtime", "On", "Off");
		setting("Respawn on spawn blocks", "strafemc_spawnblocks", "On", "Off");
		row("C4 keypad", () -> onOff(dev.strafemc.client.ClientGameplay.c4Keypad()),
				() -> ConsoleCommands.execute("strafemc_c4_keypad " + (dev.strafemc.client.ClientGameplay.c4Keypad() ? 0 : 1)));
		setting("Hits", "strafemc_knockback", "Knock back", "Slow down");
		row("Step up slabs", () -> onOff(dev.strafemc.client.ClientGameplay.slabStep()), () -> ConsoleCommands.execute("strafemc_slabstep"));
		row("Players pick movement", () -> onOff(dev.strafemc.client.ClientGameplay.movementChoice()),
				() -> ConsoleCommands.execute("strafemc_movement_choice " + (dev.strafemc.client.ClientGameplay.movementChoice() ? 0 : 1)));
		setting("Lag compensation", "strafemc_lagcomp", "On", "Off");
		settingNumber("Max lag rewind (ms)", "strafemc_maxunlag");

		header("Bots");
		settingNumber("Bot count", "bot_quota");
		settingNumber("Bot skill (1-10)", "bot_difficulty");
		setting("Bots fight", "strafemc_bot_ffa", "Everyone", "Other team");
		row("Add a bot", () -> "T", () -> ConsoleCommands.quiet("bot_add_t"));
		row("Add a bot ", () -> "CT", () -> ConsoleCommands.quiet("bot_add_ct"));
		row("Kick all bots", () -> "Kick", () -> ConsoleCommands.quiet("bot_kick"));

		header("CS2 server");
		if (ServerLauncher.configured()) // optional host extra
			row("CS2 server", () -> Cs2Bridge.INSTANCE.connected() ? "Stop" : "Start",
					() -> { if (Cs2Bridge.INSTANCE.connected()) ServerLauncher.stop(); else ServerLauncher.start(); });
		cvarToggle("Auto bunnyhop", "sv_autobunnyhopping", false);
		cvarToggle("Bhop speed cap", "sv_enablebunnyhopping", true);
		// sv_legacy_jump 1: CS2's pre-2026 jump (full height every time, no weaker jump right after landing)
		cvarToggle("Pre-2026 jump", "sv_legacy_jump", false);
		cvarToggle("Cheats", "sv_cheats", false);
		row("Noclip", () -> onOff(MovementController.INSTANCE.noclip()), () -> ConsoleCommands.execute("noclip"));
		number("Max velocity", "sv_maxvelocity");
		number("Accelerate", "sv_accelerate");
		number("Air accelerate", "sv_airaccelerate");
		number("Friction", "sv_friction");
		number("Gravity", "sv_gravity");
		number("Max speed", "sv_maxspeed");
		number("Jump impulse", "sv_jump_impulse");
		number("Timescale", "host_timescale");
		row("Movement cvars", () -> "Reset", () -> ConsoleCommands.execute("strafemc_reset_movement"));

		menuEdit = new EditBox(font, 0, 0, 10, ROW_H, Component.literal("value"));
		menuEdit.setMaxLength(32);
		menuEdit.visible = false;
		addRenderableWidget(menuEdit);
		layoutMenu();

		if (!RelayTransport.remote()) NetconTransport.INSTANCE.ensureConnected(); // on someone else's server, commands are relayed
		updateSuggestions();
	}

	private void header(String text) {
		items.add(new MenuItem(text, null, null, null, null, false, true));
	}

	/** A row: its name, and a control showing value (clicking runs action). */
	private void row(String name, Supplier<String> value, Runnable action) {
		Button b = addRenderableWidget(Button.builder(Component.literal(value.get()), x -> action.run()).bounds(0, 0, CTRL_W, ROW_H).build());
		items.add(new MenuItem(name, value, b, null, null, false, false));
	}

	/** Steps a server setting through preset values. */
	private static void cycle(String command, int current, int... presets) {
		int next = presets[0];
		for (int i = 0; i < presets.length; i++) if (presets[i] == current) next = presets[(i + 1) % presets.length];
		ConsoleCommands.execute(command + " " + next);
	}

	/** An on/off server setting (its console command takes 1 or 0), shown with the server's current value. */
	private void setting(String name, String command, String on, String off) {
		row(name, () -> {
			double v = dev.strafemc.client.ClientGameplay.setting(command);
			return Double.isNaN(v) ? "?" : v != 0 ? on : off;
		}, () -> {
			double v = dev.strafemc.client.ClientGameplay.setting(command);
			ConsoleCommands.execute(command + " " + (v != 0 && !Double.isNaN(v) ? 0 : 1));
		});
	}

	/** A numeric server setting: click to type a value. */
	private void settingNumber(String name, String command) {
		Supplier<String> value = () -> {
			double v = dev.strafemc.client.ClientGameplay.setting(command);
			return Double.isNaN(v) ? "?" : String.valueOf((long) v);
		};
		Button b = addRenderableWidget(Button.builder(Component.literal(value.get()), x -> startEdit(command, x, value.get())).bounds(0, 0, CTRL_W, ROW_H).build());
		items.add(new MenuItem(name, value, b, null, command, true, false));
	}

	/** Boolean CS2 cvar with a reset button; inverted = shows the opposite (e.g. "speed cap On" = enablebunnyhopping 0). */
	private void cvarToggle(String name, String cvar, boolean inverted) {
		Supplier<String> value = () -> onOff(cvBool(cvar) != inverted);
		Button b = addRenderableWidget(Button.builder(Component.literal(value.get()),
				x -> ConsoleCommands.quiet(cvar + " " + (cvBool(cvar) ? 0 : 1))).bounds(0, 0, CTRL_W, ROW_H).build());
		b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(cvar)));
		items.add(new MenuItem(name, value, b, resetButton(cvar), cvar, false, false));
	}

	/** Numeric CS2 cvar: click to type a value; the reset button restores the server default. */
	private void number(String name, String cvar) {
		Supplier<String> value = () -> cvStr(cvar);
		Button b = addRenderableWidget(Button.builder(Component.literal(value.get()), x -> startEdit(cvar, x, cvStr(cvar))).bounds(0, 0, CTRL_W, ROW_H).build());
		b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(cvar)));
		items.add(new MenuItem(name, value, b, resetButton(cvar), cvar, true, false));
	}

	private Button resetButton(String cvar) {
		Button r = addRenderableWidget(Button.builder(Component.literal("↺"), x -> {
			String d = Cs2Bridge.INSTANCE.defaults().get(cvar);
			if (d != null) ConsoleCommands.quiet(cvar + " " + d);
			else ConsoleLog.error("No default known for " + cvar + " yet");
		}).bounds(0, 0, ROW_H, ROW_H).build());
		r.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Back to CS2's value")));
		return r;
	}

	private void startEdit(String cvar, Button over, String current) {
		editingCvar = cvar;
		menuEdit.setX(over.getX());
		menuEdit.setY(over.getY());
		menuEdit.setWidth(over.getWidth());
		menuEdit.setValue(current);
		menuEdit.moveCursorToEnd(false);
		menuEdit.visible = true;
		over.visible = false;
		setFocused(menuEdit);
	}

	private void finishEdit(boolean apply) {
		if (editingCvar == null) return;
		String v = menuEdit.getValue().trim();
		if (apply && !v.isEmpty()) {
			if (editingCvar.startsWith("strafemc_") || editingCvar.startsWith("bot_")) ConsoleCommands.execute(editingCvar + " " + v);
			else ConsoleCommands.quiet(editingCvar + " " + v);
		}
		editingCvar = null;
		menuEdit.visible = false;
		setFocused(input);
		layoutMenu();
	}

	private int menuTop() {
		return wy + 18;
	}

	private int menuBottom() {
		return wy + wh - 30; // stop above the input / Submit bar
	}

	private int menuContentHeight() {
		return items.size() * (ROW_H + ROW_GAP);
	}

	private void layoutMenu() {
		int maxScroll = Math.max(0, menuContentHeight() - (menuBottom() - menuTop()));
		menuScroll = Math.max(0, Math.min(menuScroll, maxScroll));
		int x1 = wx + ww - 8, my = menuTop() - menuScroll;
		for (MenuItem it : items) {
			boolean inside = my >= menuTop() && my + ROW_H <= menuBottom();
			if (it.button != null) {
				it.button.setX(it.reset != null ? x1 - ROW_H - 2 - CTRL_W : x1 - CTRL_W);
				it.button.setY(my);
				it.button.setWidth(CTRL_W);
				it.button.visible = menuOpen && inside && !(it.cvar != null && it.cvar.equals(editingCvar));
			}
			if (it.reset != null) {
				it.reset.setX(x1 - ROW_H);
				it.reset.setY(my);
				it.reset.visible = menuOpen && inside;
			}
			my += ROW_H + ROW_GAP;
		}
		if (editingCvar != null && !menuOpen) finishEdit(false);
	}

	private static String capital(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static String onOff(boolean b) {
		return b ? "On" : "Off";
	}

	private static String cvStr(String n) {
		String v = dev.strafemc.client.ClientGameplay.cvar(n);
		return v == null ? "?" : v;
	}

	private static boolean cvBool(String n) {
		String v = dev.strafemc.client.ClientGameplay.cvar(n);
		return "1".equals(v) || "true".equals(v);
	}

	private static double num(String v) {
		try {
			return Double.parseDouble(v);
		} catch (NumberFormatException e) {
			return Double.NaN;
		}
	}

	// ---------------------------------------------------------------- input

	private void submit() {
		String v = input.getValue().trim();
		if (v.isEmpty()) return;
		remember(v);
		historyPos = -1;
		input.setValue("");
		scroll = 0;
		ConsoleCommands.execute(v);
	}

	/** Each log line's wrapped rows at the current width (worked out once per line). */
	private final java.util.Map<ConsoleLog.Line, List<net.minecraft.util.FormattedCharSequence>> wraps = new java.util.IdentityHashMap<>();
	private int wrapWidth;

	private List<net.minecraft.util.FormattedCharSequence> wrapped(ConsoleLog.Line l, int w) {
		if (w != wrapWidth || wraps.size() > 4000) {
			wraps.clear();
			wrapWidth = w;
		}
		return wraps.computeIfAbsent(l, k -> {
			List<net.minecraft.util.FormattedCharSequence> parts = font.split(net.minecraft.network.chat.FormattedText.of(k.text()), Math.max(20, w));
			return parts.isEmpty() ? List.of(net.minecraft.util.FormattedCharSequence.EMPTY) : parts;
		});
	}

	private static List<String> loadHistory() {
		List<String> h = new ArrayList<>();
		try {
			if (java.nio.file.Files.exists(HISTORY_FILE))
				for (String l : java.nio.file.Files.readAllLines(HISTORY_FILE)) if (!l.isBlank()) h.add(l);
		} catch (Exception ignored) {
		}
		return h;
	}

	/** A run command goes to the end of the history (an older copy of it is dropped) and the file is rewritten. */
	private static void remember(String v) {
		HISTORY.remove(v);
		HISTORY.add(v);
		while (HISTORY.size() > HISTORY_MAX) HISTORY.remove(0);
		try { // passwords stay out of the file
			java.nio.file.Files.write(HISTORY_FILE, HISTORY.stream().filter(c -> !c.toLowerCase(Locale.ROOT).contains("password")).toList());
		} catch (Exception ignored) {
		}
	}

	private void updateSuggestions() {
		sel = -1;
		String raw = input == null ? "" : input.getValue().toLowerCase(Locale.ROOT);
		String v = raw.trim();
		String[] parts = raw.split(" ", -1);
		if (parts.length >= 2 && (parts[0].equals("bind") || parts[0].equals("unbind"))) {
			bindSuggestions(parts);
			return;
		}
		if (v.isEmpty() || v.contains(" ")) {
			suggestions = List.of();
			return;
		}
		List<String[]> pre = new ArrayList<>(), mid = new ArrayList<>();
		for (var l : ConsoleCommands.LOCAL.values()) add(pre, mid, v, l.name(), "", l.help());
		for (var a : ConsoleCommands.ALIASES.entrySet()) add(pre, mid, v, a.getKey(), "", "alias for " + a.getValue());
		for (Map.Entry<String, String[]> e : NetconTransport.INSTANCE.known.entrySet())
			add(pre, mid, v, e.getKey(), e.getValue()[0], e.getValue()[2]);
		pre.addAll(mid);
		int room = Math.max(1, (height - (wy + wh + 4)) / 11);
		int max = Math.min(10, room);
		suggestions = pre.size() > max ? pre.subList(0, max) : pre;
	}

	/** "bind <key> <command>" / "unbind <key>": complete key names, then commands. Entries carry the full replacement text. */
	private void bindSuggestions(String[] parts) {
		List<String[]> pre = new ArrayList<>(), mid = new ArrayList<>();
		if (parts.length == 2) {
			String q = parts[1];
			for (String k : Binds.keyNames()) {
				String cur = Binds.get(k);
				add(pre, mid, q, k, cur == null ? "" : "= " + cur, "");
			}
			for (String[] e : pre) e[3] = parts[0] + " " + e[0];
			for (String[] e : mid) e[3] = parts[0] + " " + e[0];
		} else if (parts[0].equals("bind") && parts.length >= 3) {
			String q = String.join(" ", java.util.Arrays.copyOfRange(parts, 2, parts.length)).replace("\"", "");
			if (q.contains(" ")) {
				suggestions = List.of();
				return;
			}
			add(pre, mid, q, "+jump", "", "jump while held (mwheel: one jump per notch)");
			add(pre, mid, q, "+duck", "", "crouch while held");
			add(pre, mid, q, "+speed", "", "walk while held");
			for (var l : ConsoleCommands.LOCAL.values()) add(pre, mid, q, l.name(), "", l.help());
			for (Map.Entry<String, String[]> e : NetconTransport.INSTANCE.known.entrySet()) add(pre, mid, q, e.getKey(), e.getValue()[0], e.getValue()[2]);
			for (String[] e : pre) e[3] = "bind " + parts[1] + " " + e[0];
			for (String[] e : mid) e[3] = "bind " + parts[1] + " " + e[0];
		}
		pre.addAll(mid);
		int room = Math.max(1, (height - (wy + wh + 4)) / 11);
		int max = Math.min(10, room);
		suggestions = pre.size() > max ? pre.subList(0, max) : pre;
	}

	private static void add(List<String[]> pre, List<String[]> mid, String q, String name, String value, String help) {
		String n = name.toLowerCase(Locale.ROOT);
		if (n.startsWith(q)) pre.add(new String[] {name, value, help, null});
		else if (n.contains(q) && mid.size() < 40) mid.add(new String[] {name, value, help, null});
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		int k = e.key();
		if (editingCvar != null && menuEdit.isFocused()) {
			if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) {
				finishEdit(true);
				return true;
			}
			if (k == GLFW.GLFW_KEY_ESCAPE) {
				finishEdit(false);
				return true;
			}
			return super.keyPressed(e);
		}
		if (k == GLFW.GLFW_KEY_ESCAPE || k == KeyMappingHelper.getBoundKeyOf(ConsoleKeys.CONSOLE).getValue()) {
			onClose();
			return true;
		}
		if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) {
			if (sel >= 0 && sel < suggestions.size()) complete();
			else submit();
			return true;
		}
		if (k == GLFW.GLFW_KEY_TAB) {
			if (!suggestions.isEmpty()) {
				if (sel < 0) sel = 0;
				complete();
			}
			return true;
		}
		if (k == GLFW.GLFW_KEY_UP || k == GLFW.GLFW_KEY_DOWN) {
			int d = k == GLFW.GLFW_KEY_UP ? -1 : 1;
			if (!suggestions.isEmpty() && historyPos < 0) {
				sel = Math.floorMod(sel + d + (sel < 0 && d < 0 ? 1 : 0), suggestions.size());
			} else if (!HISTORY.isEmpty()) {
				historyPos = historyPos < 0 ? HISTORY.size() - 1 : Math.max(0, Math.min(HISTORY.size() - 1, historyPos + d));
				input.setValue(HISTORY.get(historyPos));
				suggestions = List.of();
			}
			return true;
		}
		if (k == GLFW.GLFW_KEY_PAGE_UP) {
			scroll += 10;
			return true;
		}
		if (k == GLFW.GLFW_KEY_PAGE_DOWN) {
			scroll = Math.max(0, scroll - 10);
			return true;
		}
		return super.keyPressed(e);
	}

	private void complete() {
		String[] s = suggestions.get(sel);
		input.setValue((s.length > 3 && s[3] != null ? s[3] : s[0]) + " ");
		input.moveCursorToEnd(false);
		suggestions = List.of();
		sel = -1;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (menuOpen && mx >= wx + ww - menuW - 6 && mx <= wx + ww && my >= menuTop() && my <= menuBottom()) {
			menuScroll -= (int) Math.signum(sy) * (ROW_H + ROW_GAP);
			layoutMenu();
			return true;
		}
		scroll = Math.max(0, scroll + (int) Math.signum(sy) * 3);
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
		// click a suggestion
		int sy = wy + wh + 2;
		for (int i = 0; i < suggestions.size(); i++) {
			if (e.y() >= sy + i * 11 && e.y() < sy + (i + 1) * 11 && e.x() >= wx && e.x() <= wx + ww) {
				sel = i;
				complete();
				return true;
			}
		}
		return super.mouseClicked(e, dbl);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ---------------------------------------------------------------- render

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		// no blur/dim: the game stays visible behind the console, like Source
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		for (MenuItem it : items) if (it.button != null) it.button.setMessage(Component.literal(it.value.get()));

		g.fill(wx - 1, wy - 1, wx + ww + 1, wy + wh + 1, BORDER);
		g.fill(wx, wy, wx + ww, wy + wh, BG);
		g.fill(wx, wy, wx + ww, wy + 16, TITLE);
		g.text(font, "Console", wx + 6, wy + 4, 0xFFFFFFFF, false);
		boolean remote = RelayTransport.remote();
		boolean live = remote ? dev.strafemc.client.ClientGameplay.cs2Online() : NetconTransport.INSTANCE.connected();
		// the world's strafeMC bots (mannequins named "BOT ..." in sight of this client)
		int bots = 0;
		var lvl = net.minecraft.client.Minecraft.getInstance().level;
		if (lvl != null)
			for (var en : lvl.entitiesForRendering())
				if (en instanceof net.minecraft.world.entity.decoration.Mannequin && en.getCustomName() != null && en.getCustomName().getString().startsWith("BOT ")) bots++;
		String status = live ? "§aCS2 server connected" + (bots >= 0 ? "§7 | " + bots + (bots == 1 ? " bot" : " bots") : "")
				: "§cCS2 server offline";
		g.text(font, status, wx + ww - 26 - font.width(status), wy + 4, 0xFFFFFFFF, false);

		int lx0 = wx + 6, ly0 = wy + 20, lx1 = (menuOpen ? wx + ww - menuW - 10 : wx + ww - 6), ly1 = wy + wh - 28;
		g.fill(lx0 - 2, ly0 - 2, lx1 + 2, ly1 + 2, LOG_BG);
		List<ConsoleLog.Line> lines = ConsoleLog.snapshot();
		int lineH = 10, rows = Math.max(1, (ly1 - ly0) / lineH);
		// long lines wrap: rows are gathered from the newest line back, only as many as the view and the scroll need
		List<net.minecraft.util.FormattedCharSequence> shown = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		boolean all = true;
		for (int i = lines.size() - 1; i >= 0; i--) {
			if (shown.size() >= rows + scroll) {
				all = false;
				break;
			}
			ConsoleLog.Line l = lines.get(i);
			List<net.minecraft.util.FormattedCharSequence> parts = wrapped(l, lx1 - lx0);
			for (int k = parts.size() - 1; k >= 0; k--) {
				shown.add(parts.get(k));
				colors.add(l.color());
			}
		}
		if (all) scroll = Math.min(scroll, Math.max(0, shown.size() - rows));
		g.enableScissor(lx0, ly0, lx1, ly1);
		int y = ly0 + (Math.min(rows, shown.size() - scroll) - 1) * lineH; // top-aligned until the view is full
		for (int r = scroll; r < Math.min(shown.size(), scroll + rows); r++) {
			g.text(font, shown.get(r), lx0, y, colors.get(r), false);
			y -= lineH;
		}
		g.disableScissor();
		if (scroll > 0) g.text(font, "↑ " + scroll + " more (PgDn)", lx1 - 100, ly1 - 9, 0xFFFFC864, false);

		if (menuOpen) {
			int px0 = wx + ww - menuW - 6, px1 = wx + ww - 2;
			g.fill(px0, menuTop() - 2, px1, menuBottom() + 2, MENU_BG);
			g.fill(px0, menuTop() - 2, px0 + 1, menuBottom() + 2, MENU_LINE);
			// names and section headers, like the strafeMC settings rows
			g.enableScissor(px0, menuTop(), px1, menuBottom());
			int ry = menuTop() - menuScroll;
			for (MenuItem it : items) {
				if (ry + ROW_H >= menuTop() && ry <= menuBottom()) {
					if (it.header) {
						g.text(font, it.name.toUpperCase(), px0 + 6, ry + 6, ACCENT, false);
						g.fill(px0 + 6, ry + ROW_H - 2, px1 - 6, ry + ROW_H - 1, MENU_LINE);
					} else {
						int room = (it.button != null ? it.button.getX() : px1) - px0 - 14;
						g.text(font, font.plainSubstrByWidth(it.name, room), px0 + 8, ry + 5, 0xFFFFFFFF, false);
						g.fill(px0 + 6, ry + ROW_H + ROW_GAP / 2, px1 - 6, ry + ROW_H + ROW_GAP / 2 + 1, 0x14FFFFFF);
					}
				}
				ry += ROW_H + ROW_GAP;
			}
			g.disableScissor();
			int content = menuContentHeight(), view = menuBottom() - menuTop();
			if (content > view) { // scrollbar
				int barH = Math.max(12, view * view / content), barY = menuTop() + (view - barH) * menuScroll / Math.max(1, content - view);
				g.fill(wx + ww - 4, barY, wx + ww - 2, barY + barH, 0xFF8A8A8A);
			}
		}

		super.extractRenderState(g, mouseX, mouseY, partial);

		// suggestions below the window
		if (!suggestions.isEmpty()) {
			int sy = wy + wh + 2, w = 0;
			for (String[] s : suggestions) w = Math.max(w, font.width(row(s)));
			w = Math.min(w + 8, ww);
			g.fill(wx, sy - 1, wx + w, sy + suggestions.size() * 11 + 1, 0xF0181818);
			for (int i = 0; i < suggestions.size(); i++) {
				if (i == sel) g.fill(wx, sy + i * 11, wx + w, sy + (i + 1) * 11, SEL);
				g.text(font, row(suggestions.get(i)), wx + 4, sy + i * 11 + 2, 0xFFE0E0E0, false);
			}
		}
	}

	private static String row(String[] s) {
		String v = s[1].isEmpty() || s[1].equals("cmd") ? "" : " §7" + s[1];
		String h = s[2].isEmpty() ? "" : "  §8" + (s[2].length() > 70 ? s[2].substring(0, 70) + "..." : s[2]);
		return s[0] + v + h;
	}
}
