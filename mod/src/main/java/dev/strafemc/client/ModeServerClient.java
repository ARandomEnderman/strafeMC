package dev.strafemc.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;

/**
 * A game mode server (strafemc_gamemode_server 1) plays like CS2: no inventory screen (E is CS2's use key: it defuses
 * the bomb), first person only (F5 does nothing), no entity hitboxes (F3+B; the player's own setting comes back when
 * they leave).
 */
public final class ModeServerClient {
	/** The player's hitbox setting before a game mode server turned it off, or null. */
	private static DebugScreenEntryStatus hitboxesBefore;

	private ModeServerClient() {}

	public static boolean on() {
		return Minecraft.getInstance().player != null && ClientGameplay.rule("modeServer", false);
	}

	public static void register() {
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.START_CLIENT_TICK.register(ModeServerClient::tick);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> mc.execute(ModeServerClient::restore));
	}

	/** Before Minecraft handles its keys this tick: E and F5 clicks are dropped, the view kept in first person. */
	private static void tick(Minecraft mc) {
		if (!on()) {
			restore();
			return;
		}
		while (mc.options.keyInventory.consumeClick()) {} // held E still reads as down: the defuse uses it
		while (mc.options.keyTogglePerspective.consumeClick()) {}
		WeaponSelectHud.keys(mc); // 1-5 are CS2's gear slots
		if (!mc.options.getCameraType().isFirstPerson()) mc.options.setCameraType(CameraType.FIRST_PERSON);
		if (mc.debugEntries.isCurrentlyEnabled(DebugScreenEntries.ENTITY_HITBOXES)) {
			if (hitboxesBefore == null) hitboxesBefore = mc.debugEntries.getStatus(DebugScreenEntries.ENTITY_HITBOXES);
			mc.debugEntries.setStatus(DebugScreenEntries.ENTITY_HITBOXES, DebugScreenEntryStatus.NEVER);
		}
	}

	private static void restore() {
		if (hitboxesBefore == null) return;
		Minecraft.getInstance().debugEntries.setStatus(DebugScreenEntries.ENTITY_HITBOXES, hitboxesBefore);
		hitboxesBefore = null;
	}

	/** The key that defuses the bomb: E (CS2's use) on a game mode server, else Minecraft's use button. */
	public static boolean useDown(Minecraft mc) {
		return on() ? mc.options.keyInventory.isDown() : mc.options.keyUse.isDown();
	}
}
