package dev.strafemc.client;

import dev.strafemc.weapon.GloveItem;
import dev.strafemc.weapon.Loadout;
import dev.strafemc.weapon.SkinWrapItem;
import dev.strafemc.weapon.SkinWraps;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The loadout (key U, or the strafeMC menu): a full-screen page with a tile per loadout slot (gloves, agent, knife, each gun), your inventory
 * below, and a preview of the slot under the mouse on the right: the worn gloves in 3D, posed like in first person.
 * Drag gloves and skin wraps into their slots; shift-click moves them in and out. FLAGGED: wrap slots preview the item,
 * not yet the skinned 3D weapon (comes with the weapon skin renderer).
 */
public final class LoadoutScreen extends AbstractContainerScreen<Loadout.Menu> {
	private static final int BG = 0xF0101317, PANEL = 0xC01C2127, TILE = 0xE52B2B2B, TILE_EMPTY = 0x80202020, SLOT = 0xFF0E0F11, ACCENT = 0xFFEABE54;
	private int previewSlot = 0; // the gloves until the mouse picks another
	/** A wrap in the inventory under the mouse (previewed unequipped), or empty. */
	private ItemStack hoveredWrap = ItemStack.EMPTY;

	public LoadoutScreen(Loadout.Menu menu, Inventory inv, Component title) {
		super(menu, inv, title, Loadout.Menu.WIDTH, Loadout.Menu.HEIGHT);
	}

