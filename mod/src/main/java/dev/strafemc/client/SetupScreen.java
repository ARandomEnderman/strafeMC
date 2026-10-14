package dev.strafemc.client;

import dev.strafemc.StrafeMc;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.setup.ConverterDownload;
import dev.strafemc.setup.Cs2Finder;
import dev.strafemc.setup.Setup;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * strafeMC's first-time setup (and its top-ups after mod updates): explains what's needed, then either converts
 * automatically (after showing exactly what will happen) or points to the manual guide. Shown on world join while the
 * setup is missing or incomplete, unless the player chose "don't ask again".
 */
public final class SetupScreen extends Screen {
	public static final String STORE = "https://store.steampowered.com/app/730/";
	public static final String GUIDE = "https://github.com/ARandomEnderman/strafeMC/blob/main/docs/MANUAL_SETUP.md";
	/** Rough size of a full setup (all guns and knives, shrunk textures, effects, sounds); see setup.json for the real files. */
	private static final String SIZE = "about 900 MB";

	private enum Page { INTRO, CONFIRM, RUNNING, DONE, FAILED }

	/** Offered once per game start: on the title screen, or on joining a world if the title screen was skipped. */
	private static boolean offered;
	/** The setup finished in this game session: its files only load on the next start, so keep reminding until then. */
	private static volatile boolean restartNeeded;
	private static boolean reminded;
	private final Setup.Status status;
	private final Screen parent;
	private Page page = Page.INTRO;
	private EditBox folder;
	private String typed = "";
	private Path cs2;
	/** The running setup's progress (shared: the screen can be closed and reopened, and menus / the HUD show it too). */
	private static volatile boolean running;
	private static volatile double fraction;
	private static volatile String step = "";
	private static volatile String runError;
	private volatile String error;

	public SetupScreen(Setup.Status status, Screen parent) {
		super(Component.literal("strafeMC setup"));
		this.status = status;
		this.parent = parent;
		this.cs2 = Cs2Finder.find(StrafeConfig.cs2Path);
	}

	/** Opens the screen over {@code current} (null in a world) when CS2 files are missing or the setup is outdated, once per game start. */
	public static void offer(Screen current) {
		var mc0 = net.minecraft.client.Minecraft.getInstance();
		if (current == null) reminded = false; // joining a world: remind once more
		if (restartNeeded) { // once per world join and once back on the title screen, until the restart
			if (reminded) return;
			reminded = true;
			SetupScreen done = new SetupScreen(Setup.status(StrafeConfig.cacheDir()), current);
			done.page = Page.DONE;
			mc0.execute(() -> mc0.setScreen(done));
			return;
		}
		if (running || offered || StrafeConfig.setupNeverAsk) return;
		offered = true;
		Setup.Status st = Setup.status(StrafeConfig.cacheDir());
		if (!st.needsSetup()) return;
		var mc = net.minecraft.client.Minecraft.getInstance();
		mc.execute(() -> mc.setScreen(new SetupScreen(st, current)));
	}

	/** Opened by hand (Options > strafeMC...): always shows, also when the setup is complete (to redo it). */
	public static void open(Screen parent) {
		var mc = net.minecraft.client.Minecraft.getInstance();
		SetupScreen s = new SetupScreen(Setup.status(StrafeConfig.cacheDir()), parent);
		if (running) s.page = Page.RUNNING; // reopened while it runs: back to the progress bar
		mc.setScreen(s);
	}

	/** The setup's progress while it runs, the restart reminder after it, else null (shown on menus and in game). */
	public static String progressLine() {
		if (running) return String.format("§e[strafeMC] Setting up: %.0f%%§7 (%s)", fraction * 100, step);
		return restartNeeded ? "§e[strafeMC] Setup done: restart Minecraft to load the CS2 files" : AssetLoading.line();
	}

