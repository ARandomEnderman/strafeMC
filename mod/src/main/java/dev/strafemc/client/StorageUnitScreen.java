package dev.strafemc.client;

import dev.strafemc.weapon.CaseItem;
import dev.strafemc.weapon.Cases;
import dev.strafemc.weapon.GunItem;
import dev.strafemc.weapon.SkinWrapItem;
import dev.strafemc.weapon.SkinWraps;
import dev.strafemc.weapon.StorageUnitItem;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A Storage Unit open: six rows of its 1,000 places (scroll, or drag the bar), the count, your inventory, and on the
 * right a preview of the item under the mouse like the loadout's (a wrap on its gun, a gun or knife, a case's picture).
 */
public final class StorageUnitScreen extends AbstractContainerScreen<StorageUnitItem.Menu> {
	private static final int BG = 0xF0101317, PANEL = 0xC01C2127, SLOT = 0xFF0E0F11, ACCENT = 0xFFEABE54;
	private static final int BAR_X = 176, BAR_Y = 18, BAR_W = 8, BAR_H = StorageUnitItem.ROWS * 18;
	/** The last storable item under the mouse (the preview keeps showing it). */
	private ItemStack shown = ItemStack.EMPTY;
	private boolean dragging;

	public StorageUnitScreen(StorageUnitItem.Menu menu, Inventory inv, Component title) {
		super(menu, inv, title, 190, 222);
	}

	@Override
	protected void init() {
		super.init();
		int preview = width >= imageWidth + 120 ? Math.min(180, width - imageWidth - 20) : 0; // room for the preview on the right
		leftPos = (width - imageWidth - preview) / 2;
		inventoryLabelY = 18 + StorageUnitItem.ROWS * 18 + 3;
		if (topPos + imageHeight <= height - 28)
			addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 26, 200, 20).build());
	}

	private int maxRow() {
		return StorageUnitItem.TOTAL_ROWS - StorageUnitItem.ROWS;
	}

	private void scrollTo(int row) {
		row = Math.max(0, Math.min(maxRow(), row));
		if (row != menu.row() && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, row);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		scrollTo(menu.row() - (int) Math.signum(sy));
		return true;
	}

	private boolean onBar(double mx, double my) {
		return mx >= leftPos + BAR_X && mx < leftPos + BAR_X + BAR_W && my >= topPos + BAR_Y && my < topPos + BAR_Y + BAR_H;
	}

	private void dragTo(double my) {
		double f = (my - topPos - BAR_Y) / BAR_H;
		scrollTo((int) Math.round(f * maxRow()));
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e, boolean doubleClick) {
		if (onBar(e.x(), e.y())) {
			dragging = true;
			dragTo(e.y());
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent e, double dx, double dy) {
		if (dragging) {
			dragTo(e.y());
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent e) {
		dragging = false;
		return super.mouseReleased(e);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, BG);
		g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, PANEL);
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot s = menu.slots.get(i);
			boolean closed = i < StorageUnitItem.PAGE && menu.row() * StorageUnitItem.COLS + i >= StorageUnitItem.CAPACITY; // past 1,000
			g.fill(leftPos + s.x - 1, topPos + s.y - 1, leftPos + s.x + 17, topPos + s.y + 17, closed ? 0xFF2A1A1A : SLOT);
		}
		// the scrollbar: where the six rows are among all of them
		g.fill(leftPos + BAR_X, topPos + BAR_Y, leftPos + BAR_X + BAR_W, topPos + BAR_Y + BAR_H, SLOT);
		int knob = Math.max(8, BAR_H * StorageUnitItem.ROWS / StorageUnitItem.TOTAL_ROWS);
		int ky = topPos + BAR_Y + (int) ((BAR_H - knob) * (menu.row() / (double) Math.max(1, maxRow())));
		g.fill(leftPos + BAR_X + 1, ky, leftPos + BAR_X + BAR_W - 1, ky + knob, dragging ? ACCENT : 0xFF808890);
		preview(g);
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor g, int mx, int my) {
		g.text(font, title, 8, 6, ACCENT, false);
		String n = menu.stored() + " / " + StorageUnitItem.CAPACITY;
		g.text(font, n, imageWidth - 8 - font.width(n), 6, 0xFFB0B0B0, false);
		g.text(font, playerInventoryTitle, 8, inventoryLabelY, 0xFFB0B0B0, false);
	}

	/** Right of the panel: the item last under the mouse, big. */
	private void preview(GuiGraphicsExtractor g) {
		int px = leftPos + imageWidth + 10, pw = width - px - 10, top = topPos;
		if (pw < 100 || shown.isEmpty()) return;
		g.fill(px, top, px + pw, top + imageHeight, PANEL);
		int bx0 = px + 4, by0 = top + 8, bx1 = px + pw - 4, by1 = top + 92;
		float yaw = (float) Math.sin(System.nanoTime() / 1e9 * 0.6) * 25;
		ItemStack s = shown;
		if (s.getItem() instanceof SkinWrapItem w) {
			SkinWraps.Wrap wrap = SkinWraps.get(SkinWrapItem.skin(s));
			String weapon = wrap == null ? null : w.knife() ? "weapon_knife_karambit" : wrap.kits().isEmpty() ? null : wrap.kits().keySet().iterator().next();
			String kit = weapon == null ? null : SkinWraps.kitFor(wrap.id(), weapon);
			if (kit != null) LoadoutPreview.drawTurned(g, bx0, by0, bx1, by1, weapon, kit, SkinWrapItem.finish(s), yaw, Float.NaN);
		} else if (s.getItem() instanceof GunItem gun && !(gun instanceof dev.strafemc.weapon.GrenadeItem)) {
			LoadoutPreview.draw(g, bx0, by0, bx1, by1, gun.weaponClass);
		} else {
			net.minecraft.resources.Identifier pic = null;
			if (s.getItem() instanceof CaseItem) {
				Cases.Case c = Cases.get(CaseItem.caseId(s));
				if (c != null) pic = CaseImages.image("panorama/images/" + c.image() + "_png");
			} else if (s.getItem() instanceof dev.strafemc.weapon.KeyItem) {
				Cases.Key k = Cases.key(dev.strafemc.weapon.KeyItem.keyId(s));
				if (k != null && !k.image().isEmpty()) pic = CaseImages.image("panorama/images/" + k.image() + "_png");
			}
			int ph = by1 - by0, pwid = Math.min(bx1 - bx0, ph * 4 / 3), cx = (bx0 + bx1) / 2;
			if (pic != null) g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, pic, cx - pwid / 2, by0, 0, 0, pwid, pwid * 3 / 4, pwid, pwid * 3 / 4);
			else { // gloves and the rest: the item itself, large
				var pose = g.pose();
				pose.pushMatrix();
				pose.translate(cx - 32, (by0 + by1) / 2f - 32);
				pose.scale(4, 4);
				g.item(s, 0, 0);
				pose.popMatrix();
			}
		}
		int y = by1 + 6;
		for (Component line : getTooltipFromItem(minecraft, s))
			for (var l : font.split(line, pw - 16)) {
				if (y > top + imageHeight - 10) return;
				g.text(font, l, px + 8, y, 0xFFFFFFFF, false);
				y += 10;
			}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		for (Slot s : menu.slots) // the storable item under the mouse becomes the preview
			if (mx >= leftPos + s.x - 1 && mx < leftPos + s.x + 17 && my >= topPos + s.y - 1 && my < topPos + s.y + 17 && StorageUnitItem.storable(s.getItem()))
				shown = s.getItem();
		super.extractRenderState(g, mx, my, delta);
		extractTooltip(g, mx, my);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
