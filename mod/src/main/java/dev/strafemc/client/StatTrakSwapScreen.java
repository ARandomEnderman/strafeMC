package dev.strafemc.client;

import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.StatTrak;
import dev.strafemc.weapon.StickerSlots;
import dev.strafemc.weapon.WeaponItems;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Using a StatTrak™ Swap Tool: your StatTrak wraps in a list; pick two of the same weapon (after the first, only its
 * kind can be picked) and swap their Confirmed Kills, which uses the tool, as in CS2.
 */
public final class StatTrakSwapScreen extends Screen implements dev.strafemc.client.ui.UiFit.Sized {
	private static final int GOLD = 0xFFEABE54, ROW = 22;
	private final int toolSlot;
	private String first, second;
	private double scroll;
	private Button swap;

	private StatTrakSwapScreen(int toolSlot) {
		super(Component.literal("StatTrak Swap Tool"));
		this.toolSlot = toolSlot;
	}

	public static void open(int toolSlot) {
		Minecraft.getInstance().setScreen(new StatTrakSwapScreen(toolSlot));
	}

	private int listW() {
		return Math.max(140, Math.min(220, width / 2 - 20));
	}

	private List<NameTagScreen.Row> rows() {
		return NameTagScreen.wrapRows(s -> StickerSlots.statTrakType(s) != null);
	}

	/** Whether a wrap can be picked now: anything StatTrak first, then only the first's kind. */
	private boolean pickable(String ref) {
		if (first == null || ref.equals(first)) return true;
		String t = StickerSlots.statTrakType(NameTagScreen.wrapAt(first));
		return t != null && t.equals(StickerSlots.statTrakType(NameTagScreen.wrapAt(ref)));
	}

	@Override
	protected void init() {
		int rx = 20 + listW() + 20, rw = Math.min(260, width - rx - 20);
		swap = addRenderableWidget(Button.builder(Component.literal("Swap StatTrak™ Counts"), b -> {
			if (first == null || second == null) return;
			if (ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE))
				ClientPlayNetworking.send(new StrafePayloads.Action("stattrak_swap", toolSlot + " " + first + " " + second));
			onClose();
		}).bounds(rx, 176, rw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Clear"), b -> {
			first = null;
			second = null;
		}).bounds(rx, 200, rw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 26, 200, 20).build());
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		int max = Math.max(0, rows().size() * ROW - (height - 70));
		scroll = Math.max(0, Math.min(max, scroll - sy * ROW * 2));
		return true;
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) return true;
		if (e.x() < 20 || e.x() >= 20 + listW() || e.y() < 36 || e.y() >= height - 34) return false;
		List<NameTagScreen.Row> rows = rows();
		int i = (int) ((e.y() - 36 + scroll) / ROW);
		if (i < 0 || i >= rows.size() || rows.get(i).ref() == null) return false;
		String ref = rows.get(i).ref();
		if (ref.equals(first)) { // unpick
			first = second;
			second = null;
		} else if (ref.equals(second)) second = null;
		else if (first == null) first = ref;
		else if (pickable(ref)) second = ref;
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, StrafeSettingsScreen.BG);
		g.text(font, "STATTRAK™ SWAP TOOL", 20, 12, GOLD, false);
		var p = minecraft.player;
		boolean haveTool = p != null && toolSlot >= 0 && toolSlot < p.getInventory().getContainerSize() && p.getInventory().getItem(toolSlot).is(WeaponItems.STATTRAK_SWAP);
		if (first != null && NameTagScreen.wrapAt(first).isEmpty()) first = null;
		if (second != null && (NameTagScreen.wrapAt(second).isEmpty() || !pickable(second))) second = null;
		List<NameTagScreen.Row> rows = rows();
		int lw = listW();
		g.fill(20, 36, 20 + lw, height - 34, 0xC01C2127);
		g.enableScissor(20, 36, 20 + lw, height - 34);
		for (int i = 0; i < rows.size(); i++) {
			int y = 36 + i * ROW - (int) Math.round(scroll);
			if (y + ROW < 36 || y > height - 34) continue;
			NameTagScreen.Row r = rows.get(i);
			if (r.header() != null) {
				g.text(font, r.header(), 26, y + 8, 0xFFB0B0B0, false);
				continue;
			}
			ItemStack s = NameTagScreen.wrapAt(r.ref());
			boolean can = pickable(r.ref()), sel = r.ref().equals(first) || r.ref().equals(second);
			boolean over = can && mx >= 20 && mx < 20 + lw && my >= Math.max(36, y) && my < y + ROW && my < height - 34;
			if (over || sel) g.fill(20, y, 20 + lw, y + ROW, sel ? 0xFF2F3742 : 0xFF262C34);
			if (sel) g.fill(20, y, 22, y + ROW, GOLD);
			CaseImages.draw(g, s, 26, y + 2, 24, 18);
			String name = font.plainSubstrByWidth(s.getHoverName().getString(), lw - 90);
			g.text(font, name, 54, y + 7, can ? CaseImages.rarityColor(s, 0xFFFFFFFF) : 0xFF606060, false);
			String k = String.valueOf(StatTrak.kills(s));
			g.text(font, k, 20 + lw - 6 - font.width(k), y + 7, can ? 0xFF000000 | StatTrak.COLOR : 0xFF606060, false);
		}
		if (rows.isEmpty()) g.text(font, "You have no StatTrak™ wraps.", 26, 44, 0xFF909090, false);
		g.disableScissor();
		int rx = 20 + lw + 20, rw = Math.min(260, width - rx - 20);
		String[] picked = {first, second};
		for (int k = 0; k < 2; k++) {
			int y = 36 + k * 66;
			g.fill(rx, y, rx + rw, y + 60, 0xC01C2127);
			if (picked[k] == null) {
				g.text(font, k == 0 ? "Pick a StatTrak™ wrap" : "Pick another of the same weapon", rx + 8, y + 26, 0xFF909090, false);
				continue;
			}
			ItemStack s = NameTagScreen.wrapAt(picked[k]);
			CaseImages.draw(g, s, rx + 4, y + 6, 64, 48);
			g.text(font, font.plainSubstrByWidth(s.getHoverName().getString(), rw - 80), rx + 74, y + 16, 0xFFFFFFFF, false);
			g.text(font, StatTrak.kills(s) + " Confirmed Kills", rx + 74, y + 30, 0xFF000000 | StatTrak.COLOR, false);
		}
		swap.active = haveTool && first != null && second != null;
		if (!haveTool) g.text(font, "The tool is gone from its slot.", rx, 226, 0xFFE06060, false);
		super.extractRenderState(g, mx, my, delta);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
