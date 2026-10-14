package dev.strafemc.weapon;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/**
 * A CS2 knife (its own item): left click = light slash (primary), right click = heavy stab (secondary). Reuses the gun
 * plumbing (viewmodel, deploy, inspect, move speed from weapons.vdata); no magazine. Attacks: GunClient and GunServer.knife.
 */
public class KnifeItem extends GunItem {
	public KnifeItem(Properties p, String weaponClass) {
		super(p, weaponClass, null);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
	}
}
