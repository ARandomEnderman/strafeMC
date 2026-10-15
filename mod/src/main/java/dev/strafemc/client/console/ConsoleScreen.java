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
	private static final List<String> HISTORY = new ArrayList<>();
	private static boolean menuOpen;

	private static final int BG = 0xE0202020, TITLE = 0xF0303030, BORDER = 0xFF5A5A5A, LOG_BG = 0xC0101010, SEL = 0xFF3C5A8C;

	private EditBox input;
	private Button submit, gear;
	private final List<MenuItem> items = new ArrayList<>();
	private int historyPos = -1, scroll, sel = -1;
	private int wx, wy, ww, wh;
	private List<String[]> suggestions = List.of();

	/** One quick-menu row: main button, optional reset-to-default button, the cvar it edits (numbers open a text field). */
	private record MenuItem(Button button, Supplier<String> label, Button reset, String cvar, boolean numeric) {}

	private static final int ROW_H = 20, ROW_GAP = 2;
	/** Quick-menu width: 184 GUI px, shrunk on small screens / large GUI scales so the log keeps at least half the window. */
	private int menuW = 184;
	private int menuScroll;
	private EditBox menuEdit;
	private String editingCvar;

	public ConsoleScreen() {
		super(Component.literal("Console"));
	}

	@Override
	protected void init() {
		ww = (int) (width * 0.84);
		wh = (int) (height * 0.72);
		wx = (width - ww) / 2;
		wy = (int) (height * 0.05);
		menuW = Math.max(110, Math.min(184, ww / 2 - 10));

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
		header("Personal");
		toggle(() -> "Team: " + ("t".equals(StrafeConfig.team) ? "T" : "CT"), () -> ConsoleCommands.execute("strafemc_team"));
		toggle(() -> "Scroll wheel: " + (StrafeConfig.scrollJump ? "Jump" : "Hotbar"), () -> ConsoleCommands.execute("cl_scrolljump"));
		toggle(() -> "cl_showpos: " + onOff(StrafeConfig.showSpeed), () -> ConsoleCommands.execute("cl_showpos"));
		toggle(() -> "Double-tap fly: " + onOff(StrafeConfig.doubleTapFly), () -> ConsoleCommands.execute("cl_doubletapfly"));
		toggle(() -> "Source movement: " + onOff(dev.strafemc.client.ClientGameplay.sourceMovement()), () -> ConsoleCommands.execute("strafemc_movement"));
		toggle(() -> "Server log: " + onOff(StrafeConfig.showServerLog), () -> ConsoleCommands.execute("strafemc_serverlog"));
		toggle(() -> "Jump stats: " + onOff(StrafeConfig.jumpStats), () -> ConsoleCommands.execute("cl_jumpstats"));
		toggle(() -> "Speed graph: " + onOff(StrafeConfig.velGraph), () -> ConsoleCommands.execute("cl_velgraph"));
		toggle(() -> "Speed meter: " + onOff(StrafeConfig.showSpeedMeter), () -> ConsoleCommands.execute("cl_showspeed"));
		header("Presets");
		toggle(() -> "Presets...", () -> minecraft.setScreen(new PresetScreen(this)));
		header("CS2 server");
		if (ServerLauncher.configured()) // optional host extra
			toggle(() -> Cs2Bridge.INSTANCE.connected() ? "§cStop CS2 server" : "§aStart CS2 server",
					() -> { if (Cs2Bridge.INSTANCE.connected()) ServerLauncher.stop(); else ServerLauncher.start(); });
		cvarToggle("Auto bunnyhop", "sv_autobunnyhopping", false);
		cvarToggle("Bhop speed cap", "sv_enablebunnyhopping", true);
		// sv_legacy_jump 1: CS2's pre-2026 jump (full height every time, no weaker jump right after landing)
		cvarToggle("Pre-2026 jump", "sv_legacy_jump", false);
		// sv_stepsize 18 (CS2) vs 20: a slab is 20 units at 40 units per block, so 20 lets you walk up slabs
		toggle(() -> "Step up slabs: " + onOff(dev.strafemc.client.ClientGameplay.slabStep()), () -> ConsoleCommands.execute("strafemc_slabstep"));
		toggle(() -> "Loadout on death: " + (dev.strafemc.client.ClientGameplay.loadoutKeep() ? "kept" : "dropped"), () -> ConsoleCommands.execute("strafemc_loadout_keep"));
		toggle(() -> "Buy menu: " + dev.strafemc.weapon.BuyMenu.modeName(dev.strafemc.client.ClientGameplay.buyMode()), () -> ConsoleCommands.execute("strafemc_buymenu"));
		// buy time presets (strafemc_buytime takes any number of seconds)
		toggle(() -> "Buy time: " + (dev.strafemc.client.ClientGameplay.buyTime() == 0 ? "no limit" : dev.strafemc.client.ClientGameplay.buyTime() + " s"), () -> {
			int[] presets = {0, 10, 20, 45, 90};
			int cur = dev.strafemc.client.ClientGameplay.buyTime(), next = presets[0];
			for (int i = 0; i < presets.length; i++) if (presets[i] == cur) next = presets[(i + 1) % presets.length];
			ConsoleCommands.execute("strafemc_buytime " + next);
		});
		number("Max velocity", "sv_maxvelocity");
		number("Accelerate", "sv_accelerate");
		number("Friction", "sv_friction");
		number("Gravity", "sv_gravity");
		number("Max speed", "sv_maxspeed");
		cvarToggle("sv_cheats", "sv_cheats", false);
		toggle(() -> "Noclip: " + onOff(MovementController.INSTANCE.noclip()), () -> ConsoleCommands.execute("noclip"));
		number("Timescale", "host_timescale");
		toggle(() -> "Add bot", () -> ConsoleCommands.quiet("bot_add"));
		toggle(() -> "Kick all bots", () -> ConsoleCommands.quiet("bot_kick"));
		toggle(() -> "Reset all movement cvars", () -> ConsoleCommands.execute("strafemc_reset_movement"));

		menuEdit = new EditBox(font, 0, 0, 10, ROW_H, Component.literal("value"));
		menuEdit.setMaxLength(32);
		menuEdit.visible = false;
		addRenderableWidget(menuEdit);
		layoutMenu();

		if (!RelayTransport.remote()) NetconTransport.INSTANCE.ensureConnected(); // on someone else's server, commands are relayed
		updateSuggestions();
	}

	private void header(String text) {
		Button b = addRenderableWidget(Button.builder(Component.literal(text), x -> {}).bounds(0, 0, 10, ROW_H).build());
		b.active = false;
		items.add(new MenuItem(b, () -> "§e" + text, null, null, false));
	}

	private void toggle(Supplier<String> label, Runnable action) {
		Button b = addRenderableWidget(Button.builder(Component.literal(label.get()), x -> action.run()).bounds(0, 0, 10, ROW_H).build());
		items.add(new MenuItem(b, label, null, null, false));
	}

	/** Boolean CS2 cvar with a reset button; inverted = label shows the opposite (e.g. "speed cap ON" = enablebunnyhopping 0). */
	private void cvarToggle(String name, String cvar, boolean inverted) {
		Supplier<String> label = () -> name + ": " + onOff(cvBool(cvar) != inverted);
		Button b = addRenderableWidget(Button.builder(Component.literal(label.get()),
				x -> ConsoleCommands.quiet(cvar + " " + (cvBool(cvar) ? 0 : 1))).bounds(0, 0, 10, ROW_H).build());
		items.add(new MenuItem(b, label, resetButton(cvar), cvar, false));
	}

	/** Numeric CS2 cvar: click to type a value; the reset button restores the server default. */
	private void number(String name, String cvar) {
		Supplier<String> label = () -> name + ": §b" + cvStr(cvar);
		Button b = addRenderableWidget(Button.builder(Component.literal(label.get()), x -> startEdit(cvar, x)).bounds(0, 0, 10, ROW_H).build());
		b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Click to type a value (" + cvar + ")")));
		items.add(new MenuItem(b, label, resetButton(cvar), cvar, true));
	}

	private Button resetButton(String cvar) {
		Button r = addRenderableWidget(Button.builder(Component.literal("↺"), x -> {
			String d = Cs2Bridge.INSTANCE.defaults().get(cvar);
			if (d != null) ConsoleCommands.quiet(cvar + " " + d);
			else ConsoleLog.error("No default known for " + cvar + " yet");
		}).bounds(0, 0, ROW_H, ROW_H).build());
		r.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Reset " + cvar + " to the server default")));
		return r;
	}

	private void startEdit(String cvar, Button over) {
		editingCvar = cvar;
		menuEdit.setX(over.getX());
		menuEdit.setY(over.getY());
		menuEdit.setWidth(over.getWidth());
		menuEdit.setValue(cvStr(cvar));
		menuEdit.moveCursorToEnd(false);
		menuEdit.visible = true;
		over.visible = false;
		setFocused(menuEdit);
	}

	private void finishEdit(boolean apply) {
		if (editingCvar == null) return;
		String v = menuEdit.getValue().trim();
		if (apply && !v.isEmpty()) ConsoleCommands.quiet(editingCvar + " " + v);
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
		int mx = wx + ww - menuW - 4, my = menuTop() - menuScroll;
		for (MenuItem it : items) {
			boolean inside = my >= menuTop() && my + ROW_H <= menuBottom();
			Button b = it.button;
			b.setX(mx);
			b.setY(my);
			b.setWidth(it.reset != null ? menuW - ROW_H - 2 : menuW);
			b.visible = menuOpen && inside && !(it.cvar != null && it.cvar.equals(editingCvar));
			if (it.reset != null) {
				it.reset.setX(mx + menuW - ROW_H);
				it.reset.setY(my);
				it.reset.visible = menuOpen && inside;
			}
			my += ROW_H + ROW_GAP;
		}
		if (editingCvar != null && !menuOpen) finishEdit(false);
	}

	private static String onOff(boolean b) {
		return b ? "§aON" : "§cOFF";
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
		if (HISTORY.isEmpty() || !HISTORY.get(HISTORY.size() - 1).equals(v)) HISTORY.add(v);
		historyPos = -1;
		input.setValue("");
		scroll = 0;
		ConsoleCommands.execute(v);
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
		for (MenuItem it : items) it.button.setMessage(Component.literal(it.label.get()));

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
		scroll = Math.min(scroll, Math.max(0, lines.size() - rows));
		int start = Math.max(0, lines.size() - rows - scroll);
		g.enableScissor(lx0, ly0, lx1, ly1);
		int y = ly0;
		for (int i = start; i < Math.min(lines.size(), start + rows); i++) {
			ConsoleLog.Line l = lines.get(i);
			g.text(font, l.text(), lx0, y, l.color(), false);
			y += lineH;
		}
		g.disableScissor();
		if (scroll > 0) g.text(font, "↑ " + scroll + " more (PgDn)", lx1 - 100, ly1 - 9, 0xFFFFC864, false);

		if (menuOpen) {
			g.fill(wx + ww - menuW - 6, menuTop() - 2, wx + ww - 2, menuBottom() + 2, 0xC0181818);
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
