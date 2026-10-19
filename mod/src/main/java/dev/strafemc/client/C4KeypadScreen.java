package dev.strafemc.client;

import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.C4Item;
import dev.strafemc.weapon.PlantedC4;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The C4's keypad (strafemc_c4_keypad): one key lights up; press it (click, or type it) and the next one lights, with
 * the C4's key press sound for each press and a beep for each new key, while the bomb's display fills with stars.
 * Planting, the keys spell CS2's 7355608; defusing, they're random (14 keys, 7 with a defuse kit). Finishing them is the
 * only way to plant or defuse while it's on. A wrong key starts over; closing it (Esc) stops.
 */
public final class C4KeypadScreen extends Screen {
	private static final String[] KEYS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"};
	private static final int KEY = 30, GAP = 6, GOLD = 0xFFEABE54;
	private final PlantedC4 bomb;
	private final String code;
	private int typed;
	private long wrongUntil, sentAt;
	private boolean done;

	private C4KeypadScreen(PlantedC4 bomb, String code) {
		super(Component.literal("C4"));
		this.bomb = bomb;
		this.code = code;
	}

	/** Planting: CS2's code. */
	public static C4KeypadScreen plant() {
		return new C4KeypadScreen(null, C4Item.CODE);
	}

	/** Defusing b: random keys, twice as many without a defuse kit. */
	public static C4KeypadScreen defuse(PlantedC4 b) {
		StringBuilder s = new StringBuilder();
		var r = new java.util.Random();
		var p = Minecraft.getInstance().player;
		int n = p != null && PlantedC4.hasKit(p) ? PlantedC4.KEYPAD_KEYS_KIT : PlantedC4.KEYPAD_KEYS;
		for (int i = 0; i < n; i++) s.append(r.nextInt(10));
		return new C4KeypadScreen(b, s.toString());
	}

	/** The bomb being defused, or null when planting. */
	public PlantedC4 bomb() {
		return bomb != null && bomb.isAlive() && !bomb.defused() ? bomb : null;
	}

	private int left() {
		return width / 2 - (3 * KEY + 2 * GAP) / 2;
	}

	private int top() {
		return height / 2 - 40;
	}

	private void sound(String event) {
		var p = minecraft.player;
		if (p != null) dev.strafemc.client.sound.CsSounds.play(event, p.getX(), p.getY(), p.getZ(), 1.0);
	}

	/** The key lit now (the next of the code), or null once it's all pressed. */
	private String lit() {
		return done || typed >= code.length() ? null : String.valueOf(code.charAt(typed));
	}

	@Override
	protected void init() {
		if (typed == 0 && !done) sound("c4.draw.beep"); // the first key lights
	}

	private void press(String k) {
		if (done) return;
		sound("c4.keypressquiet");
		if (k.equals(lit())) {
			if (++typed == code.length()) finish();
			else sound("c4.draw.beep"); // the next key lights
		} else {
			typed = 0;
			wrongUntil = System.nanoTime() + 400_000_000L;
			sound("c4.draw.beep");
		}
	}

	private void finish() {
		done = true;
		if (bomb == null) dev.strafemc.client.weapon.GunClient.INSTANCE.plantTyped(); // arms once the quickest plant has passed
		else sendTyped();
	}

	private void sendTyped() {
		sentAt = System.nanoTime();
		if (bomb != null && ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE))
			ClientPlayNetworking.send(new StrafePayloads.Action("c4_defuse_typed", String.valueOf(bomb.getId())));
	}

	@Override
	public void tick() {
		if (bomb != null && bomb() == null) { // defused or gone
			onClose();
			return;
		}
		// typed before the quickest defuse: told again until the server takes it (or the hold finishes it)
		if (done && bomb != null && System.nanoTime() - sentAt > 250_000_000L) sendTyped();
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e, boolean doubleClick) {
		for (int i = 0; i < KEYS.length; i++) {
			int x = left() + (i % 3) * (KEY + GAP), y = top() + (i / 3) * (KEY + GAP);
			if (e.x() >= x && e.x() < x + KEY && e.y() >= y && e.y() < y + KEY) {
				press(KEYS[i]);
				return true;
			}
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean charTyped(net.minecraft.client.input.CharacterEvent e) {
		String s = Character.toString(e.codepoint());
		for (String k : KEYS)
			if (k.equals(s)) {
				press(k);
				return true;
			}
		return super.charTyped(e);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		int l = left(), t = top(), w = 3 * KEY + 2 * GAP;
		g.fill(l - 14, t - 62, l + w + 14, t + 4 * (KEY + GAP) + 10, 0xE0101317);
		g.outline(l - 14, t - 62, w + 28, 4 * (KEY + GAP) + 72, 0xFF3A4048);
		String head = bomb == null ? "ARM THE BOMB" : "DEFUSE THE BOMB";
		g.text(font, head, width / 2 - font.width(head) / 2, t - 54, GOLD, false);
		// the bomb's display: a star for each key pressed (red after a wrong one)
		boolean wrong = System.nanoTime() < wrongUntil;
		g.fill(l - 4, t - 40, l + w + 4, t - 22, 0xFF0B1A0E);
		StringBuilder stars = new StringBuilder();
		for (int i = 0; i < code.length(); i++) stars.append(i < typed ? '*' : '_');
		String shown = stars.toString();
		g.text(font, shown, width / 2 - font.width(shown) / 2, t - 35, wrong ? 0xFFE05050 : 0xFF6CFF6C, false);
		String lit = lit();
		boolean blink = (System.nanoTime() / 250_000_000L) % 2 == 0;
		for (int i = 0; i < KEYS.length; i++) {
			int x = l + (i % 3) * (KEY + GAP), y = t + (i / 3) * (KEY + GAP);
			boolean over = mx >= x && mx < x + KEY && my >= y && my < y + KEY, on = KEYS[i].equals(lit);
			g.fill(x, y, x + KEY, y + KEY, on ? (blink ? 0xFFE8C04A : 0xFFB8921F) : over ? 0xFF3A414B : 0xFF2A2F36);
			g.outline(x, y, KEY, KEY, on ? 0xFFFFF0B0 : over ? 0xFF8A909A : 0xFF4A505A);
			g.text(font, KEYS[i], x + KEY / 2 - font.width(KEYS[i]) / 2, y + KEY / 2 - 4, on ? 0xFF1A1300 : 0xFFFFFFFF, false);
		}
		String hint = done ? (bomb == null ? "Arming..." : "Defusing...") : "Press the lit key - Esc to stop";
		g.text(font, hint, width / 2 - font.width(hint) / 2, t + 4 * (KEY + GAP) + 2, 0xFF909090, false);
		super.extractRenderState(g, mx, my, delta);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
