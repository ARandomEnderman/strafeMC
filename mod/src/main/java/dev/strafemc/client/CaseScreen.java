package dev.strafemc.client;

import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.Cases;
import dev.strafemc.weapon.SkinWraps;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A CS2 weapon case: its contents by tier in CS2's rarity colours, then "Unlock Container" (or 2x-5x at once): the
 * server rolls, one reel per case spins and slows down onto what came out, and the drops are revealed, as in CS2.
 */
public final class CaseScreen extends Screen {
	/** One reel / contents tile: the item to show, its rarity colour and name. */
	private record Tile(ItemStack stack, int color, String name, Cases.Entry entry) {}

	/** One case's reel and what it lands on. */
	/** land: where across the winning tile the line stops (0..1), different every time, as in CS2. */
	private record Spin(List<Tile> tiles, int landing, double delay, Tile result, int rarity, int pattern, double land) {}

	private static final int TILE = 64, GOLD = 0xFFE4AE39;
	private static final double SPIN_SECONDS = 6.5, STAGGER = 0.25;
	private static CaseScreen open;

	private final Cases.Case c;
	private final List<Tile> contents = new ArrayList<>();
	private final List<Button> unlocks = new ArrayList<>();
	private List<Spin> spins;
	private long spinStart;
	private int lastTick = -1;
	private boolean revealed, claimed;
	private Button close, prev, next;
	/** Which drop the reveal shows (several opened: arrows switch, as in CS2). */
	private int shown;
	/** The reveal's 3D model, turned by dragging with the mouse. */
	private float yaw = -20, pitch = 10, zoom = 1;

