package dev.strafemc.client;

import dev.strafemc.config.StrafeConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The agents (strafeMC settings): every CS2 agent of a side in a grid with CS2's picture of them; click one to wear it
 * on that side (click it again, or "None", for none). The other side is a button away.
 */
public final class AgentScreen extends Screen {
	private static final int GOLD = 0xFFEABE54, TILE_W = 96, TILE_H = 120;
	private final Screen parent;
	/** Instead of going back to parent: what closing does (the loadout reopens). */
	private Runnable back;
	private String side = "t".equals(StrafeConfig.team) ? "t" : "ct";
	private double scroll;

	public AgentScreen(Screen parent) {
		super(Component.literal("Agents"));
		this.parent = parent;
	}

	/** One side's agents, opened from the loadout: closing runs back. */
	public AgentScreen(String side, Runnable back) {
		this(null);
		this.side = side;
		this.back = back;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.literal("Terrorists"), b -> {
			side = "t";
			scroll = 0;
		}).bounds(width / 2 - 154, 26, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Counter-Terrorists"), b -> {
			side = "ct";
			scroll = 0;
		}).bounds(width / 2 - 50, 26, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("None"), b -> pick(null)).bounds(width / 2 + 54, 26, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 26, 200, 20).build());
	}

	private String picked() {
		return side.equals("t") ? StrafeConfig.agentT : StrafeConfig.agentCT;
	}

	private void pick(String id) {
		String v = id == null || id.equals(picked()) ? "" : id;
		if (side.equals("t")) StrafeConfig.agentT = v;
		else StrafeConfig.agentCT = v;
		try {
			StrafeConfig.save();
		} catch (Exception ignored) {
		}
		dev.strafemc.client.sound.CsSounds.playFile("sounds/ui/panorama/submenu_leveloptions_select_01", 0.4f);
	}

	private int cols() {
		return Math.max(1, (width - 40) / (TILE_W + 8));
	}

	private int top() {
		return 54;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		List<Agents.Agent> list = Agents.of(side);
		int rows = (list.size() + cols() - 1) / cols(), max = Math.max(0, rows * (TILE_H + 8) - (height - 34 - top()));
		scroll = Math.max(0, Math.min(max, scroll - sy * (TILE_H + 8) / 2));
		return true;
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) return true;
		if (e.y() < top() || e.y() >= height - 34) return false;
		List<Agents.Agent> list = Agents.of(side);
		int cols = cols(), x0 = width / 2 - cols * (TILE_W + 8) / 2;
		for (int i = 0; i < list.size(); i++) {
			int x = x0 + (i % cols) * (TILE_W + 8), y = top() + (i / cols) * (TILE_H + 8) - (int) Math.round(scroll);
			if (e.x() >= x && e.x() < x + TILE_W && e.y() >= y && e.y() < y + TILE_H) {
				pick(list.get(i).id());
				return true;
			}
		}
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, StrafeSettingsScreen.BG);
		var font = minecraft.font;
		String head = (side.equals("t") ? "Terrorist" : "Counter-Terrorist") + " agents  —  your viewmodel wears their sleeves";
		g.text(font, head, width / 2 - font.width(head) / 2, 10, 0xFFFFFFFF, true);
		List<Agents.Agent> list = Agents.of(side);
		int cols = cols(), x0 = width / 2 - cols * (TILE_W + 8) / 2;
		g.enableScissor(0, top(), width, height - 34);
		for (int i = 0; i < list.size(); i++) {
			Agents.Agent a = list.get(i);
			int x = x0 + (i % cols) * (TILE_W + 8), y = top() + (i / cols) * (TILE_H + 8) - (int) Math.round(scroll);
			if (y + TILE_H < top() || y > height - 34) continue;
			boolean on = a.id().equals(picked()), over = mx >= x && mx < x + TILE_W && my >= y && my < y + TILE_H && my >= top() && my < height - 34;
			g.fill(x, y, x + TILE_W, y + TILE_H, over ? 0xFF2A313A : 0xFF22272E);
			g.fill(x, y + TILE_H - 3, x + TILE_W, y + TILE_H, 0xFF000000 | a.rarity().color);
			if (on) g.outline(x - 1, y - 1, TILE_W + 2, TILE_H + 2, GOLD);
			var pic = CaseImages.image("panorama/images/" + a.image() + "_png");
			int ph = TILE_H - 34, pw = Math.min(TILE_W - 4, ph * 4 / 3);
			if (pic != null) g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, pic, x + (TILE_W - pw) / 2, y + 2, 0, 0, pw, ph * pw / (ph * 4 / 3), pw,
					ph * pw / (ph * 4 / 3));
			String[] parts = a.name().split(" \\| ", 2);
			g.text(font, font.plainSubstrByWidth(parts[0], TILE_W - 6), x + TILE_W / 2 - Math.min(TILE_W - 6, font.width(parts[0])) / 2, y + TILE_H - 28, 0xFFE0E0E0, false);
			if (parts.length > 1)
				g.text(font, font.plainSubstrByWidth(parts[1], TILE_W - 6), x + TILE_W / 2 - Math.min(TILE_W - 6, font.width(parts[1])) / 2, y + TILE_H - 16, 0xFF909090, false);
		}
		g.disableScissor();
		super.extractRenderState(g, mx, my, delta);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}

	@Override
	public void onClose() {
		if (back != null) {
			minecraft.setScreen(null);
			back.run();
			return;
		}
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
