package dev.strafemc.weapon;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/**
 * One magazine for a CS2 gun. CS2 counts reserve ammo in magazines (m_bReserveAmmoAsClips), so each magazine is its
 * own item with its own round count; the bar shows how full it is.
 */
public class MagazineItem extends Item {
	public final String weaponClass;

	public MagazineItem(Properties p, String weaponClass) {
		super(p);
		this.weaponClass = weaponClass;
	}

	/** Shotguns loaded shell by shell (Nova, XM1014, Sawed-Off): this item is one shell, used up when loaded. */
	public boolean shells() {
		WeaponStats st = GunItem.statsOf(weaponClass);
		return st != null && st.q().singleShells();
	}

	public int capacity() {
		WeaponStats st = GunItem.statsOf(weaponClass); // client: the server's synced numbers
		return st == null ? 0 : st.q().singleShells() ? 1 : st.clip();
	}

	/**
	 * A magazine of this gun with these rounds (full: no rounds component, so full ones stack); an empty one is
	 * flagged (custom model data flag 0) so its icon gets the red X.
	 */
	public static ItemStack withRounds(ItemStack s, int rounds) {
		int cap = s.getItem() instanceof MagazineItem m ? m.capacity() : 0;
		if (cap > 0 ? rounds >= cap : rounds == Integer.MAX_VALUE) s.remove(WeaponItems.ROUNDS);
		else s.set(WeaponItems.ROUNDS, Math.max(0, rounds));
		if (rounds <= 0) s.set(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA,
				new net.minecraft.world.item.component.CustomModelData(java.util.List.of(), java.util.List.of(true), java.util.List.of(), java.util.List.of()));
		else s.remove(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA);
		return s;
	}

	public int rounds(ItemStack s) {
		Integer r = s.get(WeaponItems.ROUNDS);
		return r != null ? r : capacity();
	}

	@Override
	public boolean isBarVisible(ItemStack s) {
		return capacity() > 0 && rounds(s) < capacity();
	}

	@Override
	public int getBarWidth(ItemStack s) {
		int cap = capacity();
		return cap <= 0 ? 0 : Math.round(13f * rounds(s) / cap);
	}

	@Override
	public int getBarColor(ItemStack s) {
		return 0xE0C060;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
		out.accept(Component.literal("§7" + rounds(stack) + " / " + capacity() + " rounds"));
	}
}
