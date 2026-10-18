package dev.strafemc.client;

import dev.strafemc.client.viewmodel.SideView;
import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.CharmItem;
import dev.strafemc.weapon.Loadout;
import dev.strafemc.weapon.SkinWrapItem;
import dev.strafemc.weapon.SkinWraps;
import dev.strafemc.weapon.StickerItem;
import dev.strafemc.weapon.StickerSlots;
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
import java.util.List;

/**
 * Applying a sticker or hanging a charm (right-click one), placed like in CS2: your gun wraps on the left (the
 * loadout's first, then your inventory's); the picked one on the right seen from the side, in CS2's sticker space. For a
 * sticker pick a free slot: it shows at that slot's place, drag it anywhere on the gun and scroll to turn it (its size is
 * the gun's slot size, as in CS2), then Apply. A charm starts at the gun's keychain point; drag it to any point on the gun
 * and Apply. Right-click a sticker to scrape it off (it's destroyed), a charm to take it off (a Charm Detachment).
 */
public final class StickerScreen extends Screen {
	private static final int GOLD = 0xFFEABE54, ROW = 22;
	/** The inventory slot of the sticker or charm being applied. */
	private final int stickerSlot;

	private record Target(String ref, ItemStack stack) {}

	private final List<Target> targets = new ArrayList<>();
	private int picked = -1;
	private double scroll;
	/** Being placed: which slot (-1: none; the charm uses 0), where (sticker space) and its turn (radians). */
	private int pendingSlot = -1;
	private double pu, pv, pturn;
	/** How scraped the sticker being placed goes on (0-90%, by SCRAPE steps). */
	private double pwear;
	private ScrapeSlider wearSlider;

	/** The scrape slider for the sticker being placed: as new to 90% scraped, in scrape steps, previewed live. */
	private final class ScrapeSlider extends net.minecraft.client.gui.components.AbstractSliderButton {
		ScrapeSlider(int x, int y, int w) {
			super(x, y, w, 20, Component.empty(), 0);
			updateMessage();
		}

		double wear() {
			int steps = (int) Math.round(1 / StickerSlots.SCRAPE) - 1;
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
	private boolean dragging;
	private Button apply, cancel;

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
		int was = picked;
		picked = -1;
		for (int i = 0; i < targets.size(); i++) if (targets.get(i).ref().equals(keep)) picked = i;
		if (picked < 0 && !targets.isEmpty()) picked = 0;
		if (picked != was && was >= 0) pendingSlot = -1;
	}

	private Target target() {
		return picked >= 0 && picked < targets.size() ? targets.get(picked) : null;
	}

	/** The picked wrap's gun seen from the side (null while it's made). */
	private SideView view() {
		Target t = target();
		if (t == null) return null;
		String gun = StickerSlots.gun(t.stack());
		if (gun == null) return null;
		return SideView.of(gun, SkinWraps.kitFor(SkinWrapItem.skin(t.stack()), gun), SkinWrapItem.finish(t.stack()));
	}

	private int listW() {
		return Math.min(220, width / 3);
	}

	/** The side view's box: x, y, width, height (sticker space u 0-1 across its width). */
	private int[] viewBox(SideView v) {
		int px = 20 + listW() + 20, pw = width - px - 20;
		int ph = v == null ? pw / 3 : (int) Math.round(pw * (double) v.h / SideView.W);
		int maxH = height - 150;
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

	/** Spot i's button under the view. */
	private int[] spotBox(int i, int n, int[] vb) {
		int size = 40, total = n * size + (n - 1) * 6, x = vb[0] + (vb[2] - total) / 2 + i * (size + 6), y = vb[1] + vb[3] + 14;
		return new int[] {x, y, size};
	}

	/** Where entry sits in sticker space: its own place, or slot i's (a charm: the keychain point / its point). */
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
		double[] p = charmMode() ? v.keychain : slot < v.slots.size() ? new double[] {v.slots.get(slot).u(), v.slots.get(slot).v(), v.slots.get(slot).rotation()} : null;
		if (p == null) p = new double[] {0.5, (v.v0 + v.v1) / 2, 0};
		pu = p[0];
		pv = p[1];
		pturn = p.length > 2 ? p[2] : 0;
		if (wearSlider != null) wearSlider.reset();
	}

