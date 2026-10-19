package dev.strafemc.weapon;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Sections inside strafeMC's creative tabs: each section starts on a new row under a banner that takes the place of
 * one row of slots (9 x 1 slots). The creative screen (client mixin) lays the tab out by the counts recorded here and
 * draws the banners.
 * <p>
 * A banner's texture: assets/strafemc/textures/gui/creative/&lt;tab&gt;/&lt;section&gt;.png, 162 x 18 pixels (the 9 x 1
 * slot row, 18 px a slot), or any whole multiple of it (324 x 36, 486 x 54...) drawn scaled down to fit. Without the
 * file a plain banner with the section's title is drawn.
 */
public final class CreativeSections {
	/** The banner's size in GUI pixels: a row of nine slots. */
	public static final int BANNER_W = 162, BANNER_H = 18;

	/** A section: its id (the texture's name), the title the stand-in banner shows, and what goes in it. */
	public record Section(String id, String title, Consumer<List<ItemStack>> fill) {}

	/** A tab's sections as last filled: id, title and how many items each holds. */
	public record Filled(String tab, String id, String title, int count) {
		public Identifier texture() {
			return WeaponItems.id("textures/gui/creative/" + tab + "/" + id + ".png");
		}
	}

	private static final Map<Identifier, List<Filled>> FILLED = new ConcurrentHashMap<>();

	private CreativeSections() {}

	/** Fills a tab (name: its banners' folder; id: its registered id) from its sections in order, keeping each section's size. */
	static void fill(String tab, Identifier id, List<Section> sections, Consumer<ItemStack> out) {
		List<Filled> filled = new ArrayList<>();
		for (Section s : sections) {
			List<ItemStack> items = new ArrayList<>();
			s.fill().accept(items);
			for (ItemStack i : items) out.accept(i);
			filled.add(new Filled(tab, s.id(), s.title(), items.size()));
		}
		FILLED.put(id, List.copyOf(filled));
	}

	/** A tab's sections (by the tab's id), or null when it has none. */
	public static List<Filled> of(Identifier tab) {
		return FILLED.get(tab);
	}
}
