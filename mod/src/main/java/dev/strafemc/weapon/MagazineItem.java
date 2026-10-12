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

	public int capacity() {
		WeaponStats st = GunItem.statsOf(weaponClass); // client: the server's synced numbers
		return st == null ? 0 : st.clip();
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
