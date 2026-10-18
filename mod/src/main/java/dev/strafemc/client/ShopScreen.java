package dev.strafemc.client;

import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.CaseItem;
import dev.strafemc.weapon.Cases;
import dev.strafemc.weapon.GloveItem;
import dev.strafemc.weapon.Shop;
import dev.strafemc.weapon.SkinWrapItem;
import dev.strafemc.weapon.SkinWraps;
import dev.strafemc.weapon.Upgrader;
import dev.strafemc.weapon.Worth;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The hourly shop (strafeMC menu): your balance and the time to the next rotation at the top, skins to sell on the left
 * (inventory, then each Storage Unit, foldable; 75% of their worth), and this rotation's eight offers on the right.
 */
public final class ShopScreen extends Screen {
	private static final int GOLD = 0xFFEABE54, LIST_TOP = 44;
	private final Screen parent;
	private final List<Integer> selling = new ArrayList<>();
	private final java.util.Set<Integer> folded = new java.util.HashSet<>();
	private double listScroll;
	private int listHeight, chosen = -1;
	private Button sell, buy;
	private static int lastProgress = -1;
	private static long progressAt;

	private record Section(String label, int key, List<Integer> refs) {}

	public ShopScreen(Screen parent) {
		super(Component.literal("Shop"));
		this.parent = parent;
	}

	private Shop.State state() {
		return Shop.get(minecraft.player);
	}

	private List<Shop.Offer> offers() {
		return Shop.offers(minecraft.player.getUUID(), state().rotation());
	}

	@Override
	protected void init() {
		sell = addRenderableWidget(Button.builder(Component.literal("Sell"), b -> sell()).bounds(20, height - 52, colW(), 20).build());
		buy = addRenderableWidget(Button.builder(Component.literal("Buy"), b -> buy()).bounds(offersX() + offersW() / 2 - 60, height - 52, 120, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 26, 200, 20).build());
	}

	private int colW() {
		return Math.max(110, Math.min(160, width / 4));
	}

	private int offersX() {
		return 20 + colW() + 20;
	}

	private int offersW() {
		return width - offersX() - 20;
	}

