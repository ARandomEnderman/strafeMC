package dev.strafemc.client;

import dev.strafemc.weapon.Loadout;
import dev.strafemc.weapon.SprayItem;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * CS2's graffiti wheel: hold the spray key (G), point at one of the loadout's six graffiti and let go to spray it where
 * you're looking (in reach). Let go with the mouse still in the middle and it sprays the one used last, like CS2. The
 * middle shows the pointed-at (or last) graffiti and how many sprays it has left.
 */
public final class SprayWheelScreen extends Screen {
	private static final int GOLD = 0xFFEABE54, SLOTS = Loadout.SPRAYS;
	/** The graffiti sprayed last (the middle's pick). */
	private static int last;
	private int hovered = -1;

	public SprayWheelScreen() {
		super(Component.literal("Graffiti"));
	}

	private int radius() {
		return Math.max(50, Math.min(100, Math.min(width, height) / 2 - 50));
	}

	/** Slot i's centre: the first at the top, then clockwise. */
	private double[] at(int i) {
		double a = -Math.PI / 2 + i * 2 * Math.PI / SLOTS;
		return new double[] {width / 2.0 + Math.cos(a) * radius(), height / 2.0 + Math.sin(a) * radius()};
	}

	/** The slot under the mouse (by angle, outside the middle), or -1 in the middle. */
	private int slice(double mx, double my) {
		double dx = mx - width / 2.0, dy = my - height / 2.0;
		if (dx * dx + dy * dy < 30 * 30) return -1;
		double a = Math.atan2(dy, dx) + Math.PI / 2 + Math.PI / SLOTS;
		a = (a % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI);
		return (int) (a / (2 * Math.PI / SLOTS)) % SLOTS;
	}

	private ItemStack slot(int i) {
		var p = Minecraft.getInstance().player;
		return p == null ? ItemStack.EMPTY : Loadout.get(p, Loadout.sprayId(i));
	}

	/** The one letting go now would spray: the pointed-at slot, or in the middle the last one. */
	private int target() {
		return hovered >= 0 ? hovered : last;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, 0x90101317);
		var font = Minecraft.getInstance().font;
		int cx = width / 2, cy = height / 2, r = radius(), t;
		hovered = slice(mx, my);
		t = target();
		for (int k = 0; k < 120; k++) { // the ring
			double a = k * Math.PI * 2 / 120;
			int x = (int) Math.round(cx + Math.cos(a) * r), y = (int) Math.round(cy + Math.sin(a) * r);
			g.fill(x, y, x + 1, y + 1, 0x60FFFFFF);
		}
		for (int i = 0; i < SLOTS; i++) {
			double[] p = at(i);
			int x = (int) p[0], y = (int) p[1], s = 24;
			ItemStack st = slot(i);
			boolean hot = i == t && !st.isEmpty();
			g.fill(x - s, y - s, x + s, y + s, hot ? 0xE0303843 : 0xC01A1F26);
			if (hot) g.outline(x - s, y - s, 2 * s, 2 * s, GOLD);
			if (st.isEmpty()) g.text(font, "-", x - font.width("-") / 2, y - 4, 0xFF606060, false);
			else CaseImages.draw(g, st, x - s + 3, y - s + 3, 2 * s - 6, 2 * s - 6);
		}
		// the middle: which one, and its sprays left
		ItemStack st = slot(t);
		if (st.isEmpty()) {
			String e = "No graffiti", sub = "Put graffiti in your loadout";
			g.text(font, e, cx - font.width(e) / 2, cy - 9, 0xFF909090, true);
			g.text(font, sub, cx - font.width(sub) / 2, cy + 3, 0xFF707070, false);
		} else {
			String name = st.getHoverName().getString().replace("Graffiti | ", "");
			name = font.plainSubstrByWidth(name, 2 * r - 70);
			String left = st.getOrDefault(dev.strafemc.weapon.WeaponItems.OPENED, SprayItem.USES) + " sprays left";
			g.text(font, name, cx - font.width(name) / 2, cy - 9, GOLD, true);
			g.text(font, left, cx - font.width(left) / 2, cy + 3, 0xFFB0B0B0, false);
		}
		super.extractRenderState(g, mx, my, delta);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}

	/** Let go of the spray key: spray the pointed-at one (or the last one), then back to the game. */
	@Override
	public boolean keyReleased(net.minecraft.client.input.KeyEvent e) {
		if (SprayKey.matches(e)) {
			int t = target();
			onClose();
			if (!slot(t).isEmpty()) {
				last = t;
				if (ClientPlayNetworking.canSend(dev.strafemc.net.StrafePayloads.Action.TYPE))
					ClientPlayNetworking.send(new dev.strafemc.net.StrafePayloads.Action("spray_slot", String.valueOf(t)));
			}
			return true;
		}
		return super.keyReleased(e);
	}

	/** A click picks too (the key may have been let go outside the game window). */
	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e, boolean doubleClick) {
		int t = target();
		onClose();
		if (!slot(t).isEmpty() && ClientPlayNetworking.canSend(dev.strafemc.net.StrafePayloads.Action.TYPE)) {
			last = t;
			ClientPlayNetworking.send(new dev.strafemc.net.StrafePayloads.Action("spray_slot", String.valueOf(t)));
		}
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
