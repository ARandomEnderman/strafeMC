package dev.strafemc.client;

import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.BulkOpener;
import dev.strafemc.weapon.CaseItem;
import dev.strafemc.weapon.Cases;
import dev.strafemc.weapon.StorageUnitItem;
import dev.strafemc.weapon.Upgrader;
import dev.strafemc.weapon.WeaponItems;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bulk opener: your cases, capsules, graffiti boxes and charm collections (your items, then each Storage Unit's)
 * in a list; left click picks one more, right click one less, shift + left click as many as fit. Up to BulkOpener.MAX
 * of any mix; the panel shows the keys they take (each case's own keys first, then Universal Keys) and opens them all,
 * which uses the tool.
 */
public final class BulkOpenScreen extends Screen {
	private static final int GOLD = 0xFFEABE54, ROW = 22;
	private final int toolSlot;
	/** Picked: ref (Upgrader.at) -> how many. */
	private final Map<Integer, Integer> picked = new LinkedHashMap<>();
	private double scroll;
	private Button openButton;

	private record Row(String header, int ref) {}

	private BulkOpenScreen(int toolSlot) {
		super(Component.literal("Bulk Unboxer"));
		this.toolSlot = toolSlot;
	}

	public static void open(int toolSlot) {
		Minecraft.getInstance().setScreen(new BulkOpenScreen(toolSlot));
	}

	private int listW() {
		return Math.max(160, Math.min(260, width / 2 - 20));
	}

	private int total() {
		int n = 0;
		for (int v : picked.values()) n += v;
		return n;
	}

	private List<Row> rows() {
		List<Row> out = new ArrayList<>();
		var p = minecraft.player;
		if (p == null) return out;
		var inv = p.getInventory();
		List<Integer> loose = new ArrayList<>();
		for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).getItem() instanceof CaseItem) loose.add(i);
		section(out, "YOUR ITEMS", loose);
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack unit = inv.getItem(i);
			if (!(unit.getItem() instanceof StorageUnitItem)) continue;
			List<Integer> in = new ArrayList<>();
			List<ItemStack> all = unit.getOrDefault(StorageUnitItem.CONTENTS, List.<ItemStack>of());
			for (int j = 0; j < all.size(); j++) if (all.get(j).getItem() instanceof CaseItem) in.add(Upgrader.unitRef(i, j));
			section(out, unit.getHoverName().getString().toUpperCase(), in);
		}
		return out;
	}

	private static void section(List<Row> out, String header, List<Integer> refs) {
		if (refs.isEmpty()) return;
		out.add(new Row(header, Integer.MIN_VALUE));
		for (int r : refs) out.add(new Row(null, r));
	}

	@Override
	protected void init() {
		int rx = 20 + listW() + 20, rw = Math.min(260, width - rx - 20);
		openButton = addRenderableWidget(Button.builder(Component.literal("Open"), b -> openAll()).bounds(rx, height - 60, rw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Clear"), b -> picked.clear()).bounds(rx, height - 84, rw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 26, 200, 20).build());
	}

	private void openAll() {
		var p = minecraft.player;
		if (p == null || picked.isEmpty()) return;
		StringBuilder arg = new StringBuilder().append(toolSlot);
		Cases.Case first = null;
		for (var e : picked.entrySet()) {
			arg.append(' ').append(e.getKey()).append(':').append(e.getValue());
			if (first == null) first = Cases.get(CaseItem.caseId(Upgrader.at(p, e.getKey())));
		}
		if (first == null || !ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE)) return;
		ClientPlayNetworking.send(new StrafePayloads.Action("bulk_open", arg.toString()));
		dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/case_unlock_01", 1f);
		CaseScreen.openBulk(first);
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
		List<Row> rows = rows();
		int i = (int) ((e.y() - 36 + scroll) / ROW);
		if (i < 0 || i >= rows.size() || rows.get(i).header() != null) return false;
		int ref = rows.get(i).ref(), have = Upgrader.at(minecraft.player, ref).getCount(), now = picked.getOrDefault(ref, 0);
		int room = BulkOpener.MAX - total();
		int next = e.button() == 1 ? now - 1 : now + Math.min(room, minecraft.hasShiftDown() ? have - now : 1);
		next = Math.max(0, Math.min(have, next));
		if (next == 0) picked.remove(ref);
		else picked.put(ref, next);
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, StrafeSettingsScreen.BG);
		g.text(font, "BULK UNBOXER", 20, 12, GOLD, false);
		String hint = "Left click +1   Right click -1   Shift: all";
		g.text(font, font.plainSubstrByWidth(hint, width - 40), 20, 24, 0xFF808080, false);
		var p = minecraft.player;
		boolean haveTool = p != null && toolSlot >= 0 && toolSlot < p.getInventory().getContainerSize() && p.getInventory().getItem(toolSlot).is(WeaponItems.BULK_OPENER);
		picked.entrySet().removeIf(e -> !(Upgrader.at(p, e.getKey()).getItem() instanceof CaseItem) || Upgrader.at(p, e.getKey()).getCount() < e.getValue());
		List<Row> rows = rows();
		int lw = listW();
		g.fill(20, 36, 20 + lw, height - 34, 0xC01C2127);
		g.enableScissor(20, 36, 20 + lw, height - 34);
		for (int i = 0; i < rows.size(); i++) {
			int y = 36 + i * ROW - (int) Math.round(scroll);
			if (y + ROW < 36 || y > height - 34) continue;
			Row r = rows.get(i);
			if (r.header() != null) {
				g.text(font, r.header(), 26, y + 8, 0xFFB0B0B0, false);
				continue;
			}
			ItemStack s = Upgrader.at(p, r.ref());
			int n = picked.getOrDefault(r.ref(), 0);
			boolean over = mx >= 20 && mx < 20 + lw && my >= Math.max(36, y) && my < y + ROW && my < height - 34;
			if (over || n > 0) g.fill(20, y, 20 + lw, y + ROW, n > 0 ? 0xFF2F3742 : 0xFF262C34);
			if (n > 0) g.fill(20, y, 22, y + ROW, GOLD);
			CaseImages.draw(g, s, 26, y + 2, 24, 18);
			String count = n + " / " + s.getCount();
			g.text(font, font.plainSubstrByWidth(s.getHoverName().getString(), lw - 50 - font.width(count)), 54, y + 7, 0xFFFFFFFF, false);
			g.text(font, count, 20 + lw - 6 - font.width(count), y + 7, n > 0 ? GOLD : 0xFF808080, false);
		}
		if (rows.isEmpty()) g.text(font, "You have no cases, capsules or boxes.", 26, 44, 0xFF909090, false);
		g.disableScissor();
		// the panel: what's picked, by case, and the keys it takes
		int rx = 20 + lw + 20, rw = Math.min(260, width - rx - 20), y = 36;
		g.fill(rx, 36, rx + rw, height - 90, 0xC01C2127);
		int total = total();
		g.text(font, total + " / " + BulkOpener.MAX + " containers", rx + 8, y + 6, GOLD, false);
		y += 20;
		Map<String, Integer> byCase = new LinkedHashMap<>();
		for (var e : picked.entrySet()) byCase.merge(Upgrader.at(p, e.getKey()).getHoverName().getString(), e.getValue(), Integer::sum);
		for (var e : byCase.entrySet()) {
			if (y > height - 130) {
				g.text(font, "...", rx + 8, y, 0xFF909090, false);
				break;
			}
			g.text(font, font.plainSubstrByWidth(e.getValue() + " x " + e.getKey(), rw - 16), rx + 8, y, 0xFFE0E0E0, false);
			y += 11;
		}
		BulkOpener.Keys k = p == null ? new BulkOpener.Keys(0, 0, 0) : BulkOpener.keys(p, picked);
		boolean creative = p != null && p.getAbilities().instabuild;
		int ky = height - 126;
		g.text(font, "Keys: " + k.own() + " own, " + k.universal() + " Universal", rx + 8, ky, 0xFFB0B0B0, false);
		if (k.missing() > 0 && !creative) g.text(font, k.missing() + " more key" + (k.missing() > 1 ? "s" : "") + " needed", rx + 8, ky + 12, 0xFFE05050, false);
		if (!haveTool) g.text(font, "The Bulk Unboxer is gone from its slot.", rx, height - 100, 0xFFE05050, false);
		openButton.active = haveTool && total > 0 && (k.missing() == 0 || creative);
		openButton.setMessage(Component.literal(total > 0 ? "Open " + total : "Open"));
		super.extractRenderState(g, mx, my, delta);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
