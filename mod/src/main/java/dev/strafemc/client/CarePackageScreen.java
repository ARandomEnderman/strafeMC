package dev.strafemc.client;

import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.CarePackage;
import dev.strafemc.weapon.CaseItem;
import dev.strafemc.weapon.Cases;
import dev.strafemc.weapon.SkinWrapItem;
import dev.strafemc.weapon.SkinWraps;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * CS2's Weekly Care Package screen (rankup_redemption_store), hourly: four item tiles with CS2's inventory pictures, pick
 * as many as your balance (2) and Claim Rewards; claimed tiles say CLAIMED. A bar shows the time to the next package.
 */
public final class CarePackageScreen extends Screen {
	private static final int GOLD = 0xFFEABE54, CASE_GREY = 0xFFB0C3D9;
	private final Screen parent;
	private int selected;
	private Button claim;
	/** When the shown progress arrived (the server syncs it each minute; the bar counts on between). */
	private static int lastProgress = -1;
	private static long progressAt;

	public CarePackageScreen(Screen parent) {
		super(Component.literal("Care Package"));
		this.parent = parent;
	}

	private static CarePackage.State state() {
		var p = Minecraft.getInstance().player;
		return p == null ? CarePackage.State.NONE : CarePackage.get(p);
	}

	/** Whether there's something to claim (the quick menu marks it). */
	public static boolean claimable() {
		CarePackage.State s = state();
		return !s.items().isEmpty() && s.balance() > 0;
	}

