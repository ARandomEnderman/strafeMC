package dev.strafemc.client;

import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.Cases;
import dev.strafemc.weapon.SkinWrapItem;
import dev.strafemc.weapon.SkinWraps;
import dev.strafemc.weapon.Upgrader;
import dev.strafemc.weapon.Worth;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The upgrader (strafeMC menu): pick skins to stake (left: your inventory, then each Storage Unit you carry under its
 * name, foldable), one of the five offers for exactly that stake (right), and Upgrade: a needle spins round the dial
 * and stops in the green part (your chance) or not.
 */
public final class UpgraderScreen extends Screen {
	private static final int GOLD = 0xFFEABE54, WIN = 0xFF4CAF50, LOSE = 0xFFE04040;
	private static UpgraderScreen open;
	private final Screen parent;
	/** The staked skins, by ref (Upgrader.at: inventory slot or storage unit place). */
	/** The stake: ref -> how many of that stack (left click adds one, right click takes one off). */
	private final java.util.Map<Integer, Integer> stake = new java.util.LinkedHashMap<>();
	/** Folded sections (-1 the inventory, else the storage unit's inventory slot), and the left column's scroll. */
	private static final java.util.Set<Integer> folded = new java.util.HashSet<>();
	private double listScroll;
	private int listHeight;

	/** A group of stakeable skins: the inventory, or one Storage Unit (key: its inventory slot). */
	private record Section(String label, int key, List<Integer> refs) {}
	private List<Upgrader.Offer> offers = List.of();
	private int chosen = -1;
	private Button go;
	/** The spin: when it started, where the needle stops (turns, 0 = top), the outcome; spinStart 0 = none. */
	private long spinStart;
	private double stopAt, from;
	private Boolean won;
	private String verdict;
	/** Sent, waiting for the roll: the stake and offers stay as they were (the server has already taken the items). */
	private boolean waiting;
	private long waitingSince;
	private static final double SPIN_SECONDS = 3;

