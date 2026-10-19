package dev.strafemc.client;

import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.Cases;
import dev.strafemc.weapon.SkinWrapItem;
import dev.strafemc.weapon.TradeUp;
import dev.strafemc.weapon.Upgrader;
import dev.strafemc.weapon.Worth;
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
 * CS2's Trade Up Contract (strafeMC menu): pick 10 skins of one quality (left: inventory and Storage Units), see every
 * possible result with its odds and float (right), and sign: the result is revealed.
 */
public final class TradeUpScreen extends Screen {
	private static final int GOLD = 0xFFEABE54, LIST_TOP = 30;
	private static TradeUpScreen open;
	private final Screen parent;
	private final List<Integer> picked = new ArrayList<>();
	private final java.util.Set<Integer> folded = new java.util.HashSet<>();
	private double listScroll, outScroll;
	private int listHeight;
	private Button sign;
	/** The last contract's result, shown until the next pick. */
	private ItemStack got = ItemStack.EMPTY;

	private record Section(String label, int key, List<Integer> refs) {}

	public TradeUpScreen(Screen parent) {
		super(Component.literal("Trade Up Contract"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		open = this;
		sign = addRenderableWidget(Button.builder(Component.literal("Sign Contract"), b -> sign()).bounds(width / 2 - 75, height - 60, 150, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 26, 200, 20).build());
	}

	private int colW() {
		return Math.max(110, Math.min(160, width / 4));
	}

	private List<ItemStack> inputs() {
		List<ItemStack> out = new ArrayList<>();
		for (int ref : picked) out.add(Upgrader.at(minecraft.player, ref));
		return out;
	}

	private void sign() {
		if (TradeUp.problem(inputs()) != null) return;
		StringBuilder b = new StringBuilder();
		for (int ref : picked) b.append(ref).append(' ');
		if (ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE)) ClientPlayNetworking.send(new StrafePayloads.Action("tradeup", b.toString().trim()));
		picked.clear();
		sign.active = false;
	}

	public static void result(StrafePayloads.TradeUpResult r) {
		TradeUpScreen s = open;
		if (s == null || Minecraft.getInstance().screen != s) return;
		s.got = r.item();
		int rarity = 0;
		var w = dev.strafemc.weapon.SkinWraps.get(SkinWrapItem.skin(r.item()));
		if (w != null && w.rarity() != null) rarity = w.rarity().ordinal();
		String sound = rarity >= 5 ? "ancient" : rarity == 4 ? "legendary" : rarity == 3 ? "mythical" : "rare";
		dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/case_reveal_" + sound + "_01", 1f);
	}

	@Override
	public void tick() {
		super.tick();
		picked.removeIf(ref -> !TradeUp.eligible(Upgrader.at(minecraft.player, ref)));
		sign.active = TradeUp.problem(inputs()) == null;
	}

	// ---------------------------------------------------------------- the pick list

	private List<Section> sections() {
		List<Section> out = new ArrayList<>();
		var inv = minecraft.player.getInventory();
		List<Integer> equipped = new ArrayList<>(); // the current loadout's (picking one is warned about)
		var defs = dev.strafemc.weapon.Loadout.defs();
		for (int i = 0; i < defs.size(); i++) if (TradeUp.eligible(dev.strafemc.weapon.Loadout.get(minecraft.player, defs.get(i).id()))) equipped.add(Upgrader.loadoutRef(i));
		if (!equipped.isEmpty()) out.add(new Section("CURRENT LOADOUT", -2, equipped));
		List<Integer> loose = new ArrayList<>();
		for (int i = 0; i < inv.getContainerSize(); i++) if (TradeUp.eligible(inv.getItem(i))) loose.add(i);
		out.add(new Section("YOUR ITEMS", -1, loose));
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack unit = inv.getItem(i);
			if (!(unit.getItem() instanceof dev.strafemc.weapon.StorageUnitItem)) continue;
			List<Integer> in = new ArrayList<>();
			List<ItemStack> all = unit.getOrDefault(dev.strafemc.weapon.StorageUnitItem.CONTENTS, List.<ItemStack>of());
			for (int j = 0; j < all.size(); j++) if (TradeUp.eligible(all.get(j))) in.add(Upgrader.unitRef(i, j));
			out.add(new Section(unit.getHoverName().getString(), i, in));
		}
		return out;
	}

