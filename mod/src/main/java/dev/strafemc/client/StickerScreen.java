package dev.strafemc.client;

import dev.strafemc.client.viewmodel.SideView;
import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.CharmItem;
import dev.strafemc.weapon.Loadout;
import dev.strafemc.weapon.SkinWrapItem;
import dev.strafemc.weapon.SkinWraps;
import dev.strafemc.weapon.StickerItem;
import dev.strafemc.weapon.StickerSlots;
import dev.strafemc.weapon.StorageUnitItem;
import dev.strafemc.weapon.Upgrader;
import dev.strafemc.weapon.WeaponItems;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Applying a sticker or hanging a charm (right-click one), placed like in CS2. Left, as in the strafeMC menu's screens:
 * the gun wraps that take stickers in your current loadout, then your items, then each Storage Unit you carry, each
 * foldable. Right, the picked one seen from the side in CS2's sticker space. For a sticker pick a free slot: it shows at
 * that slot's place; drag it over the gun's sticker area (CS2's sticker mask; elsewhere is dimmed) and scroll to turn it
 * (its size is the gun's slot size, as in CS2), pick its scrape, then Apply. A charm starts at the gun's keychain point
 * and can hang from any point on the gun. Pick a slot that has a sticker to Scrape it (a bit more each time, previewed;
 * scraped through it comes off and is gone), or the charm's spot to Detach it (uses a Charm Detachment, gives it back).
 */
public final class StickerScreen extends Screen {
	private static final int GOLD = 0xFFEABE54, LIST_TOP = 30;
	/** The inventory slot of the sticker or charm being applied. */
	private final int stickerSlot;

	/** A group of wraps: the loadout, your items, or one Storage Unit (key: -2, -1, its inventory slot). refs: L:, I:, U: targets. */
	private record Section(String label, int key, List<String> refs) {}

	private final Set<Integer> folded = new HashSet<>();
	private double listScroll;
	private int listHeight;
	private String picked;
	/** Being placed: which slot (-1: none; the charm uses 0), where (sticker space) and its turn (radians); the slot picked to scrape. */
	private int pendingSlot = -1, chosenSpot = -1;
	private double pu, pv, pturn, pwear;
	private boolean dragging;
	private Button apply, cancel, scrape, detach;
	private ScrapeSlider wearSlider;

	/** The scrape slider for the sticker being placed: as new to fully scraped, in scrape steps, previewed live. */
	private final class ScrapeSlider extends net.minecraft.client.gui.components.AbstractSliderButton {
		ScrapeSlider(int x, int y, int w) {
			super(x, y, w, 20, Component.empty(), 0);
			updateMessage();
		}

		double wear() {
			int steps = (int) Math.round(1 / StickerSlots.SCRAPE);
			return Math.round(value * steps) * StickerSlots.SCRAPE;
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal("Scrape: " + Math.round(wear() * 100) + "%"));
		}

		@Override
		protected void applyValue() {
			pwear = wear();
		}

		void reset() {
			value = 0;
			pwear = 0;
			updateMessage();
		}
	}

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
		apply = addRenderableWidget(Button.builder(Component.literal("Apply"), b -> applyPending()).bounds(width - 230, height - 52, 100, 20).build());
		cancel = addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> pendingSlot = -1).bounds(width - 124, height - 52, 100, 20).build());
		wearSlider = addRenderableWidget(new ScrapeSlider(width - 230, height - 76, 206));
		scrape = addRenderableWidget(Button.builder(Component.literal("Scrape"), b -> scrapeChosen()).bounds(width - 230, height - 52, 100, 20).build());
		detach = addRenderableWidget(Button.builder(Component.literal("Detach"), b -> scrapeChosen()).bounds(width - 230, height - 52, 100, 20).build());
	}

	private ItemStack held() {
		var p = minecraft.player;
		return p == null || stickerSlot < 0 || stickerSlot >= p.getInventory().getContainerSize() ? ItemStack.EMPTY : p.getInventory().getItem(stickerSlot);
	}

	private boolean charmMode() {
		return held().getItem() instanceof CharmItem;
	}

	private int spots(ItemStack wrap) {
		return charmMode() ? (StickerSlots.count(wrap) > 0 ? 1 : 0) : StickerSlots.count(wrap);
	}

	/** What's in each spot (entries with their placement): the stickers, or the charm. */
	private String[] in(ItemStack wrap) {
		return charmMode() ? new String[] {StickerSlots.charm(wrap)} : StickerSlots.get(wrap);
	}

	private ItemStack shown(String entry) {
		String id = StickerSlots.idOf(entry);
		return charmMode() ? CharmItem.stack(WeaponItems.CHARM, id) : StickerItem.stack(WeaponItems.STICKER, id);
	}

	/** The wrap at a target ref, as the client sees it. */
	private ItemStack at(String ref) {
		var p = minecraft.player;
		if (p == null) return ItemStack.EMPTY;
		if (ref.startsWith("L:")) return Loadout.get(p, ref.substring(2));
		int r = Integer.parseInt(ref.substring(2));
		return ref.startsWith("I:") ? p.getInventory().getItem(r) : Upgrader.at(p, r);
	}

	/** Your current loadout's gun wraps, your items' gun wraps, then each Storage Unit's (with its name). */
	private List<Section> sections() {
		List<Section> out = new ArrayList<>();
		var p = minecraft.player;
		if (p == null) return out;
		List<String> loadout = new ArrayList<>();
		for (Loadout.Def d : Loadout.defs())
			if (d.kind() == Loadout.Kind.GUN && StickerSlots.count(Loadout.get(p, d.id())) > 0) loadout.add("L:" + d.id());
		out.add(new Section("CURRENT LOADOUT", -2, loadout));
		var inv = p.getInventory();
		List<String> loose = new ArrayList<>();
		for (int i = 0; i < inv.getContainerSize(); i++) if (StickerSlots.count(inv.getItem(i)) > 0) loose.add("I:" + i);
		out.add(new Section("YOUR ITEMS", -1, loose));
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack unit = inv.getItem(i);
			if (!(unit.getItem() instanceof StorageUnitItem)) continue;
			List<String> in = new ArrayList<>();
			List<ItemStack> all = unit.getOrDefault(StorageUnitItem.CONTENTS, List.<ItemStack>of());
			for (int j = 0; j < all.size(); j++) if (StickerSlots.count(all.get(j)) > 0) in.add("U:" + Upgrader.unitRef(i, j));
			out.add(new Section(unit.getHoverName().getString(), i, in));
		}
		return out;
	}

	private interface Visit {
		void header(Section s, int y);

		void item(String ref, int x, int y);
	}

	private int colW() {
		return Math.max(110, Math.min(170, (width - 40) / 3));
	}

	private int listBottom() {
		return height - 34;
	}

	/** Walks the left column's layout (scrolled): each section's header, then its wraps unless it's folded. */
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

	private ItemStack target() {
		if (picked == null) return ItemStack.EMPTY;
		try {
			ItemStack s = at(picked);
			return StickerSlots.count(s) > 0 ? s : ItemStack.EMPTY;
		} catch (RuntimeException e) {
			return ItemStack.EMPTY;
		}
	}

	/** The picked wrap's gun seen from the side (null while it's made). */
	private SideView view() {
		ItemStack t = target();
		String gun = StickerSlots.gun(t);
		if (gun == null) return null;
		return SideView.of(gun, SkinWraps.kitFor(SkinWrapItem.skin(t), gun), SkinWrapItem.finish(t));
	}

	/** The side view's box: x, y, width, height (sticker space u 0-1 across its width). */
	private int[] viewBox(SideView v) {
		int px = 20 + colW() + 20, pw = width - px - 20;
		int ph = v == null ? pw / 3 : (int) Math.round(pw * (double) v.h / SideView.W);
		int maxH = height - 170;
		if (ph > maxH) {
			pw = (int) Math.round(pw * (double) maxH / ph);
			ph = maxH;
		}
		return new int[] {px + (width - px - 20 - pw) / 2, 36, pw, ph};
	}

	private double[] toScreen(SideView v, int[] box, double u, double vv) {
		return new double[] {box[0] + u * box[2], box[1] + (vv - v.v0) / (v.v1 - v.v0) * box[3]};
	}

	private double[] toSpace(SideView v, int[] box, double x, double y) {
		return new double[] {(x - box[0]) / box[2], v.v0 + (y - box[1]) / box[3] * (v.v1 - v.v0)};
	}

	/** Spot i's box under the view. */
	private int[] spotBox(int i, int n, int[] vb) {
		int size = 40, total = n * size + (n - 1) * 6, x = vb[0] + (vb[2] - total) / 2 + i * (size + 6), y = vb[1] + vb[3] + 14;
		return new int[] {x, y, size};
	}

	/** Where entry sits in sticker space: its own place, or slot i's (a charm: its point / the keychain point). */
	private double[] placeOf(SideView v, String entry, int i) {
		double[] p = StickerSlots.placeOf(entry);
		if (charmMode()) {
			if (p != null && p.length == 3) return v.uvOf((float) p[0], (float) p[1], (float) p[2]);
			return v.keychain;
		}
		if (p != null && p.length == 3) return new double[] {p[0], p[1], p[2]};
		if (i < v.slots.size()) return new double[] {v.slots.get(i).u(), v.slots.get(i).v(), v.slots.get(i).rotation()};
		return null;
	}

	/** A sticker's size on the view: 1 / its slot's scale of sticker space (a charm: a small fixed size). */
	private double sizeOf(SideView v, int slot, int[] box) {
		if (charmMode()) return box[2] * 0.06;
		double sc = slot < v.slots.size() ? v.slots.get(slot).scale() : 10;
		return box[2] / Math.max(1, sc);
	}

	private void startPending(SideView v, int slot) {
		pendingSlot = slot;
		chosenSpot = -1;
		double[] p = charmMode() ? v.keychain : slot < v.slots.size() ? new double[] {v.slots.get(slot).u(), v.slots.get(slot).v(), v.slots.get(slot).rotation()} : null;
		if (p == null) p = new double[] {0.5, (v.v0 + v.v1) / 2, 0};
		pu = p[0];
		pv = p[1];
		pturn = p.length > 2 ? p[2] : 0;
		if (wearSlider != null) wearSlider.reset();
	}

	/** Whether the thing being placed may go where it is: a sticker on the gun's sticker area, a charm anywhere on the gun. */
	private boolean placeable(SideView v) {
		return charmMode() ? v.pointAt(pu, pv) != null : v.allowedAt(pu, pv);
	}

	private void applyPending() {
		SideView v = view();
		if (picked == null || v == null || pendingSlot < 0 || !placeable(v)) return;
		if (charmMode()) {
			float[] pt = v.pointAt(pu, pv);
			if (pt == null) return;
			send("charm_apply", stickerSlot + " " + picked + " " + String.format(java.util.Locale.ROOT, "%.5f,%.5f,%.5f", pt[0], pt[1], pt[2]));
		} else send("sticker_apply", stickerSlot + " " + picked + " " + pendingSlot + " " + String.format(java.util.Locale.ROOT, "%.4f,%.4f,%.4f %.2f", pu, pv, pturn, pwear));
		dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/claim_gift_01", 0.3f);
		pendingSlot = -1;
	}

	/** Scrapes the chosen sticker once, or detaches the charm. */
	private void scrapeChosen() {
		if (picked == null || chosenSpot < 0) return;
		send(charmMode() ? "charm_remove" : "sticker_scrape", charmMode() ? picked : picked + " " + chosenSpot);
		dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/submenu_leveloptions_select_01", 0.4f);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (mx < 20 + colW()) {
			listScroll = Math.max(0, Math.min(Math.max(0, listHeight - (listBottom() - LIST_TOP)), listScroll - sy * 18));
			return true;
		}
		if (pendingSlot >= 0 && !charmMode()) { // turn the sticker being placed
			pturn += Math.toRadians(sy * 5);
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
				public void item(String ref, int x, int y) {
					if (done[0] || mx < x || mx >= x + 18 || my < y || my >= y + 18) return;
					picked = ref;
					pendingSlot = -1;
					chosenSpot = -1;
					dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/submenu_leveloptions_select_01", 0.4f);
					done[0] = true;
				}
			});
			return done[0];
		}
		ItemStack t = target();
		SideView v = view();
		if (t.isEmpty() || v == null) return false;
		int[] vb = viewBox(v);
		if (pendingSlot >= 0 && mx >= vb[0] && mx < vb[0] + vb[2] && my >= vb[1] && my < vb[1] + vb[3]) { // grab it (or put it where clicked)
			double[] s = toSpace(v, vb, mx, my);
			pu = s[0];
			pv = s[1];
			dragging = true;
			return true;
		}
		int n = spots(t);
		String[] in = in(t);
		for (int i = 0; i < n; i++) {
			int[] b = spotBox(i, n, vb);
			if (mx < b[0] || mx >= b[0] + b[2] || my < b[1] || my >= b[1] + b[2]) continue;
			if (in[i].isEmpty()) {
				if (charmMode() || held().getItem() instanceof StickerItem) startPending(v, i);
			} else { // a sticker or charm already on: pick it, to scrape or detach it
				pendingSlot = -1;
				chosenSpot = chosenSpot == i ? -1 : i;
			}
			dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/submenu_leveloptions_select_01", 0.4f);
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent e, double dx, double dy) {
		SideView v = view();
		if (dragging && v != null && pendingSlot >= 0) {
			int[] vb = viewBox(v);
			double[] s = toSpace(v, vb, Math.max(vb[0], Math.min(vb[0] + vb[2], e.x())), Math.max(vb[1], Math.min(vb[1] + vb[3], e.y())));
			pu = s[0];
			pv = s[1];
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent e) {
		dragging = false;
		return super.mouseReleased(e);
	}

	private static void send(String cmd, String arg) {
		if (ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE)) ClientPlayNetworking.send(new StrafePayloads.Action(cmd, arg));
	}

	/** A sticker or charm drawn on the view at (x, y), size s, turned by turn radians; a sticker as worn as it is. */
	private void drawOn(GuiGraphicsExtractor g, ItemStack st, double x, double y, double s, double turn, double wear) {
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate((float) x, (float) y);
		pose.rotate((float) turn);
		drawWorn(g, st, (int) Math.round(-s / 2), (int) Math.round(-s / 2), (int) Math.round(s), (int) Math.round(s), wear);
		pose.popMatrix();
	}

	/** A sticker's own art (square, as it goes on the gun) at its wear, else its inventory picture while that loads; charms their picture. */
	private void drawWorn(GuiGraphicsExtractor g, ItemStack st, int x, int y, int w, int h, double wear) {
		Identifier worn = st.getItem() instanceof StickerItem ? StickerWear.texture(StickerItem.sticker(st), wear) : null;
		if (worn == null) {
			CaseImages.draw(g, st, x, y, w, h);
			return;
		}
		int side = Math.min(w, h);
		g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, worn, x + (w - side) / 2, y + (h - side) / 2, 0, 0, side, side, side, side);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, StrafeSettingsScreen.BG);
		var font = minecraft.font;
		ItemStack st = held();
		boolean charm = charmMode();
		String head = st.getItem() instanceof StickerItem || charm ? (charm ? "Hang " : "Apply ") + st.getHoverName().getString() : "Nothing to apply in that slot any more";
		g.text(font, head, width / 2 - font.width(head) / 2, 10, 0xFFFFFFFF, true);
		// left: the wraps that take stickers, by section (click a header to fold it); scrolls
		int cw = colW();
		ItemStack[] hover = {ItemStack.EMPTY};
		g.enableScissor(0, LIST_TOP, 20 + cw + 4, listBottom());
		layout(new Visit() {
			@Override
			public void header(Section s, int y) {
				boolean fold = folded.contains(s.key());
				String label = (fold ? "▶ " : "▼ ") + s.label() + "  " + s.refs().size();
				g.text(font, font.plainSubstrByWidth(label, cw), 20, y + 1, s.key() < 0 ? GOLD : 0xFF9AC8FF, false);
				if (!fold && s.refs().isEmpty()) g.text(font, "No gun wraps", 30, y + 13, 0xFF707070, false);
			}

			@Override
			public void item(String ref, int x, int y) {
				ItemStack s = at(ref);
				boolean on = ref.equals(picked);
				g.fill(x, y, x + 17, y + 17, on ? GOLD : 0xFF0E0F11);
				if (on) g.fill(x + 1, y + 1, x + 16, y + 16, 0xFF2A313A);
				g.item(s, x, y);
				if (mx >= x && mx < x + 18 && my >= y && my < y + 18 && my >= LIST_TOP && my < listBottom()) hover[0] = s;
			}
		});
		g.disableScissor();
		if (!hover[0].isEmpty()) g.setTooltipForNextFrame(font, hover[0], mx, my);
		ItemStack t = target();
		SideView v = view();
		apply.visible = cancel.visible = pendingSlot >= 0;
		wearSlider.visible = pendingSlot >= 0 && !charm;
		String[] in = t.isEmpty() ? new String[0] : in(t);
		boolean chosen = pendingSlot < 0 && chosenSpot >= 0 && chosenSpot < in.length && !in[chosenSpot].isEmpty();
		scrape.visible = chosen && !charm;
		detach.visible = chosen && charm;
		if (t.isEmpty()) {
			String s = "Pick a gun wrap on the left";
			g.text(font, s, 20 + cw + 30, 50, 0xFF909090, false);
		} else if (v == null) g.text(font, "Loading the gun...", 20 + cw + 30, 50, 0xFF909090, false);
		if (!t.isEmpty() && v != null) {
			int[] vb = viewBox(v);
			boolean placingSticker = pendingSlot >= 0 && !charm;
			g.fill(vb[0] - 2, vb[1] - 2, vb[0] + vb[2] + 2, vb[1] + vb[3] + 2, 0xFF161A1F);
			g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, placingSticker ? v.allowedTexture : v.texture, vb[0], vb[1], 0, 0, vb[2], vb[3], vb[2], vb[3]);
			int n = spots(t);
			boolean previewScrape = scrape.visible && scrape.isHovered();
			for (int i = 0; i < n; i++) { // what's on it already
				if (in[i].isEmpty()) continue;
				double[] p = placeOf(v, in[i], i);
				if (p == null) continue;
				double[] s = toScreen(v, vb, p[0], p[1]);
				double size = sizeOf(v, i, vb);
				String shownEntry = previewScrape && i == chosenSpot ? StickerSlots.scraped(in[i]) : in[i];
				if (shownEntry.isEmpty()) { // the next scrape takes it off: faint, outlined red
					drawOn(g, shown(in[i]), s[0], s[1], size, p.length > 2 ? p[2] : 0, 1);
					g.outline((int) (s[0] - size / 2), (int) (s[1] - size / 2), (int) size, (int) size, 0xFFE04040);
				} else drawOn(g, shown(in[i]), s[0], s[1], size, charm ? 0 : (p.length > 2 ? p[2] : 0), charm ? 0 : StickerSlots.wearOf(shownEntry));
				if (i == chosenSpot && pendingSlot < 0)
					g.outline((int) (s[0] - size / 2) - 1, (int) (s[1] - size / 2) - 1, (int) size + 2, (int) size + 2, GOLD);
			}
			if (pendingSlot >= 0) { // the one being placed
				double[] s = toScreen(v, vb, pu, pv);
				double size = sizeOf(v, pendingSlot, vb);
				boolean ok = placeable(v);
				drawOn(g, charm ? CharmItem.stack(WeaponItems.CHARM, CharmItem.charm(st)) : StickerItem.stack(WeaponItems.STICKER, StickerItem.sticker(st)), s[0], s[1], size,
						charm ? 0 : pturn, charm ? 0 : pwear);
				g.outline((int) (s[0] - size / 2) - 1, (int) (s[1] - size / 2) - 1, (int) size + 2, (int) size + 2, ok ? GOLD : 0xFFE04040);
				apply.active = ok;
				String help = charm ? (ok ? "Drag it to where it should hang, then Apply" : "Put it on the gun")
						: ok ? "Drag it over the gun's sticker area, scroll to turn it, pick its scrape, then Apply" : "Stickers only go on the lit part of the gun";
				g.text(font, help, vb[0] + vb[2] / 2 - font.width(help) / 2, height - 90, ok ? 0xFF909090 : 0xFFE04040, false);
			}
			if (chosen) {
				String what = in[chosenSpot];
				String info = charm ? "Detaching uses a Charm Detachment; the charm comes back"
						: StickerSlots.scraped(what).isEmpty() ? "Scraped through: the next scrape takes it off (it's gone)"
						: "Worn " + Math.round(StickerSlots.wearOf(what) * 100) + "%  (hover Scrape to preview the next one)";
				g.text(font, info, vb[0] + vb[2] / 2 - font.width(info) / 2, height - 66, 0xFF909090, false);
			}
			for (int i = 0; i < n; i++) { // the spots
				int[] b = spotBox(i, n, vb);
				boolean over = mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[2], sel = i == pendingSlot || i == chosenSpot;
				g.fill(b[0], b[1], b[0] + b[2], b[1] + b[2], over ? 0xFF2A313A : 0xFF22272E);
				g.outline(b[0], b[1], b[2], b[2], sel || over ? GOLD : 0xFF3A4048);
				if (in[i].isEmpty()) {
					String plus = "+";
					g.text(font, plus, b[0] + b[2] / 2 - font.width(plus) / 2, b[1] + b[2] / 2 - 4, 0xFF909090, false);
				} else drawWorn(g, shown(in[i]), b[0] + 2, b[1] + 2, b[2] - 4, b[2] - 4, charm ? 0 : StickerSlots.wearOf(in[i]));
				g.text(font, charm ? "Charm" : String.valueOf(i + 1), b[0] + 3, b[1] + 3, 0xFF707070, false);
			}
			if (pendingSlot < 0 && !chosen) {
				String help = charm ? "Click the charm spot to place it, or the charm to detach it" : "Click a free slot to place it, or a sticker to scrape it";
				int[] b0 = spotBox(0, Math.max(1, n), vb);
				g.text(font, help, vb[0] + vb[2] / 2 - font.width(help) / 2, b0[1] + b0[2] + 8, 0xFF909090, false);
			}
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
