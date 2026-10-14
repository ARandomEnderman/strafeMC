package dev.strafemc.weapon;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/**
 * CS2's Medi-Shot (weapon_healthshot): left click plays the inject animation; at its WPN_HEALTHSHOT_INJECT event
 * (0.667 s into the 1.9 s clip) the server heals healthshot_health (CS2 default 50); it is used up when the clip ends. CS2's defaults:
 * healthshot_allow_use_at_full on, healthshot_healthboost_time 1 s with speed and damage multipliers
 * 1.0 (so the boost changes nothing and isn't modelled).
 */
public class HealthshotItem extends GunItem {
	public static final String CLS = "weapon_healthshot";
	/** The inject event and the inject clip's length (s), from CS2's healthshot clip. */
	public static final double INJECT_AT = 0.667, CLIP = 1.9, HEAL = 50;

	public HealthshotItem(Properties p) {
		super(p, CLS, null);
	}

	/** Players whose injection is still animating: the hotbar slot and the server tick when the clip ends. */
	private record Pending(int slot, long until) {}

	private static final java.util.Map<java.util.UUID, Pending> PENDING = new java.util.concurrent.ConcurrentHashMap<>();

	/** The inject event reached the server: heal now, use the Medi-Shot up when the inject clip ends (CS2 keeps it in hand until then). */
	public static void inject(ServerPlayer p) {
		ItemStack held = p.getMainHandItem();
		if (!(held.getItem() instanceof HealthshotItem) || PENDING.containsKey(p.getUUID())) return;
		String v = dev.strafemc.bridge.Cs2Bridge.INSTANCE.cvar("healthshot_health");
		double hp;
		try {
			hp = v == null ? HEAL : Double.parseDouble(v);
		} catch (NumberFormatException e) {
			hp = HEAL;
		}
		p.heal((float) (hp * dev.strafemc.config.StrafeConfig.hpScale));
		long rest = Math.round((CLIP - INJECT_AT) * 20);
		if (!p.getAbilities().instabuild) PENDING.put(p.getUUID(), new Pending(p.getInventory().getSelectedSlot(), p.level().getGameTime() + rest));
		GunServer.soundAt(p.level(), "Healthshot.Success", p.position(), p);
	}

	/** Uses up the Medi-Shots whose inject clip has finished (in the slot it was injected from). */
	public static void tick(net.minecraft.server.MinecraftServer server) {
		if (PENDING.isEmpty()) return;
		PENDING.entrySet().removeIf(e -> {
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null) return true;
			if (p.level().getGameTime() < e.getValue().until()) return false;
			ItemStack s = p.getInventory().getItem(e.getValue().slot());
			if (s.getItem() instanceof HealthshotItem) s.shrink(1);
			return true;
		});
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
	}
}