	private interface Visit {
		void header(Section s, int y);

		void item(int ref, int x, int y);
	}

	private void layout(Visit v) {
		int per = Math.max(1, (colW() - 4) / 18), y = LIST_TOP - (int) listScroll;
		for (Section s : sections()) {
			v.header(s, y);
			y += 12;
			if (!folded.contains(s.key())) {
				for (int i = 0; i < s.refs().size(); i++) v.item(s.refs().get(i), 20 + (i % per) * 18, y + (i / per) * 18);
				y += Math.max(1, (s.refs().size() + per - 1) / per) * 18;
			}
			y += 4;
		}
		listHeight = y + (int) listScroll - LIST_TOP;
	}

	private int listBottom() {
		return height - 58;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (mx < 20 + colW()) {
			listScroll = Math.max(0, Math.min(Math.max(0, listHeight - (listBottom() - LIST_TOP)), listScroll - sy * 18));
			return true;
		}
		if (mx > width - 20 - colW()) {
			outScroll = Math.max(0, outScroll - sy * 22);
			return true;
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) return true;
		double mx = e.x(), my = e.y();
		if (mx >= 20 && mx < 20 + colW() && my >= LIST_TOP && my < listBottom()) {
			boolean[] done = {false};
			layout(new Visit() {
				@Override
				public void header(Section s, int y) {
					if (!done[0] && my >= y && my < y + 11) {
						if (!folded.remove(s.key())) folded.add(s.key());
						done[0] = true;
					}
				}

				@Override
				public void item(int ref, int x, int y) {
					if (done[0] || mx < x || mx >= x + 18 || my < y || my >= y + 18) return;
					if (!picked.remove(Integer.valueOf(ref))) {
						List<ItemStack> next = new ArrayList<>(inputs());
						next.add(Upgrader.at(minecraft.player, ref));
						if (picked.size() < TradeUp.needed(next)) picked.add(ref); // 10, or 5 for Covert
					}
					got = ItemStack.EMPTY;
					dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/submenu_leveloptions_select_01", 0.4f);
					done[0] = true;
				}
			});
			if (done[0]) return true;
		}
		// a contract slot: take that one back out
		int[] slots = slotsOrigin();
		for (int i = 0; i < picked.size(); i++) {
			int x = slots[0] + (i % 5) * 20, y = slots[1] + (i / 5) * 20;
			if (mx >= x && mx < x + 18 && my >= y && my < y + 18) {
				picked.remove(i);
				return true;
			}
		}
		return false;
	}

