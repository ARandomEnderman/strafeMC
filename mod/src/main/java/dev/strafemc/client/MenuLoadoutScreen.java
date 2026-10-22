package dev.strafemc.client;

import com.google.gson.JsonObject;
import dev.strafemc.weapon.Cases;
import dev.strafemc.weapon.GloveItem;
import dev.strafemc.weapon.GloveSkins;
import dev.strafemc.weapon.MenuLoadout;
import dev.strafemc.weapon.SkinWrapItem;
import dev.strafemc.weapon.SkinWraps;
import dev.strafemc.weapon.WeaponItems;
import dev.strafemc.weapon.WeaponTable;
import dev.strafemc.weapon.Wear;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Your loadout, from the main menu (also the in-game loadout on servers that use menu loadouts), kept by MyLoadout:
 * BUY MENU sets which weapons CS2's buy menu shows in each slot for each side, as CS2's loadout does (pistols, mid-tier,
 * rifles, five each; the first pistol slot is the starting pistol); SKINS picks a finish (float, pattern, StatTrak) for
 * every gun, the knife and its finish, and gloves; AGENTS opens the agents.
 */
public final class MenuLoadoutScreen extends Screen implements dev.strafemc.client.ui.UiFit.Sized {
	private static final int GOLD = dev.strafemc.client.ui.Style.GOLD, TILE = 0xFF22272E, TILE_HOVER = 0xFF2A313A, PANEL = 0xC01C2127,
			MUTED = dev.strafemc.client.ui.Style.MUTED;
	private static final String[] TITLES = {"PISTOLS", "MID-TIER", "RIFLES"};
	private static final int TOP = dev.strafemc.client.ui.Style.BAR_H + 6, ROW = 14, TILE_W = 76, TILE_H = 60;

	private static int lastTab;
	private static boolean lastT;
	private static String lastBuySlot = "secondary0", lastSkin = "knife_model";

	private final Screen parent;
	private int tab = lastTab;
	private boolean t = lastT;
	private String buySlot = lastBuySlot, skinSlot = lastSkin;
	private double listScroll, gridScroll;
	/** The tab buttons and the side buttons (BUY MENU: T, CT), for marking the chosen one. */
	private final List<Button> tabButtons = new ArrayList<>(), sideButtons = new ArrayList<>();
	private List<Option> options = List.of();
	private String optionsFor;

	/**
	 * One choice in the skins grid: its id (null: the default look), name, rarity colour and CS2 picture. No items: this
	 * screen opens from the main menu, before a world has set items up.
	 */
	private record Option(String id, String label, int color, Cases.Entry picture, String image) {}

	/** One row of the skins list: a slot ("knife_model", "knife", "gloves", a gun's class), or a group header (slot null). */
	private record Row(String slot, String label) {}

	public MenuLoadoutScreen(Screen parent) {
		super(Component.literal("Loadout"));
		this.parent = parent;
	}

	/** Opens on a tab (0 buy menu, 1 skins). */
	public MenuLoadoutScreen(Screen parent, int tab) {
		this(parent);
		this.tab = tab;
	}

	/** In a world (a game mode server, mid-match): skins and agents only; the buy menu loadout is set in the main menu. */
	private boolean inGame() {
		return minecraft != null && minecraft.level != null;
	}

