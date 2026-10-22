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

	/** Essential's Multiplayer tabs (its own buttons, found by their labels). */
	private static final java.util.Set<String> ESSENTIAL_TABS = java.util.Set.of("Favorites", "Friends", "Discover");

	/**
	 * Your loadout (buy menu slots, skins, agents) before joining a server: a button on the Multiplayer screen. With
	 * Essential it sits in the row of its Favorites / Friends / Discover tabs (right of them, else left, if there's room);
	 * otherwise at the top right. Essential rebuilds its buttons when you switch tabs, so it's put back if it's gone.
	 */
	private static void loadoutButton(net.minecraft.client.gui.screens.Screen screen) {
		var mc = net.minecraft.client.Minecraft.getInstance();
		var widgets = net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen);
		var button = net.minecraft.client.gui.components.Button.builder(net.minecraft.network.chat.Component.literal("strafeMC Loadout"),
				b -> mc.setScreen(new MenuLoadoutScreen(screen))).build();
		place(button, widgets, screen.width);
		widgets.add(button);
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterTick(screen).register(s -> {
			var now = net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(s);
			if (now.contains(button)) return;
			place(button, now, s.width);
			now.add(button);
		});
	}

	private static void place(net.minecraft.client.gui.components.Button button, java.util.List<net.minecraft.client.gui.components.AbstractWidget> widgets, int screenW) {
		int left = Integer.MAX_VALUE, right = Integer.MIN_VALUE, y = -1, w = 100;
		for (var wd : widgets)
			if (wd != button && ESSENTIAL_TABS.contains(wd.getMessage().getString())) {
				left = Math.min(left, wd.getX());
				right = Math.max(right, wd.getX() + wd.getWidth());
				y = wd.getY();
			}
		if (y >= 0 && right + 4 + w <= screenW - 4) button.setRectangle(w, 20, right + 4, y);
		else if (y >= 0 && left - 4 - w >= 4) button.setRectangle(w, 20, left - 4 - w, y);
		else button.setRectangle(110, 20, screenW - 116, 6); // no Essential, or no room beside its tabs
	}

	@Override
	public void onInitializeClient() {
		StrafeConfig.load();
		// the surf slope's outer corner as real triangles (Fabric renderer)
		net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin.register(ctx -> ctx.modifyBlockModelAfterBake()
				.register((model, c) -> SurfSlopeCornerModel.wrap(model, c.state())));
		dev.strafemc.client.assets.RuntimePack.build();
		Cs2Bridge.INSTANCE.start();
		dev.strafemc.weapon.GunItem.clientThread = Thread.currentThread(); // client init runs on the render thread
		dev.strafemc.weapon.GunItem.clientStats = ClientGameplay::stats; // (class, mode)
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.GameplaySync.TYPE,
				(msg, ctx) -> ClientGameplay.apply(msg.json()));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.ShotSeed.TYPE,
				(msg, ctx) -> dev.strafemc.client.weapon.GunClient.INSTANCE.setShotSeed(msg.seed(), msg.next()));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.Flashed.TYPE,
				(msg, ctx) -> dev.strafemc.client.weapon.GrenadeClient.onFlashed(msg));
		dev.strafemc.client.weapon.GrenadeClient.registerHud();
		dev.strafemc.client.weapon.GrenadeCam.register();
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.ArmorSync.TYPE,
				(msg, ctx) -> { armor = msg.value(); helmet = msg.helmet(); });
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> armor = 0);
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(net.minecraft.resources.Identifier.fromNamespaceAndPath("strafemc", "armor"),
				(g, delta) -> { // CS armor: shown while worn
					var mc = Minecraft.getInstance();
					if (armor <= 0 || mc.options.hideGui || mc.player == null || dev.strafemc.client.ModeHud.active()) return; // a game mode's HUD shows it
					g.text(mc.font, (helmet ? "\u00a7f\u26e8+ " : "\u00a7f\u26e8 ") + armor, 8, g.guiHeight() - 20, 0xFFFFFFFF, true);
				});
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(dev.strafemc.client.weapon.GrenadeClient::tick);
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(dev.strafemc.client.ui.UiSweep::tick);
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.level != null && mc.level.getGameTime() % 200 == 0) dev.strafemc.client.viewmodel.AgentModels.tidy();
		});
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(net.minecraft.resources.Identifier.fromNamespaceAndPath("strafemc", "perf"),
				(g, delta) -> dev.strafemc.client.perf.Perf.hud(g));
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(dev.strafemc.client.perf.PerfBench::tick);
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> {
			long t = dev.strafemc.client.perf.Perf.start();
			dev.strafemc.client.weapon.GrenadeClient.render(ctx);
			dev.strafemc.client.perf.Perf.end("grenade_preview", t);
		});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> dev.strafemc.client.weapon.GrenadeClient.clear());
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.GrenadeFx.TYPE,
				(msg, ctx) -> dev.strafemc.client.weapon.GrenadeClient.onEffect(msg));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.InfernoState.TYPE,
				(msg, ctx) -> dev.strafemc.client.weapon.InfernoClient.onState(msg));
		net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.strafemc.weapon.StrafePearlEntity.TYPE,
				net.minecraft.client.renderer.entity.ThrownItemRenderer::new);
		net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.strafemc.weapon.GrenadeEntity.TYPE,
				dev.strafemc.client.viewmodel.GrenadeRenderer::new);
		net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.strafemc.weapon.PlantedC4.TYPE,
				dev.strafemc.client.viewmodel.PlantedC4Renderer::new);
		C4Client.register();
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
		dev.strafemc.client.particle.ParticleBloom.init();
		dev.strafemc.client.smoke.SmokeRenderer.init();
		SetupScreen.registerHud();
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (!(screen instanceof SetupScreen)) // the running setup's progress on every menu (title, pause, options...)
				net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterExtract(screen).register((s, g, mx, my, d) -> {
					String p = SetupScreen.progressLine();
					if (p != null) g.centeredText(mc.font, p, s.width / 2, 2, 0xFFFFFFFF);
				});
			if (screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
				SetupScreen.offer(screen);
			}
			if (screen instanceof net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen) loadoutButton(screen);
			// on a game mode server: the skins (not the buy menu loadout) from the Esc menu, mid-match
			if (screen instanceof net.minecraft.client.gui.screens.PauseScreen && dev.strafemc.weapon.MenuLoadout.client)
				net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(net.minecraft.client.gui.components.Button.builder(
						net.minecraft.network.chat.Component.literal("Skins"), b -> mc.setScreen(new MenuLoadoutScreen(screen, 1)))
						.bounds(screen.width / 2 - 50, 6, 100, 20).build());
		});
		MyLoadout.register();
		net.minecraft.client.gui.screens.MenuScreens.register(dev.strafemc.weapon.Loadout.MENU, LoadoutScreen::new);
		net.minecraft.client.gui.screens.MenuScreens.register(dev.strafemc.weapon.MagPouchItem.MENU, net.minecraft.client.gui.screens.inventory.ContainerScreen::new);
		net.minecraft.client.gui.screens.MenuScreens.register(dev.strafemc.weapon.StorageUnitItem.MENU, StorageUnitScreen::new);
		LoadoutKey.register();
		SprayKey.register();
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.Refundable.TYPE,
				(msg, ctx) -> BuyMenuScreen.refundable(msg.items()));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> BuyMenuScreen.refundable(java.util.List.of()));
		dev.strafemc.weapon.ZeusRecharge.clientGameTime = () -> { // the Zeus charging bar
			var l = net.minecraft.client.Minecraft.getInstance().level;
			return l == null ? 0 : l.getGameTime();
		};
		LoadoutPreview.register();
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
		dev.strafemc.client.ModeHud.register();
		dev.strafemc.client.ZoneRender.register();
		dev.strafemc.client.ModeScoreboard.register();
		dev.strafemc.client.weapon.ScopeHud.register();
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
					// the player you watch fires: their viewmodel shoots too
					if (src != null && src == dev.strafemc.client.viewmodel.Viewmodel.holder() && dev.strafemc.client.viewmodel.Viewmodel.watching()
							&& msg.event().startsWith("Weapon_") && (msg.event().contains("Single") || msg.event().contains("Silenced")))
						dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.play("shoot1");
					// sounds on you (being hit, your own feedback) play as your own, not from a spot above your head
					if (src != null && src == ctx.client().player) dev.strafemc.client.sound.CsSounds.playOn(msg.event(), src, 1.0, msg.suppressed());
					else dev.strafemc.client.sound.CsSounds.play(msg.event(), msg.x(), msg.y(), msg.z(), 1.0, msg.suppressed(), null);
				});
		ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.KillNotice.TYPE, (msg, ctx) -> {
			DeathNotices.receive(msg);
			KillCards.notice(msg);
			DeathCam.notice(msg);
		});
		ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.CaseResult.TYPE, (msg, ctx) -> CaseScreen.result(msg));
		ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.BulkCaseResult.TYPE, (msg, ctx) -> CaseScreen.bulkResult(msg));
		ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.UpgradeResult.TYPE, (msg, ctx) -> UpgraderScreen.result(msg));
		ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.TradeUpResult.TYPE, (msg, ctx) -> TradeUpScreen.result(msg));
		ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.DeathInfo.TYPE, (msg, ctx) -> DeathCam.info(msg));
		DeathCam.register();
		StrafeToasts.register();
		dev.strafemc.StrafeMc.openUnseal = () -> Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(new UnsealScreen()));
		ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.Toast.TYPE, (msg, ctx) -> StrafeToasts.show(msg.text(), msg.color()));
		ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.SprayPlaced.TYPE, (msg, ctx) -> dev.strafemc.client.impact.SprayDecals.placed(msg));
		dev.strafemc.weapon.CaseItem.clientOpen = CaseScreen::open;
		dev.strafemc.weapon.StickerItem.clientOpen = StickerScreen::open;
		dev.strafemc.weapon.NameTagItem.clientOpen = NameTagScreen::open;
		dev.strafemc.weapon.NameTagItem.clientOpenSwap = StatTrakSwapScreen::open;
		dev.strafemc.weapon.NameTagItem.clientOpenBulk = BulkOpenScreen::open;
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> {
			DeathNotices.clear();
			KillCards.clear();
		});
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(net.minecraft.resources.Identifier.fromNamespaceAndPath("strafemc", "deathnotices"),
				(g, delta) -> DeathNotices.render(g));
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(net.minecraft.resources.Identifier.fromNamespaceAndPath("strafemc", "killcards"),
				(g, delta) -> KillCards.render(g));
		ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.MoveSound.TYPE, // another player's CS2 footstep / jump / landing
				(msg, ctx) -> dev.strafemc.client.sound.CsSounds.play(msg.event(), msg.x(), msg.y(), msg.z(),
						msg.gain() * dev.strafemc.config.StrafeConfig.movementVolume, false, null));
		ClientPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.BulletImpact.TYPE,
				(msg, ctx) -> dev.strafemc.client.impact.BulletHoles.onImpact(msg));
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> {
			long t = dev.strafemc.client.perf.Perf.start();
			dev.strafemc.client.impact.BulletHoles.render(ctx);
			dev.strafemc.client.perf.Perf.end("bullet_holes", t);
		});
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> {
			long t = dev.strafemc.client.perf.Perf.start();
			dev.strafemc.client.impact.SprayDecals.render(ctx);
			dev.strafemc.client.perf.Perf.end("sprays", t);
		});
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> DeathCam.frame(Minecraft.getInstance())); // every frame
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx -> {
			long t = dev.strafemc.client.perf.Perf.start();
			dev.strafemc.client.particle.Effects.renderWorld(ctx);
			dev.strafemc.client.perf.Perf.end("particles", t);
		});
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
