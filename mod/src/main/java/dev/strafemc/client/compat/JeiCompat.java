package dev.strafemc.client.compat;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.resources.Identifier;

/** JEI (when installed): its item list stays off our own screens (the loadout, a weapon case), like CS2's menus. */
@JeiPlugin
public final class JeiCompat implements IModPlugin {
	@Override
	public Identifier getPluginUid() {
		return Identifier.fromNamespaceAndPath("strafemc", "jei");
	}

	@Override
	public void registerGuiHandlers(IGuiHandlerRegistration r) {
		// the loadout is an inventory screen: JEI only keeps out of areas marked as taken, so the whole screen is
		r.addGuiContainerHandler(dev.strafemc.client.LoadoutScreen.class, new mezz.jei.api.gui.handlers.IGuiContainerHandler<dev.strafemc.client.LoadoutScreen>() {
			@Override
			public java.util.List<net.minecraft.client.renderer.Rect2i> getGuiExtraAreas(dev.strafemc.client.LoadoutScreen screen) {
				return java.util.List.of(new net.minecraft.client.renderer.Rect2i(0, 0, screen.width, screen.height));
			}
		});
		r.addGuiScreenHandler(dev.strafemc.client.CaseScreen.class, screen -> null);
	}
}