	private void send(String cmd, String arg) {
		if (ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE)) ClientPlayNetworking.send(new StrafePayloads.Action(cmd, arg));
	}

	private long sellValue() {
		long c = 0;
		for (int ref : selling) {
			ItemStack s = Upgrader.at(minecraft.player, ref);
			if (Upgrader.stakeable(s)) c += Math.round(Worth.of(s) * s.getCount() * Shop.SELL_RATE * 100);
		}
		return c;
	}

	private void sell() {
		if (selling.isEmpty()) return;
		StringBuilder b = new StringBuilder();
		for (int ref : selling) b.append(ref).append(' ');
		send("shop_sell", b.toString().trim());
		dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/claim_gift_01", 0.3f);
		selling.clear();
	}

	private void buy() {
		if (chosen < 0) return;
		send("shop_buy", String.valueOf(chosen));
		dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/claim_gift_01", 0.3f);
		chosen = -1;
	}

	@Override
	public void tick() {
		super.tick();
		selling.removeIf(ref -> !Upgrader.stakeable(Upgrader.at(minecraft.player, ref)));
		sell.active = !selling.isEmpty();
		sell.setMessage(Component.literal(selling.isEmpty() ? "Sell" : "Sell for " + Shop.money(sellValue())));
		List<Shop.Offer> offers = offers();
		Shop.State s = state();
		buy.active = chosen >= 0 && chosen < offers.size() && !s.bought(chosen) && s.cents() >= offers.get(chosen).cents();
	}

	// ---------------------------------------------------------------- the sell list (as the upgrader's)

	private List<Section> sections() {
		List<Section> out = new ArrayList<>();
		var inv = minecraft.player.getInventory();
		List<Integer> loose = new ArrayList<>();
		for (int i = 0; i < inv.getContainerSize(); i++) if (Upgrader.stakeable(inv.getItem(i))) loose.add(i);
		out.add(new Section("YOUR ITEMS", -1, loose));
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack unit = inv.getItem(i);
			if (!(unit.getItem() instanceof dev.strafemc.weapon.StorageUnitItem)) continue;
			List<Integer> in = new ArrayList<>();
			List<ItemStack> all = unit.getOrDefault(dev.strafemc.weapon.StorageUnitItem.CONTENTS, List.<ItemStack>of());
			for (int j = 0; j < all.size(); j++) if (Upgrader.stakeable(all.get(j))) in.add(Upgrader.unitRef(i, j));
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

	// ---------------------------------------------------------------- the offers grid

	private int tileW() {
		return Math.max(60, Math.min(120, offersW() / 4 - 6));
	}

	private int tileH() {
		return tileW() * 3 / 4 + 30;
	}

	private int[] tilePos(int i) {
		int w = tileW(), h = tileH(), x0 = offersX() + (offersW() - 4 * (w + 6) + 6) / 2;
		return new int[] {x0 + (i % 4) * (w + 6), LIST_TOP + (i / 4) * (h + 6)};
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (mx < 20 + colW()) {
			listScroll = Math.max(0, Math.min(Math.max(0, listHeight - (listBottom() - LIST_TOP)), listScroll - sy * 18));
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
					if (!selling.remove(Integer.valueOf(ref))) selling.add(ref);
					dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/submenu_leveloptions_select_01", 0.4f);
					done[0] = true;
				}
			});
			if (done[0]) return true;
		}
		List<Shop.Offer> offers = offers();
		for (int i = 0; i < offers.size(); i++) {
			int[] p = tilePos(i);
			if (mx >= p[0] && mx < p[0] + tileW() && my >= p[1] && my < p[1] + tileH() && !state().bought(i)) {
				chosen = chosen == i ? -1 : i;
				dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/premier_deselectmap", 0.4f);
				return true;
			}
		}
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, StrafeSettingsScreen.BG);
		var font = minecraft.font;
		Shop.State s = state();
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(width / 2f, 10);
		pose.scale(2f, 2f);
		g.text(font, "Shop", -font.width("Shop") / 2, 0, 0xFFFFFFFF, true);
		pose.popMatrix();
		String bal = "Balance: " + Shop.money(s.cents());
		g.text(font, bal, width - 20 - font.width(bal), 12, 0xFF55FF55, true);
		String next = "New items in " + timeLeft(s);
		g.text(font, next, width / 2 - font.width(next) / 2, 30, 0xFF909090, false);
		int cw = colW();
		g.text(font, "SELL (" + Math.round(Shop.SELL_RATE * 100) + "%)", 20, 30, GOLD, false);
		ItemStack[] hover = {ItemStack.EMPTY};
		boolean[] offerHover = {false};
		g.enableScissor(0, LIST_TOP, 20 + cw + 4, listBottom());
		layout(new Visit() {
			@Override
			public void header(Section sec, int y) {
				boolean fold = folded.contains(sec.key());
				g.text(font, font.plainSubstrByWidth((fold ? "▶ " : "▼ ") + sec.label() + "  " + sec.refs().size(), cw), 20, y + 1,
						sec.key() < 0 ? GOLD : 0xFF9AC8FF, false);
				if (!fold && sec.refs().isEmpty()) g.text(font, "No skins", 30, y + 13, 0xFF707070, false);
			}

			@Override
			public void item(int ref, int x, int y) {
				ItemStack st = Upgrader.at(minecraft.player, ref);
				boolean in = selling.contains(ref);
				g.fill(x, y, x + 17, y + 17, in ? GOLD : 0xFF0E0F11);
				if (in) g.fill(x + 1, y + 1, x + 16, y + 16, 0xFF2A313A);
				g.item(st, x, y);
				if (mx >= x && mx < x + 18 && my >= y && my < y + 18 && my >= LIST_TOP && my < listBottom()) hover[0] = st;
			}
		});
		g.disableScissor();
		// the offers
		List<Shop.Offer> offers = offers();
		int w = tileW(), h = tileH();
		for (int i = 0; i < offers.size(); i++) {
			Shop.Offer o = offers.get(i);
			int[] p = tilePos(i);
			boolean sold = s.bought(i), over = mx >= p[0] && mx < p[0] + w && my >= p[1] && my < p[1] + h;
			g.fill(p[0], p[1], p[0] + w, p[1] + h, over && !sold ? 0xFF2A313A : 0xFF22272E);
			g.fill(p[0], p[1] + h - 3, p[0] + w, p[1] + h, color(o.item()));
			if (i == chosen) g.outline(p[0] - 1, p[1] - 1, w + 2, h + 2, GOLD);
			Identifier pic = picture(o.item());
			int ph = w * 3 / 4 - 6, pw = ph * 4 / 3;
			if (pic != null) g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, pic, p[0] + (w - pw) / 2, p[1] + 2, 0, 0, pw, ph, pw, ph);
			else {
				pose.pushMatrix();
				pose.translate(p[0] + w / 2f - 16, p[1] + ph / 2f - 14);
				pose.scale(2, 2);
				g.item(o.item(), 0, 0);
				pose.popMatrix();
			}
			TileText.draw(g, font, o.item().getHoverName().getString(), p[0] + w / 2, p[1] + h - 30, w - 6, 0xFFE0E0E0);
			String price = Shop.money(o.cents());
			g.text(font, price, p[0] + w / 2 - font.width(price) / 2, p[1] + h - 13, s.cents() >= o.cents() ? 0xFF55FF55 : 0xFFE04040, false);
			if (sold) {
				g.fill(p[0], p[1], p[0] + w, p[1] + h, 0xB0101317);
				g.text(font, "SOLD", p[0] + w / 2 - font.width("SOLD") / 2, p[1] + h / 2 - 4, GOLD, true);
			}
			if (over) {
				hover[0] = o.item();
				offerHover[0] = true;
			}
		}
		super.extractRenderState(g, mx, my, delta);
		if (!hover[0].isEmpty() && offerHover[0]) { // an offer: its tooltip without its worth (the price's roll stays hidden)
			List<Component> lines = new ArrayList<>(getTooltipFromItem(minecraft, hover[0]));
			lines.removeIf(l -> l.getString().contains("Worth:")); // the line carries legacy colour codes (§7), so not startsWith
			g.setTooltipForNextFrame(font, lines, java.util.Optional.empty(), mx, my);
		} else if (!hover[0].isEmpty()) g.setTooltipForNextFrame(font, hover[0], mx, my);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}

	private static String timeLeft(Shop.State s) {
		if (s.progress() != lastProgress) {
			lastProgress = s.progress();
			progressAt = System.currentTimeMillis();
		}
		long ticks = Math.max(0, Shop.ROTATION - s.progress() - (System.currentTimeMillis() - progressAt) / 50);
		long sec = ticks / 20;
		return sec >= 60 ? (sec / 60) + " min" : sec + " s";
	}

	private static int color(ItemStack s) {
		SkinWraps.Wrap w = SkinWraps.get(SkinWrapItem.skin(s));
		return w != null && w.rarity() != null ? 0xFF000000 | w.rarity().color : 0xFFB0C3D9;
	}

	private static Identifier picture(ItemStack s) {
		if (s.getItem() instanceof CaseItem) {
			Cases.Case c = Cases.get(CaseItem.caseId(s));
			return c == null ? null : CaseImages.image("panorama/images/" + c.image() + "_png");
		}
		if (s.getItem() instanceof GloveItem g) return CaseImages.get(new Cases.Entry(g.kit(s), g.kind.id));
		SkinWraps.Wrap w = SkinWraps.get(SkinWrapItem.skin(s));
		if (s.getItem() instanceof SkinWrapItem && w != null && !w.kits().isEmpty()) {
			var e = w.kits().entrySet().iterator().next();
			return CaseImages.get(new Cases.Entry(e.getValue(), e.getKey()));
		}
		return null;
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
		if (LoadoutKey.closesAll(this, e)) return true;
		return super.keyPressed(e);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