	private int[] slotsOrigin() {
		return new int[] {width / 2 - 50, 44};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, StrafeSettingsScreen.BG);
		var font = minecraft.font;
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(width / 2f, 10);
		pose.scale(1.5f, 1.5f);
		g.text(font, "Trade Up Contract", -font.width("Trade Up Contract") / 2, 0, 0xFFFFFFFF, true);
		pose.popMatrix();
		int cw = colW();
		ItemStack[] hover = {ItemStack.EMPTY};
		g.enableScissor(0, LIST_TOP, 20 + cw + 4, listBottom());
		layout(new Visit() {
			@Override
			public void header(Section sec, int y) {
				boolean fold = folded.contains(sec.key());
				g.text(font, font.plainSubstrByWidth((fold ? "▶ " : "▼ ") + sec.label() + "  " + sec.refs().size(), cw), 20, y + 1,
						sec.key() < 0 ? GOLD : 0xFF9AC8FF, false);
				if (!fold && sec.refs().isEmpty()) g.text(font, "No eligible skins", 30, y + 13, 0xFF707070, false);
			}

			@Override
			public void item(int ref, int x, int y) {
				ItemStack st = Upgrader.at(minecraft.player, ref);
				boolean in = picked.contains(ref);
				g.fill(x, y, x + 17, y + 17, in ? GOLD : 0xFF0E0F11);
				if (in) g.fill(x + 1, y + 1, x + 16, y + 16, 0xFF2A313A);
				CaseImages.icon(g, st, x, y);
				if (mx >= x && mx < x + 18 && my >= y && my < y + 18 && my >= LIST_TOP && my < listBottom()) hover[0] = st;
			}
		});
		g.disableScissor();
		// the contract: ten slots and what's wrong (or ready)
		int[] o = slotsOrigin();
		List<ItemStack> in = inputs();
		for (int i = 0; i < TradeUp.needed(in); i++) {
			int x = o[0] + (i % 5) * 20, y = o[1] + (i / 5) * 20;
			g.fill(x - 1, y - 1, x + 17, y + 17, 0xFF0E0F11);
			if (i < in.size()) {
				CaseImages.icon(g, in.get(i), x, y);
				if (mx >= x && mx < x + 18 && my >= y && my < y + 18) hover[0] = in.get(i);
			}
		}
		String problem = TradeUp.problem(in);
		String status = in.isEmpty() ? "Select eligible items to exchange for a higher quality item" : problem == null ? "Ready to Trade Up" : problem;
		for (var line : font.split(Component.literal(status), Math.max(100, width - 2 * cw - 80))) {
			g.text(font, line, width / 2 - font.width(line) / 2, o[1] + 46, problem == null && !in.isEmpty() ? 0xFF55FF55 : 0xFFB0B0B0, false);
			o[1] += 10;
		}
		// the result of the last contract
		if (!got.isEmpty()) {
			int cy = height / 2 + 10;
			var pic = picture(got);
			if (pic != null) g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, pic, width / 2 - 48, cy - 36, 0, 0, 96, 72, 96, 72);
			TileText.draw(g, font, got.getHoverName().getString(), width / 2, cy + 40, 200, 0xFFFFFFFF);
			String ext = dev.strafemc.weapon.Wear.exterior(SkinWrapItem.wear(got)) + "  " + Worth.format(Math.max(0, Worth.of(got)));
			g.text(font, ext, width / 2 - font.width(ext) / 2, cy + 60, 0xFF55FF55, true);
			if (mx >= width / 2 - 48 && mx < width / 2 + 48 && my >= cy - 36 && my < cy + 36) hover[0] = got;
		}
		// right: every possible result, its odds and the float it would have
		int ox = width - 20 - cw, y = LIST_TOP;
		g.text(font, "POSSIBLE RESULTS", ox, y, GOLD, false);
		List<TradeUp.Outcome> outs = TradeUp.outcomes(in);
		double place = TradeUp.place(in);
		g.enableScissor(ox, LIST_TOP + 12, width, listBottom());
		y = LIST_TOP + 12 - (int) outScroll;
		for (TradeUp.Outcome oc : outs) {
			ItemStack s = Cases.stack(oc.entry(), 0, TradeUp.wearFor(oc.entry().kit(), place));
			g.text(font, font.plainSubstrByWidth(TileText.clean(s.getHoverName().getString()), cw), ox, y, oc.rarity() == null ? 0xFFE4AE39 : 0xFF000000 | oc.rarity().color, false); // knives / gloves: gold
			String line = String.format(java.util.Locale.ROOT, "%.1f%%  %.4f", oc.chance() * 100, TradeUp.wearFor(oc.entry().kit(), place));
			g.text(font, line, ox, y + 10, 0xFF909090, false);
			if (mx >= ox && my >= y && my < y + 20 && my >= LIST_TOP + 12 && my < listBottom()) hover[0] = s;
			y += 22;
		}
		g.disableScissor();
		boolean equippedPicked = false; // something from the current loadout is in: say so under the button
		for (int ref : picked) equippedPicked |= Upgrader.equipped(ref);
		if (equippedPicked) {
			String warn = "You chose a currently equipped item";
			g.text(font, warn, width / 2 - font.width(warn) / 2, height - 37, 0xFFE05050, false);
		}
		super.extractRenderState(g, mx, my, delta);
		if (!hover[0].isEmpty()) g.setTooltipForNextFrame(font, hover[0], mx, my);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}

	private static net.minecraft.resources.Identifier picture(ItemStack s) {
		var w = dev.strafemc.weapon.SkinWraps.get(SkinWrapItem.skin(s));
		if (w == null || w.kits().isEmpty()) return null;
		var e = w.kits().entrySet().iterator().next();
		return CaseImages.get(new Cases.Entry(e.getValue(), e.getKey()));
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
		if (LoadoutKey.closesAll(this, e)) return true;
		return super.keyPressed(e);
	}

	@Override
	public void onClose() {
		open = null;
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
