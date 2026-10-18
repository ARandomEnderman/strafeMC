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
 * The upgrader (strafeMC menu): pick skins from your inventory to stake (left), one of the five offers for exactly that
 * stake (right), and Upgrade: a needle spins round the dial and stops in the green part (your chance) or not.
 */
public final class UpgraderScreen extends Screen {
	private static final int GOLD = 0xFFEABE54, WIN = 0xFF4CAF50, LOSE = 0xFFE04040;
	private static UpgraderScreen open;
	private final Screen parent;
	/** Inventory slots being staked. */
	private final List<Integer> stake = new ArrayList<>();
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
		go = addRenderableWidget(Button.builder(Component.literal("Upgrade"), b -> upgrade()).bounds(width / 2 - 75, height - 52, 150, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 26, 200, 20).build());
		refresh();
	}

	/** The player's stakeable items, by inventory slot. */
	private List<Integer> items() {
		List<Integer> out = new ArrayList<>();
		var p = minecraft.player;
		if (p == null) return out;
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (Upgrader.stakeable(inv.getItem(i))) out.add(i);
		return out;
	}

	private List<ItemStack> stakeStacks() {
		List<ItemStack> out = new ArrayList<>();
		for (int sl : stake) out.add(minecraft.player.getInventory().getItem(sl));
		return out;
	}

	private void refresh() {
		stake.removeIf(sl -> !Upgrader.stakeable(minecraft.player.getInventory().getItem(sl)));
		offers = Upgrader.offers(minecraft.player.getUUID(), stakeStacks());
		if (chosen >= offers.size()) chosen = -1;
	}

	private boolean spinning() {
		return spinStart != 0 && (System.nanoTime() - spinStart) / 1e9 < SPIN_SECONDS;
	}

	private void upgrade() {
		if (chosen < 0 || stake.isEmpty() || spinStart != 0 && spinning()) return;
		StringBuilder b = new StringBuilder().append(chosen);
		for (int sl : stake) b.append(' ').append(sl);
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
		if (spinStart != 0 && !spinning() && won != null) { // landed: the sound, then a fresh start (the stake is gone)
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
		// your items: toggle into / out of the stake
		List<Integer> items = items();
		int cw = colW(), x0 = 20, y0 = 40, per = Math.max(1, (cw - 4) / 18);
		for (int i = 0; i < items.size(); i++) {
			int x = x0 + (i % per) * 18, y = y0 + (i / per) * 18;
			if (mx >= x && mx < x + 18 && my >= y && my < y + 18) {
				Integer sl = items.get(i);
				if (stake.contains(sl)) stake.remove(sl);
				else if (stake.size() < Upgrader.MAX_STAKE) stake.add(sl);
				dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/submenu_leveloptions_select_01", 0.4f);
				refresh();
				return true;
			}
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
		// left: your items
		g.text(font, "YOUR ITEMS", 20, 30, GOLD, false);
		List<Integer> items = items();
		int per = Math.max(1, (cw - 4) / 18);
		ItemStack hover = ItemStack.EMPTY;
		for (int i = 0; i < items.size(); i++) {
			int x = 20 + (i % per) * 18, y = 40 + (i / per) * 18;
			ItemStack s = minecraft.player.getInventory().getItem(items.get(i));
			boolean in = stake.contains(items.get(i));
			g.fill(x, y, x + 17, y + 17, in ? GOLD : 0xFF0E0F11);
			if (in) g.fill(x + 1, y + 1, x + 16, y + 16, 0xFF2A313A);
			g.item(s, x, y);
			if (mx >= x && mx < x + 18 && my >= y && my < y + 18) hover = s;
		}
		if (items.isEmpty()) g.text(font, "No skins to stake", 20, 40, 0xFF808080, false);
		// right: the five offers for this stake
		int ox = width - 20 - cw;
		g.text(font, "UPGRADE TO", ox, 30, GOLD, false);
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
			if (over) hover = o.item();
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
		super.extractRenderState(g, mx, my, delta);
		if (!hover.isEmpty()) g.setTooltipForNextFrame(font, hover, mx, my);
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

	@Override
	public void onClose() {
		open = null;
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
