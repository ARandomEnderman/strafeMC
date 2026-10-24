package dev.strafemc.client;

import dev.strafemc.client.console.ConsoleCommands;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.data.WeaponData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CS2's buy menu (B), laid out from its panorama files (buymenu.vxml / buymenu.vcss): five columns GEAR, PISTOLS,
 * MID-TIER, RIFLES, GRENADES (18/18/22/24/18 % of a 950 px panel, 180 px from the top), each with five 90 px slots
 * 10 px apart in CS2's loadout order; dark tiles with the key top left, the name top right, the white equipment icon
 * in the middle and the price bottom right; a number key picks a column, a second one buys from it. Prices are
 * CS2's (weapons.vdata m_nPrice) though strafeMC has no money. Sizes are CS2's 1080p pixels scaled to the window.
 * The server decides
 * whether you may buy (strafemc_buymenu, strafemc_buytime) and applies CS2's grenade limits. Weapon columns show CS2's
 * default loadout for your side (items_game flexible_loadout_default). FLAGGED: Minecraft's font for CS2's Stratum; no
 * agent model. The weapons in each slot are chosen in the main menu's loadout (MenuLoadoutScreen).
 */
public final class BuyMenuScreen extends Screen {
	/** CS2 equipment icons the runtime pack converts for this menu. */
	public static final String[] ICONS = java.util.stream.Stream.concat(java.util.stream.Stream.of("flashbang", "smokegrenade", "hegrenade", "molotov",
			"incgrenade", "decoy", "kevlar", "assaultsuit", "defuser"), dev.strafemc.weapon.WeaponTable.guns().stream().map(dev.strafemc.weapon.WeaponTable.Entry::shortName))
			.distinct().toArray(String[]::new);

	/** One slot: the buy command's item (null: shown, not for sale), CS2's name, icon and the class its price is read from. */
	private record Slot(String buy, String name, String icon, String priceOf) {}

	private static final String[] TITLES = {"GEAR", "PISTOLS", "MID-TIER", "RIFLES", "GRENADES"};
	private static final double[] WIDTHS = {0.18, 0.18, 0.22, 0.24, 0.18};
	private static final Map<String, int[]> SIZES = new ConcurrentHashMap<>();
	/** The loadout screen's colours (LoadoutScreen), for one look across strafeMC's menus. */
	private static final int BG = 0xF0101317, PANEL = 0xC01C2127, TILE = 0xE52B2B2B, TILE_HOVER = 0xE5464C55, TILE_EMPTY = 0x40202020, ACCENT = 0xFFEABE54;

	/** When this client's player last (re)spawned (a new LocalPlayer), for the buy time shown. */
	private static Object lastPlayer;
	private static long spawnedAt;

	private int selected = -1;
	private String failure;
	private long failureAt;

	private BuyMenuScreen() {
		super(Component.literal("Buy menu"));
	}

	/** What this player bought since they (re)spawned (the server keeps the real list; this marks tiles and enables refunds). */
	private static volatile java.util.List<String> BOUGHT = java.util.List.of();

	/** The server's list of what can still be refunded (sent after every purchase, refund and respawn). */
	public static void refundable(java.util.List<String> items) {
		BOUGHT = java.util.List.copyOf(items);
	}

	public static void watchSpawn(Minecraft mc) {
		if (mc.player != lastPlayer) {
			lastPlayer = mc.player;
			spawnedAt = System.nanoTime();
		}
	}

	/** Refund all (CS2's sellbackall): everything bought since spawning. */
	public static void refundAll() {
		ConsoleCommands.refund("all");
	}

	public static void open(Minecraft mc) {
		if (ClientGameplay.buyMode() == 0) {
			StrafeToasts.show("You cannot buy (the buy menu is off on this server)", dev.strafemc.server.Toasts.WARN);
			return;
		}
		mc.setScreen(new BuyMenuScreen());
	}

