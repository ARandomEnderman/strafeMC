package dev.strafemc.client;

import dev.strafemc.StrafeMc;
import dev.strafemc.bridge.Cs2Bridge;
import dev.strafemc.client.console.ConsoleKeys;
import dev.strafemc.client.console.ConsoleLog;
import dev.strafemc.client.movement.MovementController;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.data.WeaponData;
import dev.strafemc.net.StrafePayloads.NoclipState;
import dev.strafemc.server.Noclip;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public class StrafeMcClient implements ClientModInitializer {
	/** Your CS armor (server-synced) for the HUD. */
	static volatile int armor;
	static volatile boolean helmet;

	@Override
	public void onInitializeClient() {
		StrafeConfig.load();
		dev.strafemc.client.assets.RuntimePack.build();
		Cs2Bridge.INSTANCE.start();
		dev.strafemc.weapon.GunItem.clientThread = Thread.currentThread(); // client init runs on the render thread
		dev.strafemc.weapon.GunItem.clientStats = ClientGameplay::stats;
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.GameplaySync.TYPE,
				(msg, ctx) -> ClientGameplay.apply(msg.json()));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.ShotSeed.TYPE,
				(msg, ctx) -> dev.strafemc.client.weapon.GunClient.INSTANCE.setShotSeed(msg.seed(), msg.next()));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.Flashed.TYPE,
				(msg, ctx) -> dev.strafemc.client.weapon.GrenadeClient.onFlashed(msg));
		dev.strafemc.client.weapon.GrenadeClient.registerHud();
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.ArmorSync.TYPE,
				(msg, ctx) -> { armor = msg.value(); helmet = msg.helmet(); });
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> armor = 0);
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(net.minecraft.resources.Identifier.fromNamespaceAndPath("strafemc", "armor"),
				(g, delta) -> { // CS armor: shown while worn
					var mc = Minecraft.getInstance();
					if (armor <= 0 || mc.options.hideGui || mc.player == null) return;
					g.text(mc.font, (helmet ? "\u00a7f\u26e8+ " : "\u00a7f\u26e8 ") + armor, 8, g.guiHeight() - 20, 0xFFFFFFFF, true);
				});
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(dev.strafemc.client.weapon.GrenadeClient::tick);
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(dev.strafemc.client.weapon.GrenadeClient::render);
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.END_MAIN.register(dev.strafemc.client.weapon.GrenadeClient::renderSmoke);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> dev.strafemc.client.weapon.GrenadeClient.clear());
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.GrenadeFx.TYPE,
				(msg, ctx) -> dev.strafemc.client.weapon.GrenadeClient.onEffect(msg));
		net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.strafemc.weapon.GrenadeEntity.TYPE,
				dev.strafemc.client.viewmodel.GrenadeRenderer::new);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.ServerTick.TYPE,
				(msg, ctx) -> LagClock.onServerTick(msg.tick()));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> LagClock.reset());
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.Tagged.TYPE,
				(msg, ctx) -> dev.strafemc.client.movement.MovementController.tag(msg.large(), msg.small()));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.ShotCorrection.TYPE,
				(msg, ctx) -> dev.strafemc.client.impact.BulletHoles.dropPredicted(msg.shot()));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.ConsoleReply.TYPE,
				(msg, ctx) -> dev.strafemc.client.console.ConsoleLog.add(msg.text(), switch (msg.kind()) {
					case 1 -> dev.strafemc.client.console.ConsoleLog.OK;
					case 2 -> dev.strafemc.client.console.ConsoleLog.ERROR;
					default -> dev.strafemc.client.console.ConsoleLog.WHITE;
				}));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> ClientGameplay.clear());
		// first-time setup / top-up after an update: offered on the title screen (or on joining, if that was skipped) while CS2 files are missing
		dev.strafemc.client.particle.ParticleBlend.init();
		SetupScreen.registerHud();
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (!(screen instanceof SetupScreen)) // the running setup's progress on every menu (title, pause, options...)
				net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterExtract(screen).register((s, g, mx, my, d) -> {
					String p = SetupScreen.progressLine();
					if (p != null) g.centeredText(mc.font, p, s.width / 2, 2, 0xFFFFFFFF);
				});
			if (screen instanceof net.minecraft.client.gui.screens.TitleScreen) SetupScreen.offer(screen);
		});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> SetupScreen.offer(null));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
				(handler, sender, mc) -> dev.strafemc.client.console.ConsoleCommands.sendTeam()); // your side, for your smokes' colour
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			// your own world gets your CS2 server (started once; it keeps running until Minecraft closes); asked once
			if (mc.getSingleplayerServer() == null) return;
			if (!StrafeConfig.hostAsked && !StrafeConfig.hostServer && dev.strafemc.client.console.ServerLauncher.canOfferHosting())
				mc.execute(() -> mc.setScreen(new HostConsentScreen()));
			else if (StrafeConfig.hostServer && !dev.strafemc.bridge.Cs2Bridge.INSTANCE.connected())
				dev.strafemc.client.console.ServerLauncher.start();
		});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			if (mc.getSingleplayerServer() != null) return; // your own world: your CS2 server is its CS2 server
			boolean local = handler.getConnection().getRemoteAddress() instanceof java.net.InetSocketAddress a && a.getAddress() != null
					&& a.getAddress().isLoopbackAddress();
			if (local || !dev.strafemc.bridge.Cs2Bridge.INSTANCE.connected()) return; // a server on this PC owns that CS2 server
			dev.strafemc.client.console.ConsoleLog.add("Joined another server: stopping your local CS2 server (it isn't used there).",
					dev.strafemc.client.console.ConsoleLog.WARN);
			dev.strafemc.client.console.ServerLauncher.stop();
		});
		WeaponData.loadAsync();
		dev.strafemc.client.sound.CsAudio.prepareAsync();
		ConsoleKeys.register();
		dev.strafemc.client.weapon.GunClient.register();
		dev.strafemc.client.weapon.AmmoHud.register();
		ShowPosHud.register();
		JumpStats.register();
		Cs2Bridge.onConnectionChange = c -> Minecraft.getInstance().execute(() -> net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(
				Minecraft.getInstance().getToastManager(), net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.NARRATOR_TOGGLE,
				net.minecraft.network.chat.Component.literal(c ? "CS2 server connected" : "CS2 server disconnected"),
				net.minecraft.network.chat.Component.literal(c ? "Live cvars, console and sounds are on" : "Movement keeps the last known cvars")));
		dev.strafemc.client.console.Binds.load();

		Noclip.clientSource = p -> p == Minecraft.getInstance().player && MovementController.INSTANCE.active();
		Noclip.clientCheck = p -> p == Minecraft.getInstance().player && MovementController.INSTANCE.noclip();
		ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.CsSound.TYPE,
				(msg, ctx) -> { // weapon sounds follow the player who made them
					var src = ctx.client().level == null ? null : ctx.client().level.getEntity(msg.entity());
					dev.strafemc.client.sound.CsSounds.play(msg.event(), msg.x(), msg.y(), msg.z(), 1.0, msg.suppressed(), null);
				});
		ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.BulletImpact.TYPE,
				(msg, ctx) -> dev.strafemc.client.impact.BulletHoles.onImpact(msg));
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(dev.strafemc.client.impact.BulletHoles::render);
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(dev.strafemc.client.particle.Effects::renderWorld);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> dev.strafemc.client.particle.Effects.clear());
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> dev.strafemc.client.impact.BulletHoles.clear());
		ClientPlayNetworking.registerGlobalReceiver(NoclipState.TYPE, (msg, ctx) -> {
			MovementController.INSTANCE.setNoclip(msg.on());
			ConsoleLog.add(msg.reason(), msg.on() ? ConsoleLog.OK : ConsoleLog.WARN);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> MovementController.INSTANCE.setNoclip(false));
		if ("1".equals(System.getenv("STRAFEMC_PROBE_OPTIONS"))) { // dev-only: open Options once and log where every button sits
			net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(new java.util.function.Consumer<Minecraft>() {
				int n;
				public void accept(Minecraft mc) {
					if (++n == 40) mc.setScreen(new net.minecraft.client.gui.screens.options.OptionsScreen(mc.screen, mc.options, false));
					if (n == 60 && mc.screen != null) for (var c : mc.screen.children())
						if (c instanceof net.minecraft.client.gui.components.AbstractWidget w)
							StrafeMc.LOG.info("[strafemc] probe widget '{}' at {},{} {}x{} visible={}", w.getMessage().getString(), w.getX(), w.getY(), w.getWidth(), w.getHeight(), w.visible);
				}
			}::accept);
		}
		StrafeMc.LOG.info("[strafemc] client init done (runtime dir {})", StrafeConfig.RUNTIME_DIR);
	}
}
