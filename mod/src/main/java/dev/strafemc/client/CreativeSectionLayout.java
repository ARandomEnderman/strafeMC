package dev.strafemc.client;

import dev.strafemc.weapon.CreativeSections;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lays a sectioned creative tab out (CreativeSections) and draws its banners: each section starts on a new row after
 * two empty rows, where its banner goes (its texture, else a plain bar with the title).
 */
public final class CreativeSectionLayout {
	private static final int ROW = 9;
	/** The current tab's banners: the first of the two rows each takes, and its section. */
	private static List<Banner> banners = List.of();
	private static final Map<Identifier, Boolean> HAS_TEXTURE = new ConcurrentHashMap<>();

	private record Banner(int row, CreativeSections.Filled section) {}

	private CreativeSectionLayout() {}

	/** Re-lays the picker's items for tab (its id); false (left as it is) when the tab has no sections or they don't add up. */
	public static boolean layOut(Identifier tab, NonNullList<ItemStack> items) {
		HAS_TEXTURE.clear(); // looked up again, so a banner added with a resource reload (F3 + T) shows
		List<CreativeSections.Filled> sections = tab == null ? null : CreativeSections.of(tab);
		int total = 0;
		if (sections != null) for (CreativeSections.Filled s : sections) total += s.count();
		if (sections == null || total != items.size()) {
			banners = List.of();
			return false;
		}
		List<ItemStack> flat = new ArrayList<>(items), out = new ArrayList<>();
		List<Banner> b = new ArrayList<>();
		int at = 0;
		for (CreativeSections.Filled s : sections) {
			if (s.count() == 0) continue;
			b.add(new Banner(out.size() / ROW, s));
			for (int i = 0; i < 2 * ROW; i++) out.add(ItemStack.EMPTY);
			out.addAll(flat.subList(at, at + s.count()));
			at += s.count();
			while (out.size() % ROW != 0) out.add(ItemStack.EMPTY);
		}
		items.clear();
		items.addAll(out);
		banners = List.copyOf(b);
		return true;
	}

	/** Draws the banners in view: x, y the slot grid's top left (its first slot's frame), top the first row shown. */
	public static void drawBanners(GuiGraphicsExtractor g, int x, int y, int top) {
		if (banners.isEmpty()) return;
		var font = Minecraft.getInstance().font;
		g.enableScissor(x, y, x + CreativeSections.BANNER_W, y + 5 * 18);
		for (Banner b : banners) {
			int by = y + (b.row() - top) * 18;
			if (by + CreativeSections.BANNER_H <= y || by >= y + 5 * 18) continue;
			Identifier tex = b.section().texture();
			if (HAS_TEXTURE.computeIfAbsent(tex, t -> Minecraft.getInstance().getResourceManager().getResource(t).isPresent())) {
				int w = CreativeSections.BANNER_W, h = CreativeSections.BANNER_H; // any whole multiple of the size is drawn scaled to it
				g.blit(RenderPipelines.GUI_TEXTURED, tex, x, by, 0, 0, w, h, w, h, w, h);
				continue;
			}
			g.fill(x, by, x + CreativeSections.BANNER_W, by + CreativeSections.BANNER_H, 0xFF2B2F36);
			g.outline(x, by, CreativeSections.BANNER_W, CreativeSections.BANNER_H, 0xFF4A505A);
			g.fill(x + 1, by + 1, x + 3, by + CreativeSections.BANNER_H - 1, 0xFFEABE54);
			g.text(font, b.section().title(), x + 9, by + (CreativeSections.BANNER_H - 8) / 2, 0xFFFFFFFF, true);
		}
		g.disableScissor();
	}
}
