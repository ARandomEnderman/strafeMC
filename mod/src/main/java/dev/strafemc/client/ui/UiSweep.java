package dev.strafemc.client.ui;

import dev.strafemc.client.console.ConsoleLog;
import dev.strafemc.config.StrafeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Developer check (console: ui_sweep): opens every strafeMC menu at each GUI scale and a few window sizes and saves a
 * screenshot of each into <runtime>/ui-sweep/<time>/, named <menu>_<gui width>x<gui height>.png. Pictures of the
 * same menu at the same GUI size are taken once. The window size and GUI scale are put back at the end.
 */
public final class UiSweep {
	/** Window sizes tried besides the current one (skipped in fullscreen). */
	private static final int[][] WINDOWS = {{854, 480}, {1280, 720}, {1920, 1080}};
	/** Ticks a menu is shown before its picture (pictures and icons load in the background). */
	private static final int SETTLE = 8;

	private record Step(int windowW, int windowH, int scale, String name, Supplier<Screen> screen) {}

	private static List<Step> steps;
	private static int at, wait, origW, origH, origScale, shots, resizeTries;
	private static Path dir;
	private static final Set<String> TAKEN = new HashSet<>();

	private UiSweep() {}

	public static boolean running() {
		return steps != null;
	}

	public static void start() {
		Minecraft mc = Minecraft.getInstance();
		if (running()) {
			ConsoleLog.error("ui_sweep: already running (ui_sweep stop)");
			return;
		}
		var w = mc.getWindow();
		origW = w.getScreenWidth();
		origH = w.getScreenHeight();
		origScale = mc.options.guiScale().get();
		List<int[]> windows = new ArrayList<>();
		windows.add(new int[] {origW, origH});
		if (!w.isFullscreen()) for (int[] s : WINDOWS) windows.add(s);
		Map<String, Supplier<Screen>> menus = menus();
		String only = System.getProperty("strafemc.uiSweepOnly"); // e.g. console_menu: just those menus
		if (only != null) menus.keySet().retainAll(java.util.List.of(only.split(",")));
		steps = new ArrayList<>();
		for (int[] win : windows)
			for (int scale = 1; scale <= 6; scale++)
				for (var e : menus.entrySet()) steps.add(new Step(win[0], win[1], scale, e.getKey(), e.getValue()));
		dir = StrafeConfig.RUNTIME_DIR.resolve("ui-sweep").resolve(String.valueOf(System.currentTimeMillis() / 1000));
		try {
			Files.createDirectories(dir);
		} catch (Exception e) {
			ConsoleLog.error("ui_sweep: " + e);
			steps = null;
			return;
		}
		TAKEN.clear();
		at = -1;
		wait = 0;
		shots = 0;
		ConsoleLog.info("ui_sweep: " + menus.size() + " menus, " + windows.size() + " window sizes, GUI scales 1-6 -> " + dir);
	}

	public static void stop() {
		if (!running()) return;
		Minecraft mc = Minecraft.getInstance();
		steps = null;
		mc.setScreen(null);
		if (!mc.getWindow().isFullscreen()) GLFW.glfwSetWindowSize(mc.getWindow().handle(), origW, origH);
		mc.options.guiScale().set(origScale);
		mc.resizeGui();
		ConsoleLog.info("ui_sweep: " + shots + " pictures in " + dir);
		dev.strafemc.StrafeMc.LOG.info("[strafemc] ui_sweep: {} pictures in {}", shots, dir);
	}

	/** -Dstrafemc.uiSweep=1 (dev runs): open a flat test world, sweep, quit. */
	private static final boolean BENCH = System.getProperty("strafemc.perfBench") != null;
	private static final boolean AUTO = System.getProperty("strafemc.uiSweep") != null || BENCH;
	private static final String AUTO_WORLD = "strafemc_ui_sweep";
	private static int autoPhase, autoTicks;

