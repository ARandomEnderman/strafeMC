package dev.strafemc.server;

import dev.strafemc.StrafeMc;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.weapon.WeaponItems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * CS console commands that change the Minecraft world, run on the MC server so they stay multiplayer-safe:
 * kill / explode for everyone; god, hurtme, give, impulse 101 only for operators (the client also requires sv_cheats 1, like CS).
 */
public final class ConsoleActions {
	private ConsoleActions() {}

	public static void run(ServerPlayer p, String cmd, String arg) {
		boolean op = Access.isOperator(p);
		boolean cheat = op && Access.cheats(); // cheat commands: operator AND sv_cheats 1, checked here, not just on the client
		switch (cmd) {
			case "strafemc_movement_choice" -> {
				if (!op) {
					p.sendSystemMessage(Component.literal("Only operators can change who decides Source movement."));
					break;
				}
				StrafeSettings.setPlayerChoice(!arg.equals("0"));
				p.level().getServer().getPlayerList().broadcastSystemMessage(Component.literal(StrafeSettings.playerChoice()
						? "Everyone can now turn Source movement on or off for themselves (strafemc_movement). Set by " + p.getScoreboardName()
						: "Operators decide Source movement for everyone again: " + (StrafeSettings.sourceMovement() ? "ON" : "OFF") + ". Set by " + p.getScoreboardName()), false);
			}
			case "strafemc_movement" -> {
				if (StrafeSettings.playerChoice()) { // players choose: this only changes your own
					StrafeSettings.setPersonal(p.getUUID(), !arg.equals("0"));
					p.sendSystemMessage(Component.literal("Your Source movement: " + (StrafeSettings.sourceMovement(p) ? "ON" : "OFF")));
					break;
				}
				if (!op) {
					p.sendSystemMessage(Component.literal("Only operators can change Source movement for the server."));
					break;
				}
				StrafeSettings.setSourceMovement(!arg.equals("0"));
				p.level().getServer().getPlayerList().broadcastSystemMessage(
						Component.literal("Source movement " + (StrafeSettings.sourceMovement() ? "ON" : "OFF") + " (set by " + p.getScoreboardName() + ")"), false);
			}
			case "strafemc_pattern" -> { // a skin wrap's pattern template: a cheat (sv_cheats 1, operator, creative); cases will roll it
				var held = p.getMainHandItem();
				int n;
				try {
					n = Math.max(0, Math.min(1000, Integer.parseInt(arg.trim())));
				} catch (NumberFormatException e) {
					p.sendSystemMessage(Component.literal("strafemc_pattern <0-1000>"));
					break;
				}
				if (!cheat || !p.getAbilities().instabuild) {
					p.sendSystemMessage(Component.literal("Setting patterns needs sv_cheats 1, operator and creative."));
					break;
				}
				// the held wrap or glove item itself; a held knife or gun: the wrap in its loadout slot
				String slot = held.getItem() instanceof dev.strafemc.weapon.KnifeItem ? "knife"
						: held.getItem() instanceof dev.strafemc.weapon.GunItem g && !(g instanceof dev.strafemc.weapon.GrenadeItem)
						&& !(g instanceof dev.strafemc.weapon.HealthshotItem) ? g.weaponClass : null;
				if (held.getItem() instanceof dev.strafemc.weapon.SkinWrapItem || held.getItem() instanceof dev.strafemc.weapon.GloveItem) {
					held.set(dev.strafemc.weapon.WeaponItems.PATTERN, n);
					p.sendSystemMessage(Component.literal("Pattern template: " + n));
				} else if (slot != null) {
					var wrap = dev.strafemc.weapon.Loadout.get(p, slot);
					if (wrap.isEmpty()) p.sendSystemMessage(Component.literal("No wrap on it in your loadout."));
					else {
						wrap = wrap.copy();
						wrap.set(dev.strafemc.weapon.WeaponItems.PATTERN, n);
						dev.strafemc.weapon.Loadout.set(p, slot, wrap);
						p.sendSystemMessage(Component.literal("Pattern template: " + n));
					}
				} else p.sendSystemMessage(Component.literal("Hold a skin wrap, gloves, or a gun / knife with a wrap in your loadout."));
			}
			case "strafemc_knockback" -> {
				if (!op) {
					p.sendSystemMessage(Component.literal("Only operators can change knockback for the server."));
					break;
				}
				StrafeSettings.setMcKnockback(!arg.equals("0"));
				p.level().getServer().getPlayerList().broadcastSystemMessage(Component.literal("Gun/knife hits: "
						+ (StrafeSettings.mcKnockback() ? "Minecraft knockback" : "CS2 tagging (no knockback)") + " (set by " + p.getScoreboardName() + ")"), false);
			}
			case "buy" -> dev.strafemc.weapon.BuyMenu.buy(p, arg.trim());
			case "refund" -> dev.strafemc.weapon.BuyMenu.refund(p, arg.trim().isEmpty() ? "all" : arg.trim());
			case "loadout" -> dev.strafemc.weapon.Loadout.open(p);
			case "silencer" -> dev.strafemc.weapon.GunServer.toggleSilencer(p);
			case "redeploy" -> dev.strafemc.weapon.GunServer.redeploy(p);
			case "burst" -> dev.strafemc.weapon.GunServer.toggleBurst(p);
			case "zoom" -> dev.strafemc.weapon.GunServer.zoom(p, arg);
			case "case_open" -> { // the held case: roll CS2's odds, use it up, hand out the drop, tell the client for its reel
				var held = p.getMainHandItem();
				if (!(held.getItem() instanceof dev.strafemc.weapon.CaseItem) || !arg.equals(dev.strafemc.weapon.CaseItem.caseId(held))) break;
				var c = dev.strafemc.weapon.Cases.get(arg);
				// a container key opens it (used up); creative needs none
				int keySlot = -1;
				var inv = p.getInventory();
				for (int i = 0; i < inv.getContainerSize() && keySlot < 0; i++)
					if (inv.getItem(i).is(dev.strafemc.weapon.WeaponItems.CASE_KEY)) keySlot = i;
				if (keySlot < 0 && !p.getAbilities().instabuild) {
					p.sendOverlayMessage(Component.literal("You need a Container Key to unlock this case."));
					break;
				}
				var drop = c == null ? null : dev.strafemc.weapon.Cases.roll(c, new java.util.Random());
				if (drop == null) break;
				if (!p.getAbilities().instabuild) {
					held.shrink(1);
					inv.getItem(keySlot).shrink(1);
				}
				if (!p.getInventory().add(drop.stack().copy())) p.drop(drop.stack().copy(), false);
				if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, dev.strafemc.net.StrafePayloads.CaseResult.TYPE))
					net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new dev.strafemc.net.StrafePayloads.CaseResult(arg, drop.entry().kit(),
							drop.entry().item(), drop.rarity() == null ? -1 : drop.rarity().ordinal(), drop.stack().getHoverName().getString()));
			}
			case "strafemc_spawnblocks" -> {
				if (!op) {
					p.sendSystemMessage(Component.literal("Only operators can change spawn blocks."));
					break;
				}
				StrafeSettings.setSpawnBlocks(!arg.equals("0"));
				p.level().getServer().getPlayerList().broadcastSystemMessage(Component.literal("Respawn on spawn blocks: "
						+ (StrafeSettings.spawnBlocks() ? "on" : "off")), false);
			}
			case "strafemc_loadout_keep" -> {
				if (!op) {
					p.sendSystemMessage(Component.literal("Only operators can change whether loadouts drop on death."));
					break;
				}
				StrafeSettings.setLoadoutKeep(!arg.equals("0"));
				p.level().getServer().getPlayerList().broadcastSystemMessage(Component.literal("Loadout on death: "
						+ (StrafeSettings.loadoutKeep() ? "kept" : "dropped (unless keepInventory)") + " (set by " + p.getScoreboardName() + ")"), false);
			}
			case "healthshot" -> dev.strafemc.weapon.HealthshotItem.inject(p);
			case "strafemc_buymenu" -> {
				if (!op) {
					p.sendSystemMessage(Component.literal("Only operators can change buying for the server."));
					break;
				}
				int mode;
				try {
					mode = Integer.parseInt(arg.trim());
				} catch (NumberFormatException e) {
					mode = (StrafeSettings.buyMode() + 1) % 3;
				}
				StrafeSettings.setBuyMode(mode);
				p.level().getServer().getPlayerList().broadcastSystemMessage(Component.literal("Buy menu: "
						+ dev.strafemc.weapon.BuyMenu.modeName(StrafeSettings.buyMode()) + " (set by " + p.getScoreboardName() + ")"), false);
			}
			case "strafemc_buytime" -> {
				if (!op) {
					p.sendSystemMessage(Component.literal("Only operators can change the buy time for the server."));
					break;
				}
				try {
					StrafeSettings.setBuyTime((int) Math.round(Double.parseDouble(arg.trim())));
				} catch (NumberFormatException e) {
					p.sendSystemMessage(Component.literal("strafemc_buytime <seconds after respawning; 0 = no limit>"));
					break;
				}
				p.level().getServer().getPlayerList().broadcastSystemMessage(Component.literal("Buy time: "
						+ (StrafeSettings.buyTime() == 0 ? "no limit" : StrafeSettings.buyTime() + " s after respawning") + " (set by " + p.getScoreboardName() + ")"), false);
			}
			case "strafemc_slabstep" -> {
				if (!op) {
					p.sendSystemMessage(Component.literal("Only operators can change stepping up slabs for the server."));
					break;
				}
				StrafeSettings.setSlabStep(!arg.equals("0"));
				p.level().getServer().getPlayerList().broadcastSystemMessage(Component.literal("Step up slabs: "
						+ (StrafeSettings.slabStep() ? "ON (half a block)" : "OFF (CS2's 18 units)") + " (set by " + p.getScoreboardName() + ")"), false);
			}
			case "strafemc_lagcomp", "strafemc_maxunlag" -> {
				if (!op) {
					p.sendSystemMessage(Component.literal("Only operators can change lag compensation for the server."));
					break;
				}
				if (cmd.equals("strafemc_lagcomp")) StrafeSettings.setLagComp(!arg.equals("0"));
				else try {
					StrafeSettings.setMaxUnlagMs(Integer.parseInt(arg.trim()));
				} catch (NumberFormatException e) {
					p.sendSystemMessage(Component.literal("strafemc_maxunlag <milliseconds>"));
					break;
				}
				p.level().getServer().getPlayerList().broadcastSystemMessage(Component.literal("Lag compensation " + (StrafeSettings.lagComp() ? "ON" : "OFF")
						+ ", max rewind " + StrafeSettings.maxUnlagMs() + " ms (set by " + p.getScoreboardName() + ")"), false);
			}
			case "sv_rethrow_last_grenade" -> {
				if (!cheat) break;
				if (!dev.strafemc.weapon.GunServer.rethrow(p)) p.sendSystemMessage(Component.literal("No grenade thrown yet."));
			}
			case "kill" -> p.hurtServer(p.level(), p.damageSources().genericKill(), Float.MAX_VALUE);
			case "explode" -> {
				// CS "explode": you blow up. A harmless-to-blocks explosion for the effect, then death.
				p.level().explode(p, p.getX(), p.getY() + 0.5, p.getZ(), 1.5f, Level.ExplosionInteraction.NONE);
				p.hurtServer(p.level(), p.damageSources().genericKill(), Float.MAX_VALUE);
			}
			case "god" -> {
				if (!cheat) break;
				var ab = p.getAbilities();
				ab.invulnerable = !ab.invulnerable;
				p.onUpdateAbilities();
				p.sendSystemMessage(Component.literal("godmode " + (ab.invulnerable ? "ON" : "OFF")));
			}
			case "healme" -> {
				if (!cheat) break;
				float amount = arg.isBlank() ? p.getMaxHealth() : (float) (parse(arg, 100) * StrafeConfig.hpScale);
				p.heal(amount);
			}
			case "setpos" -> {
				if (!cheat) break;
				String[] v = arg.trim().split("\\s+");
				if (v.length < 3) break;
				double u = StrafeConfig.unitsPerBlock; // cl_showpos coordinates: (x, -z, y) x units per block
				double x = parse(v[0], Double.NaN) / u, z = -parse(v[1], Double.NaN) / u, y = parse(v[2], Double.NaN) / u;
				if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) break;
				p.teleportTo(x, y, z);
			}
			case "hurtme" -> {
				if (!cheat) break;
				double cs = parse(arg, 10);
				p.hurtServer(p.level(), p.damageSources().generic(), (float) (cs * StrafeConfig.hpScale));
			}
			case "give" -> {
				if (!cheat) break;
				// any strafeMC weapon or magazine by its id (weapon_awp, mag_awp, weapon_knife_karambit...)
				var id = net.minecraft.resources.Identifier.tryParse("strafemc:" + arg.trim());
				var item = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id).orElse(null);
				ItemStack s = item instanceof dev.strafemc.weapon.GunItem || item instanceof dev.strafemc.weapon.MagazineItem ? new ItemStack(item) : ItemStack.EMPTY;
				if (s.isEmpty()) p.sendSystemMessage(Component.literal("give: unknown item " + arg));
				else p.getInventory().placeItemBackInInventory(s);
			}
			case "impulse" -> {
				if (!cheat || !arg.equals("101")) break;
				// impulse 101: full ammo for the guns you carry (a full set of magazines each)
				for (int i = 0; i < p.getInventory().getContainerSize(); i++)
					if (p.getInventory().getItem(i).getItem() instanceof dev.strafemc.weapon.GunItem g && WeaponItems.MAGS.containsKey(g.weaponClass)) {
						var st = dev.strafemc.weapon.WeaponStats.of(g.weaponClass);
						int mags = st == null ? 0 : st.reserveAsClips() ? st.reserveMags() : (int) Math.ceil(st.reserveMags() / (double) Math.max(1, st.clip()));
						if (mags > 0) p.getInventory().placeItemBackInInventory(new ItemStack(WeaponItems.MAGS.get(g.weaponClass), mags));
					}
			}
			default -> {
				return;
			}
		}
		StrafeMc.LOG.info("[strafemc] console action {} {} by {} (op={}, cheats={})", cmd, arg, p.getScoreboardName(), op, Access.cheats());
	}

	private static double parse(String s, double d) {
		try {
			return Double.parseDouble(s);
		} catch (Exception e) {
			return d;
		}
	}
}
