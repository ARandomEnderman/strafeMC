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
			case "strafemc_float" -> { // a skin wrap's float: a cheat like the pattern; within the skin's own range
				if (!cheat || !p.getAbilities().instabuild) {
					p.sendSystemMessage(Component.literal("Setting floats needs sv_cheats 1, operator and creative."));
					break;
				}
				var held = p.getMainHandItem();
				String slot = held.getItem() instanceof dev.strafemc.weapon.KnifeItem ? "knife"
						: held.getItem() instanceof dev.strafemc.weapon.GunItem g && !(g instanceof dev.strafemc.weapon.GrenadeItem)
						&& !(g instanceof dev.strafemc.weapon.HealthshotItem) ? g.weaponClass : null;
				ItemStack wrap = held.getItem() instanceof dev.strafemc.weapon.SkinWrapItem ? held : slot != null ? dev.strafemc.weapon.Loadout.get(p, slot).copy() : ItemStack.EMPTY;
				var w = wrap.isEmpty() ? null : dev.strafemc.weapon.SkinWraps.get(dev.strafemc.weapon.SkinWrapItem.skin(wrap));
				if (w == null) {
					p.sendSystemMessage(Component.literal(slot != null ? "No wrap on it in your loadout." : "Hold a skin wrap, or a gun / knife with a wrap in your loadout."));
					break;
				}
				double[] range = dev.strafemc.weapon.Wear.range(dev.strafemc.weapon.SkinWraps.iconKit(w));
				double f;
				double[] band = dev.strafemc.weapon.Wear.band(arg);
				if (band != null) {
					double lo = Math.max(band[0], range[0]), hi = Math.min(band[1], range[1]);
					if (lo > hi || lo >= band[1]) {
						p.sendSystemMessage(Component.literal(w.name() + " doesn't come in " + arg.trim().toUpperCase() + String.format(java.util.Locale.ROOT, " (its floats: %.2f-%.2f).", range[0], range[1])));
						break;
					}
					f = lo + p.getRandom().nextDouble() * (Math.min(hi, band[1] - 1e-6) - lo);
				} else {
					try {
						f = Double.parseDouble(arg.trim());
					} catch (NumberFormatException e) {
						p.sendSystemMessage(Component.literal("strafemc_float <0-1 | FN | MW | FT | WW | BS>"));
						break;
					}
					double clamped = Math.max(range[0], Math.min(range[1], f));
					if (clamped != f) p.sendSystemMessage(Component.literal(String.format(java.util.Locale.ROOT, "%s only comes in %.2f-%.2f.", w.name(), range[0], range[1])));
					f = clamped;
				}
				if (f > 0) wrap.set(dev.strafemc.weapon.WeaponItems.WEAR, Float.floatToIntBits((float) f));
				else wrap.remove(dev.strafemc.weapon.WeaponItems.WEAR);
				if (wrap != held) dev.strafemc.weapon.Loadout.set(p, slot, wrap);
			}
			case "strafemc_stattrak" -> { // makes a wrap StatTrak with this many kills (off: plain again); a cheat like the float
				if (!cheat || !p.getAbilities().instabuild) {
					p.sendSystemMessage(Component.literal("StatTrak needs sv_cheats 1, operator and creative."));
					break;
				}
				var held = p.getMainHandItem();
				String slot = held.getItem() instanceof dev.strafemc.weapon.KnifeItem ? "knife"
						: held.getItem() instanceof dev.strafemc.weapon.GunItem g && !(g instanceof dev.strafemc.weapon.GrenadeItem)
						&& !(g instanceof dev.strafemc.weapon.HealthshotItem) ? g.weaponClass : null;
				ItemStack wrap = held.getItem() instanceof dev.strafemc.weapon.SkinWrapItem ? held : slot != null ? dev.strafemc.weapon.Loadout.get(p, slot).copy() : ItemStack.EMPTY;
				if (!(wrap.getItem() instanceof dev.strafemc.weapon.SkinWrapItem)) {
					p.sendSystemMessage(Component.literal(slot != null ? "No wrap on it in your loadout." : "Hold a skin wrap, or a gun / knife with a wrap in your loadout."));
					break;
				}
				String a = arg.trim();
				if (a.equalsIgnoreCase("off")) wrap.remove(dev.strafemc.weapon.WeaponItems.STATTRAK);
				else {
					int k;
					try {
						k = a.isEmpty() ? 0 : Integer.parseInt(a);
					} catch (NumberFormatException e) {
						p.sendSystemMessage(Component.literal("strafemc_stattrak <kills | off>"));
						break;
					}
					wrap.set(dev.strafemc.weapon.WeaponItems.STATTRAK, Math.max(0, Math.min(dev.strafemc.weapon.StatTrak.MAX, k)));
				}
				if (wrap != held) dev.strafemc.weapon.Loadout.set(p, slot, wrap);
			}
			case "strafemc_wear" -> {
				if (!op) {
					p.sendSystemMessage(Component.literal("Only operators can change wear on case drops."));
					break;
				}
				String[] a = arg.trim().split(" ");
				boolean on = a.length < 2 ? !a[0].equals("0") : !a[1].equals("0");
				if (a.length >= 2 && StrafeSettings.WEAR_RARITIES.contains(a[0])) StrafeSettings.setWearFor(a[0], on);
				else StrafeSettings.setWear(on);
			}
			case "bot_add", "bot_add_t", "bot_add_ct", "bot_kick", "bot_quota", "bot_difficulty", "strafemc_bot_ffa", "strafemc_bot_skins" -> {
				if (!op) {
					p.sendSystemMessage(Component.literal("Only operators can change bots."));
					break;
				}
				var server = p.level().getServer();
				String msg;
				switch (cmd) {
					case "bot_add" -> { dev.strafemc.weapon.Bots.add(null); msg = "Bot added (" + StrafeSettings.botQuota() + ")"; }
					case "bot_add_t" -> { dev.strafemc.weapon.Bots.add(true); msg = "T bot added (" + StrafeSettings.botQuota() + ")"; }
					case "bot_add_ct" -> { dev.strafemc.weapon.Bots.add(false); msg = "CT bot added (" + StrafeSettings.botQuota() + ")"; }
					case "bot_kick" -> { dev.strafemc.weapon.Bots.kickAll(); msg = "Bots kicked"; }
					case "bot_quota" -> {
						try {
							StrafeSettings.setBotQuota(Integer.parseInt(arg.trim()));
						} catch (NumberFormatException e) {
							p.sendSystemMessage(Component.literal("bot_quota <number>"));
							return;
						}
						msg = "bot_quota " + StrafeSettings.botQuota();
					}
					case "bot_difficulty" -> {
						try {
							StrafeSettings.setBotDifficulty(Integer.parseInt(arg.trim()));
						} catch (NumberFormatException e) {
							p.sendSystemMessage(Component.literal("bot_difficulty <1-10>"));
							return;
						}
						msg = "bot_difficulty " + StrafeSettings.botDifficulty();
					}
					case "strafemc_bot_ffa" -> {
						StrafeSettings.setBotFfa(!arg.trim().equals("0"));
						msg = StrafeSettings.botFfa() ? "Bots fight everyone" : "Bots fight the other team";
					}
					default -> { // strafemc_bot_skins random | <names...>
						String a = arg.trim();
						StrafeSettings.setBotSkins(a.isEmpty() || a.equalsIgnoreCase("random") ? java.util.List.of() : java.util.List.of(a.split("[\\s,]+")));
						msg = StrafeSettings.botSkins().isEmpty() ? "Bots wear random players' skins (new bots)" : "Bots wear " + String.join(", ", StrafeSettings.botSkins()) + "'s skins (new bots)";
					}
				}
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
			case "drop_claim" -> dev.strafemc.weapon.CarePackage.claim(p, arg);
			case "speed_goal" -> dev.strafemc.weapon.Advancements.speedGoal(p, arg.trim());
			case "drop_redeem" -> dev.strafemc.weapon.CarePackage.redeem(p);
			case "upgrade_claim" -> dev.strafemc.weapon.Upgrader.claim(p);
			case "spray_unseal" -> dev.strafemc.weapon.SprayItem.unseal(p);
			case "storage_rename" -> dev.strafemc.weapon.StorageUnitItem.rename(p, arg);
			case "tradeup" -> { // "ref x10": a trade up contract (Upgrader refs)
				java.util.List<Integer> refs = new java.util.ArrayList<>();
				try {
					for (String a : arg.trim().split(" ")) refs.add(Integer.parseInt(a));
				} catch (NumberFormatException e) {
					break;
				}
				var got = dev.strafemc.weapon.TradeUp.sign(p, refs);
				if (got != null && net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, dev.strafemc.net.StrafePayloads.TradeUpResult.TYPE))
					net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new dev.strafemc.net.StrafePayloads.TradeUpResult(got));
			}
			case "shop_withdraw" -> {
				try {
					dev.strafemc.weapon.Shop.withdraw(p, Integer.parseInt(arg.trim()));
				} catch (NumberFormatException ignored) {
				}
			}
			case "shop_buy" -> {
				try {
					String[] a = arg.trim().split(" "); // offer and how many, for each offer bought
					for (int k = 0; k < a.length; k += 2) dev.strafemc.weapon.Shop.buy(p, Integer.parseInt(a[k]), k + 1 < a.length ? Integer.parseInt(a[k + 1]) : 1);
				} catch (NumberFormatException ignored) {
				}
			}
			case "shop_sell" -> { // "ref:count ref:count ...": Upgrader refs, how many of each (no count: all of it)
				java.util.Map<Integer, Integer> refs = new java.util.LinkedHashMap<>();
				try {
					for (String a : arg.trim().split(" ")) {
						int c = a.indexOf(':'), ref = Integer.parseInt(c < 0 ? a : a.substring(0, c));
						refs.put(ref, c < 0 ? dev.strafemc.weapon.Upgrader.at(p, ref).getCount() : Integer.parseInt(a.substring(c + 1)));
					}
				} catch (NumberFormatException e) {
					break;
				}
				dev.strafemc.weapon.Shop.sell(p, refs);
			}
			case "upgrade" -> { // "offer expect ref:count ...": stake that many at each ref (Upgrader.UNIT) on the offer the client saw as expect
				String[] a = arg.trim().split(" ");
				java.util.Map<Integer, Integer> refs = new java.util.LinkedHashMap<>();
				int offer;
				String expect;
				try {
					offer = Integer.parseInt(a[0]);
					expect = a[1];
					for (int i = 2; i < a.length; i++) {
						int k = a[i].indexOf(':'), ref = Integer.parseInt(k < 0 ? a[i] : a[i].substring(0, k));
						refs.put(ref, k < 0 ? dev.strafemc.weapon.Upgrader.at(p, ref).getCount() : Integer.parseInt(a[i].substring(k + 1)));
					}
				} catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
					break;
				}
				Boolean win = dev.strafemc.weapon.Upgrader.stake(p, offer, refs, expect);
				if (win != null && net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, dev.strafemc.net.StrafePayloads.UpgradeResult.TYPE))
					net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new dev.strafemc.net.StrafePayloads.UpgradeResult(offer, win));
			}
			case "strafemc_drop" -> { // a care package without a new rank: a cheat
				if (!StrafeSettings.drops()) p.sendSystemMessage(Component.literal("Care packages are off on this server."));
				else if (!cheat) p.sendSystemMessage(Component.literal("Care packages on demand need sv_cheats 1 and operator."));
				else dev.strafemc.weapon.CarePackage.queue(p, 1);
			}
			case "drop_everyone" -> { // a care package for every player online
				if (!StrafeSettings.drops()) p.sendSystemMessage(Component.literal("Care packages are off on this server."));
				else if (!op) p.sendSystemMessage(Component.literal("Only operators can give everyone a care package."));
				else for (var o : p.level().getServer().getPlayerList().getPlayers()) dev.strafemc.weapon.CarePackage.queue(o, 1);
			}
			case "silencer" -> dev.strafemc.weapon.GunServer.toggleSilencer(p);
			case "redeploy" -> dev.strafemc.weapon.GunServer.redeploy(p);
			case "burst" -> dev.strafemc.weapon.GunServer.toggleBurst(p);
			case "zoom" -> dev.strafemc.weapon.GunServer.zoom(p, arg);
			case "case_open" -> { // 1-5 of the held case: each needs its own key, rolls CS2's odds; drops wait for the reel
				String[] a = arg.trim().split(" ");
				String caseId = a[0];
				int n = 1;
				try {
					if (a.length > 1) n = Math.max(1, Math.min(5, Integer.parseInt(a[1])));
				} catch (NumberFormatException ignored) {
				}
				var held = p.getMainHandItem();
				if (!(held.getItem() instanceof dev.strafemc.weapon.CaseItem) || !caseId.equals(dev.strafemc.weapon.CaseItem.caseId(held))) break;
				var c = dev.strafemc.weapon.Cases.get(caseId);
				if (c == null) break;
				var inv = p.getInventory();
				boolean creative = p.getAbilities().instabuild;
				int cases = 0, keys = c.needsKey() ? dev.strafemc.weapon.CaseKeys.count(p, c.key()).total() : n; // its own and Universal Keys, storage units too; capsules need none
				for (int i = 0; i < inv.getContainerSize(); i++) {
					var s = inv.getItem(i);
					if (s.getItem() instanceof dev.strafemc.weapon.CaseItem && caseId.equals(dev.strafemc.weapon.CaseItem.caseId(s))) cases += s.getCount();
				}
				if (!creative && (cases < n || keys < n)) {
					var k = dev.strafemc.weapon.Cases.key(c.key());
					Toasts.warn(p, (cases < n ? "You need " + n + " of these cases." : "You need " + n + " " + (k != null ? k.name() : "key")
							+ (n > 1 ? "s" : "") + " to unlock " + (n > 1 ? "them." : "this case.")));
					break;
				}
				var rng = new java.util.Random();
				java.util.List<net.minecraft.world.item.ItemStack> stacks = new java.util.ArrayList<>();
				java.util.List<dev.strafemc.weapon.SkinWraps.Rarity> rarity = new java.util.ArrayList<>();
				java.util.List<dev.strafemc.net.StrafePayloads.CaseResult.Drop> sent = new java.util.ArrayList<>();
				for (int k = 0; k < n; k++) {
					var drop = dev.strafemc.weapon.Cases.roll(c, rng);
					if (drop == null) continue;
					stacks.add(drop.stack());
					rarity.add(drop.rarity());
					Integer pattern = drop.stack().get(dev.strafemc.weapon.WeaponItems.PATTERN);
					sent.add(new dev.strafemc.net.StrafePayloads.CaseResult.Drop(drop.entry().kit(), drop.entry().item(),
							drop.rarity() == null ? -1 : drop.rarity().ordinal(), drop.stack().getHoverName().getString(), pattern == null ? -1 : pattern,
							drop.stack().getItem() instanceof dev.strafemc.weapon.SkinWrapItem ? dev.strafemc.weapon.SkinWrapItem.wear(drop.stack())
									: drop.stack().getItem() instanceof dev.strafemc.weapon.GloveItem ? dev.strafemc.weapon.GloveItem.wear(drop.stack()) : -1));
				}
				if (stacks.isEmpty()) break;
				if (!creative) { // n cases and n keys used up (its own keys first, then Universal Keys)
					int needCases = stacks.size();
					for (int i = 0; i < inv.getContainerSize() && needCases > 0; i++) {
						var s = inv.getItem(i);
						if (s.getItem() instanceof dev.strafemc.weapon.CaseItem && caseId.equals(dev.strafemc.weapon.CaseItem.caseId(s))) {
							int t = Math.min(needCases, s.getCount());
							s.shrink(t);
							needCases -= t;
						}
					}
					if (c.needsKey()) dev.strafemc.weapon.CaseKeys.use(p, c.key(), stacks.size());
				}
				dev.strafemc.weapon.CaseOpening.hold(p, stacks, rarity);
				if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, dev.strafemc.net.StrafePayloads.CaseResult.TYPE))
					net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new dev.strafemc.net.StrafePayloads.CaseResult(caseId, sent));
				else dev.strafemc.weapon.CaseOpening.claim(p); // no reel to wait for
			}
			case "case_claim" -> dev.strafemc.weapon.CaseOpening.claim(p);
			case "sticker_apply" -> { // "stickerSlot target slot": target L:<loadout slot> or I:<inventory slot>
				String[] a = arg.trim().split(" ");
				try {
					if (a.length == 3) dev.strafemc.weapon.StickerSlots.apply(p, Integer.parseInt(a[0]), a[1], Integer.parseInt(a[2]));
				} catch (NumberFormatException ignored) {
				}
			}
			case "sticker_scrape" -> { // "target slot"
				String[] a = arg.trim().split(" ");
				try {
					if (a.length == 2) dev.strafemc.weapon.StickerSlots.scrape(p, a[0], Integer.parseInt(a[1]));
				} catch (NumberFormatException ignored) {
				}
			}
			case "spray_slot" -> { // the graffiti wheel let go on one of the loadout's graffiti
				try {
					dev.strafemc.weapon.SprayItem.sprayFromLoadout(p, Integer.parseInt(arg.trim()));
				} catch (NumberFormatException ignored) {
				}
			}
			case "strafemc_spawnblocks" -> {
				if (!op) {
					p.sendSystemMessage(Component.literal("Only operators can change spawn blocks."));
					break;
				}
				StrafeSettings.setSpawnBlocks(!arg.equals("0"));
			}
			case "strafemc_drops" -> {
				if (!op) {
					p.sendSystemMessage(Component.literal("Only operators can turn care packages on or off."));
					break;
				}
				StrafeSettings.setDrops(!arg.equals("0"));
				p.level().getServer().getPlayerList().broadcastSystemMessage(Component.literal("Care packages: "
						+ (StrafeSettings.drops() ? "on" : "off") + " (set by " + p.getScoreboardName() + ")"), false);
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
			case "strafemc_killcards_time" -> {
				if (!op) {
					p.sendSystemMessage(Component.literal("Only operators can change the kill card time for the server."));
					break;
				}
				try {
					StrafeSettings.setKillCardTime((int) Math.round(Double.parseDouble(arg.trim())));
				} catch (NumberFormatException e) {
					p.sendSystemMessage(Component.literal("strafemc_killcards_time <seconds; 0 = until death>"));
					break;
				}
				p.level().getServer().getPlayerList().broadcastSystemMessage(Component.literal("Kill card time: "
						+ (StrafeSettings.killCardTime() == 0 ? "until death" : StrafeSettings.killCardTime() + " s") + " (set by " + p.getScoreboardName() + ")"), false);
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