	@Override
	public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent e, double dx, double dy) {
		if (revealed) {
			yaw -= (float) dx * 1.2f;
			pitch = Math.max(-80, Math.min(80, pitch - (float) dy * 1.2f));
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	/** Scrolling zooms the reveal's model in and out. */
	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (revealed) {
			zoom = (float) Math.max(0.6, Math.min(4, zoom * Math.pow(1.15, sy)));
			return true;
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	private CaseScreen(Cases.Case c) {
		super(Component.literal(c.name()));
		this.c = c;
		for (int t = SkinWraps.Rarity.values().length - 1; t >= 0; t--) {
			var list = c.guns().get(SkinWraps.Rarity.values()[t]);
			if (list == null) continue;
			for (Cases.Entry e : list) {
				ItemStack s = Cases.stack(e, 0);
				if (!s.isEmpty()) contents.add(new Tile(s, color(t), s.getHoverName().getString(), e));
			}
		}
	}

	private static int color(int rarity) {
		return rarity < 0 ? GOLD : 0xFF000000 | SkinWraps.Rarity.values()[rarity].color;
	}

	public static void open(String caseId) {
		Cases.Case c = Cases.get(caseId);
		if (c == null) return;
		List<Cases.Entry> all = new ArrayList<>(); // every gun's picture in one converter run
		for (var list : c.guns().values()) all.addAll(list);
		CaseImages.load(all);
		Minecraft.getInstance().setScreen(open = new CaseScreen(c));
	}

	/** The server's rolls: build the reels ending on them and spin (the screen already closed: take the drops now). */
	public static void result(StrafePayloads.CaseResult r) {
		if (open == null || Minecraft.getInstance().screen != open || !open.c.id().equals(r.caseId())) {
			claim();
			return;
		}
		open.spin(r);
	}

	/** Tells the server to hand the drops over (the reels landed, or the screen was closed). */
	private static void claim() {
		if (ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE)) ClientPlayNetworking.send(new StrafePayloads.Action("case_claim", ""));
	}

	@Override
	protected void init() {
		unlocks.clear();
		int[] counts = {1, 2, 3, 4, 5};
		int bw = 46, total = 150 + 4 * (bw + 4), x = width / 2 - total / 2;
		for (int n : counts) {
			int w = n == 1 ? 150 : bw;
			Button b = addRenderableWidget(Button.builder(Component.literal(n == 1 ? "Unlock Container" : n + "x"), btn -> unlock(n))
					.bounds(x, height - 30, w, 20).build());
			b.active = spins == null;
			b.visible = !revealed;
			unlocks.add(b);
			x += w + 4;
		}
		close = addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(width / 2 - 75, height - 30, 150, 20).build());
		close.visible = revealed;
		prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> show(-1)).bounds(width / 2 - 200, height / 2 - 40, 24, 40).build());
		next = addRenderableWidget(Button.builder(Component.literal(">"), b -> show(1)).bounds(width / 2 + 176, height / 2 - 40, 24, 40).build());
		arrows();
	}

	private void show(int dir) {
		if (spins == null || spins.isEmpty()) return;
		shown = Math.floorMod(shown + dir, spins.size());
		yaw = -20;
		pitch = 10;
		zoom = 1;
	}

	private void arrows() {
		boolean many = revealed && spins != null && spins.size() > 1;
		prev.visible = many;
		next.visible = many;
	}

	/** Left / right arrow keys flip through the drops too. */
	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
		// the inventory key (E) closes it like Esc (mid-spin: the drops are handed over at once)
		if (Minecraft.getInstance().options.keyInventory.matches(e)) {
			onClose();
			return true;
		}
		if (revealed && spins != null && spins.size() > 1) {
			if (e.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT) {
				show(-1);
				return true;
			}
			if (e.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT) {
				show(1);
				return true;
			}
		}
		return super.keyPressed(e);
	}

	private void unlock(int n) {
		if (ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE)) ClientPlayNetworking.send(new StrafePayloads.Action("case_open", c.id() + " " + n));
		dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/case_unlock_01", 1f);
		for (Button b : unlocks) b.active = false;
	}

	/** Esc (or Close) mid-spin: the drops are yours at once, as in CS2 when you skip the reel. */
	@Override
	public void onClose() {
		if (spins != null && !claimed) {
			claimed = true;
			claim();
		}
		open = null;
		super.onClose();
	}

	private void spin(StrafePayloads.CaseResult r) {
		Random rng = new Random();
		List<Spin> list = new ArrayList<>();
		for (int k = 0; k < r.drops().size(); k++) {
			var d = r.drops().get(k);
			List<Tile> tiles = new ArrayList<>();
			for (int i = 0; i < 60; i++) tiles.add(filler(rng));
			int landing = 52;
			Cases.Entry e = new Cases.Entry(d.kit(), d.item());
			Tile got = new Tile(Cases.stack(e, 0), color(d.rarity()), d.name(), e);
			tiles.set(landing, got);
			CaseImages.load(List.of(e));
			list.add(new Spin(tiles, landing, k * STAGGER, got, d.rarity(), d.pattern(), 0.05 + rng.nextDouble() * 0.9));
		}
		spins = list;
		spinStart = System.nanoTime();
	}

	/** A reel tile by the case's odds among its guns: never the gold rare special one (CS2 shows it only when it drops). */
	private Tile filler(Random rng) {
		double x = rng.nextDouble() * 0.9974;
		SkinWraps.Rarity t = x < 0.7992 ? SkinWraps.Rarity.RARE : x < 0.959 ? SkinWraps.Rarity.MYTHICAL : x < 0.991 ? SkinWraps.Rarity.LEGENDARY
				: SkinWraps.Rarity.ANCIENT;
		for (int k = t.ordinal(); k >= 0; k--) {
			var list = c.guns().get(SkinWraps.Rarity.values()[k]);
			if (list == null || list.isEmpty()) continue;
			Cases.Entry e = list.get(rng.nextInt(list.size()));
			ItemStack s = Cases.stack(e, 0);
			return new Tile(s, color(k), s.getHoverName().getString(), e);
		}
		return contents.isEmpty() ? new Tile(ItemStack.EMPTY, GOLD, "", null) : contents.get(rng.nextInt(contents.size()));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, 0xE0101418);
		var font = Minecraft.getInstance().font;
		g.text(font, c.name(), width / 2 - font.width(c.name()) / 2, 12, 0xFFFFFFFF, true);
		if (spins == null) contents(g, font);
		else if (revealed) preview(g, font);
		else reels(g, font);
		super.extractRenderState(g, mx, my, delta);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}

	/** The contents grid, best first, then the rare special item line. */
	private void contents(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font) {
		int cols = Math.max(1, Math.min(6, (width - 40) / (TILE + 8))), x0 = width / 2 - cols * (TILE + 8) / 2, y = 32;
		for (int i = 0; i < contents.size(); i++) {
			int x = x0 + (i % cols) * (TILE + 8), ty = y + (i / cols) * (TILE + 6);
			tile(g, font, contents.get(i), x, ty, TILE, TILE);
		}
		int rows = (contents.size() + cols - 1) / cols;
		if (!c.special().isEmpty()) {
			String s = "★ Rare Special Item: " + (c.special().get(0).gloves() ? "gloves" : "knives") + " ★";
			g.text(font, s, width / 2 - font.width(s) / 2, y + rows * (TILE + 6) + 6, GOLD, true);
		}
	}

	private void tile(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, Tile t, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, 0xFF22272E);
		g.fill(x, y + h - 3, x + w, y + h, t.color());
		// CS2's picture of the gun in its skin, 4:3 above the name; the item's own icon until it's loaded
		var pic = t.entry() == null ? null : CaseImages.get(t.entry());
		if (pic != null) {
			int ph = Math.min(h - 16, (w - 4) * 3 / 4), pw = ph * 4 / 3, px = x + (w - pw) / 2;
			g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, pic, px, y + 2, 0, 0, pw, ph, pw, ph);
		} else if (!t.stack().isEmpty()) g.item(t.stack(), x + w / 2 - 8, y + 4);
		String n = font.plainSubstrByWidth(t.name(), w - 4);
		g.text(font, n, x + w / 2 - font.width(n) / 2, y + h - 13, 0xFFE0E0E0, false);
	}

	/** The reels, stacked, each eased to a stop on its drop (a little apart); ticks as tiles pass the line. */
	private void reels(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font) {
		int n = spins.size(), size = n <= 2 ? TILE : n <= 3 ? 56 : 48, step = size + 6;
		int rowH = size + 10, top = Math.max(28, height / 2 - n * rowH / 2);
		double elapsed = (System.nanoTime() - spinStart) / 1e9;
		boolean all = true;
		for (int r = 0; r < n; r++) {
			Spin s = spins.get(r);
			double t = Math.max(0, Math.min(1, (elapsed - s.delay()) / SPIN_SECONDS));
			if (t < 1) all = false;
			double off = (1 - Math.pow(1 - t, 4)) * (s.landing() * step + s.land() * size); // stops anywhere on the tile, like CS2
			if (r == 0) {
				int tick = (int) (off / step);
				if (tick != lastTick && t < 1) {
					lastTick = tick;
					dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/csgo_ui_crate_item_scroll", 0.6f);
				}
			}
			int cy = top + r * rowH;
			for (int i = 0; i < s.tiles().size(); i++) {
				int x = (int) (width / 2 + i * step - off);
				if (x + size < 0 || x > width) continue;
				tile(g, font, s.tiles().get(i), x, cy, size, size);
			}
			g.fill(width / 2 - 1, cy - 3, width / 2 + 1, cy + size + 3, 0xFFFFD040);
		}
		if (all && !revealed) { // every reel landed: the drops are handed over, CS2's reveal sound for the best one
			revealed = true;
			if (!claimed) {
				claimed = true;
				claim();
			}
			int best = 0;
			for (Spin s : spins) best = Math.max(best, s.rarity() < 0 ? 99 : s.rarity());
			String sound = best >= SkinWraps.Rarity.ANCIENT.ordinal() ? "ancient" : best == SkinWraps.Rarity.LEGENDARY.ordinal() ? "legendary"
					: best == SkinWraps.Rarity.MYTHICAL.ordinal() ? "mythical" : "rare"; // gold: the Covert one (FLAGGED)
			dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/case_reveal_" + sound + "_01", 1f);
			for (Button b : unlocks) b.visible = false;
			close.visible = true;
			shown = 0;
			arrows();
		}
	}

	/** The big reveal of one drop (arrows switch when several opened): CS2's picture, its name in its rarity colour, its grade and pattern template. */
	private void preview(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font) {
		Spin s = spins.get(Math.min(shown, spins.size() - 1));
		int cw = Math.min(width - 120, 320), ch = cw * 3 / 4, px = width / 2 - cw / 2, py = Math.max(30, height / 2 - ch / 2 - 30);
		g.fill(px - 4, py - 4, px + cw + 4, py + ch + 4, 0xFF1A1F26);
		g.fill(px - 4, py + ch + 1, px + cw + 4, py + ch + 4, s.result().color());
		// a gun or knife: its model in this finish, turned by dragging; gloves (and until the model's baked): CS2's picture
		Cases.Entry e = s.result().entry();
		var vm = dev.strafemc.client.viewmodel.Viewmodel.INSTANCE;
		if (!e.gloves() && vm.previewReady(e.item(), e.kit(), Math.max(0, s.pattern())))
			LoadoutPreview.drawTurned(g, px, py, px + cw, py + ch, e.item(), e.kit(), s.pattern(), yaw, pitch, zoom);
		else {
			var pic = CaseImages.get(e);
			if (pic != null) g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, pic, px, py, 0, 0, cw, ch, cw, ch);
		}
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(width / 2f, py + ch + 12);
		pose.scale(2f, 2f);
		g.text(font, s.result().name(), -font.width(s.result().name()) / 2, 0, s.result().color(), true);
		pose.popMatrix();
		String grade = s.rarity() < 0 ? "★ Rare Special Item" : SkinWraps.Rarity.values()[s.rarity()].weaponName;
		if (s.pattern() >= 0) grade += "  |  Pattern template " + s.pattern();
		g.text(font, grade, width / 2 - font.width(grade) / 2, py + ch + 34, 0xFFB0B0B0, true);
		if (spins.size() > 1) {
			String page = (shown + 1) + " / " + spins.size();
			g.text(font, page, width / 2 - font.width(page) / 2, py - 16, 0xFFFFFFFF, true);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