	private static Slot[][] columns() {
		boolean t = "t".equals(StrafeConfig.team);
		return new Slot[][] {
				{new Slot("kevlar", "Kevlar Vest", "kevlar", "item_kevlar"), new Slot("assaultsuit", "Kevlar & Helmet", "assaultsuit", "item_assaultsuit"),
						new Slot("taser", "Zeus x27", "taser", "weapon_taser"), t ? null : new Slot("defuser", "Defuse Kit", "defuser", "item_defuser"), null},
				column("secondary", t), column("smg", t), column("rifle", t),
				{new Slot("flashbang", "Flashbang", "flashbang", "weapon_flashbang"), new Slot("smokegrenade", "Smoke Grenade", "smokegrenade", "weapon_smokegrenade"),
						new Slot("hegrenade", "High Explosive Grenade", "hegrenade", "weapon_hegrenade"),
						t ? new Slot("molotov", "Molotov", "molotov", "weapon_molotov") : new Slot("incgrenade", "Incendiary Grenade", "incgrenade", "weapon_incgrenade"),
						new Slot("decoy", "Decoy Grenade", "decoy", "weapon_decoy")}};
	}

	/** A weapon column: loadout slots <group>0..4 for your side, as set in the main menu's loadout (MyLoadout; else CS2's defaults). */
	private static Slot[] column(String group, boolean t) {
		Slot[] out = new Slot[5];
		for (int i = 0; i < 5; i++) {
			var e = dev.strafemc.weapon.WeaponTable.get(MyLoadout.buy(group + i, t));
			if (e != null) out[i] = new Slot(e.shortName(), dev.strafemc.weapon.WeaponTable.name(e.cls()), e.shortName(), e.cls());
		}
		return out;
	}

	/** Seconds of buy time left (Integer.MAX_VALUE without a limit). */
	private static int timeLeft() {
		var mode = ModeHud.state();
		if (mode != null && mode.has("phase")) { // a game mode keeps its own buy time (round modes send what's left)
			int left = mode.has("buyLeft") ? mode.get("buyLeft").getAsInt() : -1;
			return left < 0 ? Integer.MAX_VALUE : left;
		}
		int limit = ClientGameplay.buyTime();
		if (limit <= 0) return Integer.MAX_VALUE;
		return (int) Math.max(0, Math.ceil(limit - (System.nanoTime() - spawnedAt) / 1e9));
	}

	/** Why buying won't work right now, as far as this client can tell (the server has the last word). */
	private String refusal() {
		var p = Minecraft.getInstance().player;
		var mode = ModeHud.state();
		if (mode != null && mode.has("phase")) { // a game mode decides for its players (the server's buy menu switch doesn't apply)
			if (timeLeft() <= 0) return "You cannot buy now";
			if (mode.has("buyZone") && !mode.get("buyZone").getAsBoolean()) return "You are not in a buy zone"; // the server's own answer
			return null;
		}
		if (ClientGameplay.buyMode() == 0) return "You cannot buy (the buy menu is off on this server)";
		if (ClientGameplay.buyMode() == 1 && (p == null || !p.isCreative())) return "You cannot buy (creative only on this server)";
		if (timeLeft() <= 0) return "The " + ClientGameplay.buyTime() + " second buy period has expired";
		return null;
	}

	private double px() { // one CS2 1080p pixel in GUI units
		var w = Minecraft.getInstance().getWindow();
		return w.getHeight() / 1080.0 / w.getGuiScale();
	}

	/** The panel's frame: x, y of the columns, panel width, and the slot geometry, in GUI units. */
	private double[] frame() {
		double u = px(), w = 950 * u;
		double x = (width - w) / 2 - 50 * u, y = 180 * u + 34 * u + 4 * u; // below the info bar
		return new double[] {x, y, w, u};
	}