	@Override
	protected void init() {
		super.init();
		// leave room on the right for the preview: up to 180 wide, narrower at big GUI scales (it fits down to 100)
		int preview = width >= imageWidth + 120 ? Math.min(180, width - imageWidth - 20) : 0;
		leftPos = (width - imageWidth - preview) / 2;
		titleLabelX = Loadout.Menu.TILES_X;
		titleLabelY = 8;
		inventoryLabelX = Loadout.Menu.INV_X;
		inventoryLabelY = Loadout.Menu.INV_Y - 11;
		if (topPos + imageHeight <= height - 28)
			addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 26, 200, 20).build());
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, BG);
		g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, PANEL);
		var defs = Loadout.defs();
		for (int i = 0; i < Math.min(3, defs.size()); i++) { // gloves, agent, knife: a tile with text
			Loadout.Def d = defs.get(i);
			int x = leftPos + Loadout.Menu.TILES_X + i * (Loadout.Menu.TILE_W + 8), y = topPos + Loadout.Menu.TILES_Y;
			ItemStack in = menu.getSlot(i).getItem();
			boolean usable = d.kind() != Loadout.Kind.AGENT;
			g.fill(x, y, x + Loadout.Menu.TILE_W, y + Loadout.Menu.TILE_H, usable ? TILE : TILE_EMPTY);
			if (i == previewSlot) g.outline(x, y, Loadout.Menu.TILE_W, Loadout.Menu.TILE_H, ACCENT);
			g.fill(x + 3, y + 3, x + 21, y + 21, SLOT);
			g.text(font, d.label(), x + 26, y + 3, usable ? 0xFFFFFFFF : 0xFF707070, false);
			String sub = !usable ? "Coming later" : in.isEmpty() ? "Empty" : in.getHoverName().getString();
			g.text(font, font.plainSubstrByWidth(sub, Loadout.Menu.TILE_W - 30), x + 26, y + 13, in.isEmpty() ? 0xFF808080 : 0xFFB8B8B8, false);
		}
		g.text(font, "GUN WRAPS", leftPos + Loadout.Menu.GUNS_X, topPos + Loadout.Menu.GUNS_Y - 11, 0xFFB0B0B0, false);
		for (int i = 3; i < defs.size(); i++) { // the guns: a slot each, the gun's picture faded in while it's empty
			int x = leftPos + Loadout.Menu.slotX(i), y = topPos + Loadout.Menu.slotY(i);
			g.fill(x - 1, y - 1, x + 17, y + 17, i == previewSlot ? ACCENT : SLOT);
			if (i == previewSlot) g.fill(x, y, x + 16, y + 16, SLOT);
			if (menu.getSlot(i).getItem().isEmpty()) {
				var gun = dev.strafemc.weapon.WeaponItems.GUNS.get(defs.get(i).weapon());
				if (gun != null) {
					g.item(new ItemStack(gun), x, y);
					g.fill(x, y, x + 16, y + 16, 0xA0101317); // faded: nothing in the slot yet
				}
			}
		}
		for (int i = defs.size(); i < menu.slots.size(); i++) { // inventory slot backgrounds
			Slot s = menu.slots.get(i);
			g.fill(leftPos + s.x - 1, topPos + s.y - 1, leftPos + s.x + 17, topPos + s.y + 17, SLOT);
		}
		preview(g);
	}

	/** Right of the panel: the slot under the mouse (or last looked at), big. */
	private void preview(GuiGraphicsExtractor g) {
		int px = leftPos + imageWidth + 10, pw = width - px - 10;
		if (pw < 100) return;
		var defs = Loadout.defs();
		if (!hoveredWrap.isEmpty() && hoverWrap(g, px, pw)) return;
		if (previewSlot >= defs.size()) return;
		Loadout.Def d = defs.get(previewSlot);
		ItemStack s = menu.getSlot(previewSlot).getItem();
		int top = topPos;
		g.fill(px, top, px + pw, top + imageHeight, PANEL);
		g.text(font, d.label().toUpperCase(), px + 8, top + 8, ACCENT, false);
		// 3D: the worn gloves; the knife you'd use (in the knife wrap); the gun (in its wrap)
		if (d.kind() == Loadout.Kind.GLOVES) LoadoutPreview.draw(g, px + 4, top + 20, px + pw - 4, top + 104, null);
		else if (d.kind() == Loadout.Kind.KNIFE) LoadoutPreview.draw(g, px + 4, top + 20, px + pw - 4, top + 104, previewKnife());
		else if (d.weapon() != null) LoadoutPreview.draw(g, px + 4, top + 20, px + pw - 4, top + 104, d.weapon());
		int y = top + 110;
		if (s.isEmpty()) {
			g.text(font, d.kind() == Loadout.Kind.GLOVES ? "Your side's default gloves" : d.kind() == Loadout.Kind.AGENT ? "Agents aren't in strafeMC yet"
					: "No wrap: the plain weapon", px + 8, y, 0xFF909090, false);
			return;
		}
		for (var line : font.split(s.getHoverName(), pw - 16)) {
			g.text(font, line, px + 8, y, 0xFFFFFFFF, false);
			y += 10;
		}
		if (s.getItem() instanceof SkinWrapItem w) {
			SkinWraps.Wrap wrap = SkinWraps.get(SkinWrapItem.skin(s));
			String on = w.knife() ? "On every knife" : wrap == null ? "" : "On every " + d.label();
			g.text(font, on, px + 8, y + 4, 0xFFB0B0B0, false);
		} else if (s.getItem() instanceof GloveItem) g.text(font, "Factory New", px + 8, y + 4, 0xFFB0B0B0, false);
	}

	/**
	 * A wrap under the mouse in the inventory: previewed on its gun (or the knife) without equipping it. False when it
	 * has nothing to show on.
	 */
	private boolean hoverWrap(GuiGraphicsExtractor g, int px, int pw) {
		SkinWraps.Wrap wrap = SkinWraps.get(SkinWrapItem.skin(hoveredWrap));
		if (wrap == null) return false;
		String weapon = wrap.knife() ? previewKnife() : wrap.kits().isEmpty() ? null : wrap.kits().keySet().iterator().next();
		String kit = weapon == null ? null : SkinWraps.kitFor(wrap.id(), weapon);
		if (kit == null) return false;
		int top = topPos;
		g.fill(px, top, px + pw, top + imageHeight, PANEL);
		g.text(font, (wrap.knife() ? "KNIFE" : dev.strafemc.weapon.SkinWraps.WEAPON_NAMES.getOrDefault(weapon, weapon)).toUpperCase(), px + 8, top + 8, ACCENT, false);
		float yaw = (float) Math.sin(System.nanoTime() / 1e9 * 0.6) * 25;
		LoadoutPreview.drawTurned(g, px + 4, top + 20, px + pw - 4, top + 104, weapon, kit, SkinWrapItem.finish(hoveredWrap), yaw, Float.NaN);
		int y = top + 110;
		for (var line : font.split(hoveredWrap.getHoverName(), pw - 16)) {
			g.text(font, line, px + 8, y, 0xFFFFFFFF, false);
			y += 10;
		}
		g.text(font, "Not equipped", px + 8, y + 4, 0xFFB0B0B0, false);
		return true;
	}

	/** The knife the preview shows: the first in the hotbar (left to right), else in the inventory, else the Karambit. */
	private String previewKnife() {
		var p = minecraft.player;
		if (p != null) {
			var inv = p.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) // 0-8 are the hotbar
				if (inv.getItem(i).getItem() instanceof dev.strafemc.weapon.KnifeItem k) return k.weaponClass;
		}
		return "weapon_knife_karambit";
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor g, int mx, int my) {
		g.text(font, "LOADOUT", titleLabelX, titleLabelY, ACCENT, false);
		g.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xFFB0B0B0, false);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		// the whole tile (the box with the slot and its text) or the gun's slot picks what the preview shows
		var defs = Loadout.defs();
		for (int i = 0; i < defs.size(); i++) {
			int x = i < 3 ? leftPos + Loadout.Menu.TILES_X + i * (Loadout.Menu.TILE_W + 8) : leftPos + Loadout.Menu.slotX(i) - 1;
			int y = i < 3 ? topPos + Loadout.Menu.TILES_Y : topPos + Loadout.Menu.slotY(i) - 1;
			int w = i < 3 ? Loadout.Menu.TILE_W : 18, h = i < 3 ? Loadout.Menu.TILE_H : 18;
			if (mx >= x && mx < x + w && my >= y && my < y + h) {
				previewSlot = i;
				hoveredWrap = ItemStack.EMPTY;
			}
		}
		// a wrap in the inventory under the mouse previews instead, while it's hovered
		ItemStack inv = ItemStack.EMPTY;
		for (int i = defs.size(); i < menu.slots.size(); i++) {
			Slot sl = menu.slots.get(i);
			if (mx >= leftPos + sl.x - 1 && mx < leftPos + sl.x + 17 && my >= topPos + sl.y - 1 && my < topPos + sl.y + 17 && sl.getItem().getItem() instanceof SkinWrapItem)
				inv = sl.getItem();
		}
		hoveredWrap = inv;
		super.extractRenderState(g, mx, my, delta);
		extractTooltip(g, mx, my);
	}

	/** Opened from the strafeMC menu: closing goes back to it, like its other screens. */
	@Override
	public void onClose() {
		super.onClose();
		if (LoadoutKey.fromMenu) {
			LoadoutKey.fromMenu = false;
			minecraft.setScreen(new QuickMenuScreen(false));
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
