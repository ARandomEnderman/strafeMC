package dev.strafemc.weapon;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/**
 * CS2's Medi-Shot (weapon_healthshot): left click plays the inject animation; at its WPN_HEALTHSHOT_INJECT event
 * (0.667 s into the 1.9 s clip) the server heals healthshot_health (CS2 default 50) and uses one up. CS2's defaults
 * from its code: healthshot_allow_use_at_full on, healthshot_healthboost_time 1 s with speed and damage multipliers
 * 1.0 (so the boost changes nothing and isn't modelled).
 */
public class HealthshotItem extends GunItem {
	public static final String CLS = "weapon_healthshot";
	public static final double INJECT_AT = 0.667, HEAL = 50;

	public HealthshotItem(Properties p) {
		super(p, CLS, null);
	}

	/** The inject event reached the server: heal and use one up (only with a Medi-Shot in hand). */
	public static void inject(ServerPlayer p) {
		ItemStack held = p.getMainHandItem();
		if (!(held.getItem() instanceof HealthshotItem)) return;
		String v = dev.strafemc.bridge.Cs2Bridge.INSTANCE.cvar("healthshot_health");
		double hp;
		try {
			hp = v == null ? HEAL : Double.parseDouble(v);
		} catch (NumberFormatException e) {
			hp = HEAL;
		}
		p.heal((float) (hp * dev.strafemc.config.StrafeConfig.hpScale));
		if (!p.getAbilities().instabuild) held.shrink(1);
		GunServer.soundAt(p.level(), "Healthshot.Success", p.position(), p);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
		out.accept(Component.literal("§7Left click to inject: +50 health"));
	}
}