	/** A line at the top of the screen in game until Minecraft is restarted after the setup. */
	public static void registerHud() {
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
				net.minecraft.resources.Identifier.fromNamespaceAndPath("strafemc", "setup_restart"), (g, delta) -> {
					var mc = net.minecraft.client.Minecraft.getInstance();
					if (mc.options.hideGui) return;
					String p = progressLine();
					if (p != null) g.centeredText(mc.font, p, g.guiWidth() / 2, 4, 0xFFFFFFFF);
				});
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	@Override
	protected void init() {
		int cx = width / 2, bottom = height - 32;
		switch (page) {
			case INTRO -> {
				if (cs2 == null)
					addRenderableWidget(Button.builder(Component.literal("Get CS2 on Steam (free)"), b -> Util.getPlatform().openUri(STORE))
							.bounds(cx - 155, bottom - 52, 310, 20).build());
				addRenderableWidget(Button.builder(Component.literal(cs2 == null ? "I have CS2: pick its folder" : "Set up automatically"), b -> toConfirm())
						.bounds(cx - 155, bottom - 26, 150, 20).build());
				addRenderableWidget(Button.builder(Component.literal("Do it myself (guide)"), b -> Util.getPlatform().openUri(GUIDE))
						.bounds(cx + 5, bottom - 26, 150, 20).build());
				addRenderableWidget(Button.builder(Component.literal(cs2 == null ? "Play without it" : "Later"), b -> onClose()).bounds(cx - 155, bottom, 150, 20).build());
				addRenderableWidget(Button.builder(Component.literal("Don't ask again"), b -> {
					StrafeConfig.setupNeverAsk = true;
					save();
					onClose();
				}).bounds(cx + 5, bottom, 150, 20).build());
			}
			case CONFIRM -> {
				folder = new EditBox(font, cx - 155, 74, 236, 20, Component.literal("CS2 folder"));
				folder.setMaxLength(400);
				folder.setValue(cs2 != null ? cs2.toString() : typed);
				folder.setHint(Component.literal("…\\steamapps\\common\\" + Cs2Finder.FOLDER));
				folder.setResponder(v -> {
					typed = v;
					error = null;
				});
				addRenderableWidget(folder);
				addRenderableWidget(Button.builder(Component.literal("Browse…"), b -> browse()).bounds(cx + 85, 74, 70, 20).build());
				if (cs2 == null)
					addRenderableWidget(Button.builder(Component.literal("Get CS2 on Steam (free)"), b -> Util.getPlatform().openUri(STORE))
							.bounds(cx - 155, bottom - 26, 310, 20).build());
				addRenderableWidget(Button.builder(Component.literal("§aStart"), b -> start()).bounds(cx - 155, bottom, 150, 20).build());
				addRenderableWidget(Button.builder(Component.literal("Back"), b -> {
					page = Page.INTRO;
					rebuildWidgets();
				}).bounds(cx + 5, bottom, 150, 20).build());
			}
			case RUNNING -> addRenderableWidget(Button.builder(Component.literal("Keep playing"), b -> onClose())
					.bounds(cx - 75, bottom, 150, 20).build());
			case DONE -> {
				addRenderableWidget(Button.builder(Component.literal("§aQuit Minecraft now"), b -> minecraft.stop()).bounds(cx - 155, bottom, 150, 20).build());
				addRenderableWidget(Button.builder(Component.literal("Later"), b -> onClose()).bounds(cx + 5, bottom, 150, 20).build());
			}
			case FAILED -> addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(cx - 75, bottom, 150, 20).build());
		}
	}

	private void toConfirm() {
		page = Page.CONFIRM;
		rebuildWidgets();
	}

	private boolean converterReady() {
		return !StrafeConfig.vrfPath.isBlank() && Files.isRegularFile(Path.of(StrafeConfig.vrfPath));
	}

	/** A native folder picker (it blocks, so it runs off the render thread); the pick goes through the same normalizing. */
	private void browse() {
		Thread t = new Thread(() -> {
			String picked = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_selectFolderDialog("Choose your Counter-Strike 2 folder",
					folder.getValue().isBlank() ? System.getProperty("user.home") : folder.getValue());
			if (picked == null) return;
			minecraft.execute(() -> {
				Path n = Cs2Finder.normalize(picked);
				folder.setValue(n != null ? n.toString() : picked);
			});
		}, "strafemc-browse");
		t.setDaemon(true);
		t.start();
	}

	private void start() {
		Path chosen = Cs2Finder.normalize(folder.getValue());
		if (chosen == null) {
			error = "No CS2 game files there. In Steam: right-click Counter-Strike 2 → Manage → Browse local files, and pick that folder.";
			return;
		}
		cs2 = chosen;
		error = null;
		page = Page.RUNNING;
		rebuildWidgets();
		running = true;
		fraction = 0;
		step = "";
		Thread t = new Thread(() -> {
			try {
				Path converter = converterReady() ? Path.of(StrafeConfig.vrfPath)
						: ConverterDownload.install(StrafeConfig.RUNTIME_DIR.resolve("tools").resolve("vrf"), (f, s) -> {
							fraction = f * 0.05;
							step = s;
						});
				StrafeConfig.cs2Path = cs2.toString();
				StrafeConfig.vrfPath = converter.toString();
				save();
				new Setup(cs2, converter, StrafeConfig.cacheDir(), (f, s) -> {
					fraction = 0.05 + f * 0.95;
					step = s;
				}).run(!status.done() || !status.needsSetup());
				page = Page.DONE;
				restartNeeded = true;
				reminded = true; // this page is the reminder right now
			} catch (Exception e) {
				StrafeMc.LOG.error("[strafemc] setup failed", e);
				error = e.getMessage();
				runError = error;
				page = Page.FAILED;
			}
			running = false;
			minecraft.execute(() -> {
				rebuildWidgets();
				if (!(minecraft.screen instanceof SetupScreen)) { // closed while it ran: say it in chat and bring the result page up
					minecraft.gui.getChat().addClientSystemMessage(Component.literal(page == Page.DONE
							? "§a[strafeMC] Setup done. Restart Minecraft to load the converted CS2 files."
							: "§c[strafeMC] Setup stopped: " + error));
					SetupScreen result = new SetupScreen(status, minecraft.screen);
					result.page = page;
					result.error = error;
					minecraft.setScreen(result);
				}
			});
		}, "strafemc-setup");
		t.setDaemon(true);
		t.start();
	}

	private static void save() {
		try {
			StrafeConfig.save();
		} catch (Exception e) {
			StrafeMc.LOG.warn("[strafemc] could not save settings: {}", e.toString());
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
		super.extractRenderState(g, mx, my, partial);
		if (page == Page.RUNNING && !running) { // a reopened progress page: the setup finished meanwhile
			page = restartNeeded ? Page.DONE : Page.FAILED;
			if (error == null) error = runError;
			rebuildWidgets();
		}
		int cx = width / 2, x = cx - 155, y = 16;
		g.centeredText(font, "§lstrafeMC setup", cx, y, 0xFFFFFFFF);
		y += 20;
		List<String> lines = new ArrayList<>();
		switch (page) {
			case INTRO -> {
				if (!status.needsSetup())
					lines.add("§aThe setup is complete.§r Run it again to convert everything fresh, for example after a Counter-Strike 2 update.");
				else if (status.done())
					lines.add("This version of strafeMC needs more files from your Counter-Strike 2 install"
							+ (status.missingFiles() > 0 ? " (" + status.missingFiles() + " files are missing)." : " (new weapons or effects)."));
				else lines.add("strafeMC needs files from your own Counter-Strike 2 install before guns, sounds and effects work.");
				lines.add("");
				if (cs2 == null) {
					lines.add("§eCounter-Strike 2 doesn't seem to be installed on this PC.");
					lines.add("§7It's free on Steam (a large download, around 60 GB). Install it, then restart Minecraft and this screen comes back. If it's installed somewhere unusual, pick its folder instead.");
					lines.add("§7Without it you can still play: CS2 movement works, and guns, knives and sounds use placeholders. On a server, its gameplay values still apply to you.");
					lines.add("§c§lHeads up:§r§c strafeMC is built around CS2's files. Without them it's a much worse experience: no real guns, knives, sounds or effects.");
					lines.add("");
				}
				lines.add("It converts them once, on this PC, into a strafemc folder in your Minecraft profile. Nothing is uploaded, and your CS2 install is never changed.");
				lines.add("");
				if (status.done()) lines.add("§7Only the new or missing files are converted: usually a few MB and under a minute.");
				else lines.add("§7Needs " + SIZE + " of disk space and a few minutes. Until then you can play with placeholders.");
			}
			case CONFIRM -> {
				lines.add(cs2 == null ? "§eCounter-Strike 2 wasn't found automatically. Pick its folder:" : "Counter-Strike 2 folder:");
				drawWrapped(g, lines, x, y);
				lines.clear();
				Path n = Cs2Finder.normalize(folder == null ? "" : folder.getValue());
				g.text(font, n != null ? "§a✔ CS2 found" + (n.toString().equals(folder.getValue().trim()) ? "" : ": " + n) : "§c✘ no CS2 game files here", x, 98, 0xFFFFFFFF, false);
				y = 114;
				if (cs2 == null) {
					lines.add("§7In Steam: right-click Counter-Strike 2 → Manage → Browse local files. Use Browse… to pick that folder (or paste it).");
					lines.add("§7Don't have CS2? It's free on Steam; install it, then come back here.");
				}
				if (converterReady()) lines.add("• Uses the ValveResourceFormat converter strafeMC already has (no download).");
				else {
					var b = ConverterDownload.forThisComputer();
					lines.add("• Downloads the open-source ValveResourceFormat converter " + ConverterDownload.VERSION + " (" + b.size() / 1_000_000
							+ " MB) from its official GitHub release, and checks it against its published checksum.");
				}
				lines.add("• Reads your CS2 files (read-only) and converts every gun, knife, sound and effect strafeMC uses.");
				lines.add(status.done() ? "• Adds the new files to " + StrafeConfig.RUNTIME_DIR + "."
						: "• Saves " + SIZE + " into " + StrafeConfig.RUNTIME_DIR + ".");
				if (error != null) lines.add("§c" + error);
			}
			case RUNNING -> {
				lines.add("Setting up… you can keep playing: it runs in the background, its progress shows at the top of the screen, and Options > strafeMC > Open setup... brings this page back.");
				lines.add("");
				lines.add("§7" + step);
				int bw = 310, by = y + 50;
				g.fill(x, by, x + bw, by + 6, 0xFF333333);
				g.fill(x, by, x + (int) (bw * fraction), by + 6, 0xFF00DC78);
				g.centeredText(font, String.format("%.0f%%", fraction * 100), cx, by + 12, 0xFFFFFFFF);
			}
			case DONE -> {
				lines.add("§aThe setup is done.");
				lines.add("");
				lines.add("§e§lRestart Minecraft to finish.");
				lines.add("§eThe converted guns, knives, sounds and effects only load when Minecraft starts. Until you restart, everything still uses placeholders.");
			}
			case FAILED -> {
				lines.add("§cThe setup stopped: " + error);
				lines.add("");
				lines.add("Nothing was changed in your CS2 install. You can try again next time you join a world, or follow the manual guide on GitHub.");
			}
		}
		drawWrapped(g, lines, x, y);
	}

	private void drawWrapped(GuiGraphicsExtractor g, List<String> lines, int x, int y) {
		for (String l : lines) {
			if (l.isEmpty()) {
				y += 6;
				continue;
			}
			String prefix = l.startsWith("§") ? l.substring(0, 2) : "";
			StringBuilder line = new StringBuilder();
			for (String w : l.split(" ")) {
				if (line.length() > 0 && font.width(line + " " + w) > 310) {
					g.text(font, line.toString(), x, y, 0xFFE0E0E0, false);
					y += 11;
					line.setLength(0);
					line.append(prefix);
				}
				if (line.length() > 0 && !line.toString().equals(prefix)) line.append(' ');
				line.append(w);
			}
			g.text(font, line.toString(), x, y, 0xFFE0E0E0, false);
			y += 13;
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
