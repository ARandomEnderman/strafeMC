package dev.strafemc.weapon;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bulk opener: a one-use tool that opens up to MAX cases, capsules, graffiti boxes and charm collections at once,
 * of any mix. Each case that needs a key takes one of its own keys first, then Universal Keys (as opening one by one
 * does); capsules need none. Picked in its screen from your items and Storage Units (Upgrader refs).
 */
public final class BulkOpener {
	public static final int MAX = 50;

	private BulkOpener() {}

	/** How many keys a pick takes: of the cases' own, of Universal Keys, and how many are missing. */
	public record Keys(int own, int universal, int missing) {}

	/** The keys the cases at these refs (ref -> how many) need, as they'd be used: own keys first, then Universal Keys. */
	public static Keys keys(Player p, Map<Integer, Integer> refs) {
		Map<String, Integer> perKey = new LinkedHashMap<>();
		for (var e : refs.entrySet()) {
			ItemStack s = Upgrader.at(p, e.getKey());
			Cases.Case c = s.getItem() instanceof CaseItem ? Cases.get(CaseItem.caseId(s)) : null;
			if (c != null && c.needsKey()) perKey.merge(c.key(), e.getValue(), Integer::sum);
		}
		int own = 0, uniNeeded = 0, uniHave = CaseKeys.count(p, CaseKeys.UNIVERSAL + "_none").universal();
		for (var e : perKey.entrySet()) {
			int have = CaseKeys.count(p, e.getKey()).own(), use = Math.min(have, e.getValue());
			own += use;
			uniNeeded += e.getValue() - use;
		}
		int uni = Math.min(uniNeeded, uniHave);
		return new Keys(own, uni, uniNeeded - uni);
	}

	/** The cases at refs (ref -> how many) a bulk opener can take: cases or capsules, as many as are there, MAX at most. */
	static boolean valid(Player p, Map<Integer, Integer> refs) {
		int total = 0;
		for (var e : refs.entrySet()) {
			ItemStack s = Upgrader.at(p, e.getKey());
			if (!(s.getItem() instanceof CaseItem) || Cases.get(CaseItem.caseId(s)) == null || e.getValue() <= 0 || e.getValue() > s.getCount()) return false;
			total += e.getValue();
		}
		return total > 0 && total <= MAX;
	}

	/** Opens them (server): uses the tool in toolSlot, the cases and their keys, rolls each and starts the reels. */
	public static void open(ServerPlayer p, int toolSlot, Map<Integer, Integer> refs) {
		var inv = p.getInventory();
		boolean creative = p.getAbilities().instabuild;
		if (toolSlot < 0 || toolSlot >= inv.getContainerSize() || !inv.getItem(toolSlot).is(WeaponItems.BULK_OPENER) || !valid(p, refs)) return;
		Keys k = keys(p, refs);
		if (k.missing() > 0 && !creative) {
			dev.strafemc.server.Toasts.warn(p, "You need " + k.missing() + " more key" + (k.missing() > 1 ? "s" : "") + " for these.");
			return;
		}
		var rng = new java.util.Random();
		List<ItemStack> stacks = new ArrayList<>();
		List<SkinWraps.Rarity> rarity = new ArrayList<>();
		List<String> caseIds = new ArrayList<>();
		List<dev.strafemc.net.StrafePayloads.CaseResult.Drop> sent = new ArrayList<>();
		Map<String, Integer> perKey = new LinkedHashMap<>();
		Map<Integer, Integer> opened = new LinkedHashMap<>(); // only what gave something is used up
		for (var e : refs.entrySet()) {
			Cases.Case c = Cases.get(CaseItem.caseId(Upgrader.at(p, e.getKey())));
			for (int n = 0; n < e.getValue(); n++) {
				var drop = Cases.roll(c, rng);
				if (drop == null) continue;
				opened.merge(e.getKey(), 1, Integer::sum);
				if (c.needsKey()) perKey.merge(c.key(), 1, Integer::sum);
				stacks.add(drop.stack());
				rarity.add(drop.rarity());
				caseIds.add(c.id());
				Integer pattern = drop.stack().get(WeaponItems.PATTERN);
				sent.add(new dev.strafemc.net.StrafePayloads.CaseResult.Drop(drop.entry().kit(), drop.entry().item(),
						drop.rarity() == null ? -1 : drop.rarity().ordinal(), drop.stack().getHoverName().getString(), pattern == null ? -1 : pattern,
						drop.stack().getItem() instanceof SkinWrapItem ? SkinWrapItem.wear(drop.stack())
								: drop.stack().getItem() instanceof GloveItem ? GloveItem.wear(drop.stack()) : -1));
			}
		}
		if (stacks.isEmpty()) return;
		if (!creative) {
			Upgrader.take(p, opened);
			for (var e : perKey.entrySet()) CaseKeys.use(p, e.getKey(), e.getValue());
			inv.getItem(toolSlot).shrink(1);
		}
		CaseOpening.hold(p, stacks, rarity);
		if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, dev.strafemc.net.StrafePayloads.BulkCaseResult.TYPE))
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new dev.strafemc.net.StrafePayloads.BulkCaseResult(caseIds, sent));
		else CaseOpening.claim(p);
	}
}
