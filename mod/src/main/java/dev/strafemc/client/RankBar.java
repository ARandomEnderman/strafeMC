package dev.strafemc.client;

import dev.strafemc.weapon.CarePackage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;

/** Your CS2 rank as CS2 shows it: the rank icon (panorama icons/xp/levelN), its name, and the XP bar to the next rank (or package). */
public final class RankBar {
	private static final int GOLD = 0xFFEABE54;

	private RankBar() {}

	/** Draws it centred on cx, its top at y, the bar w wide. */
	public static void draw(GuiGraphicsExtractor g, int cx, int y, int w) {
		var mc = Minecraft.getInstance();
		if (mc.player == null || !ClientGameplay.rule("levels", true)) return;
		var font = mc.font;
		CarePackage.State s = CarePackage.get(mc.player);
		int rank = s.rank(), icon = 24, x0 = cx - w / 2;
		var pic = CaseImages.image("panorama/images/icons/xp/level" + Math.min(rank, CarePackage.MAX_RANK) + "_png"); // past 40: 40's icon
		if (pic != null) g.blit(RenderPipelines.GUI_TEXTURED, pic, x0 - icon - 6, y, 0, 0, icon, icon, icon, icon);
		String name = CarePackage.rankName(rank) + "  " + rank;
		g.text(font, name, x0, y + 2, 0xFFFFFFFF, true);
		int need = CarePackage.need(rank);
		String xp = s.rankXp() + " / " + need + " XP";
		g.text(font, xp, x0 + w - font.width(xp), y + 2, 0xFF909090, false);
		g.fill(x0, y + 14, x0 + w, y + 18, 0xFF2A2F36);
		g.fill(x0, y + 14, x0 + (int) (w * (s.rankXp() / (float) need)), y + 18, GOLD);
	}
}
