package dev.strafemc.client;

import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.Loadout;
import dev.strafemc.weapon.SkinWrapItem;
import dev.strafemc.weapon.SkinWraps;
import dev.strafemc.weapon.StickerSlots;
import dev.strafemc.weapon.StorageUnitItem;
import dev.strafemc.weapon.Upgrader;
import dev.strafemc.weapon.WeaponItems;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Using a Name Tag: your wraps (the loadout's knife and guns, your items, your Storage Units') in a list on the left;
 * pick one, type its new name (up to 20 characters, as in CS2) and apply it, which uses the tag. A named wrap's tag can
 * be taken off for free.
 */
public final class NameTagScreen extends Screen {
	private static final int GOLD = 0xFFEABE54, ROW = 22;
	private final int tagSlot;
	private String picked;
	private double scroll;
	private EditBox name;
	private Button apply, remove;

	private record Row(String header, String ref) {}

	private NameTagScreen(int tagSlot) {
		super(Component.literal("Name Tag"));
		this.tagSlot = tagSlot;
	}

	public static void open(int tagSlot) {
		Minecraft.getInstance().setScreen(new NameTagScreen(tagSlot));
	}

	private int listW() {
		return Math.max(140, Math.min(220, width / 2 - 20));
	}

	@Override
	protected void init() {
		int rx = 20 + listW() + 20, rw = Math.min(260, width - rx - 20);
		name = new EditBox(font, rx, 150, rw, 20, Component.literal("Name"));
		name.setMaxLength(StickerSlots.NAME_TAG_MAX);
		name.setHint(Component.literal("New name"));
		addRenderableWidget(name);
		apply = addRenderableWidget(Button.builder(Component.literal("Apply Name Tag"), b -> {
			String n = StickerSlots.cleanName(name.getValue());
			if (picked == null || n == null) return;
			send("nametag_apply", tagSlot + " " + picked + " " + n);
			onClose();
		}).bounds(rx, 176, rw, 20).build());
		remove = addRenderableWidget(Button.builder(Component.literal("Remove Name Tag"), b -> {
			if (picked != null) send("nametag_remove", picked);
		}).bounds(rx, 200, rw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 26, 200, 20).build());
		pick(picked);
	}

	private void pick(String ref) {
		picked = ref;
		String tag = ref == null ? null : StickerSlots.nameTag(at(ref));
		name.setValue(tag == null ? "" : tag);
	}

	private static void send(String cmd, String arg) {
		if (ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE)) ClientPlayNetworking.send(new StrafePayloads.Action(cmd, arg));
	}

	private ItemStack at(String ref) {
		var p = minecraft.player;
		if (p == null) return ItemStack.EMPTY;
		if (ref.startsWith("L:")) return Loadout.get(p, ref.substring(2));
		int r = Integer.parseInt(ref.substring(2));
		return ref.startsWith("I:") ? p.getInventory().getItem(r) : Upgrader.at(p, r);
	}

	/** The wraps in sections, in the loadout's order. */
	private List<Row> rows() {
		List<Row> out = new ArrayList<>();
		var p = minecraft.player;
		if (p == null) return out;
		List<String> loadout = new ArrayList<>();
		for (Loadout.Def d : Loadout.defs())
			if ((d.kind() == Loadout.Kind.GUN || d.kind() == Loadout.Kind.KNIFE) && Loadout.get(p, d.id()).getItem() instanceof SkinWrapItem) loadout.add("L:" + d.id());
		section(out, "CURRENT LOADOUT", loadout);
		var inv = p.getInventory();
		List<String> loose = new ArrayList<>();
		for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).getItem() instanceof SkinWrapItem) loose.add("I:" + i);
		loose.sort(java.util.Comparator.comparingInt((String r) -> SkinWraps.order(at(r))).thenComparing(r -> at(r).getHoverName().getString()));
		section(out, "YOUR ITEMS", loose);
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack unit = inv.getItem(i);
			if (!(unit.getItem() instanceof StorageUnitItem)) continue;
			List<String> in = new ArrayList<>();
			List<ItemStack> all = unit.getOrDefault(StorageUnitItem.CONTENTS, List.<ItemStack>of());
			for (int j = 0; j < all.size(); j++) if (all.get(j).getItem() instanceof SkinWrapItem) in.add("U:" + Upgrader.unitRef(i, j));
			in.sort(java.util.Comparator.comparingInt((String r) -> SkinWraps.order(at(r))).thenComparing(r -> at(r).getHoverName().getString()));
			section(out, unit.getHoverName().getString().toUpperCase(), in);
		}
		return out;
	}

	private static void section(List<Row> out, String header, List<String> refs) {
		if (refs.isEmpty()) return;
		out.add(new Row(header, null));
		for (String r : refs) out.add(new Row(null, r));
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
		if (i >= 0 && i < rows.size() && rows.get(i).ref() != null) {
			pick(rows.get(i).ref());
			return true;
		}
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, StrafeSettingsScreen.BG);
		g.text(font, "NAME TAG", 20, 12, GOLD, false);
		var p = minecraft.player;
		boolean haveTag = p != null && tagSlot >= 0 && tagSlot < p.getInventory().getContainerSize() && p.getInventory().getItem(tagSlot).is(WeaponItems.NAME_TAG);
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
			ItemStack s = at(r.ref());
			boolean over = mx >= 20 && mx < 20 + lw && my >= Math.max(36, y) && my < y + ROW && my < height - 34, sel = r.ref().equals(picked);
			if (over || sel) g.fill(20, y, 20 + lw, y + ROW, sel ? 0xFF2F3742 : 0xFF262C34);
			if (sel) g.fill(20, y, 22, y + ROW, GOLD);
			CaseImages.draw(g, s, 26, y + 2, 24, 18);
			g.text(font, font.plainSubstrByWidth(s.getHoverName().getString(), lw - 40), 54, y + 7, CaseImages.rarityColor(s, 0xFFFFFFFF), false);
		}
		if (rows.isEmpty()) g.text(font, "You have no gun or knife wraps.", 26, 44, 0xFF909090, false);
		g.disableScissor();
		int rx = 20 + lw + 20, rw = Math.min(260, width - rx - 20);
		ItemStack sel = picked == null ? ItemStack.EMPTY : at(picked);
		if (sel.isEmpty()) {
			picked = null;
			g.text(font, "Pick a wrap on the left.", rx, 40, 0xFF909090, false);
		} else {
			CaseImages.draw(g, sel, rx, 36, rw, 90);
			g.text(font, font.plainSubstrByWidth(sel.getHoverName().getString(), rw), rx, 132, 0xFFFFFFFF, false);
		}
		String clean = StickerSlots.cleanName(name.getValue());
		apply.active = haveTag && picked != null && clean != null && !clean.equals(StickerSlots.nameTag(sel));
		remove.active = picked != null && StickerSlots.nameTag(sel) != null;
		remove.visible = remove.active;
		if (!haveTag) g.text(font, "The Name Tag is gone from its slot.", rx, 226, 0xFFE06060, false);
		super.extractRenderState(g, mx, my, delta);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
