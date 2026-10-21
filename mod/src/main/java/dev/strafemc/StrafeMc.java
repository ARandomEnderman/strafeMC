package dev.strafemc;

import dev.strafemc.net.StrafePayloads.NoclipRequest;
import dev.strafemc.net.StrafePayloads.NoclipState;
import dev.strafemc.server.Access;
import dev.strafemc.server.Noclip;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StrafeMc implements ModInitializer {
	/** Opens the graffiti unseal screen (set by the client; nothing on a dedicated server). */
	public static Runnable openUnseal = () -> {};

	public static final String MOD_ID = "strafemc";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		dev.strafemc.weapon.WeaponItems.register();
		dev.strafemc.weapon.Loadout.register();
		dev.strafemc.weapon.MenuLoadout.register();
		dev.strafemc.weapon.CsTeam.register();
		dev.strafemc.server.ServerRules.register();
		dev.strafemc.weapon.CarePackage.register();
		dev.strafemc.weapon.Shop.register();
		dev.strafemc.weapon.SprayLog.register();
		dev.strafemc.weapon.Stats.register();
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof net.minecraft.server.level.ServerPlayer p) {
				dev.strafemc.weapon.Loadout.onDeath(p);
				dev.strafemc.weapon.DamageReport.died(p, source);
			}
		});
		// gameplay data lives on the server side (dedicated or integrated): weapons.vdata and the CS2 bridge start here
		dev.strafemc.data.WeaponData.loadAsync();
		dev.strafemc.server.StrafeOps.register();
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(dev.strafemc.server.StrafeOps::load);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(dev.strafemc.server.WorldCvars::load);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(dev.strafemc.server.StrafeSettings::load);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(dev.strafemc.world.SpawnPoints::load);
		dev.strafemc.weapon.StructureLoot.register();
		dev.strafemc.mode.GameModes.register(); // after StrafeSettings' SERVER_STARTED: it reads the world's mode
		dev.strafemc.mode.Zones.register();
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.ZoneList.TYPE, dev.strafemc.net.StrafePayloads.ZoneList.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.ModeState.TYPE, dev.strafemc.net.StrafePayloads.ModeState.CODEC);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(s -> dev.strafemc.server.StrafeSettings.unload());
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(s -> dev.strafemc.server.WorldCvars.unload());
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.strafemc.server.WorldCvars::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(s -> dev.strafemc.server.StrafeOps.unload());
		dev.strafemc.bridge.Cs2Bridge.INSTANCE.start();
		PayloadTypeRegistry.serverboundPlay().register(dev.strafemc.net.StrafePayloads.ConsoleCommand.TYPE, dev.strafemc.net.StrafePayloads.ConsoleCommand.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.ConsoleReply.TYPE, dev.strafemc.net.StrafePayloads.ConsoleReply.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.ConsoleCommand.TYPE,
				(msg, ctx) -> { if (dev.strafemc.server.RateLimit.allow(ctx.player(), "console", 5)) dev.strafemc.server.ConsoleRelay.handle(ctx.player(), msg.line()); });
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.GameplaySync.TYPE, dev.strafemc.net.StrafePayloads.GameplaySync.CODEC);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> dev.strafemc.server.GameplaySync.onJoin(handler.getPlayer()));
		// buy time counts from joining or respawning (strafemc_buytime)
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> dev.strafemc.weapon.BuyMenu.spawned(handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> dev.strafemc.weapon.BuyMenu.forget(handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> dev.strafemc.weapon.DamageReport.forget(handler.getPlayer()));
		net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((oldP, newP, alive) -> {
			dev.strafemc.weapon.BuyMenu.spawned(newP);
			// a random spawn block, if the world has any and they're on
			if (!alive && dev.strafemc.server.StrafeSettings.spawnBlocks()) dev.strafemc.world.SpawnPoints.respawn(newP);
			// CS2: you come back with full magazines (the guns you kept), a charged Zeus
			if (!alive) {
				var inv = newP.getInventory();
				for (int i = 0; i < inv.getContainerSize(); i++) {
					var s = inv.getItem(i);
					if (s.getItem() instanceof dev.strafemc.weapon.GunItem) {
						s.remove(dev.strafemc.weapon.WeaponItems.CLIP);
						s.remove(dev.strafemc.weapon.WeaponItems.RECHARGE);
					}
				}
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> dev.strafemc.server.GameplaySync.onLeave(handler.getPlayer()));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.strafemc.server.GameplaySync::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.strafemc.weapon.CaseOpening::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.strafemc.weapon.Shop::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.strafemc.weapon.DamageReport::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.strafemc.weapon.Spin::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.strafemc.weapon.Upgrader::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> dev.strafemc.weapon.Upgrader.claim(handler.getPlayer()));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> dev.strafemc.weapon.Shop.sync(handler.getPlayer()));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.strafemc.weapon.Bots::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((e, level) -> dev.strafemc.weapon.Bots.onLoad(e));
		PayloadTypeRegistry.serverboundPlay().register(dev.strafemc.net.StrafePayloads.Action.TYPE, dev.strafemc.net.StrafePayloads.Action.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.Action.TYPE,
				(msg, ctx) -> { // rethrow is meant to be spammed (bound to the wheel): its own, higher limit
					boolean rethrow = msg.cmd().equals("sv_rethrow_last_grenade");
					if (dev.strafemc.server.RateLimit.allow(ctx.player(), rethrow ? "rethrow" : "action", rethrow ? 20 : 5))
						dev.strafemc.server.ConsoleActions.run(ctx.player(), msg.cmd(), msg.arg());
				});
		PayloadTypeRegistry.serverboundPlay().register(dev.strafemc.net.StrafePayloads.Fire.TYPE, dev.strafemc.net.StrafePayloads.Fire.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(dev.strafemc.net.StrafePayloads.Reload.TYPE, dev.strafemc.net.StrafePayloads.Reload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(dev.strafemc.net.StrafePayloads.Knife.TYPE, dev.strafemc.net.StrafePayloads.Knife.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.Knife.TYPE,
				(msg, ctx) -> { if (dev.strafemc.server.RateLimit.allow(ctx.player(), "knife", 10)) dev.strafemc.weapon.GunServer.knife(ctx.player(), msg); });
		ServerPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.Fire.TYPE,
				(msg, ctx) -> { if (dev.strafemc.server.RateLimit.allow(ctx.player(), "fire", 40)) dev.strafemc.weapon.GunServer.fire(ctx.player(), msg); });
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.ShotSeed.TYPE, dev.strafemc.net.StrafePayloads.ShotSeed.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.ShotCorrection.TYPE, dev.strafemc.net.StrafePayloads.ShotCorrection.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.Tagged.TYPE, dev.strafemc.net.StrafePayloads.Tagged.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.ServerTick.TYPE, dev.strafemc.net.StrafePayloads.ServerTick.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.GrenadeFx.TYPE, dev.strafemc.net.StrafePayloads.GrenadeFx.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.InfernoState.TYPE, dev.strafemc.net.StrafePayloads.InfernoState.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.Flashed.TYPE, dev.strafemc.net.StrafePayloads.Flashed.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.ArmorSync.TYPE, dev.strafemc.net.StrafePayloads.ArmorSync.CODEC);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.strafemc.weapon.CsArmor::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.strafemc.weapon.GrenadeZones::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.strafemc.weapon.HealthshotItem::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.strafemc.weapon.ZeusRecharge::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(s -> dev.strafemc.weapon.GrenadeZones.clear());
		PayloadTypeRegistry.serverboundPlay().register(dev.strafemc.net.StrafePayloads.Throw.TYPE, dev.strafemc.net.StrafePayloads.Throw.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(dev.strafemc.net.StrafePayloads.TeamChoice.TYPE, dev.strafemc.net.StrafePayloads.TeamChoice.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.Refundable.TYPE, dev.strafemc.net.StrafePayloads.Refundable.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(dev.strafemc.net.StrafePayloads.MoveSound.TYPE, dev.strafemc.net.StrafePayloads.MoveSound.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.MoveSound.TYPE, dev.strafemc.net.StrafePayloads.MoveSound.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.KillNotice.TYPE, dev.strafemc.net.StrafePayloads.KillNotice.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.CaseResult.TYPE, dev.strafemc.net.StrafePayloads.CaseResult.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.BulkCaseResult.TYPE, dev.strafemc.net.StrafePayloads.BulkCaseResult.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.UpgradeResult.TYPE, dev.strafemc.net.StrafePayloads.UpgradeResult.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.TradeUpResult.TYPE, dev.strafemc.net.StrafePayloads.TradeUpResult.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.SprayPlaced.TYPE, dev.strafemc.net.StrafePayloads.SprayPlaced.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.Toast.TYPE, dev.strafemc.net.StrafePayloads.Toast.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.DeathInfo.TYPE, dev.strafemc.net.StrafePayloads.DeathInfo.CODEC);
		// a player's CS2 footsteps / jump / landing, passed on to the players near them (not back to them)
		ServerPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.MoveSound.TYPE, (msg, ctx) -> {
			var p = ctx.player();
			if (!dev.strafemc.server.RateLimit.allow(p, "movesound", 30) || p.position().distanceToSqr(msg.x(), msg.y(), msg.z()) > 16) return;
			var out = new dev.strafemc.net.StrafePayloads.MoveSound(msg.event(), msg.x(), msg.y(), msg.z(), Math.min(1, msg.gain()), p.getId());
			for (var o : p.level().players())
				if (o != p && o.distanceToSqr(p) < 64 * 64 && ServerPlayNetworking.canSend(o, dev.strafemc.net.StrafePayloads.MoveSound.TYPE))
					ServerPlayNetworking.send(o, out);
		});
		PayloadTypeRegistry.serverboundPlay().register(dev.strafemc.net.StrafePayloads.MenuLoadoutChoice.TYPE, dev.strafemc.net.StrafePayloads.MenuLoadoutChoice.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.MenuLoadoutChoice.TYPE,
				(msg, ctx) -> { if (dev.strafemc.server.RateLimit.allow(ctx.player(), "menuloadout", 4)) dev.strafemc.weapon.MenuLoadout.receive(ctx.player(), msg.json()); });
		ServerPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.TeamChoice.TYPE,
				(msg, ctx) -> dev.strafemc.mode.GameModes.joinTeam(ctx.player(), msg.t() ? "t" : "ct"));
		ServerPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.Throw.TYPE,
				(msg, ctx) -> { if (dev.strafemc.server.RateLimit.allow(ctx.player(), "throw", 5)) dev.strafemc.weapon.GunServer.throwGrenade(ctx.player(), msg); });
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
			dev.strafemc.server.LagHistory.tick(server);
			var msg = new dev.strafemc.net.StrafePayloads.ServerTick(server.getTickCount());
			for (var pl : server.getPlayerList().getPlayers())
				if (ServerPlayNetworking.canSend(pl, dev.strafemc.net.StrafePayloads.ServerTick.TYPE)) ServerPlayNetworking.send(pl, msg);
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(s -> dev.strafemc.server.LagHistory.clear());
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> dev.strafemc.weapon.ServerShots.sendSeed(handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> dev.strafemc.weapon.ServerShots.forget(handler.getPlayer()));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.strafemc.weapon.ServerShots::tick);
		ServerPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.Reload.TYPE,
				(msg, ctx) -> { if (dev.strafemc.server.RateLimit.allow(ctx.player(), "reload", 10)) dev.strafemc.weapon.GunServer.reload(ctx.player(), msg.action()); });
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.CsSound.TYPE, dev.strafemc.net.StrafePayloads.CsSound.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.strafemc.net.StrafePayloads.BulletImpact.TYPE, dev.strafemc.net.StrafePayloads.BulletImpact.CODEC);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.strafemc.weapon.GunServer::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> dev.strafemc.weapon.GunServer.forget(handler.getPlayer().getUUID()));
		PayloadTypeRegistry.serverboundPlay().register(NoclipRequest.TYPE, NoclipRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(NoclipState.TYPE, NoclipState.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(dev.strafemc.net.StrafePayloads.SourceMovement.TYPE, dev.strafemc.net.StrafePayloads.SourceMovement.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.SourceMovement.TYPE, (msg, ctx) -> {
			if (!dev.strafemc.server.RateLimit.allow(ctx.player(), "source", 4)) return;
			if (msg.on()) Noclip.SOURCE.add(ctx.player().getUUID());
			else Noclip.SOURCE.remove(ctx.player().getUUID());
		});

		ServerPlayNetworking.registerGlobalReceiver(NoclipRequest.TYPE, (msg, ctx) -> {
			if (!dev.strafemc.server.RateLimit.allow(ctx.player(), "noclip", 4)) return;
			var p = ctx.player();
			if (msg.on() && (!Access.isOperator(p) || !Access.cheats())) {
				Noclip.SERVER.remove(p.getUUID());
				ServerPlayNetworking.send(p, new NoclipState(false, Access.isOperator(p) ? "noclip needs sv_cheats 1" : "noclip needs operator permission"));
				LOG.info("[strafemc] noclip denied for {} (not op)", p.getScoreboardName());
				return;
			}
			if (msg.on()) Noclip.SERVER.add(p.getUUID());
			else Noclip.SERVER.remove(p.getUUID());
			ServerPlayNetworking.send(p, new NoclipState(msg.on(), msg.on() ? "noclip ON" : "noclip OFF"));
			LOG.info("[strafemc] noclip {} for {}", msg.on() ? "ON" : "OFF", p.getScoreboardName());
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> Noclip.SERVER.remove(handler.getPlayer().getUUID()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> Noclip.SOURCE.remove(handler.getPlayer().getUUID()));
		PayloadTypeRegistry.serverboundPlay().register(dev.strafemc.net.StrafePayloads.FallDamage.TYPE, dev.strafemc.net.StrafePayloads.FallDamage.CODEC);
		// fall damage is measured by the server (ServerFall); client reports are no longer trusted and are ignored
		ServerPlayNetworking.registerGlobalReceiver(dev.strafemc.net.StrafePayloads.FallDamage.TYPE, (msg, ctx) -> {});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.strafemc.server.ServerFall::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			dev.strafemc.server.ServerFall.forget(handler.getPlayer());
			dev.strafemc.server.RateLimit.forget(handler.getPlayer());
		});
		LOG.info("[strafemc] common init");
	}
}
