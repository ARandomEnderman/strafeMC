package dev.strafemc.client;

import dev.strafemc.weapon.C4Item;
import dev.strafemc.weapon.GrenadeItem;
import dev.strafemc.weapon.GunItem;
import dev.strafemc.weapon.KnifeItem;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * CS2's weapon selection (panorama hudweaponselection) in place of Minecraft's hotbar on a game mode server: a column
 * at the bottom right, one row per gear slot, top to bottom (1 primary, 2 pistol, 3 knife, 4 grenades side by side, 5 bomb) with its
 * key number, rows 300 px wide 6 px in from the edge, above the always-on strip (84 px); icons 61.5 px tall at 0.7 and
 * 80% opacity in the team's colour, the selected one at 0.9 and brighter with its name (16 px) under it. Keys 1-5 pick
 * the slots as in CS2 (4 again: the next grenade). FLAGGED ours: shown 3 s after a change then faded over 0.5 s (CS2
 * decides that in code), icons tinted plainly by the side's colour.
 */
public final class WeaponSelectHud {
	private static final int CT = 0xFF6F9CE6, T = 0xFFEABE54;
	private static long changedAt;
	private static int lastSelected = -1, lastHash;

	private WeaponSelectHud() {}

	/** One weapon in the list: its inventory slot, icon (deathnotice icon name) and stack. */
	private record Item(int slot, String icon, ItemStack stack) {}

