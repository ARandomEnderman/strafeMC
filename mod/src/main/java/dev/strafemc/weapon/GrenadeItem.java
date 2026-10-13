package dev.strafemc.weapon;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/** A CS2 grenade: hold left / right / both to pull the pin, release to throw (overhand, underhand, in between). */
public class GrenadeItem extends GunItem {
	public final Grenade type;

	public GrenadeItem(Properties p, Grenade type) {
		super(p, type.cls, null);
		this.type = type;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
		out.accept(Component.literal("§7Hold left: throw far, right: lob short, both: in between"));
		out.accept(Component.literal("§7Release to throw"));
	}
}
