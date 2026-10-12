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
	private final Setup.Status status;
	private final Screen parent;
	private Page page = Page.INTRO;
	private EditBox folder;
	private String typed = "";
	private Path cs2;
	private volatile double fraction;
	private volatile String step = "";
	private volatile String error;

	public SetupScreen(Setup.Status status, Screen parent) {
		super(Component.literal("strafeMC setup"));
		this.status = status;
		this.parent = parent;
		this.cs2 = Cs2Finder.find(StrafeConfig.cs2Path);
	}

	/** Opens the screen over {@code current} (null in a world) when CS2 files are missing or the setup is outdated, once per game start. */
	public static void offer(Screen current) {
		if (offered || StrafeConfig.setupNeverAsk) return;
		offered = true;
		Setup.Status st = Setup.status(StrafeConfig.cacheDir());
		if (!st.needsSetup()) return;
		var mc = net.minecraft.client.Minecraft.getInstance();
		mc.execute(() -> mc.setScreen(new SetupScreen(st, current)));
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
			case DONE, FAILED -> addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(cx - 75, bottom, 150, 20).build());
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
				}).run(!status.done());
				page = Page.DONE;
			} catch (Exception e) {
				StrafeMc.LOG.error("[strafemc] setup failed", e);
				error = e.getMessage();
				page = Page.FAILED;
			}
			minecraft.execute(() -> {
				rebuildWidgets();
				if (minecraft.screen != this) // closed while it ran: tell the player in chat
					minecraft.gui.getChat().addClientSystemMessage(Component.literal(page == Page.DONE
							? "§a[strafeMC] Setup done. Restart Minecraft to load the converted CS2 files."
							: "§c[strafeMC] Setup stopped: " + error));
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
		int cx = width / 2, x = cx - 155, y = 16;
		g.centeredText(font, "§lstrafeMC setup", cx, y, 0xFFFFFFFF);
		y += 20;
		List<String> lines = new ArrayList<>();
		switch (page) {
			case INTRO -> {
				if (status.done())
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
				lines.add("§7Needs " + SIZE + " of disk space and a few minutes. Until then you can play with placeholders.");
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
				lines.add("• Saves " + SIZE + " into " + StrafeConfig.RUNTIME_DIR + ".");
				if (error != null) lines.add("§c" + error);
			}
			case RUNNING -> {
				lines.add("Setting up… you can keep playing: it runs in the background, and the chat says when it's done.");
				lines.add("");
				lines.add("§7" + step);
				int bw = 310, by = y + 50;
				g.fill(x, by, x + bw, by + 6, 0xFF333333);
				g.fill(x, by, x + (int) (bw * fraction), by + 6, 0xFF00DC78);
				g.centeredText(font, String.format("%.0f%%", fraction * 100), cx, by + 12, 0xFFFFFFFF);
			}
			case DONE -> {
				lines.add("§aDone.");
				lines.add("");
				lines.add("Restart Minecraft so strafeMC loads the converted files (guns, sounds, effects and icons).");
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