	/** The inventory sorted into CS2's gear slots 1-5 (index 0-4). */
	private static List<List<Item>> rows(LocalPlayer p) {
		List<List<Item>> rows = new ArrayList<>();
		for (int i = 0; i < 5; i++) rows.add(new ArrayList<>());
		var inv = p.getInventory();
		for (int i = 0; i < 36; i++) { // the hotbar and the main inventory (not armour or the offhand)
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || !(s.getItem() instanceof GunItem g)) continue;
			int row;
			if (g instanceof C4Item) row = 4;
			else if (g instanceof GrenadeItem) row = 3;
			else if (g instanceof KnifeItem || g.weaponClass.equals("weapon_taser")) row = 2;
			else {
				var e = dev.strafemc.weapon.WeaponTable.get(g.weaponClass);
				row = e != null && e.slot() != null && e.slot().startsWith("secondary") ? 1 : 0;
			}
			var e = dev.strafemc.weapon.WeaponTable.get(g.weaponClass);
			String icon = g instanceof C4Item ? "c4" : e != null ? e.shortName() : g.weaponClass.replace("weapon_", "");
			rows.get(row).add(new Item(i, icon, s));
		}
		// slot 3: the knife first, the Zeus on the next press (CS2)
		rows.get(2).sort(java.util.Comparator.comparingInt(it -> it.stack().getItem() instanceof KnifeItem ? 0 : 1));
		return rows;
	}

	public static void register() {
		HudElementRegistry.replaceElement(VanillaHudElements.HOTBAR, old -> (g, delta) -> {
			if (ModeServerClient.on()) draw(g);
			else old.extractRenderState(g, delta);
		});
	}

	private static void draw(net.minecraft.client.gui.GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null || p.isSpectator() || mc.options.hideGui) return;
		var rows = rows(p);
		int sel = p.getInventory().getSelectedSlot();
		int hash = 0;
		for (var r : rows) for (Item it : r) hash = hash * 31 + it.slot * 7 + it.stack.getCount() + it.stack.getItem().hashCode();
		long now = System.currentTimeMillis();
		if (sel != lastSelected || hash != lastHash) changedAt = now;
		lastSelected = sel;
		lastHash = hash;
		float alpha = now - changedAt < 3000 ? 1 : Math.max(0, 1 - (now - changedAt - 3000) / 500f);
		if (alpha <= 0) return;
		double u = g.guiHeight() / 1080.0;
		var font = mc.font;
		int team = dev.strafemc.weapon.CsTeam.isT(p) ? T : CT;
		double right = g.guiWidth() - 6 * u - 10 * u, y = g.guiHeight() - 84 * u;
		for (int r = 4; r >= 0; r--) { // stacked upward from the bottom: slot 5 lowest, slot 1 (the primary) on top, as CS2 lists them
			var row = rows.get(r);
			if (row.isEmpty()) continue;
			boolean rowSelected = row.stream().anyMatch(it -> it.slot == sel);
			double rowH = (rowSelected ? 0.9 : 0.7) * 61.5 * u + (rowSelected ? 22 * u : 4 * u);
			y -= rowH;
			double x = right - 16 * u; // the key number sits at the row's right
			text(g, font, String.valueOf(r + 1), g.guiWidth() - 6 * u - 4 * u, y + 4 * u, 16 * u, a(0xFFFFFFFF, alpha), true);
			for (Item it : row) { // grenades flow to the left
				boolean on = it.slot == sel;
				double ih = (on ? 0.9 : 0.7) * 61.5 * u;
				int col = on ? brighter(team) : team;
				int w = icon(g, it.icon, x, y, ih, a(col, alpha * (on ? 1 : 0.8f)));
				if (it.stack.getCount() > 1)
					text(g, font, String.valueOf(it.stack.getCount()), x, y + ih - 16 * u, 16 * u, a(on ? 0xFFFFFFFF : 0xFFA0A0A0, alpha), true);
				if (on) text(g, font, it.stack.getHoverName().getString(), right, y + ih + 2 * u, 16 * u, a(0xFFFFFFFF, alpha), true);
				x -= w + 8 * u;
			}
		}
	}

	/** An icon right-aligned at x, top at y, h tall; its width (0 when the picture is missing). */
	private static int icon(net.minecraft.client.gui.GuiGraphicsExtractor g, String name, double x, double y, double h, int color) {
		String rel = "gui/deathnotice/" + name + ".png";
		if (!dev.strafemc.client.assets.RuntimePack.hasTexture(rel)) return 0;
		int[] sz = BuyMenuScreen.iconSize(rel);
		if (sz[1] <= 0) return 0;
		int ih = (int) Math.round(h), iw = (int) Math.round((double) sz[0] / sz[1] * h);
		g.blit(RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath("strafemc", "textures/" + rel), (int) Math.round(x - iw), (int) Math.round(y), 0, 0,
				iw, ih, iw, ih, iw, ih, color);
		return iw;
	}

	/** Text right-aligned at x (if right) or from x, at size px. */
	private static void text(net.minecraft.client.gui.GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, String s, double x, double y, double size, int color,
			boolean right) {
		var pose = g.pose();
		float k = (float) (size / 9.0);
		pose.pushMatrix();
		pose.translate((float) (right ? x - font.width(s) * k : x), (float) y);
		pose.scale(k, k);
		g.text(font, s, 0, 0, color, true);
		pose.popMatrix();
	}

	private static int a(int argb, float alpha) {
		return (Math.round(((argb >>> 24) & 0xFF) * alpha) << 24) | (argb & 0xFFFFFF);
	}

	/** The selected icon: brightness 1.5 (CS2), capped at white. */
	private static int brighter(int argb) {
		int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * 1.5)), gr = Math.min(255, (int) (((argb >> 8) & 0xFF) * 1.5)), b = Math.min(255, (int) ((argb & 0xFF) * 1.5));
		return (argb & 0xFF000000) | r << 16 | gr << 8 | b;
	}

	/**
	 * Keys 1-5 as CS2's slots on a game mode server (before Minecraft reads them): 1 primary, 2 pistol, 3 knife, 4 the
	 * next grenade, 5 the bomb; 6-9 do nothing.
	 */
	static void keys(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null) return;
		var rows = rows(p);
		for (int k = 0; k < 9; k++)
			while (mc.options.keyHotbarSlots[k].consumeClick()) {
				if (k >= 5 || rows.get(k).isEmpty()) continue;
				var row = rows.get(k);
				int sel = p.getInventory().getSelectedSlot(), pick = row.get(0).slot;
				for (int i = 0; i < row.size(); i++) if (row.get(i).slot == sel) pick = row.get((i + 1) % row.size()).slot; // the next grenade
				if (Inventory.isHotbarSlot(pick)) p.getInventory().setSelectedSlot(pick);
				else if (mc.gameMode != null) // in the main inventory (carried in from survival): swapped into your hand, as a number key does in the inventory
					mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, pick, sel, net.minecraft.world.inventory.ContainerInput.SWAP, p);
			}
	}
}