	@Override
	protected void init() {
		claim = addRenderableWidget(Button.builder(Component.literal("Claim Rewards"), b -> claim()).bounds(width / 2 - 75, height - 34, 150, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(8, 8, 50, 20).build());
		for (ItemStack s : state().items()) picture(s); // start converting the pictures
	}

	private void claim() {
		if (selected == 0) {
			dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/weapon_cant_buy", 0.5f);
			return;
		}
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < 4; i++) if ((selected >> i & 1) != 0) b.append(i).append(' ');
		if (ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE)) ClientPlayNetworking.send(new StrafePayloads.Action("drop_claim", b.toString().trim()));
		dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/claim_gift_01", 0.3f);
		selected = 0;
	}

	/** The tile boxes: four in a row, as big as fits. */
	private int tileW() {
		return Math.max(60, Math.min(120, (width - 40) / 4 - 8));
	}

	private int tileX(int i) {
		int w = tileW();
		return width / 2 - (4 * (w + 8) - 8) / 2 + i * (w + 8);
	}

	private int tileY() {
		return Math.max(70, height / 2 - tileW() / 2 - 10);
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e, boolean doubleClick) {
		CarePackage.State s = state();
		int w = tileW(), h = w + 22, y = tileY();
		for (int i = 0; i < s.items().size(); i++) {
			int x = tileX(i);
			if (e.x() < x || e.x() >= x + w || e.y() < y || e.y() >= y + h || s.claimed(i) || s.items().get(i).isEmpty()) continue;
			boolean on = (selected >> i & 1) != 0;
			if (!on && Integer.bitCount(selected) >= s.balance()) { // over the balance: CS2 pulses the picked ones with this sound
				dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/weapon_cant_buy", 0.5f);
				return true;
			}
			selected ^= 1 << i;
			// CS2's panorama plays gift_select when a tile turns off and gift_deselect when it turns on
			dev.strafemc.client.sound.CsSounds.playFile(on ? "sounds/ui/panorama/submenu_leveloptions_select_01" : "sounds/ui/panorama/premier_deselectmap", 0.5f);
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, StrafeSettingsScreen.BG);
		var font = Minecraft.getInstance().font;
		CarePackage.State s = state();
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(width / 2f, 14);
		pose.scale(2f, 2f);
		g.text(font, "Care Package", -font.width("Care Package") / 2, 0, 0xFFFFFFFF, true);
		pose.popMatrix();
		boolean open = !s.items().isEmpty() && s.balance() > 0;
		String badge = s.items().isEmpty() ? "Play to earn rewards" : open ? "Claim your rewards" : "Rewards Claimed";
		g.text(font, badge, width / 2 - font.width(badge) / 2, 38, open ? GOLD : 0xFFB0B0B0, true);
		String desc = open ? "Pick " + s.balance() + (s.balance() == 1 ? " item." : " items.") + " Unclaimed rewards are gone when the next package arrives."
				: "You will receive a new Care Package in " + timeLeft(s) + ".";
		g.text(font, desc, width / 2 - font.width(desc) / 2, 50, 0xFFB0B0B0, false);
		int w = tileW(), h = w + 22, y = tileY();
		for (int i = 0; i < s.items().size(); i++) tile(g, font, s.items().get(i), tileX(i), y, w, h, s.claimed(i), (selected >> i & 1) != 0, mx, my);
		// the time to the next package, as CS2's XP bar toward it
		int bw = Math.min(300, width - 40), bx = width / 2 - bw / 2, by = height - 52;
		float f = progress(s) / (float) CarePackage.INTERVAL;
		g.fill(bx, by, bx + bw, by + 4, 0xFF2A2F36);
		g.fill(bx, by, bx + (int) (bw * f), by + 4, GOLD);
		String next = "Next in " + timeLeft(s);
		g.text(font, next, bx + bw - font.width(next), by - 11, 0xFF909090, false);
		claim.visible = open;
		claim.active = selected != 0;
		super.extractRenderState(g, mx, my, delta);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}

	private static int progress(CarePackage.State s) {
		if (s.progress() != lastProgress) {
			lastProgress = s.progress();
			progressAt = System.currentTimeMillis();
		}
		return (int) Math.min(CarePackage.INTERVAL, s.progress() + (System.currentTimeMillis() - progressAt) / 50);
	}

	private static String timeLeft(CarePackage.State s) {
		int sec = Math.max(0, (CarePackage.INTERVAL - progress(s)) / 20);
		return sec >= 60 ? (sec / 60) + " min" : sec + " s";
	}

	private void tile(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, ItemStack s, int x, int y, int w, int h, boolean claimed, boolean on, int mx, int my) {
		boolean hover = mx >= x && mx < x + w && my >= y && my < y + h && !claimed;
		g.fill(x, y, x + w, y + h, hover ? 0xFF2A313A : 0xFF22272E);
		g.fill(x, y + h - 3, x + w, y + h, color(s));
		if (on) g.outline(x - 1, y - 1, w + 2, h + 2, GOLD);
		Identifier pic = picture(s);
		int ph = Math.min(h - 26, (w - 4) * 3 / 4), pw = ph * 4 / 3, px = x + (w - pw) / 2;
		if (pic != null) g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, pic, px, y + 4, 0, 0, pw, ph, pw, ph);
		else if (!s.isEmpty()) g.item(s, x + w / 2 - 8, y + 4 + ph / 2 - 8);
		String n = font.plainSubstrByWidth(s.getHoverName().getString(), w - 6);
		g.text(font, n, x + w / 2 - font.width(n) / 2, y + h - 15, 0xFFE0E0E0, false);
		if (on) { // CS2's check mark in the corner
			g.fill(x + w - 14, y + 2, x + w - 2, y + 14, GOLD);
			g.text(font, "✔", x + w - 12, y + 4, 0xFF101317, false);
		}
		if (claimed) {
			g.fill(x, y, x + w, y + h, 0xB0101317);
			g.text(font, "CLAIMED", x + w / 2 - font.width("CLAIMED") / 2, y + h / 2 - 4, GOLD, true);
		}
	}

	/** CS2's inventory picture: a case's, or the gun in the wrap's skin (like the case screen). */
	private static Identifier picture(ItemStack s) {
		if (s.getItem() instanceof CaseItem) {
			Cases.Case c = Cases.get(CaseItem.caseId(s));
			return c == null ? null : CaseImages.image("panorama/images/" + c.image() + "_png");
		}
		if (s.getItem() instanceof SkinWrapItem) {
			SkinWraps.Wrap w = SkinWraps.get(SkinWrapItem.skin(s));
			if (w == null || w.kits().isEmpty()) return null;
			var e = w.kits().entrySet().iterator().next();
			return CaseImages.get(new Cases.Entry(e.getValue(), e.getKey()));
		}
		return null;
	}

	/** The rarity colour under the tile: a wrap's, a case's base grade grey. */
	private static int color(ItemStack s) {
		if (s.getItem() instanceof SkinWrapItem) {
			SkinWraps.Wrap w = SkinWraps.get(SkinWrapItem.skin(s));
			if (w != null && w.rarity() != null) return 0xFF000000 | w.rarity().color;
		}
		return CASE_GREY;
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