	private void applyPending() {
		Target t = target();
		SideView v = view();
		if (t == null || v == null || pendingSlot < 0) return;
		if (charmMode()) {
			float[] pt = v.pointAt(pu, pv);
			if (pt == null) return;
			send("charm_apply", stickerSlot + " " + t.ref() + " " + String.format(java.util.Locale.ROOT, "%.5f,%.5f,%.5f", pt[0], pt[1], pt[2]));
		} else send("sticker_apply", stickerSlot + " " + t.ref() + " " + pendingSlot + " " + String.format(java.util.Locale.ROOT, "%.4f,%.4f,%.4f %.2f", pu, pv, pturn, pwear));
		dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/claim_gift_01", 0.3f);
		pendingSlot = -1;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (mx < 20 + listW()) {
			scroll = Math.max(0, Math.min(Math.max(0, targets.size() * ROW - (height - 90)), scroll - sy * ROW));
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
		if (mx >= 20 && mx < 20 + listW() && my >= 40 && my < height - 50) {
			int i = (int) ((my - 40 + scroll) / ROW);
			if (i >= 0 && i < targets.size()) {
				picked = i;
				pendingSlot = -1;
				dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/submenu_leveloptions_select_01", 0.4f);
			}
			return true;
		}
		Target t = target();
		SideView v = view();
		if (t == null || v == null) return false;
		int[] vb = viewBox(v);
		if (pendingSlot >= 0 && mx >= vb[0] && mx < vb[0] + vb[2] && my >= vb[1] && my < vb[1] + vb[3]) { // grab it (or put it where clicked)
			double[] s = toSpace(v, vb, mx, my);
			pu = s[0];
			pv = s[1];
			dragging = true;
			return true;
		}
		int n = spots(t.stack());
		String[] in = in(t.stack());
		for (int i = 0; i < n; i++) {
			int[] b = spotBox(i, n, vb);
			if (mx < b[0] || mx >= b[0] + b[2] || my < b[1] || my >= b[1] + b[2]) continue;
			if (e.button() == 1 && !in[i].isEmpty()) send(charmMode() ? "charm_remove" : "sticker_scrape", charmMode() ? t.ref() : t.ref() + " " + i);
			else if (e.button() == 0 && in[i].isEmpty() && (charmMode() || held().getItem() instanceof StickerItem)) startPending(v, i);
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

	/** A sticker's picture worn by scraping (CS2's scratches), else the plain picture. */
	private void drawWorn(GuiGraphicsExtractor g, ItemStack st, int x, int y, int w, int h, double wear) {
		Identifier worn = wear > 0 && st.getItem() instanceof StickerItem ? StickerWear.texture(StickerItem.sticker(st), wear) : null;
		if (worn == null) {
			CaseImages.draw(g, st, x, y, w, h);
			return;
		}
		int pw = Math.min(w, h * 4 / 3), ph = pw * 3 / 4; // CS2's sticker pictures are 4:3
		g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, worn, x + (w - pw) / 2, y + (h - ph) / 2, 0, 0, pw, ph, pw, ph);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		refresh();
		g.fill(0, 0, width, height, StrafeSettingsScreen.BG);
		var font = minecraft.font;
		ItemStack st = held();
		boolean charm = charmMode();
		String head = st.getItem() instanceof StickerItem || charm ? (charm ? "Hang " : "Apply ") + st.getHoverName().getString() : "Nothing to apply in that slot any more";
		g.text(font, head, width / 2 - font.width(head) / 2, 10, 0xFFFFFFFF, true);
		int lw = listW();
		g.text(font, "Your gun wraps", 20, 26, 0xFFB0B0B0, false);
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
		if (targets.isEmpty()) g.text(font, "No gun wraps (knives don't take stickers or charms)", 24, 44, 0xFF808080, false);
		Target t = target();
		SideView v = view();
		apply.visible = cancel.visible = pendingSlot >= 0;
		wearSlider.visible = pendingSlot >= 0 && !charmMode();
		if (t != null && v == null) g.text(font, "Loading the gun...", 20 + lw + 30, 50, 0xFF909090, false);
		if (t != null && v != null) {
			int[] vb = viewBox(v);
			g.fill(vb[0] - 2, vb[1] - 2, vb[0] + vb[2] + 2, vb[1] + vb[3] + 2, 0xFF161A1F);
			g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, v.texture, vb[0], vb[1], 0, 0, vb[2], vb[3], vb[2], vb[3]);
			int n = spots(t.stack());
			String[] in = in(t.stack());
			int hoverSpot = -1; // a spot under the mouse: its sticker shows how the next scrape leaves it
			for (int i = 0; i < n; i++) {
				int[] b = spotBox(i, n, vb);
				if (mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[2]) hoverSpot = i;
			}
			for (int i = 0; i < n; i++) { // what's on it already
				if (in[i].isEmpty()) continue;
				double[] p = placeOf(v, in[i], i);
				if (p == null) continue;
				double[] s = toScreen(v, vb, p[0], p[1]);
				double size = sizeOf(v, i, vb);
				if (!charm && hoverSpot < 0 && Math.abs(mx - s[0]) < size / 2 && Math.abs(my - s[1]) < size / 2) hoverSpot = i;
				boolean preview = !charm && i == hoverSpot;
				String next = preview ? StickerSlots.scraped(in[i]) : in[i];
				if (preview && next.isEmpty()) { // the next scrape takes it off: shown faint, outlined red
					drawOn(g, shown(in[i]), s[0], s[1], size, p.length > 2 ? p[2] : 0, 0.9);
					g.outline((int) (s[0] - size / 2), (int) (s[1] - size / 2), (int) size, (int) size, 0xFFE04040);
				} else drawOn(g, shown(in[i]), s[0], s[1], size, charm ? 0 : (p.length > 2 ? p[2] : 0), charm ? 0 : StickerSlots.wearOf(next));
				if (preview) {
					String wl = next.isEmpty() ? "Next scrape removes it" : "After a scrape: " + Math.round(StickerSlots.wearOf(next) * 100) + "% worn";
					g.setTooltipForNextFrame(font, List.of(shown(in[i]).getHoverName(), Component.literal("§7Worn " + Math.round(StickerSlots.wearOf(in[i]) * 100) + "%"),
							Component.literal("§e" + wl), Component.literal("§8Right-click its slot to scrape")), java.util.Optional.empty(), mx, my);
				}
			}
			if (pendingSlot >= 0) { // the one being placed
				double[] s = toScreen(v, vb, pu, pv);
				double size = sizeOf(v, pendingSlot, vb);
				drawOn(g, charm ? CharmItem.stack(WeaponItems.CHARM, CharmItem.charm(st)) : StickerItem.stack(WeaponItems.STICKER, StickerItem.sticker(st)), s[0], s[1], size,
						charm ? 0 : pturn, charm ? 0 : pwear);
				g.outline((int) (s[0] - size / 2) - 1, (int) (s[1] - size / 2) - 1, (int) size + 2, (int) size + 2, GOLD);
				boolean onGun = v.pointAt(pu, pv) != null;
				apply.active = !charm || onGun;
				String help = charm ? (onGun ? "Drag it to where it should hang, then Apply" : "Put it on the gun")
						: "Drag it into place, scroll to turn it, pick its scrape, then Apply";
				g.text(font, help, vb[0] + vb[2] / 2 - font.width(help) / 2, height - 90, 0xFF909090, false);
			}
			for (int i = 0; i < n; i++) { // the spots
				int[] b = spotBox(i, n, vb);
				boolean over = mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[2], sel = i == pendingSlot;
				g.fill(b[0], b[1], b[0] + b[2], b[1] + b[2], over ? 0xFF2A313A : 0xFF22272E);
				g.outline(b[0], b[1], b[2], b[2], sel || over ? GOLD : 0xFF3A4048);
				if (in[i].isEmpty()) {
					String plus = "+";
					g.text(font, plus, b[0] + b[2] / 2 - font.width(plus) / 2, b[1] + b[2] / 2 - 4, 0xFF909090, false);
				} else {
					ItemStack on = shown(in[i]);
					drawWorn(g, on, b[0] + 2, b[1] + 2, b[2] - 4, b[2] - 4, charm ? 0 : StickerSlots.wearOf(over ? StickerSlots.scraped(in[i]) : in[i]));
					if (over && charm) g.setTooltipForNextFrame(font, List.of(on.getHoverName(), Component.literal("§7Right-click to take it off (uses a Charm Detachment)")),
							java.util.Optional.empty(), mx, my);
				}
				g.text(font, charm ? "Charm" : String.valueOf(i + 1), b[0] + 3, b[1] + 3, 0xFF707070, false);
			}
			if (pendingSlot < 0) {
				String help = charm ? "Click the charm spot to place it" : "Click a free slot to place it";
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
