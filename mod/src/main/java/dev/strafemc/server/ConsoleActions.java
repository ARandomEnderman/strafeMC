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
		switch (cmd) {
			case "kill" -> p.hurtServer(p.level(), p.damageSources().genericKill(), Float.MAX_VALUE);
			case "explode" -> {
				// CS "explode": you blow up. A harmless-to-blocks explosion for the effect, then death.
				p.level().explode(p, p.getX(), p.getY() + 0.5, p.getZ(), 1.5f, Level.ExplosionInteraction.NONE);
				p.hurtServer(p.level(), p.damageSources().genericKill(), Float.MAX_VALUE);
			}
			case "god" -> {
				if (!op) break;
				var ab = p.getAbilities();
				ab.invulnerable = !ab.invulnerable;
				p.onUpdateAbilities();
				p.sendSystemMessage(Component.literal("godmode " + (ab.invulnerable ? "ON" : "OFF")));
			}
			case "hurtme" -> {
				if (!op) break;
				double cs = parse(arg, 10);
				p.hurtServer(p.level(), p.damageSources().generic(), (float) (cs * StrafeConfig.hpScale));
			}
			case "give" -> {
				if (!op) break;
				ItemStack s = switch (arg) {
					case "weapon_ak47" -> new ItemStack(WeaponItems.AK47);
					case "mag_ak47" -> new ItemStack(WeaponItems.MAG_AK47);
					default -> ItemStack.EMPTY;
				};
				if (s.isEmpty()) p.sendSystemMessage(Component.literal("give: unknown item " + arg));
				else p.getInventory().placeItemBackInInventory(s);
			}
			case "impulse" -> {
				if (!op || !arg.equals("101")) break;
				// impulse 101: every weapon + full ammo
				p.getInventory().placeItemBackInInventory(new ItemStack(WeaponItems.AK47));
				for (int i = 0; i < 3; i++) p.getInventory().placeItemBackInInventory(new ItemStack(WeaponItems.MAG_AK47));
			}
			default -> {
				return;
			}
		}
		StrafeMc.LOG.info("[strafemc] console action {} {} by {} (op={})", cmd, arg, p.getScoreboardName(), op);
	}

	private static double parse(String s, double d) {
		try {
			return Double.parseDouble(s);
		} catch (Exception e) {
			return d;
		}
	}
}
