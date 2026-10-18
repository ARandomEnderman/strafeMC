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
 * CS2's Care Package screen (rankup_redemption_store), one package per CS2 rank: Redeem opens the next queued package,
 * four item tiles with CS2's inventory pictures; pick as many as your balance (2) and Claim Rewards; claimed tiles say
 * CLAIMED. Your rank and its XP bar (CS2's, toward the next package) sit under them.
 */
public final class CarePackageScreen extends Screen {
	private static final int GOLD = 0xFFEABE54, CASE_GREY = 0xFFB0C3D9;
	private final Screen parent;
	private int selected;
	private Button claim, redeem;

	public CarePackageScreen(Screen parent) {
		super(Component.literal("Care Package"));
		this.parent = parent;
	}

	private static CarePackage.State state() {
		var p = Minecraft.getInstance().player;
		return p == null ? CarePackage.State.NONE : CarePackage.get(p);
	}

	/** Whether there's something to claim or redeem (the quick menu marks it). */
	public static boolean claimable() {
		CarePackage.State s = state();
		return ClientGameplay.drops() && (s.open() || s.queued() > 0);
	}

	@Override
	protected void init() {
		claim = addRenderableWidget(Button.builder(Component.literal("Claim Rewards"), b -> claim()).bounds(width / 2 - 75, height - 52, 150, 20).build());
		redeem = addRenderableWidget(Button.builder(Component.literal("Redeem Care Package"), b -> {
			if (ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE)) ClientPlayNetworking.send(new StrafePayloads.Action("drop_redeem", ""));
			selected = 0;
		}).bounds(width / 2 - 75, height - 52, 150, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 26, 200, 20).build());
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
		int w = tileW(), h = w + 40, y = tileY();
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
		boolean open = s.open();
		String badge = open ? "Claim your rewards" : s.queued() > 0 ? "Redeem your Care Package" : s.items().isEmpty() ? "Earn an XP level" : "Rewards Claimed";
		g.text(font, badge, width / 2 - font.width(badge) / 2, 38, open || s.queued() > 0 ? GOLD : 0xFFB0B0B0, true);
		String desc = open ? "Pick " + s.balance() + (s.balance() == 1 ? " item." : " items.")
				: s.queued() > 0 ? s.queued() + (s.queued() == 1 ? " Care Package" : " Care Packages") + " to redeem."
				: "You will receive a new Care Package when you earn your next rank.";
		g.text(font, desc, width / 2 - font.width(desc) / 2, 50, 0xFFB0B0B0, false);
		int w = tileW(), h = w + 40, y = tileY();
		for (int i = 0; i < s.items().size(); i++) tile(g, font, s.items().get(i), tileX(i), y, w, h, s.claimed(i), (selected >> i & 1) != 0, mx, my);
		RankBar.draw(g, width / 2 + 14, height - 80, Math.min(260, width - 80)); // CS2's XP bar toward the next package
		claim.visible = open;
		claim.active = selected != 0;
		redeem.visible = !open && s.queued() > 0;
		redeem.setMessage(Component.literal(s.queued() > 1 ? "Redeem Care Package (" + s.queued() + ")" : "Redeem Care Package"));
		super.extractRenderState(g, mx, my, delta);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}

	private void tile(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, ItemStack s, int x, int y, int w, int h, boolean claimed, boolean on, int mx, int my) {
		boolean hover = mx >= x && mx < x + w && my >= y && my < y + h && !claimed;
		g.fill(x, y, x + w, y + h, hover ? 0xFF2A313A : 0xFF22272E);
		g.fill(x, y + h - 3, x + w, y + h, color(s));
		if (on) g.outline(x - 1, y - 1, w + 2, h + 2, GOLD);
		Identifier pic = picture(s);
		int ph = Math.min(h - 42, (w - 4) * 3 / 4), pw = ph * 4 / 3, px = x + (w - pw) / 2;
		if (pic != null) g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, pic, px, y + 4, 0, 0, pw, ph, pw, ph);
		else if (!s.isEmpty()) g.item(s, x + w / 2 - 8, y + 4 + ph / 2 - 8);
		// the name (weapon / finish, never cut off), then the exterior: what you'd get, before picking it
		TileText.draw(g, font, s.getHoverName().getString(), x + w / 2, y + h - 34, w - 6, 0xFFE0E0E0);
		String ext = s.getItem() instanceof SkinWrapItem ? (dev.strafemc.weapon.StatTrak.kills(s) >= 0 ? "StatTrak™ " : "")
				+ dev.strafemc.weapon.Wear.exterior(SkinWrapItem.wear(s)) : s.getItem() instanceof dev.strafemc.weapon.SprayItem ? "Graffiti" : containerKind(s);
		if (s.getCount() > 1) g.text(font, "x" + s.getCount(), x + 4, y + 4, 0xFFFFFFFF, true); // two sticker capsules
		g.text(font, font.plainSubstrByWidth(ext, w - 6), x + w / 2 - Math.min(w - 6, font.width(ext)) / 2, y + h - 14, 0xFF909090, false);
		if (mx >= x && mx < x + w && my >= y && my < y + h && !s.isEmpty()) g.setTooltipForNextFrame(font, s, mx, my); // float, pattern...
		if (on) { // CS2's check mark in the corner
			g.fill(x + w - 14, y + 2, x + w - 2, y + 14, GOLD);
			g.text(font, "✔", x + w - 12, y + 4, 0xFF101317, false);
		}
		if (claimed) {
			g.fill(x, y, x + w, y + h, 0xB0101317);
			g.text(font, "CLAIMED", x + w / 2 - font.width("CLAIMED") / 2, y + h / 2 - 4, GOLD, true);
		}
	}

	private static String containerKind(ItemStack s) {
		Cases.Case c = s.getItem() instanceof CaseItem ? Cases.get(CaseItem.caseId(s)) : null;
		return c == null || !c.capsule() ? "Weapon Case" : c.kind().equals("spray") ? "Graffiti Box" : "Sticker Capsule";
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
	public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
		if (LoadoutKey.closesAll(this, e)) return true; // the strafeMC menu key closes every menu screen
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
