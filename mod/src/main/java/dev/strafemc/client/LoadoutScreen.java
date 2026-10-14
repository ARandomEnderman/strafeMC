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
 * The loadout (key U): a full-screen page with a tile per loadout slot (gloves, agent, knife, each gun), your inventory
 * below, and a preview of the slot under the mouse on the right: the worn gloves in 3D, posed like in first person.
 * Drag gloves and skin wraps into their slots; shift-click moves them in and out. FLAGGED: wrap slots preview the item,
 * not yet the skinned 3D weapon (comes with the weapon skin renderer).
 */
public final class LoadoutScreen extends AbstractContainerScreen<Loadout.Menu> {
	private static final int BG = 0xF0101317, PANEL = 0xC01C2127, TILE = 0xE52B2B2B, TILE_EMPTY = 0x80202020, SLOT = 0xFF0E0F11, ACCENT = 0xFFEABE54;
	private int previewSlot = 2; // the knife until the mouse picks another

	public LoadoutScreen(Loadout.Menu menu, Inventory inv, Component title) {
		super(menu, inv, title, Loadout.Menu.WIDTH, Loadout.Menu.HEIGHT);
	}

	@Override
	protected void init() {
		super.init();
		// leave room on the right for the preview when the window is wide enough
		int preview = width >= imageWidth + 200 ? 180 : 0;
		leftPos = (width - imageWidth - preview) / 2;
		titleLabelX = Loadout.Menu.TILES_X;
		titleLabelY = 8;
		inventoryLabelX = Loadout.Menu.INV_X;
		inventoryLabelY = Loadout.Menu.INV_Y - 11;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, BG);
		g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, PANEL);
		var defs = Loadout.defs();
		for (int i = 0; i < defs.size(); i++) {
			Loadout.Def d = defs.get(i);
			int x = leftPos + Loadout.Menu.TILES_X + (i % 2) * (Loadout.Menu.TILE_W + 8), y = topPos + Loadout.Menu.TILES_Y + (i / 2) * (Loadout.Menu.TILE_H + 4);
			ItemStack in = menu.getSlot(i).getItem();
			boolean usable = d.kind() != Loadout.Kind.AGENT;
			g.fill(x, y, x + Loadout.Menu.TILE_W, y + Loadout.Menu.TILE_H, usable ? TILE : TILE_EMPTY);
			if (i == previewSlot) g.outline(x, y, Loadout.Menu.TILE_W, Loadout.Menu.TILE_H, ACCENT);
			g.fill(x + 3, y + 3, x + 21, y + 21, SLOT);
			g.text(font, d.label(), x + 26, y + 3, usable ? 0xFFFFFFFF : 0xFF707070, false);
			String sub = !usable ? "Coming with agents" : in.isEmpty() ? "Empty" : in.getHoverName().getString();
			g.text(font, font.plainSubstrByWidth(sub, Loadout.Menu.TILE_W - 30), x + 26, y + 13, in.isEmpty() ? 0xFF808080 : 0xFFB8B8B8, false);
		}
		for (int i = defs.size(); i < menu.slots.size(); i++) { // inventory slot backgrounds
			Slot s = menu.slots.get(i);
			g.fill(leftPos + s.x - 1, topPos + s.y - 1, leftPos + s.x + 17, topPos + s.y + 17, SLOT);
		}
		preview(g);
		g.text(font, "Drag gloves and skin wraps into a slot, or shift-click them. Every gun of that type wears the wrap.", 8, height - 12, 0xFF808080, false);
	}

	/** Right of the panel: the slot under the mouse (or last looked at), big. */
	private void preview(GuiGraphicsExtractor g) {
		int px = leftPos + imageWidth + 10, pw = width - px - 10;
		if (pw < 150) return;
		var defs = Loadout.defs();
		if (previewSlot >= defs.size()) return;
		Loadout.Def d = defs.get(previewSlot);
		ItemStack s = menu.getSlot(previewSlot).getItem();
		int top = topPos;
		g.fill(px, top, px + pw, top + imageHeight, PANEL);
		g.text(font, d.label().toUpperCase(), px + 8, top + 8, ACCENT, false);
		if (d.kind() == Loadout.Kind.GLOVES) LoadoutPreview.draw(g, px + 4, top + 20, px + pw - 4, top + 104); // the worn gloves in 3D
		else {
			var pose = g.pose();
			pose.pushMatrix();
			pose.translate(px + pw / 2f - 32, top + 34);
			pose.scale(4, 4);
			if (!s.isEmpty()) g.item(s, 0, 0);
			pose.popMatrix();
		}
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

	@Override
	protected void extractLabels(GuiGraphicsExtractor g, int mx, int my) {
		g.text(font, "LOADOUT", titleLabelX, titleLabelY, ACCENT, false);
		g.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xFFB0B0B0, false);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		// the whole tile (the box with the slot and its text) picks what the preview shows
		var defs = Loadout.defs();
		for (int i = 0; i < defs.size(); i++) {
			int x = leftPos + Loadout.Menu.TILES_X + (i % 2) * (Loadout.Menu.TILE_W + 8), y = topPos + Loadout.Menu.TILES_Y + (i / 2) * (Loadout.Menu.TILE_H + 4);
			if (mx >= x && mx < x + Loadout.Menu.TILE_W && my >= y && my < y + Loadout.Menu.TILE_H) previewSlot = i;
		}
		super.extractRenderState(g, mx, my, delta);
		extractTooltip(g, mx, my);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
