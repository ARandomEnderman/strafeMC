package dev.strafemc.client.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.strafemc.client.StrafeSettingsScreen;

/** Mod Menu: strafeMC's Config button opens the strafeMC settings. Only loaded when Mod Menu is installed. */
public final class ModMenuCompat implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return StrafeSettingsScreen::new;
	}
}
