package dev.strafemc.client;

import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.Loadout;
import dev.strafemc.weapon.SkinWrapItem;
import dev.strafemc.weapon.StickerItem;
import dev.strafemc.weapon.StickerSlots;
import dev.strafemc.weapon.WeaponItems;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Applying a sticker (right-click one): your gun wraps on the left (the loadout's first, then your inventory's), the
 * picked one on the right with its sticker slots. Click an empty slot to put the sticker there; right-click a slot
 * with a sticker to scrape it off (it's gone, as in CS2).
 */
public final class StickerScreen extends Screen {
	private static final int GOLD = 0xFFEABE54, ROW = 22;
	/** The inventory slot of the sticker being applied. */
	private final int stickerSlot;
	private record Target(String ref, ItemStack stack) {}
	private final List<Target> targets = new ArrayList<>();
	private int picked = -1;
	private double scroll;

	private StickerScreen(int stickerSlot) {
		super(Component.literal("Apply Sticker"));
		this.stickerSlot = stickerSlot;
	}

	public static void open(int stickerSlot) {
		Minecraft.getInstance().setScreen(new StickerScreen(stickerSlot));
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 26, 200, 20).build());
	}

	private ItemStack sticker() {
		var p = minecraft.player;
		return p == null || stickerSlot < 0 || stickerSlot >= p.getInventory().getContainerSize() ? ItemStack.EMPTY : p.getInventory().getItem(stickerSlot);
	}

	/** Every gun wrap you have that takes stickers: the loadout's, then the inventory's. */
	private void refresh() {
		String keep = picked >= 0 && picked < targets.size() ? targets.get(picked).ref() : null;
		targets.clear();
		var p = minecraft.player;
		if (p == null) return;
		for (Loadout.Def d : Loadout.defs()) {
			if (d.kind() != Loadout.Kind.GUN) continue;
			ItemStack s = Loadout.get(p, d.id());
			if (StickerSlots.count(s) > 0) targets.add(new Target("L:" + d.id(), s));
		}
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++)
			if (inv.getItem(i).getItem() instanceof SkinWrapItem && StickerSlots.count(inv.getItem(i)) > 0) targets.add(new Target("I:" + i, inv.getItem(i)));
		picked = -1;
		for (int i = 0; i < targets.size(); i++) if (targets.get(i).ref().equals(keep)) picked = i;
		if (picked < 0 && !targets.isEmpty()) picked = 0;
	}

	private int listW() {
		return Math.min(240, width / 3);
	}

	/** Slot i's box in the right panel. */
	private int[] slotBox(int i, int n) {
		int px = 20 + listW() + 20, pw = width - px - 20, size = Math.max(32, Math.min(64, (pw - (n - 1) * 8) / Math.max(1, n)));
		int total = n * size + (n - 1) * 8, x = px + (pw - total) / 2 + i * (size + 8), y = Math.min(height - 70 - size, 40 + pw * 3 / 8 + 20);
		return new int[] {x, y, size};
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (mx < 20 + listW()) {
			scroll = Math.max(0, Math.min(Math.max(0, targets.size() * ROW - (height - 90)), scroll - sy * ROW));
			return true;
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) return true;
		double mx = e.x(), my = e.y();
		if (mx >= 20 && mx < 20 + listW() && my >= 40 && my < height - 50) {
			int i = (int) ((my - 40 + scroll) / ROW);
			if (i >= 0 && i < targets.size()) {
				picked = i;
				dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/submenu_leveloptions_select_01", 0.4f);
			}
			return true;
		}
		if (picked < 0 || picked >= targets.size()) return false;
		Target t = targets.get(picked);
		int n = StickerSlots.count(t.stack());
		String[] in = StickerSlots.get(t.stack());
		for (int i = 0; i < n; i++) {
			int[] b = slotBox(i, n);
			if (mx < b[0] || mx >= b[0] + b[2] || my < b[1] || my >= b[1] + b[2]) continue;
			if (e.button() == 1 && !in[i].isEmpty()) send("sticker_scrape", t.ref() + " " + i);
			else if (e.button() == 0 && in[i].isEmpty() && sticker().getItem() instanceof StickerItem) {
				send("sticker_apply", stickerSlot + " " + t.ref() + " " + i);
				dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/claim_gift_01", 0.3f);
			}
			return true;
		}
		return false;
	}

	private static void send(String cmd, String arg) {
		if (ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE)) ClientPlayNetworking.send(new StrafePayloads.Action(cmd, arg));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		refresh();
		g.fill(0, 0, width, height, StrafeSettingsScreen.BG);
		var font = minecraft.font;
		ItemStack st = sticker();
		String head = st.getItem() instanceof StickerItem ? "Apply " + st.getHoverName().getString() : "No sticker in that slot any more";
		g.text(font, head, width / 2 - font.width(head) / 2, 12, 0xFFFFFFFF, true);
		// left: the wraps
		int lw = listW();
		g.text(font, "Your gun wraps", 20, 28, 0xFFB0B0B0, false);
		g.enableScissor(20, 40, 20 + lw, height - 50);
		for (int i = 0; i < targets.size(); i++) {
			int y = 40 + i * ROW - (int) Math.round(scroll);
			Target t = targets.get(i);
			g.fill(20, y, 20 + lw, y + ROW - 2, i == picked ? 0xFF2A313A : 0xFF1C2127);
			if (i == picked) g.fill(20, y, 22, y + ROW - 2, GOLD);
			g.item(t.stack(), 24, y + 2);
			String name = t.stack().getHoverName().getString().replace(" Wrap", "") + (t.ref().startsWith("L:") ? "  (loadout)" : "");
			g.text(font, font.plainSubstrByWidth(name, lw - 30), 44, y + 6, 0xFFE0E0E0, false);
		}
		g.disableScissor();
		if (targets.isEmpty()) g.text(font, "No gun wraps (knives don't take stickers)", 24, 44, 0xFF808080, false);
		// right: the picked wrap and its slots
		if (picked >= 0 && picked < targets.size()) {
			Target t = targets.get(picked);
			int px = 20 + lw + 20, pw = width - px - 20;
			CaseImages.draw(g, t.stack(), px, 40, pw, pw * 3 / 8);
			int n = StickerSlots.count(t.stack());
			String[] in = StickerSlots.get(t.stack());
			for (int i = 0; i < n; i++) {
				int[] b = slotBox(i, n);
				boolean over = mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[2];
				g.fill(b[0], b[1], b[0] + b[2], b[1] + b[2], over ? 0xFF2A313A : 0xFF22272E);
				g.outline(b[0], b[1], b[2], b[2], over ? GOLD : 0xFF3A4048);
				if (in[i].isEmpty()) {
					String plus = "+";
					g.text(font, plus, b[0] + b[2] / 2 - font.width(plus) / 2, b[1] + b[2] / 2 - 4, 0xFF909090, false);
				} else {
					ItemStack on = StickerItem.stack(WeaponItems.STICKER, in[i]);
					CaseImages.draw(g, on, b[0] + 2, b[1] + 2, b[2] - 4, b[2] - 4);
					if (over) g.setTooltipForNextFrame(font, List.of(on.getHoverName(), Component.literal("§7Right-click to scrape it off (it's destroyed)")),
							java.util.Optional.empty(), mx, my);
				}
				String num = String.valueOf(i + 1);
				g.text(font, num, b[0] + 3, b[1] + 3, 0xFF707070, false);
			}
			String help = "Click an empty slot to apply it here";
			int[] b0 = slotBox(0, n);
			g.text(font, help, px + pw / 2 - font.width(help) / 2, b0[1] + b0[2] + 8, 0xFF909090, false);
		}
		super.extractRenderState(g, mx, my, delta);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
