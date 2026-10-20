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
public final class CaseScreen extends Screen implements dev.strafemc.client.ui.UiFit.Sized {
	/** One reel / contents tile: the item to show, its rarity colour and name. */
	private record Tile(ItemStack stack, int color, String name, Cases.Entry entry) {}

	/** One case's reel and what it lands on. */
	/** land: where across the winning tile the line stops (0..1), different every time, as in CS2. */
	private record Spin(List<Tile> tiles, int landing, double delay, Tile result, int rarity, int pattern, float wear, double land) {
		/** Pattern and wear as the viewmodel bakes them. */
		int finish() {
			return dev.strafemc.weapon.Wear.finish(pattern, Math.max(0, wear));
		}
	}

	private static final int TILE = 64, GOLD = 0xFFE4AE39;
	private static final double SPIN_SECONDS = 6.5, STAGGER = 0.25, BULK_STAGGER = 0.08;
	private static CaseScreen open;

	private final Cases.Case c;
	/** A bulk opening (the bulk opener): each reel from its own case, many of them, scrolled with the wheel. */
	private final boolean bulk;
	/** Bulk: each reel's case, and how far the reels are scrolled down (pixels). */
	private List<Cases.Case> reelCases;
	private double reelScroll;
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
		if (spins == null) { // the contents, when they don't fit
			scroll = Math.max(0, Math.min(maxScroll, scroll - sy * (TILE + 6) / 2));
			return true;
		}
		if (!revealed) { // a bulk opening's reels, when they don't fit
			reelScroll = Math.max(0, reelScroll - sy * 40);
			return true;
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	/** How far the contents are scrolled down (pixels), and how far they can be. */
	private double scroll;
	private int maxScroll;

	private CaseScreen(Cases.Case c) {
		this(c, false);
	}

	private CaseScreen(Cases.Case c, boolean bulk) {
		super(Component.literal(c.name()));
		this.c = c;
		this.bulk = bulk;
		if (bulk) unlocking = true; // already sent: the reels come with the server's rolls
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

	/** A bulk opening was sent: the screen waits for the server's rolls (first: any of the cases, for its look). */
	public static void openBulk(Cases.Case first) {
		Minecraft.getInstance().setScreen(open = new CaseScreen(first, true));
	}

	/** A bulk opening's rolls: a reel for each, from its own case. */
	public static void bulkResult(StrafePayloads.BulkCaseResult r) {
		if (open == null || Minecraft.getInstance().screen != open || !open.bulk) {
			claim();
			return;
		}
		open.spinBulk(r);
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
					.bounds(x, height - 50, w, 20).build());
			b.active = spins == null && n <= openable();
			b.visible = !revealed && !bulk; // a bulk opening was already sent by the tool
			unlocks.add(b);
			x += w + 4;
		}
		close = addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 26, 200, 20).build());
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

	/** How many of this case you can open at once: the fewer of your cases and their keys (creative: any). */
	private int openable() {
		var p = Minecraft.getInstance().player;
		if (p == null) return 0;
		if (p.getAbilities().instabuild) return 5;
		int cases = 0, keys = c.needsKey() ? dev.strafemc.weapon.CaseKeys.count(p, c.key()).total() : 5; // its own and Universal Keys, storage units too; capsules need none
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.getItem() instanceof dev.strafemc.weapon.CaseItem && c.id().equals(dev.strafemc.weapon.CaseItem.caseId(s))) cases += s.getCount();
		}
		return Math.min(cases, keys);
	}

	/** Greys out the unlock buttons you don't have enough cases and keys for. */
	@Override
	public void tick() {
		super.tick();
		if (spins != null || unlocking) return;
		int max = openable();
		for (int i = 0; i < unlocks.size(); i++) unlocks.get(i).active = i + 1 <= max;
	}

	/** Sent to the server, waiting for its rolls. */
	private boolean unlocking;

	private void unlock(int n) {
		if (ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE)) ClientPlayNetworking.send(new StrafePayloads.Action("case_open", c.id() + " " + n));
		dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/case_unlock_01", 1f);
		unlocking = true;
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

	private void spinBulk(StrafePayloads.BulkCaseResult r) {
		Random rng = new Random();
		List<Spin> list = new ArrayList<>();
		reelCases = new ArrayList<>();
		for (int k = 0; k < r.drops().size(); k++) {
			var d = r.drops().get(k);
			Cases.Case cc = Cases.get(r.caseIds().get(k));
			reelCases.add(cc != null ? cc : c);
			List<Tile> tiles = new ArrayList<>();
			for (int i = 0; i < 60; i++) tiles.add(filler(reelCases.get(k), rng));
			int landing = 52;
			Cases.Entry e = new Cases.Entry(d.kit(), d.item());
			Tile got = new Tile(Cases.stack(e, 0), color(d.rarity()), d.name(), e);
			tiles.set(landing, got);
			CaseImages.load(List.of(e));
			list.add(new Spin(tiles, landing, k * BULK_STAGGER, got, d.rarity(), d.pattern(), d.wear(), 0.05 + rng.nextDouble() * 0.9));
		}
		spins = list;
		spinStart = System.nanoTime();
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
			list.add(new Spin(tiles, landing, k * STAGGER, got, d.rarity(), d.pattern(), d.wear(), 0.05 + rng.nextDouble() * 0.9));
		}
		spins = list;
		spinStart = System.nanoTime();
	}

	/** A reel tile by the case's odds among its guns: never the gold rare special one (CS2 shows it only when it drops). */
	private Tile filler(Random rng) {
		return filler(c, rng);
	}

	private Tile filler(Cases.Case c, Random rng) {
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
		return c != this.c || contents.isEmpty() ? new Tile(ItemStack.EMPTY, GOLD, "", null) : contents.get(rng.nextInt(contents.size()));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, 0xE0101418);
		var font = Minecraft.getInstance().font;
		String title = bulk ? "Bulk Unboxer: " + (spins == null ? "opening..." : spins.size() + " containers") : c.name();
		g.text(font, title, width / 2 - font.width(title) / 2, 12, 0xFFFFFFFF, true);
		if (spins == null && bulk) {
			String w = "Waiting for the server...";
			g.text(font, w, width / 2 - font.width(w) / 2, height / 2, 0xFF909090, true);
		} else if (spins == null) contents(g, font, mx, my);
		else if (revealed) preview(g, font);
		else reels(g, font);
		super.extractRenderState(g, mx, my, delta);
		universalKeys(g, font);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}

	/** Red over each unlock button that would use Universal Keys: how many ("2xuK"); the case's own keys go first. */
	private void universalKeys(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font) {
		var p = Minecraft.getInstance().player;
		if (p == null || p.getAbilities().instabuild || spins != null || unlocking || !c.needsKey()) return;
		var keys = dev.strafemc.weapon.CaseKeys.count(p, c.key());
		for (int i = 0; i < unlocks.size(); i++) {
			Button b = unlocks.get(i);
			int u = keys.universalFor(i + 1);
			if (!b.visible || !b.active || u <= 0) continue;
			String s = u + "xuK";
			g.text(font, s, b.getX() + b.getWidth() / 2 - font.width(s) / 2, b.getY() - 10, 0xFFE04040, true);
		}
	}

	/** The contents grid, best first, then the rare special item line. */
	private void contents(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, int mx, int my) {
		int cols = Math.max(1, Math.min(6, (width - 40) / (TILE + 8))), x0 = width / 2 - cols * (TILE + 8) / 2, top = 28, bottom = height - 58;
		int rows0 = (contents.size() + cols - 1) / cols, total = rows0 * (TILE + 6) + (c.special().isEmpty() ? 0 : 20) + 4;
		maxScroll = Math.max(0, total - (bottom - top));
		scroll = Math.min(scroll, maxScroll);
		int y = top + 4 - (int) Math.round(scroll);
		boolean inList = my >= top && my < bottom;
		g.enableScissor(0, top, width, bottom); // above the unlock buttons; the wheel scrolls the rest into view
		for (int i = 0; i < contents.size(); i++) {
			int x = x0 + (i % cols) * (TILE + 8), ty = y + (i / cols) * (TILE + 6);
			Tile t = contents.get(i);
			tile(g, font, t, x, ty, TILE, TILE);
			if (!inList) continue;
			if (mx >= x && mx < x + TILE && my >= ty && my < ty + TILE && t.entry() != null && c.capsule())
				g.setTooltipForNextFrame(font, java.util.List.of(Component.literal(TileText.clean(t.name())).withColor(t.color())), java.util.Optional.empty(), mx, my);
			else if (mx >= x && mx < x + TILE && my >= ty && my < ty + TILE && t.entry() != null) { // full name, and the exteriors it comes in
				double[] r = dev.strafemc.weapon.Wear.range(t.entry().kit());
				String lo = dev.strafemc.weapon.Wear.exterior((float) r[0]), hi = dev.strafemc.weapon.Wear.exterior((float) Math.max(r[0], r[1] - 1e-4));
				g.setTooltipForNextFrame(font, java.util.List.of(Component.literal(TileText.clean(t.name())).withColor(t.color()),
						Component.literal(lo.equals(hi) ? lo : lo + " to " + hi).withColor(0xFF909090)), java.util.Optional.empty(), mx, my);
			}
		}
		int rows = (contents.size() + cols - 1) / cols;
		if (!c.special().isEmpty()) {
			String s = "★ Rare Special Item: " + (c.special().get(0).gloves() ? "gloves" : "knives") + " ★";
			g.text(font, s, width / 2 - font.width(s) / 2, y + rows * (TILE + 6) + 6, GOLD, true);
		}
		g.disableScissor();
		if (maxScroll > 0) { // a scroll bar on the right
			int h = bottom - top, bar = Math.max(16, h * h / (h + maxScroll)), by = top + (int) Math.round((h - bar) * scroll / maxScroll);
			g.fill(width - 6, top, width - 3, bottom, 0x40FFFFFF);
			g.fill(width - 6, by, width - 3, by + bar, 0xC0FFFFFF);
		}
	}

	private void tile(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, Tile t, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, 0xFF22272E);
		g.fill(x, y + h - 3, x + w, y + h, t.color());
		// CS2's picture of the gun in its skin, 4:3 above the name; the item's own icon until it's loaded
		var pic = t.entry() == null ? null : CaseImages.get(t.entry());
		if (t.entry() != null && (t.entry().sticker() || t.entry().spray() || t.entry().charm()))
			CaseImages.draw(g, t.stack(), x + 2, y + 2, w - 4, Math.min(h - 6 - TileText.HEIGHT, (w - 4) * 3 / 4)); // stickers 4:3, graffiti their decal
		else if (pic != null) {
			int ph = Math.min(h - 6 - TileText.HEIGHT, (w - 4) * 3 / 4), pw = ph * 4 / 3, px = x + (w - pw) / 2;
			g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, pic, px, y + 2, 0, 0, pw, ph, pw, ph);
		} else if (!t.stack().isEmpty()) g.item(t.stack(), x + w / 2 - 8, y + 4);
		TileText.draw(g, font, t.name(), x + w / 2, y + h - 4 - TileText.HEIGHT, w - 4, 0xFFE0E0E0); // weapon / finish, never cut off
	}

	/** The reels, stacked, each eased to a stop on its drop (a little apart); ticks as tiles pass the line. */
	private void reels(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font) {
		int n = spins.size(), size = n <= 2 ? TILE : n <= 3 ? 56 : 48, step = size + 6;
		int rowH = size + 10, top = Math.max(28, height / 2 - n * rowH / 2);
		// many reels (a bulk opening): they scroll under the title, the wheel moves them
		int viewTop = 26, viewBottom = height - 34, maxReel = Math.max(0, n * rowH - (viewBottom - viewTop));
		reelScroll = Math.min(reelScroll, maxReel);
		if (maxReel > 0) top = viewTop + 4 - (int) Math.round(reelScroll);
		g.enableScissor(0, viewTop, width, viewBottom);
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
		g.disableScissor();
		if (maxReel > 0) { // a scroll bar on the right
			int h = viewBottom - viewTop, bar = Math.max(16, h * h / (h + maxReel)), by = viewTop + (int) Math.round((h - bar) * reelScroll / maxReel);
			g.fill(width - 6, viewTop, width - 2, viewBottom, 0x40FFFFFF);
			g.fill(width - 6, by, width - 2, by + bar, 0xC0FFFFFF);
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
		if (e.sticker() || e.spray() || e.charm()) CaseImages.draw(g, s.result().stack(), px + 8, py + 8, cw - 16, ch - 16); // the sticker's or charm's picture, the graffiti's decal
		else if (!e.gloves() && vm.previewReady(e.item(), e.kit(), s.finish()))
			LoadoutPreview.drawTurned(g, px, py, px + cw, py + ch, e.item(), e.kit(), s.finish(), yaw, pitch, zoom);
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
		if (e.sticker() && s.rarity() >= 0) grade = dev.strafemc.weapon.Stickers.grade(SkinWraps.Rarity.values()[s.rarity()]) + " Sticker";
		else if (e.charm() && s.rarity() >= 0) grade = dev.strafemc.weapon.Stickers.grade(SkinWraps.Rarity.values()[s.rarity()]) + " Charm";
		else if (e.spray() && s.rarity() >= 0) grade = SkinWraps.Rarity.values()[s.rarity()].weaponName.replace(" Grade", "") + " Graffiti";
		if (s.wear() >= 0) grade += "  |  " + dev.strafemc.weapon.Wear.exterior(s.wear()) + " " + String.format(java.util.Locale.ROOT, "%.8f", s.wear());
		if (s.pattern() >= 0) grade += "  |  Pattern template " + s.pattern();
		g.text(font, grade, width / 2 - font.width(grade) / 2, py + ch + 34, 0xFFB0B0B0, true);
		// its worth: the drop rebuilt from what the server sent (finish, pattern, wear, StatTrak by its name)
		ItemStack got = Cases.stack(e, Math.max(0, s.pattern()), Math.max(0, s.wear()));
		if (s.result().name().startsWith("StatTrak")) got.set(dev.strafemc.weapon.WeaponItems.STATTRAK, 0);
		double worth = dev.strafemc.weapon.Worth.of(got);
		if (worth >= 0) {
			String w = "Worth: " + dev.strafemc.weapon.Worth.format(worth);
			g.text(font, w, width / 2 - font.width(w) / 2, py + ch + 46, 0xFF55FF55, true);
		}
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