	@Override
	protected void init() {
		if (inGame() && tab == 0) tab = 1;
		else lastTab = tab;
		tabButtons.clear();
		sideButtons.clear();
		String[] tabs = {"BUY MENU", "SKINS", "AGENTS"};
		int first = inGame() ? 1 : 0, tw = 90, tx = width / 2 - (tabs.length - first) * tw / 2;
		for (int i = first; i < tabs.length; i++) {
			final int k = i;
			tabButtons.add(addRenderableWidget(Button.builder(Component.literal(tabs[i]), b -> {
				if (k == 2) {
					minecraft.setScreen(new AgentScreen(this));
					return;
				}
				tab = k;
				rebuildWidgets();
			}).bounds(tx + (i - first) * tw, 5, tw - 2, 20).build()));
		}
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 26, 200, 20).build());
		if (tab == 0) {
			sideButtons.add(addRenderableWidget(Button.builder(Component.literal("Terrorist"), b -> side(true)).bounds(20, TOP, 90, 20).build()));
			sideButtons.add(addRenderableWidget(Button.builder(Component.literal("Counter-Terrorist"), b -> side(false)).bounds(112, TOP, 110, 20).build()));
		} else skinWidgets();
	}

	private void side(boolean toT) {
		t = lastT = toT;
		if (!MenuLoadout.fits(buySlot, MyLoadout.buy(buySlot, t), t)) buySlot = "secondary0";
	}

	// ---------- BUY MENU ----------

	private int buyLeftW() {
		return Math.min((int) (width * 0.55), 420);
	}

	/** Tile rect of column c, row r (x0, y0, x1, y1). */
	private int[] buyTile(int c, int r) {
		int lw = buyLeftW(), cw = (lw - 16) / 3, x = 20 + c * (cw + 8), y = TOP + 40 + r * 46;
		return new int[] {x, y, x + cw, y + 40};
	}

	private List<String> buyOptions() {
		List<String> out = new ArrayList<>();
		String category = buySlot.substring(0, buySlot.length() - 1);
		for (String cls : MenuLoadout.eligible(category, t)) if (MenuLoadout.fits(buySlot, cls, t)) out.add(cls);
		return out;
	}

	private int[] optionTile(int i) {
		int x0 = 20 + buyLeftW() + 24, w = 100, cols = Math.max(1, (width - 20 - x0) / (w + 6));
		int x = x0 + (i % cols) * (w + 6), y = TOP + 40 + (i / cols) * 46;
		return new int[] {x, y, x + w, y + 40};
	}

	private void weaponTile(GuiGraphicsExtractor g, int[] r, String cls, boolean on, boolean hover, String key) {
		g.fill(r[0], r[1], r[2], r[3], hover ? TILE_HOVER : TILE);
		if (on) g.outline(r[0] - 1, r[1] - 1, r[2] - r[0] + 2, r[3] - r[1] + 2, GOLD);
		if (cls == null) return;
		var e = WeaponTable.get(cls);
		if (e != null) BuyMenuScreen.icon(g, e.shortName(), (r[0] + r[2]) / 2.0, (r[1] + r[3]) / 2.0 - 3, (r[3] - r[1]) * 0.5, 0xFFFFFFFF);
		String name = font.plainSubstrByWidth(WeaponTable.name(cls), r[2] - r[0] - 6);
		g.text(font, name, r[2] - 3 - font.width(name), r[3] - 10, 0xFFE0E0E0, false);
		if (key != null) g.text(font, key, r[0] + 3, r[1] + 3, 0x66FFFFFF, false);
	}

	private void renderBuy(GuiGraphicsExtractor g, int mx, int my) {
		for (int c = 0; c < 3; c++) {
			int[] h = buyTile(c, 0);
			g.text(font, TITLES[c], h[0], h[1] - 12, GOLD, false);
			for (int r = 0; r < MenuLoadout.SLOTS; r++) {
				int[] rc = buyTile(c, r);
				String slot = MenuLoadout.CATEGORIES.get(c) + r;
				boolean over = in(mx, my, rc);
				weaponTile(g, rc, MyLoadout.buy(slot, t), slot.equals(buySlot), over, String.valueOf(r + 1));
			}
		}
		List<String> opts = buyOptions();
		int[] o0 = optionTile(0);
		String head = buySlot.equals("secondary0") ? "Starting pistol" : "Choose for slot " + (buySlot.charAt(buySlot.length() - 1) - '0' + 1);
		g.text(font, head, o0[0], o0[1] - 12, GOLD, false);
		String current = MyLoadout.buy(buySlot, t);
		for (int i = 0; i < opts.size(); i++) {
			int[] rc = optionTile(i);
			weaponTile(g, rc, opts.get(i), opts.get(i).equals(current), in(mx, my, rc), null);
		}
		g.text(font, "Click a slot, then the weapon for it. A weapon already in another slot swaps places.", 20, height - 44, MUTED, false);
	}

	private boolean clickBuy(double mx, double my) {
		for (int c = 0; c < 3; c++)
			for (int r = 0; r < MenuLoadout.SLOTS; r++)
				if (in(mx, my, buyTile(c, r))) {
					buySlot = lastBuySlot = MenuLoadout.CATEGORIES.get(c) + r;
					click();
					return true;
				}
		List<String> opts = buyOptions();
		for (int i = 0; i < opts.size(); i++)
			if (in(mx, my, optionTile(i))) {
				MyLoadout.setBuy(buySlot, t, opts.get(i));
				click();
				return true;
			}
		return false;
	}

	// ---------- SKINS ----------

	private List<Row> rows() {
		List<Row> out = new ArrayList<>(List.of(new Row("knife_model", "Knife"), new Row("knife", "Knife finish"), new Row("gloves", "Gloves")));
		List<WeaponTable.Entry> guns = new ArrayList<>(WeaponTable.guns());
		guns.sort(java.util.Comparator.comparingInt((WeaponTable.Entry e) -> WeaponTable.order(e.cls())));
		int group = -1;
		for (WeaponTable.Entry e : guns) {
			int gr = WeaponTable.group(e.cls());
			if (gr != group) {
				group = gr;
				out.add(new Row(null, gr < WeaponTable.GROUP_NAMES.size() ? WeaponTable.GROUP_NAMES.get(gr) : "Other"));
			}
			out.add(new Row(e.cls(), WeaponTable.name(e.cls())));
		}
		return out;
	}

	private static final int LIST_X = 10, LIST_W = 130, PANEL_W = 160;

	private int gridX0() {
		return LIST_X + LIST_W + 10;
	}

	private int gridX1() {
		return width - PANEL_W - 16;
	}

	private int gridCols() {
		return Math.max(1, (gridX1() - gridX0()) / (TILE_W + 4));
	}

	private int[] gridTile(int i) {
		int x = gridX0() + (i % gridCols()) * (TILE_W + 4), y = TOP + (i / gridCols()) * (TILE_H + 4) - (int) Math.round(gridScroll);
		return new int[] {x, y, x + TILE_W, y + TILE_H};
	}

	private static String after(String name) {
		int bar = name.indexOf(" | ");
		return bar >= 0 ? name.substring(bar + 3) : name;
	}

	/** The knife model finish pictures are shown on: the chosen knife, else the Karambit (default knives have no finishes). */
	private static String knifeForPictures() {
		String k = MyLoadout.knife();
		return k != null ? k : "weapon_knife_karambit";
	}

	private List<Option> options() {
		String key = skinSlot + "|" + knifeForPictures();
		if (key.equals(optionsFor)) return options;
		List<Option> out = new ArrayList<>();
		int covert = 0xFF000000 | SkinWraps.Rarity.ANCIENT.color;
		switch (skinSlot) {
			case "knife_model" -> {
				out.add(new Option(null, "Default", 0xFF808080, null, null));
				for (WeaponTable.Entry e : WeaponTable.knives()) {
					if (e.loadoutDefault()) continue; // the sides' own knives
					out.add(new Option(e.cls(), WeaponTable.name(e.cls()).replace(" Knife", ""), covert, null, "panorama/images/econ/weapons/base_weapons/" + e.cls() + "_png"));
				}
			}
			case "knife" -> {
				out.add(new Option(null, "Vanilla", 0xFF808080, null, null));
				String knife = knifeForPictures();
				for (SkinWraps.Wrap w : SkinWraps.all()) {
					if (!w.knife() || w.kits().isEmpty()) continue;
					String kit = w.kits().get(knife);
					Cases.Entry pic = kit != null ? new Cases.Entry(kit, knife) : new Cases.Entry(w.kits().values().iterator().next(), w.kits().keySet().iterator().next());
					out.add(new Option(w.id(), after(w.name()), covert, pic, null));
				}
			}
			case "gloves" -> {
				out.add(new Option(null, "Default", 0xFF808080, null, null));
				for (GloveSkins.Skin s : GloveSkins.ALL) {
					String name = Component.translatable("item.strafemc." + s.kind().id).getString() + " | "
							+ Component.translatableWithFallback("strafemc.paintkit." + s.kit(), GloveSkins.fallbackName(s.kit())).getString();
					out.add(new Option(s.kit(), name, covert, new Cases.Entry(s.kit(), s.kind().id), null));
				}
			}
			default -> {
				out.add(new Option(null, "Default", 0xFF808080, null, null));
				for (SkinWraps.Wrap w : SkinWraps.all()) {
					String kit = w.knife() ? null : w.kits().get(skinSlot);
					if (kit == null) continue;
					var r = w.rarity();
					out.add(new Option(w.id(), after(w.name()), r == null ? 0xFF808080 : 0xFF000000 | r.color, new Cases.Entry(kit, skinSlot), null));
				}
			}
		}
		List<Cases.Entry> pics = new ArrayList<>();
		List<String> images = new ArrayList<>();
		for (Option o : out) {
			if (o.picture != null) pics.add(o.picture);
			if (o.image != null) images.add(o.image);
		}
		CaseImages.load(pics); // one converter run for the whole grid
		if (!images.isEmpty()) CaseImages.images(images);
		options = out;
		optionsFor = key;
		return out;
	}

	/** The selected option's id in the current slot. */
	private String chosen() {
		if (skinSlot.equals("knife_model")) return MyLoadout.knife();
		JsonObject s = MyLoadout.skin(skinSlot);
		if (s == null) return null;
		String k = skinSlot.equals("gloves") ? "kit" : "wrap";
		return s.has(k) ? s.get(k).getAsString() : null;
	}

	private void pick(Option o) {
		if (skinSlot.equals("knife_model")) MyLoadout.setKnife(o.id);
		else if (o.id == null) MyLoadout.setSkin(skinSlot, null);
		else {
			JsonObject old = MyLoadout.skin(skinSlot), e = new JsonObject();
			e.addProperty(skinSlot.equals("gloves") ? "kit" : "wrap", o.id);
			double[] r = Wear.range(kit(o.id));
			double wear = old != null && old.has("wear") ? old.get("wear").getAsDouble() : r[0];
			e.addProperty("wear", Math.max(r[0], Math.min(r[1], wear)));
			if (!skinSlot.equals("gloves")) {
				e.addProperty("seed", old != null && old.has("seed") ? old.get("seed").getAsInt() : 0);
				if (old != null && old.has("st")) e.add("st", old.get("st"));
			}
			MyLoadout.setSkin(skinSlot, e);
		}
		click();
		rebuildWidgets();
	}

	/** The paint kit of an option in the current slot (for its float range). */
	private String kit(String id) {
		if (id == null) return null;
		if (skinSlot.equals("gloves")) return id;
		if (skinSlot.equals("knife")) return SkinWraps.kitFor(id, knifeForPictures());
		return SkinWraps.kitFor(id, skinSlot);
	}

	/** The right panel's controls for the chosen finish: float, pattern seed, StatTrak. */
	private void skinWidgets() {
		if (skinSlot.equals("knife_model")) return;
		JsonObject e = MyLoadout.skin(skinSlot);
		String id = chosen();
		if (e == null || id == null) return;
		int x = width - PANEL_W - 6, y = TOP + 160;
		double[] r = Wear.range(kit(id));
		double wear = e.has("wear") ? e.get("wear").getAsDouble() : r[0];
		addRenderableWidget(new AbstractSliderButton(x, y, PANEL_W - 4, 20, Component.empty(), r[1] > r[0] ? (wear - r[0]) / (r[1] - r[0]) : 0) {
			{
				updateMessage();
			}

			private double v() {
				return r[0] + value * (r[1] - r[0]);
			}

			@Override
			protected void updateMessage() {
				setMessage(Component.literal(String.format(java.util.Locale.ROOT, "Float %.4f", v())));
			}

			@Override
			protected void applyValue() {
				JsonObject cur = MyLoadout.skin(skinSlot);
				if (cur == null) return;
				cur.addProperty("wear", Math.round(v() * 1e4) / 1e4);
				MyLoadout.setSkin(skinSlot, cur);
			}
		});
		if (skinSlot.equals("gloves")) return;
		SkinWraps.Wrap w = SkinWraps.get(id);
		if (w != null && w.pattern()) {
			EditBox seed = new EditBox(font, x, y + 34, PANEL_W - 4, 18, Component.literal("Pattern"));
			seed.setMaxLength(4);
			seed.setValue(String.valueOf(e.has("seed") ? e.get("seed").getAsInt() : 0));
			seed.setResponder(v -> {
				JsonObject cur = MyLoadout.skin(skinSlot);
				if (cur == null) return;
				try {
					int n = Math.max(0, Math.min(1000, Integer.parseInt(v.trim())));
					cur.addProperty("seed", n);
					MyLoadout.setSkin(skinSlot, cur);
				} catch (NumberFormatException ignored) {
				}
			});
			addRenderableWidget(seed);
		}
		boolean st = e.has("st") && e.get("st").getAsBoolean();
		addRenderableWidget(Button.builder(Component.literal("StatTrak: " + (st ? "on" : "off")), b -> {
			JsonObject cur = MyLoadout.skin(skinSlot);
			if (cur == null) return;
			cur.addProperty("st", !(cur.has("st") && cur.get("st").getAsBoolean()));
			MyLoadout.setSkin(skinSlot, cur);
			rebuildWidgets();
		}).bounds(x, y + 58, PANEL_W - 4, 20).build());
	}

	private void renderSkins(GuiGraphicsExtractor g, int mx, int my) {
		int bottom = height - 34;
		// the list of slots
		List<Row> rows = rows();
		g.fill(LIST_X, TOP, LIST_X + LIST_W, bottom, PANEL);
		g.enableScissor(LIST_X, TOP, LIST_X + LIST_W, bottom);
		for (int i = 0; i < rows.size(); i++) {
			Row r = rows.get(i);
			int y = TOP + 2 + i * ROW - (int) Math.round(listScroll);
			if (y + ROW < TOP || y > bottom) continue;
			if (r.slot == null) {
				g.text(font, r.label, LIST_X + 4, y + 3, GOLD, false);
				continue;
			}
			boolean sel = r.slot.equals(skinSlot), over = mx >= LIST_X && mx < LIST_X + LIST_W && my >= y && my < y + ROW && my >= TOP && my < bottom;
			if (sel || over) g.fill(LIST_X, y, LIST_X + LIST_W, y + ROW, sel ? 0x60EABE54 : 0x30FFFFFF);
			boolean set = r.slot.equals("knife_model") ? MyLoadout.knife() != null : MyLoadout.skin(r.slot) != null;
			g.text(font, font.plainSubstrByWidth(r.label, LIST_W - 18), LIST_X + 10, y + 3, set ? 0xFFFFFFFF : MUTED, false);
			if (set) g.fill(LIST_X + 4, y + 5, LIST_X + 7, y + 8, GOLD);
		}
		g.disableScissor();
		// the finishes
		List<Option> opts = options();
		String chosen = chosen();
		g.enableScissor(gridX0(), TOP, gridX1(), bottom);
		for (int i = 0; i < opts.size(); i++) {
			Option o = opts.get(i);
			int[] rc = gridTile(i);
			if (rc[3] < TOP || rc[1] > bottom) continue;
			boolean on = java.util.Objects.equals(o.id, chosen), over = in(mx, my, rc) && my >= TOP && my < bottom;
			g.fill(rc[0], rc[1], rc[2], rc[3], over ? TILE_HOVER : TILE);
			g.fill(rc[0], rc[3] - 2, rc[2], rc[3], o.color);
			if (on) g.outline(rc[0] - 1, rc[1] - 1, TILE_W + 2, TILE_H + 2, GOLD);
			Identifier pic = o.picture != null ? CaseImages.get(o.picture) : o.image != null ? CaseImages.image(o.image) : null;
			int pw = TILE_W - 8, ph = pw * 3 / 4;
			if (pic != null) g.blit(RenderPipelines.GUI_TEXTURED, pic, rc[0] + 4, rc[1] + 2, 0, 0, pw, ph, pw, ph);
			String label = font.plainSubstrByWidth(o.label, TILE_W - 4);
			g.text(font, label, rc[0] + TILE_W / 2 - font.width(label) / 2, rc[3] - 12, 0xFFE0E0E0, false);
		}
		g.disableScissor();
		// the chosen one, large, with its exterior
		int px = width - PANEL_W - 6;
		g.fill(px - 4, TOP, width - 6, bottom, PANEL);
		Option sel = null;
		for (Option o : opts) if (o.id != null && o.id.equals(chosen)) sel = o;
		int pw = PANEL_W - 8, ph = pw * 3 / 4;
		Identifier big = sel == null ? null : sel.picture != null ? CaseImages.get(sel.picture) : sel.image != null ? CaseImages.image(sel.image) : null;
		if (big != null) g.blit(RenderPipelines.GUI_TEXTURED, big, px, TOP + 4, 0, 0, pw, ph, pw, ph);
		String name = sel == null ? "Default" : skinSlot.equals("gloves") || skinSlot.equals("knife_model") ? sel.label
				: (skinSlot.equals("knife") ? WeaponTable.name(knifeForPictures()) : WeaponTable.name(skinSlot)) + " | " + sel.label;
		if (sel != null && skinSlot.equals("knife_model")) name = WeaponTable.name(sel.id);
		JsonObject entry = skinSlot.equals("knife_model") ? null : MyLoadout.skin(skinSlot);
		if (entry != null && entry.has("st") && entry.get("st").getAsBoolean()) name = "StatTrak™ " + name;
		int ty = TOP + 4 + (PANEL_W - 8) * 3 / 4 + 4;
		for (var line : font.split(Component.literal(name), PANEL_W - 8)) {
			g.text(font, line, px, ty, 0xFFFFFFFF, false);
			ty += 10;
		}
		JsonObject e = skinSlot.equals("knife_model") ? null : MyLoadout.skin(skinSlot);
		if (e != null && e.has("wear")) g.text(font, Wear.exterior(e.get("wear").getAsDouble()), px, ty, MUTED, false);
		SkinWraps.Wrap w = SkinWraps.get(chosen);
		if (w != null && w.pattern() && !skinSlot.equals("gloves") && !skinSlot.equals("knife_model"))
			g.text(font, "Pattern (0-1000)", px, TOP + 160 + 24, MUTED, false);
		for (var line : font.split(Component.literal("Worn on game mode servers."), PANEL_W - 8)) g.text(font, line, px, bottom - 12, MUTED, false);
	}

	private boolean clickSkins(double mx, double my) {
		int bottom = height - 34;
		if (my < TOP || my >= bottom) return false;
		if (mx >= LIST_X && mx < LIST_X + LIST_W) {
			List<Row> rows = rows();
			int i = (int) Math.floor((my - TOP - 2 + listScroll) / ROW);
			if (i >= 0 && i < rows.size() && rows.get(i).slot != null) {
				skinSlot = lastSkin = rows.get(i).slot;
				gridScroll = 0;
				click();
				rebuildWidgets();
			}
			return true;
		}
		if (mx >= gridX0() && mx < gridX1()) {
			List<Option> opts = options();
			for (int i = 0; i < opts.size(); i++)
				if (in(mx, my, gridTile(i))) {
					pick(opts.get(i));
					return true;
				}
		}
		return false;
	}

	// ---------- shared ----------

	private static boolean in(double mx, double my, int[] r) {
		return mx >= r[0] && mx < r[2] && my >= r[1] && my < r[3];
	}

	private static void click() {
		dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/submenu_leveloptions_select_01", 0.4f);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) return true;
		if (e.button() != 0) return false;
		return tab == 0 ? clickBuy(e.x(), e.y()) : clickSkins(e.x(), e.y());
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (tab != 1) return super.mouseScrolled(mx, my, sx, sy);
		int view = height - 34 - TOP;
		if (mx < LIST_X + LIST_W) {
			int max = Math.max(0, rows().size() * ROW + 4 - view);
			listScroll = Math.max(0, Math.min(max, listScroll - sy * ROW * 3));
		} else if (mx < gridX1()) {
			int lines = (options().size() + gridCols() - 1) / gridCols(), max = Math.max(0, lines * (TILE_H + 4) - view);
			gridScroll = Math.max(0, Math.min(max, gridScroll - sy * (TILE_H + 4) / 2.0));
		}
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		dev.strafemc.client.ui.Style.backdrop(g, width, height);
		if (tab == 0) renderBuy(g, mx, my);
		else renderSkins(g, mx, my);
		super.extractRenderState(g, mx, my, delta);
		int shown = tab - (inGame() ? 1 : 0); // in a world the buy menu tab isn't there
		if (shown >= 0 && shown < tabButtons.size()) dev.strafemc.client.ui.Style.underline(g, tabButtons.get(shown));
		if (sideButtons.size() == 2) dev.strafemc.client.ui.Style.underline(g, sideButtons.get(t ? 0 : 1));
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}

	@Override
	public void onClose() {
		MyLoadout.send();
		minecraft.setScreen(parent);
	}
}