	private static void auto(Minecraft mc) {
		if (BENCH && autoPhase >= 1) { // the bench window may be in the background: no pause menu in the way
			mc.options.pauseOnLostFocus = false;
			if (mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen) mc.setScreen(null);
		}
		if (autoPhase == 0 && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
			autoPhase = 1;
			if (mc.getLevelSource().levelExists(AUTO_WORLD)) mc.createWorldOpenFlows().openWorld(AUTO_WORLD, () -> {});
			else mc.createWorldOpenFlows().createFreshLevel(AUTO_WORLD,
					new net.minecraft.world.level.LevelSettings(AUTO_WORLD, net.minecraft.world.level.GameType.CREATIVE,
							net.minecraft.world.level.LevelSettings.DifficultySettings.DEFAULT, true, net.minecraft.world.level.WorldDataConfiguration.DEFAULT),
					new net.minecraft.world.level.levelgen.WorldOptions(1L, false, false),
					reg -> reg.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
							.getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.FLAT).value().createWorldDimensions(),
					mc.screen);
		} else if (autoPhase == 1 && mc.player != null && mc.level != null && mc.screen == null && ++autoTicks > 200) {
			autoPhase = 2;
			fill(mc);
		} else if (autoPhase == 2 && ++autoTicks > 260) {
			autoPhase = 4;
			if (BENCH) dev.strafemc.client.perf.PerfBench.startAndQuit();
			else start();
		} else if (autoPhase == 4 && !BENCH && !running()) {
			autoPhase = 3;
			mc.stop();
		}
	}

	/** Inventory slots of the sample tools (fill) that open their menus. */
	private static final int STICKER_SLOT = 0, NAME_TAG_SLOT = 1, BULK_SLOT = 2, SWAP_SLOT = 3;

	/** Sample items for the test player, so the menus show full lists: tools, a case with keys, wraps, stickers, charms. */
	private static void fill(Minecraft mc) {
		var server = mc.getSingleplayerServer();
		if (server == null || mc.player == null) return;
		java.util.UUID id = mc.player.getUUID();
		server.execute(() -> {
			try {
				var p = server.getPlayerList().getPlayer(id);
				if (p == null) return;
				var inv = p.getInventory();
				inv.clearContent();
				inv.setItem(STICKER_SLOT, dev.strafemc.weapon.StickerItem.stack(dev.strafemc.weapon.WeaponItems.STICKER, dev.strafemc.weapon.Stickers.all().get(0).id()));
				inv.setItem(NAME_TAG_SLOT, new net.minecraft.world.item.ItemStack(dev.strafemc.weapon.WeaponItems.NAME_TAG));
				inv.setItem(BULK_SLOT, new net.minecraft.world.item.ItemStack(dev.strafemc.weapon.WeaponItems.BULK_OPENER));
				inv.setItem(SWAP_SLOT, new net.minecraft.world.item.ItemStack(dev.strafemc.weapon.WeaponItems.STATTRAK_SWAP));
				var cases = dev.strafemc.weapon.Cases.all();
				java.util.Random rng = new java.util.Random(1);
				int slot = 4;
				for (int k = 0; k < 2 && k < cases.size(); k++) {
					var c = cases.get(k);
					var cs = dev.strafemc.weapon.CaseItem.stack(dev.strafemc.weapon.WeaponItems.WEAPON_CASE, c.id());
					cs.setCount(5);
					inv.setItem(slot++, cs);
					if (c.key() != null) {
						var key = dev.strafemc.weapon.KeyItem.stack(dev.strafemc.weapon.WeaponItems.CASE_KEY, c.key());
						key.setCount(5);
						inv.setItem(slot++, key);
					}
				}
				inv.setItem(slot++, dev.strafemc.weapon.CharmItem.stack(dev.strafemc.weapon.WeaponItems.CHARM, dev.strafemc.weapon.Charms.all().get(0).id()));
				// the rest: skins from the cases (wraps, knives, gloves) with random patterns and floats
				List<dev.strafemc.weapon.Cases.Entry> entries = new ArrayList<>();
				for (var c : cases) {
					for (var list : c.guns().values()) entries.addAll(list);
					entries.addAll(c.special());
				}
				for (int i = 9; i < 36 && !entries.isEmpty(); i++) {
					var st = dev.strafemc.weapon.Cases.stack(entries.get(rng.nextInt(entries.size())), rng.nextInt(1000), rng.nextFloat());
					if (!st.isEmpty()) inv.setItem(i, st);
				}
			} catch (Exception e) {
				dev.strafemc.StrafeMc.LOG.warn("[strafemc] ui_sweep sample items: {}", e.toString());
			}
		});
	}

	/** Client tick: show the next menu, then take its picture once it has settled. */
	public static void tick(Minecraft mc) {
		if (AUTO) auto(mc);
		if (!running()) return;
		if (wait > 0) {
			wait--;
			return;
		}
		if (at >= 0 && at < steps.size()) shoot(mc, steps.get(at));
		while (++at < steps.size()) {
			Step s = steps.get(at);
			var w = mc.getWindow();
			if (!w.isFullscreen() && (w.getScreenWidth() != s.windowW || w.getScreenHeight() != s.windowH)) {
				GLFW.glfwSetWindowSize(w.handle(), s.windowW, s.windowH);
				at--; // the same step again once the window has its new size
				wait = 10;
				if (++resizeTries > 3) { // the desktop can't fit it: leave this size out
					resizeTries = 0;
					int ww = s.windowW, wh = s.windowH;
					steps.removeIf(x -> x.windowW == ww && x.windowH == wh);
					at = Math.min(at, steps.size() - 1);
				}
				return;
			}
			resizeTries = 0;
			if (s.scale > w.calculateScale(0, mc.isEnforceUnicode())) continue; // bigger than auto: not offered
			if (mc.options.guiScale().get() != s.scale) {
				mc.options.guiScale().set(s.scale);
				mc.resizeGui();
			}
			Screen screen;
			try {
				screen = s.screen.get();
			} catch (Throwable t) {
				ConsoleLog.error("ui_sweep: " + s.name + ": " + t);
				dev.strafemc.StrafeMc.LOG.warn("[strafemc] ui_sweep {}: {}", s.name, t.toString());
				continue;
			}
			if (screen != null) mc.setScreen(screen); // null: the server opens it (container menus)
			String key = s.name + "_" + w.getGuiScaledWidth() + "x" + w.getGuiScaledHeight();
			if (!TAKEN.add(key)) continue; // this menu at this GUI size is already in
			wait = SETTLE;
			return;
		}
		stop();
	}

	private static void shoot(Minecraft mc, Step s) {
		var w = mc.getWindow();
		String name = s.name + "_" + w.getGuiScaledWidth() + "x" + w.getGuiScaledHeight() + ".png";
		Path out = dir.resolve(name);
		shots++;
		Screenshot.takeScreenshot(mc.getMainRenderTarget(), img -> {
			try (img) {
				img.writeToFile(out);
			} catch (Exception e) {
				ConsoleLog.error("ui_sweep: " + name + ": " + e);
			}
		});
	}

	/** Every strafeMC menu that can be shown without the server sending one. */
	private static Map<String, Supplier<Screen>> menus() {
		Minecraft mc = Minecraft.getInstance();
		Map<String, Supplier<Screen>> m = new LinkedHashMap<>();
		m.put("settings", () -> new dev.strafemc.client.StrafeSettingsScreen(null));
		for (int t = 1; t < 6; t++) {
			int tab = t;
			m.put("settings" + t, () -> new dev.strafemc.client.StrafeSettingsScreen(null, tab));
		}
		m.put("console", dev.strafemc.client.console.ConsoleScreen::new);
		m.put("console_menu", () -> {
			dev.strafemc.client.console.ConsoleScreen.showMenu();
			return new dev.strafemc.client.console.ConsoleScreen();
		});
		m.put("presets", () -> new dev.strafemc.client.console.PresetScreen(null));
		m.put("agents", () -> new dev.strafemc.client.AgentScreen(null));
		m.put("hosting_info", () -> new dev.strafemc.client.HostingInfoScreen(null));
		m.put("host_consent", dev.strafemc.client.HostConsentScreen::new);
		m.put("setup", () -> new dev.strafemc.client.SetupScreen(dev.strafemc.setup.Setup.status(StrafeConfig.cacheDir()), null));
		if (mc.player != null) {
			m.put("quick_menu", () -> new dev.strafemc.client.QuickMenuScreen(false));
			m.put("spray_wheel", dev.strafemc.client.SprayWheelScreen::new);
			m.put("buy_menu", () -> make(dev.strafemc.client.BuyMenuScreen.class));
			m.put("shop", () -> new dev.strafemc.client.ShopScreen(null));
			m.put("trade_up", () -> new dev.strafemc.client.TradeUpScreen(null));
			m.put("upgrader", () -> new dev.strafemc.client.UpgraderScreen(null));
			m.put("care_package", () -> new dev.strafemc.client.CarePackageScreen(null));
			m.put("loadout", () -> {
				mc.setScreen(null);
				dev.strafemc.client.LoadoutKey.open();
				return null;
			});
			m.put("c4_keypad", dev.strafemc.client.C4KeypadScreen::plant);
			m.put("bulk_unboxer", () -> make(dev.strafemc.client.BulkOpenScreen.class, BULK_SLOT));
			m.put("stattrak_swap", () -> make(dev.strafemc.client.StatTrakSwapScreen.class, SWAP_SLOT));
			m.put("name_tag", () -> make(dev.strafemc.client.NameTagScreen.class, NAME_TAG_SLOT));
			m.put("stickers", () -> make(dev.strafemc.client.StickerScreen.class, STICKER_SLOT));
			var cases = dev.strafemc.weapon.Cases.all();
			if (!cases.isEmpty()) {
				m.put("case", () -> make(dev.strafemc.client.CaseScreen.class, cases.get(0)));
				m.put("case_bulk", () -> make(dev.strafemc.client.CaseScreen.class, cases.get(0), true));
			}
		}
		return m;
	}

	/** A menu through its private constructor (the ones normally opened from an item in a given slot). */
	private static Screen make(Class<? extends Screen> c, Object... args) {
		for (Constructor<?> k : c.getDeclaredConstructors()) {
			if (k.getParameterCount() != args.length) continue;
			try {
				k.setAccessible(true);
				return (Screen) k.newInstance(args);
			} catch (IllegalArgumentException wrongTypes) {
				// another constructor with as many parameters
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		throw new IllegalArgumentException("no constructor for " + c.getSimpleName());
	}
}
