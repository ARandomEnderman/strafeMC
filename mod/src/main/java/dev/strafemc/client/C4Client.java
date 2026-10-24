package dev.strafemc.client;

import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.PlantedC4;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.EntityHitResult;

/**
 * The C4 on this side: defusing (holding use while looking at a planted bomb within reach tells the server every few
 * ticks; with the keypad, the keypad being open does), holding still while planting or defusing, and the progress
 * bar above the hotbar.
 */
public final class C4Client {
	/** Ticks between defuse messages while holding (the server stops a defuse after 10 ticks without one). */
	private static final int PING_EVERY = 4;
	private static final double REACH = 2.5;
	private static int pingIn;
	private static boolean defusing;

	private C4Client() {}

	public static void register() {
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(C4Client::tick);
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(net.minecraft.resources.Identifier.fromNamespaceAndPath("strafemc", "c4"),
				(g, delta) -> render(g));
	}

	/** Holding use on the bomb (or its keypad): no attacking meanwhile (the knife's right-click stab is the use button). */
	public static boolean defusing() {
		return defusing;
	}

	/** Planting or defusing: movement is held still, as in CS2. */
	public static boolean frozen() {
		return defusing || dev.strafemc.client.weapon.GunClient.INSTANCE.planting() || dev.strafemc.client.ModeHud.frozen();
	}

	/** The bomb being defused through the keypad, else the one held on with use, else null. */
	private static PlantedC4 target(Minecraft mc) {
		if (mc.player == null) return null;
		if (mc.screen instanceof C4KeypadScreen k) return k.bomb();
		if (mc.screen != null || !ModeServerClient.useDown(mc)) return null; // E on a game mode server, as CS2
		if (mc.hitResult instanceof EntityHitResult eh && eh.getEntity() instanceof PlantedC4 b && !b.defused() && b.distanceTo(mc.player) <= REACH) return b;
		return null;
	}

	private static void tick(Minecraft mc) {
		PlantedC4 b = target(mc);
		boolean was = defusing;
		defusing = b != null;
		if (b == null) {
			pingIn = 0;
			return;
		}
		if (!was && ClientGameplay.c4Keypad() && !(mc.screen instanceof C4KeypadScreen)) mc.setScreen(C4KeypadScreen.defuse(b));
		if (--pingIn <= 0) {
			pingIn = PING_EVERY;
			if (ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE)) ClientPlayNetworking.send(new StrafePayloads.Action("c4_defuse", String.valueOf(b.getId())));
		}
	}

	/** The bomb this player is defusing (as the server says), or null. */
	static PlantedC4 defusingBomb(Minecraft mc) {
		if (mc.level == null || mc.player == null) return null;
		for (var e : mc.level.entitiesForRendering())
			if (e instanceof PlantedC4 b && b.defuser() == mc.player.getId() && !b.defused()) return b;
		return null;
	}

	private static void render(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.options.hideGui || ClientGameplay.c4Keypad()) return; // the keypad shows its own progress
		double progress;
		String label;
		var gun = dev.strafemc.client.weapon.GunClient.INSTANCE;
		if (gun.planting()) {
			progress = gun.plantProgress();
			label = "Planting the bomb";
		} else {
			PlantedC4 b = defusingBomb(mc);
			if (b == null) return;
			progress = b.defuseProgress();
			label = b.defuseSeconds() < PlantedC4.DEFUSE - 0.5 ? "Defusing the bomb (defuse kit)" : "Defusing the bomb";
		}
		int w = 182, x = g.guiWidth() / 2 - w / 2, y = g.guiHeight() - 68;
		var font = mc.font;
		g.text(font, label, g.guiWidth() / 2 - font.width(label) / 2, y - 11, 0xFFFFFFFF, true);
		g.fill(x - 1, y - 1, x + w + 1, y + 6, 0xC0000000);
		g.fill(x, y, x + (int) Math.round(w * progress), y + 5, 0xFFEABE54);
	}
}
