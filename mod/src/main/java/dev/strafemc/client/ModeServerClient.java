package dev.strafemc.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;

/**
 * A game mode server (strafemc_gamemode_server 1) plays like CS2: no inventory screen (E is CS2's use key: it defuses
 * the bomb and picks up or swaps in a weapon on the ground), first person only (F5 does nothing), no entity hitboxes (F3+B; the player's own setting comes back when
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
		while (mc.options.keyInventory.consumeClick()) usePickup(mc); // E: CS2's use (held: the defuse reads it)
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

	/** How far E reaches a weapon on the ground (blocks; CS2's use reach, FLAGGED ours). */
	private static final double PICKUP_REACH = 2.5;

	/** E on a weapon on the ground: the server takes it into its slot, or swaps it for what's there (GameModes.usePickup). */
	private static void usePickup(Minecraft mc) {
		var p = mc.player;
		if (p == null || mc.level == null || mc.screen != null) return;
		var eye = p.getEyePosition();
		var end = eye.add(p.getViewVector(1).scale(PICKUP_REACH));
		net.minecraft.world.entity.item.ItemEntity best = null;
		double bestD = Double.MAX_VALUE;
		for (var e : mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, p.getBoundingBox().inflate(PICKUP_REACH + 1))) {
			var hit = e.getBoundingBox().inflate(0.25).clip(eye, end); // a little bigger than the item: dropped guns are small
			if (hit.isEmpty()) continue;
			double d = eye.distanceToSqr(hit.get());
			if (d < bestD) {
				bestD = d;
				best = e;
			}
		}
		if (best != null && net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(dev.strafemc.net.StrafePayloads.Action.TYPE))
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.strafemc.net.StrafePayloads.Action("use_pickup", String.valueOf(best.getId())));
	}

	/** The key that defuses the bomb: E (CS2's use) on a game mode server, else Minecraft's use button. */
	public static boolean useDown(Minecraft mc) {
		return on() ? mc.options.keyInventory.isDown() : mc.options.keyUse.isDown();
	}
}
