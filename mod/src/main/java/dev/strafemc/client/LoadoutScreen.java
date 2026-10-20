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
 * The loadout (key U, or the strafeMC menu): a full-screen page with tiles for the gloves, the T and CT agents (they open
 * the agent menu) and the knife, a slot per gun (a row per group, as in CS2's buy menu), your inventory
 * below, and a preview of the slot under the mouse on the right: the worn gloves in 3D, posed like in first person.
 * Drag gloves and skin wraps into their slots; shift-click moves them in and out. FLAGGED: wrap slots preview the item,
 * not yet the skinned 3D weapon (comes with the weapon skin renderer).
 */
public final class LoadoutScreen extends AbstractContainerScreen<Loadout.Menu> implements dev.strafemc.client.ui.UiFit.Sized {
	private static final int BG = 0xF0101317, PANEL = 0xC01C2127, TILE = 0xE52B2B2B, TILE_EMPTY = 0x80202020, SLOT = 0xFF0E0F11, ACCENT = 0xFFEABE54;
	private int previewSlot = 0; // the gloves until the mouse picks another
	/** The agent tile last looked at: "t" or "ct". */
	private String agentSide = "t";
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
		for (int col = 0; col < 4; col++) { // gloves, T agent, CT agent, knife: a tile with a slot and text
			int x = leftPos + Loadout.Menu.tileX(col), y = topPos + Loadout.Menu.tileY(col), w = Loadout.Menu.TILE_W;
			boolean agent = col == 1 || col == 2;
			int i = col == 0 ? 0 : col == 3 ? 2 : 1;
			String head, sub;
			Agents.Agent a = agent ? agent(col == 1 ? "t" : "ct") : null;
			boolean empty;
			if (agent) {
				head = col == 1 ? "T Agent" : "CT Agent";
				sub = a == null ? "Default" : a.name();
				empty = a == null;
			} else {
				ItemStack in = menu.getSlot(i).getItem();
				head = defs.get(i).label();
				sub = in.isEmpty() ? "Empty" : in.getHoverName().getString();
				empty = in.isEmpty();
			}
			boolean sel = agent ? previewSlot == 1 && agentSide.equals(col == 1 ? "t" : "ct") : i == previewSlot;
			g.fill(x, y, x + w, y + Loadout.Menu.TILE_H, TILE);
			if (sel) g.outline(x, y, w, Loadout.Menu.TILE_H, ACCENT);
			g.fill(x + 3, y + 3, x + 21, y + 21, SLOT);
			if (a != null) {
				var pic = CaseImages.image("panorama/images/" + a.image() + "_png");
				if (pic != null) g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, pic, x + 4, y + 6, 0, 0, 16, 12, 16, 12);
			}
			g.text(font, head, x + 25, y + 3, 0xFFFFFFFF, false);
			scrolling(g, sub, x + 25, y + 13, w - 28, empty ? 0xFF808080 : 0xFFB8B8B8);
		}
		g.text(font, "GUN WRAPS", leftPos + Loadout.Menu.GUNS_X, topPos + Loadout.Menu.GUNS_Y - 11, 0xFFB0B0B0, false);
		g.text(font, "GRAFFITI", leftPos + Loadout.Menu.TILES_X, topPos + Loadout.Menu.GUNS_Y - 11, 0xFFB0B0B0, false);
		for (int k = 0; k < 60; k++) { // the graffiti wheel's ring, as in CS2
			double a = k * Math.PI * 2 / 60;
			int x = leftPos + Loadout.Menu.RING_X + (int) Math.round(Math.cos(a) * Loadout.Menu.RING_R), y = topPos + Loadout.Menu.RING_Y + (int) Math.round(Math.sin(a) * Loadout.Menu.RING_R);
			g.fill(x, y, x + 1, y + 1, 0x50FFFFFF);
		}
		for (int i = 3; i < defs.size(); i++) { // the guns: a slot each, the gun's picture faded in while it's empty
			int x = leftPos + Loadout.Menu.slotX(i), y = topPos + Loadout.Menu.slotY(i);
			g.fill(x - 1, y - 1, x + 17, y + 17, i == previewSlot ? ACCENT : SLOT);
			if (i == previewSlot) g.fill(x, y, x + 16, y + 16, SLOT);
			if (menu.getSlot(i).getItem().isEmpty() && defs.get(i).weapon() != null) {
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
		if (d.kind() == Loadout.Kind.AGENT) {
			agentPreview(g, px, pw);
			return;
		}
		g.fill(px, top, px + pw, top + imageHeight, PANEL);
		g.text(font, d.label().toUpperCase(), px + 8, top + 8, ACCENT, false);
		// 3D: the worn gloves; the knife you'd use (in the knife wrap); the gun (in its wrap)
		if (d.kind() == Loadout.Kind.GLOVES) LoadoutPreview.draw(g, px + 4, top + 20, px + pw - 4, top + 104, null);
		else if (d.kind() == Loadout.Kind.KNIFE) LoadoutPreview.draw(g, px + 4, top + 20, px + pw - 4, top + 104, previewKnife());
		else if (d.weapon() != null) LoadoutPreview.draw(g, px + 4, top + 20, px + pw - 4, top + 104, d.weapon());
		else if (d.kind() == Loadout.Kind.SPRAY) CaseImages.draw(g, s, px + 4, top + 20, pw - 8, 84); // the graffiti itself
		int y = top + 110;
		if (s.isEmpty()) {
			String empty = d.kind() == Loadout.Kind.GLOVES ? "Your side's default gloves"
					: d.kind() == Loadout.Kind.SPRAY ? "Put an unsealed graffiti here; hold " + SprayKey.name() + " to spray" : "No wrap: the plain weapon";
			for (var line : font.split(Component.literal(empty), pw - 16)) {
				g.text(font, line, px + 8, y, 0xFF909090, false);
				y += 10;
			}
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
		} else if (s.getItem() instanceof GloveItem) g.text(font, dev.strafemc.weapon.Wear.exterior(GloveItem.wear(s)), px + 8, y + 4, 0xFFB0B0B0, false);
		else if (s.getItem() instanceof dev.strafemc.weapon.SprayItem)
			g.text(font, s.getOrDefault(dev.strafemc.weapon.WeaponItems.OPENED, dev.strafemc.weapon.SprayItem.USES) + " sprays left", px + 8, y + 4, 0xFFB0B0B0, false);
	}

	/** The agent worn on a side ("t" / "ct"), or null for the side's default. */
	private static Agents.Agent agent(String side) {
		return Agents.get(side.equals("t") ? dev.strafemc.config.StrafeConfig.agentT : dev.strafemc.config.StrafeConfig.agentCT);
	}

	/** The agent tile's side: CS2's picture of the agent worn there, and how to change it. */
	private void agentPreview(GuiGraphicsExtractor g, int px, int pw) {
		int top = topPos;
		g.fill(px, top, px + pw, top + imageHeight, PANEL);
		g.text(font, agentSide.equals("t") ? "T AGENT" : "CT AGENT", px + 8, top + 8, ACCENT, false);
		Agents.Agent a = agent(agentSide);
		int y = top + 110;
		if (a != null) {
			var pic = CaseImages.image("panorama/images/" + a.image() + "_png");
			int w = Math.min(pw - 8, 112);
			if (pic != null) g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, pic, px + (pw - w) / 2, top + 20, 0, 0, w, w * 3 / 4, w, w * 3 / 4);
			for (var line : font.split(Component.literal(a.name()), pw - 16)) {
				g.text(font, line, px + 8, y, 0xFFFFFFFF, false);
				y += 10;
			}
		} else {
			g.text(font, "The side's default", px + 8, y, 0xFF909090, false);
			y += 10;
		}
		for (var line : font.split(Component.literal("Click to choose an agent; your viewmodel wears their sleeves"), pw - 16)) {
			g.text(font, line, px + 8, y + 4, 0xFFB0B0B0, false);
			y += 10;
		}
	}

	/** Text that slides back and forth when it's wider than w, so all of it can be read. */
	private void scrolling(GuiGraphicsExtractor g, String s, int x, int y, int w, int color) {
		int tw = font.width(s);
		if (tw <= w) {
			g.text(font, s, x, y, color, false);
			return;
		}
		double over = tw - w, period = over / 20 + 1.5; // 20 px a second, a short pause at each end
		double ph = (System.nanoTime() / 1e9) % (2 * period), t = ph < period ? ph : 2 * period - ph;
		int off = (int) Math.round(Math.max(0, Math.min(over, (t - 0.75) * 20)));
		g.enableScissor(x, y - 1, x + w, y + 9);
		g.text(font, s, x - off, y, color, false);
		g.disableScissor();
	}

	/** Equipped wraps show CS2's picture of their gun in the wrap (only here); everything else as items. */
	@Override
	protected void extractSlot(GuiGraphicsExtractor g, Slot slot, int mx, int my) {
		var defs = Loadout.defs();
		int i = menu.slots.indexOf(slot);
		if (i >= 0 && i < defs.size() && slot.getItem().getItem() instanceof SkinWrapItem) { // equipped only; the inventory keeps item icons
			Loadout.Def d = defs.get(i);
			String weapon = d.kind() == Loadout.Kind.KNIFE ? previewKnife() : d.weapon();
			String kit = weapon == null ? null : SkinWraps.kitFor(SkinWrapItem.skin(slot.getItem()), weapon);
			// the wrap on this slot's weapon as it is (float, pattern, stickers); CS2's stock picture until that's made
			var pic = kit == null ? null : dev.strafemc.client.viewmodel.WrapPictures.get(weapon, kit, slot.getItem());
			if (pic == null && kit != null) pic = CaseImages.get(new dev.strafemc.weapon.Cases.Entry(kit, weapon));
			if (pic != null) {
				g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, pic, slot.x, slot.y + 2, 0, 0, 16, 12, 16, 12);
				return;
			}
		}
		super.extractSlot(g, slot, mx, my);
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
		for (int col = 0; col < 4; col++) {
			int x = leftPos + Loadout.Menu.tileX(col), y = topPos + Loadout.Menu.tileY(col);
			if (mx >= x && mx < x + Loadout.Menu.TILE_W && my >= y && my < y + Loadout.Menu.TILE_H) {
				previewSlot = col == 0 ? 0 : col == 3 ? 2 : 1;
				if (col == 1 || col == 2) agentSide = col == 1 ? "t" : "ct";
				hoveredWrap = ItemStack.EMPTY;
			}
		}
		for (int i = 3; i < defs.size(); i++) {
			int x = leftPos + Loadout.Menu.slotX(i) - 1, y = topPos + Loadout.Menu.slotY(i) - 1;
			if (mx >= x && mx < x + 18 && my >= y && my < y + 18) previewSlot = i;
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

	/** An agent tile opens that side's agents; the loadout comes back when that's closed. */
	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e, boolean doubleClick) {
		for (int col = 1; col <= 2; col++) {
			int x = leftPos + Loadout.Menu.tileX(col), y = topPos + Loadout.Menu.tileY(col);
			if (e.x() >= x && e.x() < x + Loadout.Menu.TILE_W && e.y() >= y && e.y() < y + Loadout.Menu.TILE_H && menu.getCarried().isEmpty()) {
				String side = col == 1 ? "t" : "ct";
				minecraft.player.connection.send(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(menu.containerId));
				minecraft.player.containerMenu = minecraft.player.inventoryMenu;
				minecraft.setScreen(new AgentScreen(side, LoadoutKey::open));
				return true;
			}
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
		if (LoadoutKey.closesAll(this, e)) return true; // the strafeMC menu key closes it all
		return super.keyPressed(e);
	}

	/** Opened from the strafeMC menu: closing goes back to it, like its other screens. */
	@Override
	public void onClose() {
		if (LoadoutKey.fromMenu && minecraft.player != null) {
			// straight back to the menu: tell the server it's closed without dropping to the game first (that grabs and
			// re-centres the mouse)
			LoadoutKey.fromMenu = false;
			minecraft.player.connection.send(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(menu.containerId));
			minecraft.player.containerMenu = minecraft.player.inventoryMenu;
			minecraft.setScreen(new QuickMenuScreen(false));
			return;
		}
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
