package dev.strafemc.mixin.client;

import dev.strafemc.client.CreativeSectionLayout;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * strafeMC's creative tabs in sections (CreativeSections): once a tab's items are in the picker, they're laid out with
 * an empty row before each section, and its banner is drawn over that row.
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeSectionsMixin extends AbstractContainerScreen<CreativeModeInventoryScreen.ItemPickerMenu> {
	@Shadow
	private static CreativeModeTab selectedTab;
	@Shadow
	private float scrollOffs;

	protected CreativeSectionsMixin(CreativeModeInventoryScreen.ItemPickerMenu menu, Inventory inv, Component title) {
		super(menu, inv, title);
	}

	@Inject(method = "selectTab", at = @At("TAIL"))
	private void strafemc$sectionsOnSelect(CreativeModeTab tab, CallbackInfo ci) {
		strafemc$layOut();
	}

	@Inject(method = "refreshCurrentTabContents", at = @At("TAIL"))
	private void strafemc$sectionsOnRefresh(java.util.Collection<net.minecraft.world.item.ItemStack> items, CallbackInfo ci) {
		strafemc$layOut();
	}

	private void strafemc$layOut() {
		var id = selectedTab == null ? null : BuiltInRegistries.CREATIVE_MODE_TAB.getKey(selectedTab);
		if (CreativeSectionLayout.layOut(id, menu.items)) menu.scrollTo(scrollOffs);
	}

	@Inject(method = "extractBackground", at = @At("TAIL"))
	private void strafemc$banners(GuiGraphicsExtractor g, int mx, int my, float delta, CallbackInfo ci) {
		int rows = (menu.items.size() + 8) / 9 - 5;
		int top = rows <= 0 ? 0 : (int) (scrollOffs * rows + 0.5); // the picker's own first visible row
		CreativeSectionLayout.drawBanners(g, leftPos + 8, topPos + 17, Math.max(0, top));
	}
}