	private int[] slotRect(int col, int row) {
		double[] f = frame();
		double u = f[3], x = f[0];
		for (int c = 0; c < col; c++) x += WIDTHS[c] * f[2];
		double cw = WIDTHS[col] * f[2], pad = 15 * u, top = f[1] + 15 * u + 28 * u + 15 * u; // header: padding, 28 px label, 15 px margin
		int x0 = (int) Math.round(x + pad), x1 = (int) Math.round(x + cw - pad);
		int y0 = (int) Math.round(top + row * 100 * u), y1 = (int) Math.round(top + row * 100 * u + 90 * u);
		return new int[] {x0, y0, x1, y1};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, BG);
		double[] f = frame();
		double u = f[3];
		int left = (int) Math.round(f[0]), right = (int) Math.round(f[0] + f[2]);
		// info bar: buy time (CS2's "Buy Time Remaining")
		int infoTop = (int) Math.round(180 * u), infoBottom = (int) Math.round(180 * u + 34 * u);
		g.fill(left, infoTop, right, infoBottom, PANEL);
		int left0 = timeLeft();
		String timer = left0 == Integer.MAX_VALUE ? "No buy time limit" : String.format("Buy Time Remaining  %d:%02d", left0 / 60, left0 % 60);
		text(g, timer, (left + right) / 2.0, (infoTop + infoBottom) / 2.0, 22 * u, 0xFFFFFFFF, 0);
		// panel
		int panelTop = (int) Math.round(f[1]), panelBottom = slotRect(0, 4)[3] + (int) Math.round(15 * u);
		g.fill(left, panelTop, right, panelBottom, PANEL);
		String no = refusal();
		Slot[][] cols = columns();
		double x = f[0];
		for (int c = 0; c < cols.length; c++) {
			double cw = WIDTHS[c] * f[2];
			double hy = f[1] + 15 * u + 14 * u;
			int dim = selected >= 0 && selected != c ? 0xB3000000 : 0;
			text(g, String.valueOf(c + 1), x + 15 * u, hy, 18 * u, 0x66FFFFFF, -1);
			// the title centred, moved right if it would run into its key number
			double keyRight = x + 15 * u + font.width(String.valueOf(c + 1)) * 18 * u / 9 + 6 * u, titleW = font.width(TITLES[c]) * 28 * u / 9;
			text(g, TITLES[c], Math.max(x + cw / 2, keyRight + titleW / 2), hy, 28 * u, ACCENT, 0);
			for (int r = 0; r < 5; r++) {
				int[] rc = slotRect(c, r);
				Slot s = cols[c][r];
				boolean hover = s != null && mx >= rc[0] && mx < rc[2] && my >= rc[1] && my < rc[3];
				g.fill(rc[0], rc[1], rc[2], rc[3], s == null ? TILE_EMPTY : hover ? TILE_HOVER : TILE);
				if (s != null) {
					int price = price(s.priceOf);
					boolean can = no == null && s.buy != null && canCarry(s.buy) && affordable(price);
					int textCol = can ? 0xFFFFFFFF : 0xFF808080;
					text(g, String.valueOf(r + 1), rc[0] + 5 * u, (rc[1] + rc[3]) / 2.0, 18 * u, 0x66FFFFFF, -1);
					// the name right-aligned, shrunk to stay clear of the key number on the left
					double slotKeyRight = rc[0] + 5 * u + font.width(String.valueOf(r + 1)) * 18 * u / 9 + 6 * u;
					textFit(g, s.name, rc[2] - 10 * u, rc[1] + 4 * u + 7 * u, 14 * u, textCol, rc[2] - 10 * u - slotKeyRight);
					icon(g, s.icon, (rc[0] + rc[2]) / 2.0, (rc[1] + rc[3]) / 2.0, (rc[3] - rc[1]) * 0.45, can ? 0xFFFFFFFF : 0xFF404040);
					if (no == null && s.buy != null && BOUGHT.contains(s.buy)) { // bought: CS2's undo button (click it, or right-click the tile)
						int[] ur = undoRect(rc);
						boolean overUndo = mx >= ur[0] && mx < ur[2] && my >= ur[1] && my < ur[3];
						if (overUndo) g.fill(ur[0], ur[1], ur[2], ur[3], 0x60FFFFFF);
						icon(g, "undo", (ur[0] + ur[2]) / 2.0, (ur[1] + ur[3]) / 2.0, 16 * u, 0xFFFFFFFF);
					}
					text(g, s.buy == null ? "Not in strafeMC" : price > 0 ? "$" + price : "", rc[2] - 10 * u, rc[3] - 4 * u - 7 * u, 14 * u, textCol, 1);
				}
				if (dim != 0) g.fill(rc[0], rc[1], rc[2], rc[3], dim);
			}
			x += cw;
		}
		// CS2's purchase failure bar (yellow) for refusals and the server's answers
		String shown = failure != null && System.nanoTime() - failureAt < 3_000_000_000L ? failure : no;
		if (shown != null) {
			int fy = panelBottom + (int) Math.round(10 * u);
			g.fill(left, fy, right, fy + (int) Math.round(30 * u), 0xE5463A12);
			g.fill(left, fy, left + Math.max(1, (int) Math.round(3 * u)), fy + (int) Math.round(30 * u), ACCENT);
			text(g, shown, (left + right) / 2.0, fy + 15 * u, 22 * u, 0xFFFFFFFF, 0);
		}
		text(g, "[1-5] Choose    Right click: refund    [ESC] Back", (left + right) / 2.0, height - 24 * u, 20 * u, 0xFFA1A1A1, 0);
		// CS2's Refund All, when there's something to refund
		int[] ra = refundAllRect();
		boolean overRa = mx >= ra[0] && mx < ra[2] && my >= ra[1] && my < ra[3];
		if (!BOUGHT.isEmpty() && no == null) {
			g.fill(ra[0], ra[1], ra[2], ra[3], overRa ? TILE_HOVER : TILE);
			icon(g, "undo", ra[0] + 16 * u, (ra[1] + ra[3]) / 2.0, 18 * u, 0xFFFFFFFF);
			text(g, "Refund All", (ra[0] + ra[2]) / 2.0 + 10 * u, (ra[1] + ra[3]) / 2.0, 20 * u, 0xFFFFFFFF, 0);
		}
		super.extractRenderState(g, mx, my, delta);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
		// drawn in extractRenderState (CS2's own background, no Minecraft blur)
	}

	/**
	 * CS2 greys out what you can't take any more of, as it changes: Kevlar at 100 armor, Kevlar & Helmet at 100 armor
	 * with a helmet, a grenade at its limit (ammo_grenade_limit_default 1, _flashbang 2) or at 4 grenades in all.
	 */
	private static boolean canCarry(String buy) {
		var p = Minecraft.getInstance().player;
		if (p == null) return true;
		if (buy.equals("kevlar")) return StrafeMcClient.armor < 100;
		if (buy.equals("assaultsuit")) return StrafeMcClient.armor < 100 || !StrafeMcClient.helmet;
		if (buy.equals("defuser")) {
			for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(dev.strafemc.weapon.WeaponItems.DEFUSE_KIT)) return false;
			return true;
		}
		dev.strafemc.weapon.Grenade g = null;
		for (var x : dev.strafemc.weapon.Grenade.values()) if (x.cls.equals("weapon_" + buy)) g = x;
		if (g == null) return true;
		int have = 0, total = 0;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			var st = p.getInventory().getItem(i);
			if (st.getItem() instanceof dev.strafemc.weapon.GrenadeItem gi) {
				total += st.getCount();
				if (gi.type == g) have += st.getCount();
			}
		}
		int limit = g == dev.strafemc.weapon.Grenade.FLASHBANG ? cvarInt("ammo_grenade_limit_flashbang", 2) : cvarInt("ammo_grenade_limit_default", 1);
		return have < limit && total < cvarInt("ammo_grenade_limit_total", 4);
	}

	/** CS2 greys out what costs more than you have (only where there's money: the round modes' state carries it). */
	private static boolean affordable(int price) {
		var s = dev.strafemc.client.ModeHud.state();
		return s == null || !s.has("money") || s.get("money").getAsInt() >= price;
	}

	private static int cvarInt(String name, int fallback) {
		try {
			String v = ClientGameplay.cvar(name);
			return v == null ? fallback : (int) Double.parseDouble(v);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** Text like text(), scaled down if it's wider than maxWidth (GUI units). */
	private void textFit(GuiGraphicsExtractor g, String s, double x, double y, double size, int color, double maxWidth) {
		double w = font.width(s) * size / 9;
		text(g, s, x, y, w > maxWidth && maxWidth > 0 ? size * maxWidth / w : size, color, 1);
	}

	private static int price(String cls) {
		Map<String, Object> w = WeaponData.get(cls);
		Object p = w == null ? null : w.get("m_nPrice");
		return p instanceof Number n ? n.intValue() : 0;
	}

	/** Text at a CS2 pixel size; align -1 left, 0 centred, 1 right of x; y is the line's middle. */
	private void text(GuiGraphicsExtractor g, String s, double x, double y, double size, int color, int align) {
		float k = (float) (size / 9.0);
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate((float) x, (float) y);
		pose.scale(k, k);
		int w = font.width(s);
		g.text(font, s, align < 0 ? 0 : align == 0 ? -w / 2 : -w, -4, color, false);
		pose.popMatrix();
	}

	static void icon(GuiGraphicsExtractor g, String name, double cx, double cy, double h, int color) {
		int[] sz = SIZES.computeIfAbsent(name, n -> {
			Path png = dev.strafemc.client.assets.RuntimePack.DIR.resolve("assets/strafemc/textures/gui/buymenu/" + n + ".png");
			if (!Files.exists(png)) return new int[] {0, 0};
			try (ImageInputStream in = ImageIO.createImageInputStream(png.toFile())) {
				var readers = ImageIO.getImageReaders(in);
				if (!readers.hasNext()) return new int[] {0, 0};
				var r = readers.next();
				r.setInput(in);
				return new int[] {r.getWidth(0), r.getHeight(0)};
			} catch (Exception e) {
				return new int[] {0, 0};
			}
		});
		if (sz[0] <= 0) return;
		int ih = (int) Math.round(h), iw = (int) Math.round((double) sz[0] / sz[1] * h);
		g.blit(RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath("strafemc", "textures/gui/buymenu/" + name + ".png"), (int) Math.round(cx - iw / 2.0),
				(int) Math.round(cy - ih / 2.0), 0, 0, iw, ih, sz[0], sz[1], sz[0], sz[1], color);
	}

	private void buy(Slot s) {
		if (s == null || s.buy == null) return;
		String no = refusal();
		if (no != null) {
			failure = no;
			failureAt = System.nanoTime();
			return;
		}
		ConsoleCommands.buy(s.buy);
	}

	/** The undo button on a bought tile: its bottom left corner. */
	private int[] undoRect(int[] rc) {
		double u = px();
		return new int[] {rc[0] + (int) Math.round(4 * u), rc[3] - (int) Math.round(26 * u), rc[0] + (int) Math.round(26 * u), rc[3] - (int) Math.round(4 * u)};
	}

	/** Right-click on a bought tile: CS2's refund. */
	private void refund(Slot s) {
		if (s == null || s.buy == null || !BOUGHT.contains(s.buy)) return;
		ConsoleCommands.refund(s.buy);
	}

	/** The Refund All button's box (GUI units), at the bottom right of the panel. */
	private int[] refundAllRect() {
		double[] f = frame();
		double u = f[3];
		int right = (int) Math.round(f[0] + f[2]), bottom = height - (int) Math.round(10 * u);
		return new int[] {right - (int) Math.round(170 * u), bottom - (int) Math.round(34 * u), right, bottom};
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		int[] ra = refundAllRect();
		if (e.button() == 0 && !BOUGHT.isEmpty() && e.x() >= ra[0] && e.x() < ra[2] && e.y() >= ra[1] && e.y() < ra[3]) {
			refundAll();
			return true;
		}
		if (e.button() == 1) { // right-click a bought tile: refund it
			Slot[][] cols = columns();
			for (int c = 0; c < cols.length; c++)
				for (int r = 0; r < 5; r++) {
					int[] rc = slotRect(c, r);
					if (e.x() >= rc[0] && e.x() < rc[2] && e.y() >= rc[1] && e.y() < rc[3] && cols[c][r] != null) {
						refund(cols[c][r]);
						return true;
					}
				}
		}
		if (e.button() == 0) { // the undo button on a bought tile refunds it
			Slot[][] cols = columns();
			for (int c = 0; c < cols.length; c++)
				for (int r = 0; r < 5; r++) {
					Slot s = cols[c][r];
					if (s == null || s.buy == null || !BOUGHT.contains(s.buy)) continue;
					int[] ur = undoRect(slotRect(c, r));
					if (e.x() >= ur[0] && e.x() < ur[2] && e.y() >= ur[1] && e.y() < ur[3]) {
						refund(s);
						return true;
					}
				}
		}
		if (e.button() == 0) {
			Slot[][] cols = columns();
			for (int c = 0; c < cols.length; c++)
				for (int r = 0; r < 5; r++) {
					int[] rc = slotRect(c, r);
					if (e.x() >= rc[0] && e.x() < rc[2] && e.y() >= rc[1] && e.y() < rc[3] && cols[c][r] != null) {
						buy(cols[c][r]);
						return true;
					}
				}
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		int k = e.key();
		if (k >= GLFW.GLFW_KEY_1 && k <= GLFW.GLFW_KEY_5) {
			int i = k - GLFW.GLFW_KEY_1;
			if (selected < 0) selected = i;
			else {
				buy(columns()[selected][i]);
				selected = -1;
			}
			return true;
		}
		// the buy key closes it again, and so does Minecraft's inventory key (like it closes the inventory)
		if (selected < 0 && (dev.strafemc.client.weapon.GunClient.BUY.matches(e) || minecraft.options.keyInventory.matches(e))) {
			onClose();
			return true;
		}
		if (k == GLFW.GLFW_KEY_ESCAPE && selected >= 0) { // CS2: back out of a column first
			selected = -1;
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
