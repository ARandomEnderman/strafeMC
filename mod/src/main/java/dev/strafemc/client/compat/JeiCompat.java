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
		r.addGuiScreenHandler(dev.strafemc.client.LoadoutScreen.class, screen -> null);
		r.addGuiScreenHandler(dev.strafemc.client.CaseScreen.class, screen -> null);
	}
}
