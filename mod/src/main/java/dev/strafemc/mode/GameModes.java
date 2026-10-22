package dev.strafemc.mode;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.strafemc.StrafeMc;
import dev.strafemc.net.StrafePayloads;
import dev.strafemc.server.StrafeSettings;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * strafeGM: CS2 game modes in a Minecraft world. strafemc_gamemode (operators; /strafemode in the server console) picks
 * one; "none", the default, is plain strafeMC survival. While a mode runs, each player's own inventory and experience
 * are put away with the player (given back when the mode stops, also after a restart), the mode hands out its own
 * gear, nothing drops on death, hunger stays full and the mode's CS2 cvars apply.
 */
public final class GameModes {
	public static final List<String> MODES = List.of("none", "armsrace", "deathmatch", "teamdeathmatch", "casual", "competitive", "wingman");
	/** The modes' names as CS2 shows them. */
	public static final java.util.Map<String, String> NAMES = java.util.Map.of("none", "None", "armsrace", "Arms Race", "deathmatch", "Deathmatch",
			"teamdeathmatch", "Team Deathmatch", "casual", "Casual", "competitive", "Competitive", "wingman", "Wingman");

	/** A player's own things while a mode runs. */
	record Saved(List<ItemStack> items, int level, float progress, int total, String gameType) {
		static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i.group(
				ItemStack.OPTIONAL_CODEC.listOf().fieldOf("items").forGetter(Saved::items),
				Codec.INT.optionalFieldOf("level", 0).forGetter(Saved::level),
				Codec.FLOAT.optionalFieldOf("progress", 0f).forGetter(Saved::progress),
				Codec.INT.optionalFieldOf("total", 0).forGetter(Saved::total),
				Codec.STRING.optionalFieldOf("gameType", "survival").forGetter(Saved::gameType)).apply(i, Saved::new));
	}

	static final AttachmentType<Saved> SAVED = AttachmentRegistry.create(Identifier.fromNamespaceAndPath("strafemc", "mode_saved"),
			b -> b.persistent(Saved.CODEC).copyOnDeath());

	private static MinecraftServer server;
	private static Mode race;

	private GameModes() {}

	public static void register() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			start(StrafeSettings.gameMode()); // after StrafeSettings has loaded the world's choice
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			race = null;
			server = null;
			dev.strafemc.bridge.Cs2Bridge.INSTANCE.setModeCvars(java.util.Map.of());
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(s -> {
			if (race != null) race.tick();
		});
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
			ServerPlayer p = handler.getPlayer();
			if (race != null) race.join(p);
			else if (p.hasAttached(SAVED)) giveBack(p); // the mode stopped while they were away
			send(p, race == null ? "{}" : race.state(p));
		});
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
			if (race != null) race.leave(handler.getPlayer());
		});
		net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((oldP, newP, alive) -> {
			if (race != null && !alive) race.spawned(newP);
		});
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((e, src, amount) -> race == null || race.allowDamage(e, src));
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DEATH.register((e, src, amount) -> {
			if (race != null && e instanceof ServerPlayer p && p.hasAttached(SAVED)) {
				if (race instanceof RoundMode r) r.deathDrops(p);
				p.getInventory().clearContent(); // the mode's gear doesn't drop (round modes drop the guns and the bomb)
			}
			return true;
		});
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((e, src) -> {
			if (race != null && e instanceof ServerPlayer p) race.died(p);
		});
		net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> {
			var cmd = Commands.literal("strafemode").requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
					.executes(ctx -> {
						ctx.getSource().sendSuccess(() -> Component.literal("Game mode: " + StrafeSettings.gameMode() + " (" + String.join(", ", MODES) + ")"), false);
						return 1;
					});
			cmd.then(Commands.literal("warmup_end").executes(ctx -> {
				warmupEnd();
				ctx.getSource().sendSuccess(() -> Component.literal("Warmup ended"), true);
				return 1;
			}));
			cmd.then(Commands.literal("restart").executes(ctx -> {
				restart();
				ctx.getSource().sendSuccess(() -> Component.literal("Match restarted"), true);
				return 1;
			}));
			// a dedicated game mode server (ServerRules): menu loadouts, no loadout items / levels / drops / shop / upgrader / trade ups
			var server = Commands.literal("server").executes(ctx -> {
				ctx.getSource().sendSuccess(() -> Component.literal("Game mode server: " + (dev.strafemc.server.ServerRules.modeServer() ? "on" : "off")), false);
				return 1;
			});
			for (String v : new String[] {"on", "off"})
				server.then(Commands.literal(v).executes(ctx -> {
					dev.strafemc.server.ServerRules.setModeServer(v.equals("on"));
					ctx.getSource().sendSuccess(() -> Component.literal("Game mode server: " + v), true);
					return 1;
				}));
			cmd.then(server);
			for (String m : MODES)
				cmd.then(Commands.literal(m).executes(ctx -> {
					set(m);
					ctx.getSource().sendSuccess(() -> Component.literal("Game mode: " + m), true);
					return 1;
				}));
			dispatcher.register(cmd);
			for (String cvar : StrafeSettings.MODE_CVAR_NAMES) // CS2's own commands, on the server console too
				dispatcher.register(Commands.literal(cvar).requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
						.executes(ctx -> {
							ctx.getSource().sendSuccess(() -> Component.literal(cvar + " = " + dev.strafemc.bridge.Cs2Bridge.INSTANCE.cvar(cvar)), false);
							return 1;
						})
						.then(Commands.argument("value", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
							String v = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "value"), bad = cvarRefusal(cvar, v);
							if (bad != null) {
								ctx.getSource().sendFailure(Component.literal(bad));
								return 0;
							}
							setCvar(cvar, v.equalsIgnoreCase("default") ? "" : v);
							ctx.getSource().sendSuccess(() -> Component.literal(cvar + " = " + v), true);
							return 1;
						})));
		});
	}

	public static String mode() {
		return race != null ? race.id() : "none";
	}

	/** Switches the world's game mode (saved with the world). */
	public static void set(String mode) {
		if (!MODES.contains(mode) || mode.equals(mode())) return;
		stop();
		StrafeSettings.setGameMode(mode);
		start(mode);
		if (server != null) for (ServerPlayer o : server.getPlayerList().getPlayers()) dev.strafemc.server.Toasts.info(o, mode.equals("none") ? "Game mode off" : "Game mode: " + NAMES.get(mode));
	}

	private static void start(String mode) {
		if (server == null) return;
		race = switch (mode) {
			case "armsrace" -> new ArmsRace(server);
			case "deathmatch" -> new Deathmatch(server, false);
			case "teamdeathmatch" -> new Deathmatch(server, true);
			case "casual" -> new RoundMode(server, RoundMode.CASUAL);
			case "competitive" -> new RoundMode(server, RoundMode.COMPETITIVE);
			case "wingman" -> new RoundMode(server, RoundMode.WINGMAN);
			default -> null;
		};
		if (race == null) return;
		applyCvars();
		race.begin();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) race.join(p);
		StrafeMc.LOG.info("[strafemc] strafeGM: {} started", NAMES.get(mode));
	}

	/** The running mode's cvars (its CS2 gamemode config) with the operators' own (mp_forcecamera, spec_freeze_time) on top. */
	private static void applyCvars() {
		if (race == null) return;
		java.util.Map<String, String> m = new java.util.HashMap<>(race.cvars());
		m.putAll(StrafeSettings.modeCvars());
		dev.strafemc.bridge.Cs2Bridge.INSTANCE.setModeCvars(m);
		dev.strafemc.server.GameplaySync.resendAll();
	}

	/** Why a value doesn't fit (mp_forcecamera 0-2, spec_freeze_time 0-10 s, or "default"), or null. */
	public static String cvarRefusal(String name, String value) {
		if (value.equalsIgnoreCase("default")) return null;
		try {
			double d = Double.parseDouble(value);
			if (name.equals("mp_forcecamera") && (d != Math.floor(d) || d < 0 || d > 2)) return "mp_forcecamera: 0, 1 or 2";
			if (name.equals("spec_freeze_time") && (d < 0 || d > 10)) return "spec_freeze_time: 0 to 10 seconds";
			if (name.equals("mp_autoteambalance") && d != 0 && d != 1) return "mp_autoteambalance: 0 or 1";
			if (name.equals("mp_limitteams") && (d != Math.floor(d) || d < 0 || d > 10)) return "mp_limitteams: 0 (no limit) to 10";
			return null;
		} catch (NumberFormatException e) {
			return name + ": a number, or default";
		}
	}

	/** An operator's mp_forcecamera / spec_freeze_time (empty: the mode's own), kept with the world. */
	public static void setCvar(String name, String value) {
		StrafeSettings.setModeCvar(name, value);
		applyCvars();
	}

	private static void stop() {
		if (race == null) return;
		race = null;
		dev.strafemc.bridge.Cs2Bridge.INSTANCE.setModeCvars(java.util.Map.of());
		if (server == null) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.isDeadOrDying()) server.getPlayerList().respawn(p, false, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			giveBack(p);
			send(p, "{}");
		}
		StrafeMc.LOG.info("[strafemc] strafeGM: mode stopped");
	}

	/** Puts the player's own things away (once) and empties the inventory for the mode's gear. */
	static void putAway(ServerPlayer p) {
		if (p.hasAttached(SAVED)) return;
		var inv = p.getInventory();
		List<ItemStack> items = new ArrayList<>();
		for (int i = 0; i < inv.getContainerSize(); i++) items.add(inv.getItem(i).copy());
		p.setAttached(SAVED, new Saved(items, p.experienceLevel, p.experienceProgress, p.totalExperience, p.gameMode.getGameModeForPlayer().getName()));
		inv.clearContent();
		p.setGameMode(net.minecraft.world.level.GameType.ADVENTURE); // the map stays as built; creative players can be hit
		p.setExperienceLevels(0);
		p.setExperiencePoints(0);
	}

	/** Gives the player's own things back (the mode's gear goes). */
	static void giveBack(ServerPlayer p) {
		Saved s = p.getAttached(SAVED);
		if (s == null) return;
		var inv = p.getInventory();
		inv.clearContent();
		for (int i = 0; i < Math.min(inv.getContainerSize(), s.items().size()); i++) inv.setItem(i, s.items().get(i).copy());
		p.setExperienceLevels(s.level());
		p.experienceProgress = s.progress();
		p.totalExperience = s.total();
		p.setGameMode(net.minecraft.world.level.GameType.byName(s.gameType(), net.minecraft.world.level.GameType.SURVIVAL));
		p.setCamera(p); // a round mode's dead were watching a teammate
		p.removeAttached(SAVED);
		p.inventoryMenu.broadcastChanges();
	}

	static void send(ServerPlayer p, String json) {
		if (ServerPlayNetworking.canSend(p, StrafePayloads.ModeState.TYPE)) ServerPlayNetworking.send(p, new StrafePayloads.ModeState(json));
	}

	/** A kill announced by the killfeed (any CS weapon): counts for the running mode and its scoreboard. */
	public static void kill(net.minecraft.world.entity.LivingEntity attacker, net.minecraft.world.entity.LivingEntity victim, String icon,
			net.minecraft.world.entity.LivingEntity assister) {
		if (race == null) return;
		race.record(attacker, victim, assister);
		race.kill(attacker, victim, icon);
	}

	/** jointeam t | ct | auto (CS2's command; the team menu): in a mode, its rules decide; outside one, just the side. */
	public static void joinTeam(ServerPlayer p, String side) {
		if (race == null || !p.hasAttached(SAVED)) {
			dev.strafemc.weapon.CsTeam.set(p, side.equals("t"));
			return;
		}
		race.joinTeam(p, side);
	}

	/** The player is in the running mode (their own things put away, the mode's gear in hand). */
	public static boolean playing(ServerPlayer p) {
		return race != null && p.hasAttached(SAVED);
	}

	/** A dead player in a round mode switches whom they watch (1 next, -1 previous). */
	public static void spectate(ServerPlayer p, int step) {
		if (race instanceof RoundMode r && p.hasAttached(SAVED)) r.spectate(p, step);
	}

	/** Ends the warmup now (mp_warmup_end). */
	public static void warmupEnd() {
		if (race != null) race.skipWarmup();
	}

	/** A new match now (mp_restartgame). */
	public static void restart() {
		if (race == null) return;
		String m = race.id();
		stop();
		start(m);
	}

	/** The player's HUD state (tests). */
	public static String stateOf(ServerPlayer p) {
		return race == null ? "{}" : race.state(p);
	}

	/** Ends the warmup at once (tests). */
	public static void skipWarmup() {
		if (race != null) race.skipWarmup();
	}

	/** The weapon a bot carries in the running mode (null: its own). */
	public static String botWeapon(net.minecraft.world.entity.LivingEntity bot) {
		return race == null ? null : race.botWeapon(bot);
	}

	/** Whether everyone fights everyone in the running mode (null: no mode runs). */
	public static Boolean ffa() {
		return race == null ? null : race.ffa();
	}

	/** Why the player can't buy this in the running mode: null when no mode runs, "" when they can. */
	public static String buyRefusal(ServerPlayer p, String what) {
		if (race == null || !p.hasAttached(SAVED)) return null;
		String no = race.buyRefusal(p);
		if (no.isEmpty() && "0".equals(dev.strafemc.bridge.Cs2Bridge.INSTANCE.cvar("mp_buy_allow_grenades")) && dev.strafemc.weapon.WeaponTable.get("weapon_" + what) == null
				&& !what.equals("kevlar") && !what.equals("assaultsuit") && !what.equals("knife"))
			return "Grenades can't be bought in this game mode";
		return no;
	}

	/** A gun bought in the running mode. */
	public static void bought(ServerPlayer p, String cls) {
		if (race != null && p.hasAttached(SAVED)) race.bought(p, cls);
	}

	/** Why the player can't plant the C4 here and now: null when anywhere goes (no round mode), else "" or the reason. */
	public static String plantRefusal(ServerPlayer p) {
		return race instanceof RoundMode r && p.hasAttached(SAVED) ? r.plantRefusal(p) : null;
	}

	public static void planted(ServerPlayer p, dev.strafemc.weapon.PlantedC4 bomb) {
		bomb.setSite(BombSites.siteLetter(p)); // its beep is the site's (drawn sites or site blocks; none: A's)
		if (race instanceof RoundMode r) r.planted(p, bomb);
	}

	public static void exploded(dev.strafemc.weapon.PlantedC4 bomb) {
		if (race instanceof RoundMode r) r.exploded(bomb);
	}

	public static void defused(net.minecraft.world.entity.LivingEntity by, dev.strafemc.weapon.PlantedC4 bomb) {
		if (race instanceof RoundMode r) r.defused(by, bomb);
	}

	/** Freeze time: bots hold still. */
	public static boolean botFrozen() {
		return race instanceof RoundMode r && r.frozen();
	}

	/** Where a bot's objective is (the bomb site for the bomb carrier, the planted bomb for a CT), or null. */
	public static net.minecraft.world.phys.Vec3 botGoal(net.minecraft.world.entity.LivingEntity bot) {
		return race instanceof RoundMode r ? r.botGoal(bot) : null;
	}

	/** A bot standing at its objective: planting or defusing. */
	public static void botAtGoal(net.minecraft.world.entity.LivingEntity bot) {
		if (race instanceof RoundMode r) r.botAtGoal(bot);
	}

	/** Whether dead bots may come back now (round modes: only between rounds). */
	public static boolean botMayRespawn() {
		return !(race instanceof RoundMode r) || r.botMayRespawn();
	}

	/** Whose spawn blocks a bot uses (null: any). */
	public static dev.strafemc.world.SpawnBlock.Side botSide(net.minecraft.world.entity.LivingEntity bot) {
		if (!(race instanceof RoundMode)) return null;
		return dev.strafemc.weapon.CsTeam.isT(bot) ? dev.strafemc.world.SpawnBlock.Side.T : dev.strafemc.world.SpawnBlock.Side.CT;
	}

	/** Pays for a purchase in a mode with money; false when they can't afford it (true when no mode charges). */
	public static boolean charge(ServerPlayer p, int price) {
		return !(race instanceof RoundMode r) || !p.hasAttached(SAVED) || r.charge(p, price);
	}

	public static void refund(ServerPlayer p, int price) {
		if (race instanceof RoundMode r && p.hasAttached(SAVED)) r.refund(p, price);
	}

	/**
	 * How much of a teammate's damage gets through (ff_damage_reduction_bullets / _grenade / _other; 1 for enemies and
	 * outside modes). kind: "bullets", "grenade" or "other".
	 */
	public static double teamDamage(net.minecraft.world.entity.LivingEntity attacker, net.minecraft.world.entity.LivingEntity victim, String kind) {
		if (race == null || attacker == null || victim == null || !Mode.inMode(victim) || !Mode.inMode(attacker)) return 1;
		if (attacker == victim) return kind.equals("grenade") ? cvar("ff_damage_reduction_grenade_self", 1) : 1;
		if (race.ffa()) return 1; // everyone's an enemy: no cut
		if (dev.strafemc.weapon.CsTeam.isT(attacker) != dev.strafemc.weapon.CsTeam.isT(victim)) return 1;
		return cvar("ff_damage_reduction_" + kind, 0);
	}

	private static double cvar(String name, double fallback) {
		String v = dev.strafemc.bridge.Cs2Bridge.INSTANCE.cvar(name);
		try {
			return v == null ? fallback : Double.parseDouble(v);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** The player fired: their spawn protection ends. */
	public static void fired(ServerPlayer p) {
		if (race != null) race.fired(p);
	}
}
