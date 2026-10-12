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
			case "strafemc_movement" -> {
				if (!op) {
					p.sendSystemMessage(Component.literal("Only operators can change Source movement for the server."));
					break;
				}
				StrafeSettings.setSourceMovement(!arg.equals("0"));
				p.level().getServer().getPlayerList().broadcastSystemMessage(
						Component.literal("Source movement " + (StrafeSettings.sourceMovement() ? "ON" : "OFF") + " (set by " + p.getScoreboardName() + ")"), false);
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
				ItemStack s = switch (arg) {
					case "weapon_ak47" -> new ItemStack(WeaponItems.AK47);
					case "mag_ak47" -> new ItemStack(WeaponItems.MAG_AK47);
					default -> ItemStack.EMPTY;
				};
				if (s.isEmpty()) p.sendSystemMessage(Component.literal("give: unknown item " + arg));
				else p.getInventory().placeItemBackInInventory(s);
			}
			case "impulse" -> {
				if (!cheat || !arg.equals("101")) break;
				// impulse 101: every weapon + full ammo
				p.getInventory().placeItemBackInInventory(new ItemStack(WeaponItems.AK47));
				for (int i = 0; i < 3; i++) p.getInventory().placeItemBackInInventory(new ItemStack(WeaponItems.MAG_AK47));
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
