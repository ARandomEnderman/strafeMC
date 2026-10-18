package dev.strafemc.client;

import dev.strafemc.weapon.CaseItem;
import dev.strafemc.weapon.Cases;
import dev.strafemc.weapon.GunItem;
import dev.strafemc.weapon.SkinWrapItem;
import dev.strafemc.weapon.SkinWraps;
import dev.strafemc.weapon.StorageUnitItem;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A Storage Unit open (click its name to rename it): six rows of its 1,000 places (scroll, or drag the bar), the count, your inventory, and on the
 * right a preview of the item under the mouse like the loadout's (a wrap on its gun, a gun or knife, a case's picture).
 */
public final class StorageUnitScreen extends AbstractContainerScreen<StorageUnitItem.Menu> {
	private static final int BG = 0xF0101317, PANEL = 0xC01C2127, SLOT = 0xFF0E0F11, ACCENT = 0xFFEABE54;
	private static final int BAR_X = 176, BAR_Y = 18, BAR_W = 8, BAR_H = StorageUnitItem.ROWS * 18;
	/** The last storable item under the mouse (the preview keeps showing it). */
	private ItemStack shown = ItemStack.EMPTY;
	private boolean dragging;
	/** The title, clicked: a box to rename the unit (Enter saves, Esc cancels; empty clears the name). */
	private net.minecraft.client.gui.components.EditBox name;
	private Component shownTitle;

	public StorageUnitScreen(StorageUnitItem.Menu menu, Inventory inv, Component title) {
		super(menu, inv, title, 190, 222);
	}

	@Override
	protected void init() {
		super.init();
		int preview = width >= imageWidth + 120 ? Math.min(180, width - imageWidth - 20) : 0; // room for the preview on the right
		leftPos = (width - imageWidth - preview) / 2;
		inventoryLabelY = 18 + StorageUnitItem.ROWS * 18 + 3;
		if (shownTitle == null) shownTitle = title;
		name = addRenderableWidget(new net.minecraft.client.gui.components.EditBox(font, leftPos + 6, topPos + 3, 120, 12, Component.literal("Name")));
		name.setMaxLength(50);
		name.visible = false;
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

	private boolean onTitle(double mx, double my) {
		return mx >= leftPos + 6 && mx < leftPos + 10 + font.width(shownTitle) && my >= topPos + 4 && my < topPos + 16;
	}

	private void rename(boolean save) {
		if (save) {
			String n = name.getValue().strip();
			if (ClientPlayNetworking.canSend(dev.strafemc.net.StrafePayloads.Action.TYPE))
				ClientPlayNetworking.send(new dev.strafemc.net.StrafePayloads.Action("storage_rename", n));
			shownTitle = n.isEmpty() ? Component.translatable("item.strafemc.storage_unit") : Component.literal(n);
		}
		name.visible = false;
		name.setFocused(false);
		setFocused(null);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
		if (name != null && name.visible) { // typing a name: Enter saves, Esc cancels, everything else goes to the box
			if (e.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || e.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) rename(true);
			else if (e.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) rename(false);
			else name.keyPressed(e);
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e, boolean doubleClick) {
		if (name != null && name.visible && !name.isMouseOver(e.x(), e.y())) rename(true); // clicking away saves
		if (name != null && !name.visible && onTitle(e.x(), e.y())) {
			name.setValue(shownTitle.getString());
			name.visible = true;
			setFocused(name);
			name.setFocused(true);
			return true;
		}
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
		if (!name.visible) { // the name; underlined under the mouse: click to rename
			boolean over = onTitle(mx, my);
			g.text(font, over ? shownTitle.copy().withStyle(net.minecraft.ChatFormatting.UNDERLINE) : shownTitle, 8, 6, ACCENT, false);
		}
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
			CaseImages.draw(g, s, bx0, by0, bx1 - bx0, by1 - by0); // cases, keys, gloves, stickers, graffiti; the rest as a big icon
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
