package dev.strafemc.weapon;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A money voucher (ours): sold in the shop for its full dollars (onto the balance), or made there by withdrawing from
 * it, to keep or give away. Found in structure chests; a voucher without a value yet (fresh from a chest) gets one the
 * first time it's in a player's inventory: $10-$1000, higher ones rarer (10 x 100^(u^2): half are $31 or less, one in
 * ten is $440 or more). FLAGGED ours. Not a skin: no worth, so the upgrader and trade ups don't take it.
 */
public class VoucherItem extends Item {
	public static final int MIN = 10, MAX = 1000;

	public VoucherItem(Properties p) {
		super(p);
	}

	public static ItemStack stack(Item item, int dollars) {
		ItemStack s = new ItemStack(item);
		s.set(WeaponItems.VOUCHER, Math.max(1, dollars)); // withdrawn ones can be any whole-dollar amount
		return s;
	}

	/** Its dollars, or 0 if it has none yet. */
	public static int dollars(ItemStack s) {
		Integer v = s.get(WeaponItems.VOUCHER);
		return v == null ? 0 : v;
	}

	static int roll(net.minecraft.util.RandomSource rng) {
		double u = rng.nextDouble();
		return (int) Math.round(MIN * Math.pow((double) MAX / MIN, u * u));
	}

	@Override
	public void inventoryTick(ItemStack stack, net.minecraft.server.level.ServerLevel level, net.minecraft.world.entity.Entity entity,
							  net.minecraft.world.entity.EquipmentSlot slot) {
		if (!stack.has(WeaponItems.VOUCHER)) stack.set(WeaponItems.VOUCHER, roll(level.getRandom()));
	}

	@Override
	public Component getName(ItemStack stack) {
		int d = dollars(stack);
		return d > 0 ? Component.literal("Money Voucher ($" + d + ")").withColor(0x55FF55) : super.getName(stack);
	}

}