	public UpgraderScreen(Screen parent) {
		super(Component.literal("Upgrader"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		open = this;
		go = addRenderableWidget(Button.builder(Component.literal("Upgrade"), b -> upgrade()).bounds(width / 2 - 75, height - 60, 150, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 26, 200, 20).build());
		refresh();
	}

	/** The inventory's skins, then each carried Storage Unit's (with its name), by ref. */
	private List<Section> sections() {
		List<Section> out = new ArrayList<>();
		var p = minecraft.player;
		if (p == null) return out;
		var inv = p.getInventory();
		List<Integer> equipped = new ArrayList<>(); // the current loadout's (picking one is warned about)
		var defs = dev.strafemc.weapon.Loadout.defs();
		for (int i = 0; i < defs.size(); i++) if (Upgrader.stakeable(dev.strafemc.weapon.Loadout.get(p, defs.get(i).id()))) equipped.add(Upgrader.loadoutRef(i));
		if (!equipped.isEmpty()) out.add(new Section("CURRENT LOADOUT", -2, equipped));
		List<Integer> loose = new ArrayList<>();
		for (int i = 0; i < inv.getContainerSize(); i++) if (Upgrader.stakeable(inv.getItem(i))) loose.add(i);
		out.add(new Section("YOUR ITEMS", -1, loose));
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack unit = inv.getItem(i);
			if (!(unit.getItem() instanceof dev.strafemc.weapon.StorageUnitItem)) continue;
			List<Integer> in = new ArrayList<>();
			List<ItemStack> all = unit.getOrDefault(dev.strafemc.weapon.StorageUnitItem.CONTENTS, List.<ItemStack>of());
			for (int j = 0; j < all.size(); j++) if (Upgrader.stakeable(all.get(j))) in.add(Upgrader.unitRef(i, j));
			out.add(new Section(unit.getHoverName().getString(), i, in));
		}
		return out;
	}

	private interface Visit {
		void header(Section s, int y);

		void item(int ref, int x, int y);
	}

	/** Walks the left column's layout (scrolled): each section's header, then its skins unless it's folded. */
	private void layout(Visit v) {
		int per = Math.max(1, (colW() - 4) / 18), y = LIST_TOP - (int) listScroll;
		for (Section s : sections()) {
			v.header(s, y);
			y += 12;
			if (!folded.contains(s.key())) {
				for (int i = 0; i < s.refs().size(); i++) v.item(s.refs().get(i), 20 + (i % per) * 18, y + (i / per) * 18);
				y += Math.max(1, (s.refs().size() + per - 1) / per) * 18;
			}
			y += 4;
		}
		listHeight = y + (int) listScroll - LIST_TOP;
	}

	private static final int LIST_TOP = 30;

	private int listBottom() {
		return height - 60;
	}

	private List<ItemStack> stakeStacks() {
		List<ItemStack> out = new ArrayList<>();
		for (var e : stake.entrySet()) {
			ItemStack s = Upgrader.at(minecraft.player, e.getKey());
			out.add(s.copyWithCount(Math.max(1, Math.min(s.getCount(), e.getValue()))));
		}
		return out;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (mx < 20 + colW()) {
			listScroll = Math.max(0, Math.min(Math.max(0, listHeight - (listBottom() - LIST_TOP)), listScroll - sy * 18));
			return true;
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	private void refresh() {
		stake.entrySet().removeIf(e -> !Upgrader.stakeable(Upgrader.at(minecraft.player, e.getKey())));
		stake.replaceAll((ref, n) -> Math.min(n, Upgrader.at(minecraft.player, ref).getCount()));
		offers = Upgrader.offers(minecraft.player.getUUID(), stakeStacks());
		if (chosen >= offers.size()) chosen = -1;
	}

	private boolean spinning() {
		return spinStart != 0 && (System.nanoTime() - spinStart) / 1e9 < SPIN_SECONDS;
	}

	private void upgrade() {
		if (chosen < 0 || stake.isEmpty() || spinStart != 0 && spinning()) return;
		StringBuilder b = new StringBuilder().append(chosen).append(' ').append(Upgrader.key(offers.get(chosen))); // which skin, so the server can't give another
		for (var e : stake.entrySet()) b.append(' ').append(e.getKey()).append(':').append(e.getValue());
		if (ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE)) ClientPlayNetworking.send(new StrafePayloads.Action("upgrade", b.toString()));
		go.active = false;
		waiting = true;
		waitingSince = System.currentTimeMillis();
	}

	/** The server's roll: spin the needle to a stop inside the green part (a win) or outside it. */
	public static void result(StrafePayloads.UpgradeResult r) {
		UpgraderScreen s = open;
		if (s == null || Minecraft.getInstance().screen != s || r.offer() < 0 || r.offer() >= s.offers.size()) return;
		double chance = s.offers.get(r.offer()).chance(), x = Math.random();
		double land = r.win() ? x * chance : chance + x * (1 - chance);
		s.from = 0;
		s.stopAt = 4 + land; // four full turns, then the stop
		s.won = r.win();
		s.verdict = r.win() ? "Upgraded!" : "Lost";
		s.spinStart = System.nanoTime();
		s.waiting = false;
		dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/case_unlock_01", 1f);
	}

	@Override
	public void tick() {
		super.tick();
		if (spinStart != 0 && !spinning() && won != null) { // landed: the skin is handed over, the sound, then a fresh start
			claim();
			dev.strafemc.client.sound.CsSounds.playFile(won ? "sounds/ui/panorama/case_reveal_legendary_01" : "sounds/ui/weapon_cant_buy", won ? 1f : 0.6f);
			won = null;
			stake.clear();
			chosen = -1;
		}
		if (waiting && System.currentTimeMillis() - waitingSince > 5000) waiting = false; // refused: no roll came back
		if (!spinning() && !waiting) refresh();
		go.active = !spinning() && !waiting && chosen >= 0 && !stake.isEmpty();
	}

	// ---------------------------------------------------------------- layout

	private int colW() {
		return Math.max(110, Math.min(170, (width - 40) / 3));
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) return true;
		if (spinning() || waiting) return false;
		double mx = e.x(), my = e.y();
		// the left column: a header folds its section, a skin goes into / out of the stake
		int cw = colW();
		if (mx >= 20 && mx < 20 + cw && my >= LIST_TOP && my < listBottom()) {
			boolean[] done = {false};
			layout(new Visit() {
				@Override
				public void header(Section s, int y) {
					if (!done[0] && my >= y && my < y + 11) {
						if (!folded.remove(s.key())) folded.add(s.key());
						done[0] = true;
					}
				}

				@Override
				public void item(int ref, int x, int y) {
					if (done[0] || mx < x || mx >= x + 18 || my < y || my >= y + 18) return;
					int have = Upgrader.at(minecraft.player, ref).getCount();
					Integer n = stake.get(ref);
					if (e.button() == 1) { // right click: one fewer
						if (n == null) return;
						if (n <= 1) stake.remove(ref);
						else stake.put(ref, n - 1);
					} else if (n == null) {
						if (stake.size() < Upgrader.MAX_STAKE) stake.put(ref, 1); // left click: one more of the stack each click
					} else if (have <= 1) stake.remove(ref); // a single item: a second click takes it back out
					else stake.put(ref, Math.min(have, n + 1));
					dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/submenu_leveloptions_select_01", 0.4f);
					refresh();
					done[0] = true;
				}
			});
			if (done[0]) return true;
		}
		// the offers
		int ox = width - 20 - cw;
		for (int i = 0; i < offers.size(); i++) {
			int y = 40 + i * 34;
			if (mx >= ox && mx < ox + cw && my >= y && my < y + 30) {
				chosen = chosen == i ? -1 : i;
				dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/premier_deselectmap", 0.4f);
				return true;
			}
		}
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, StrafeSettingsScreen.BG);
		var font = minecraft.font;
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(width / 2f, 12);
		pose.scale(2f, 2f);
		g.text(font, "Upgrader", -font.width("Upgrader") / 2, 0, 0xFFFFFFFF, true);
		pose.popMatrix();
		int cw = colW();
		// left: your skins, then each Storage Unit's under its name (click a header to fold it); scrolls
		ItemStack[] hover = {ItemStack.EMPTY};
		g.enableScissor(0, LIST_TOP, 20 + cw + 4, listBottom());
		layout(new Visit() {
			@Override
			public void header(Section s, int y) {
				boolean fold = folded.contains(s.key());
				String label = (fold ? "▶ " : "▼ ") + s.label() + "  " + s.refs().size();
				g.text(font, font.plainSubstrByWidth(label, cw), 20, y + 1, s.key() < 0 ? GOLD : 0xFF9AC8FF, false);
				if (!fold && s.refs().isEmpty()) g.text(font, "No skins", 30, y + 13, 0xFF707070, false);
			}

			@Override
			public void item(int ref, int x, int y) {
				ItemStack s = Upgrader.at(minecraft.player, ref);
				Integer n = stake.get(ref);
				boolean in = n != null;
				g.fill(x, y, x + 17, y + 17, in ? GOLD : 0xFF0E0F11);
				if (in) g.fill(x + 1, y + 1, x + 16, y + 16, 0xFF2A313A);
				CaseImages.icon(g, s, x, y);
				if (s.getCount() > 1) { // a stack (stickers, graffiti): how many there are, and how many of them are staked
					String c = in ? n + "/" + s.getCount() : String.valueOf(s.getCount());
					var pose = g.pose();
					pose.pushMatrix();
					pose.translate(x + 17, y + 11);
					pose.scale(0.75f, 0.75f);
					g.text(font, c, -font.width(c), 0, in ? GOLD : 0xFFFFFFFF, true);
					pose.popMatrix();
				}
				if (mx >= x && mx < x + 18 && my >= y && my < y + 18 && my >= LIST_TOP && my < listBottom()) hover[0] = s;
			}
		});
		g.disableScissor();
		// right: the five offers for this stake
		int ox = width - 20 - cw;
		g.text(font, "UPGRADE TO", ox, LIST_TOP + 1, GOLD, false);
		if (offers.isEmpty()) g.text(font, font.plainSubstrByWidth("Pick skins to stake", cw), ox, 40, 0xFF808080, false);
		for (int i = 0; i < offers.size(); i++) {
			Upgrader.Offer o = offers.get(i);
			int y = 40 + i * 34;
			boolean sel = i == chosen, over = mx >= ox && mx < ox + cw && my >= y && my < y + 30;
			g.fill(ox, y, ox + cw, y + 30, over ? 0xFF2A313A : 0xFF22272E);
			if (sel) g.outline(ox - 1, y - 1, cw + 2, 32, GOLD);
			Identifier pic = picture(o.item());
			if (pic != null) g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, pic, ox + 2, y + 3, 0, 0, 32, 24, 32, 24);
			else g.item(o.item(), ox + 10, y + 7);
			int tx = ox + 38, tw = cw - 40;
			g.text(font, font.plainSubstrByWidth(TileText.clean(o.item().getHoverName().getString()), tw), tx, y + 4, rarity(o.item()), false);
			g.text(font, Worth.format(o.worth()) + "  " + String.format(java.util.Locale.ROOT, "%.1f%%", o.chance() * 100), tx, y + 16, 0xFFB0B0B0, false);
			if (over) hover[0] = o.item();
		}
		// middle: the stake, the dial and the verdict
		int cx = width / 2, cy = height / 2 + 4, r = Math.max(30, Math.min(60, Math.min(width / 6, height / 4)));
		double stakeWorth = Upgrader.worth(stakeStacks());
		String st = "Stake: " + Worth.format(stakeWorth) + " (" + stake.size() + "/" + Upgrader.MAX_STAKE + ")";
		g.text(font, st, cx - font.width(st) / 2, cy - r - 22, 0xFFFFFFFF, true);
		double chance = chosen >= 0 && chosen < offers.size() ? offers.get(chosen).chance() : 0;
		for (int k = 0; k < 180; k++) { // the dial: green for your chance (from the top, clockwise), grey for the rest
			double t = k / 180.0, a = -Math.PI / 2 + t * Math.PI * 2;
			int x = (int) Math.round(cx + Math.cos(a) * r), y = (int) Math.round(cy + Math.sin(a) * r);
			g.fill(x - 1, y - 1, x + 2, y + 2, t < chance ? WIN : 0xFF3A4048);
		}
		double turn = needle();
		for (int k = 4; k < r - 4; k += 2) { // the needle
			double a = -Math.PI / 2 + turn * Math.PI * 2;
			int x = (int) Math.round(cx + Math.cos(a) * k), y = (int) Math.round(cy + Math.sin(a) * k);
			g.fill(x, y, x + 2, y + 2, 0xFFFFFFFF);
		}
		String mid = chosen >= 0 ? String.format(java.util.Locale.ROOT, "%.1f%%", chance * 100) : "";
		if (spinStart != 0 && !spinning() && verdict != null) mid = verdict;
		g.text(font, mid, cx - font.width(mid) / 2, cy + r + 8, verdict != null && !spinning() ? (verdict.equals("Lost") ? LOSE : WIN) : 0xFFFFFFFF, true);
		boolean equippedPicked = false; // something from the current loadout is in: say so under the button
		for (int ref : stake.keySet()) equippedPicked |= Upgrader.equipped(ref);
		if (equippedPicked) {
			String warn = "You chose a currently equipped item";
			g.text(font, warn, width / 2 - font.width(warn) / 2, height - 37, 0xFFE05050, false);
		}
		super.extractRenderState(g, mx, my, delta);
		if (!hover[0].isEmpty()) g.setTooltipForNextFrame(font, hover[0], mx, my);
	}

	/** Where the needle points (turns from the top): spinning, eased to its stop; at rest, where it stopped. */
	private double needle() {
		if (spinStart == 0) return 0;
		double t = Math.min(1, (System.nanoTime() - spinStart) / 1e9 / SPIN_SECONDS);
		return from + (stopAt - from) * (1 - Math.pow(1 - t, 3));
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}

	private static int rarity(ItemStack s) {
		SkinWraps.Wrap w = SkinWraps.get(SkinWrapItem.skin(s));
		return w != null && w.rarity() != null ? 0xFF000000 | w.rarity().color : 0xFFE0E0E0;
	}

	private static Identifier picture(ItemStack s) {
		SkinWraps.Wrap w = SkinWraps.get(SkinWrapItem.skin(s));
		if (w == null || w.kits().isEmpty()) return null;
		var e = w.kits().entrySet().iterator().next();
		return CaseImages.get(new Cases.Entry(e.getValue(), e.getKey()));
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
		if (LoadoutKey.closesAll(this, e)) return true;
		return super.keyPressed(e);
	}

	/** Tells the server to hand the won skin over (the needle landed, or the upgrader is closing). */
	private static void claim() {
		if (ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE)) ClientPlayNetworking.send(new StrafePayloads.Action("upgrade_claim", ""));
	}

	@Override
	public void onClose() {
		claim(); // closed mid-spin: it's yours at once, as when skipping a case's reel
		open = null;
		minecraft.setScreen(parent);
	}

	@Override
	public void removed() {
		if (open == this) claim(); // closed another way (the menu key, a server screen)
		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
