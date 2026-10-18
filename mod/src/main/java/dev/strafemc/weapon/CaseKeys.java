package dev.strafemc.weapon;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Keys for opening a case: its own keys first, then Universal Keys (ours: opens any case), from the inventory and from
 * inside the Storage Units in it.
 */
public final class CaseKeys {
	/** The Universal Key's key id (a case key with this custom model data string). */
	public static final String UNIVERSAL = "universal";

	/** own: this case's keys; universal: Universal Keys. */
	public record Count(int own, int universal) {
		public int total() {
			return own + universal;
		}

		/** Universal Keys opening n cases would use (own keys go first). */
		public int universalFor(int n) {
			return Math.max(0, Math.min(universal, n - own));
		}
	}

	private CaseKeys() {}

	public static ItemStack universal() {
		return KeyItem.stack(WeaponItems.CASE_KEY, UNIVERSAL);
	}

	private static boolean is(ItemStack s, String keyId) {
		return s.is(WeaponItems.CASE_KEY) && keyId.equals(KeyItem.keyId(s));
	}

	public static Count count(Player p, String caseKey) {
		int own = 0, uni = 0;
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (is(s, caseKey)) own += s.getCount();
			else if (is(s, UNIVERSAL)) uni += s.getCount();
			else if (s.getItem() instanceof StorageUnitItem)
				for (ItemStack in : s.getOrDefault(StorageUnitItem.CONTENTS, List.<ItemStack>of())) {
					if (is(in, caseKey)) own += in.getCount();
					else if (is(in, UNIVERSAL)) uni += in.getCount();
				}
		}
		return new Count(own, uni);
	}

	/** Uses n keys for this case: its own (inventory, then storage units), then Universal ones; returns how many weren't found. */
	public static int use(Player p, String caseKey, int n) {
		n = take(p, caseKey, n);
		return take(p, UNIVERSAL, n);
	}

	private static int take(Player p, String keyId, int n) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize() && n > 0; i++) {
			ItemStack s = inv.getItem(i);
			if (is(s, keyId)) {
				int t = Math.min(n, s.getCount());
				s.shrink(t);
				n -= t;
			}
		}
		for (int i = 0; i < inv.getContainerSize() && n > 0; i++) {
			ItemStack unit = inv.getItem(i);
			if (!(unit.getItem() instanceof StorageUnitItem)) continue;
			List<ItemStack> all = StorageUnitItem.contents(unit);
			boolean changed = false;
			for (int j = 0; j < all.size() && n > 0; j++) {
				ItemStack s = all.get(j);
				if (!is(s, keyId)) continue;
				int t = Math.min(n, s.getCount());
				s.shrink(t);
				n -= t;
				changed = true;
			}
			if (changed) StorageUnitItem.store(unit, all);
		}
		return n;
	}
}
