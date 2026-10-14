package dev.strafemc.weapon;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Consumer;

/** A CS2 gun. Firing/reloading is handled by GunClient (input, prediction) and GunServer (authoritative). */
public class GunItem extends Item {
	public final String weaponClass;
	public final String magazineName;

	public GunItem(Properties p, String weaponClass, String magazineName) {
		super(p);
		this.weaponClass = weaponClass;
		this.magazineName = magazineName;
	}

	public WeaponStats stats() {
		return statsOf(weaponClass);
	}

	/** This gun's numbers in its current mode (a USP-S / M4A1-S with the silencer on uses CS2's silenced numbers). */
	public WeaponStats stats(ItemStack s) {
		return statsOf(weaponClass, mode(s));
	}

	/** Whether this gun has a silencer at all (CS2's m_eSilencerType). */
	public boolean hasSilencer() {
		WeaponStats w = stats();
		return w != null && w.hasSilencer();
	}

	/** Silencer on: CS2 hands these guns out with it on, so a stack that was never toggled has it on. */
	public boolean silenced(ItemStack s) {
		Integer v = s.get(WeaponItems.SILENCED);
		return hasSilencer() && (v == null || v != 0);
	}

	/** vdata mode: 1 (alt) with the silencer on, else 0. */
	public int mode(ItemStack s) {
		return silenced(s) ? 1 : 0;
	}

	/** The client's render thread and its stats lookup (the server's synced numbers), set by the client init. */
	public static volatile Thread clientThread;
	public static volatile java.util.function.BiFunction<String, Integer, WeaponStats> clientStats;

	/**
	 * Stats as the calling side must see them: on the client thread the server's synced numbers (a player without CS2
	 * files has no local ones), elsewhere (dedicated or integrated server) the server's own data.
	 */
	public static WeaponStats statsOf(String cls) {
		return statsOf(cls, 0);
	}

	public static WeaponStats statsOf(String cls, int mode) {
		var f = clientStats;
		if (f != null && Thread.currentThread() == clientThread) return f.apply(cls, mode);
		return GunServer.stats(cls, mode);
	}

	/** Rounds loaded; a gun that never had its clip set starts with a full magazine. */
	public int clip(ItemStack s) {
		Integer c = s.get(WeaponItems.CLIP);
		if (c != null) return c;
		WeaponStats st = stats();
		return st == null ? 0 : st.clip();
	}

	/** A recharging Zeus shows how far along it is. */
	@Override
	public boolean isBarVisible(ItemStack s) {
		return s.has(WeaponItems.RECHARGE);
	}

	@Override
	public int getBarWidth(ItemStack s) {
		return Math.round(13 * ZeusRecharge.progress(s, ZeusRecharge.clientGameTime.getAsLong()));
	}

	@Override
	public int getBarColor(ItemStack s) {
		return 0x5AC8FA;
	}

	@Override
	public boolean canDestroyBlock(ItemStack stack, BlockState state, Level level, BlockPos pos, LivingEntity user) {
		return false; // left click fires; it never breaks blocks
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
		WeaponStats st = stats();
		if (st == null) return;
		out.accept(Component.literal("§7" + clip(stack) + " / " + st.clip() + " rounds loaded"));
		out.accept(Component.literal("§8" + st.damage() + " dmg, " + Math.round(60 / st.cycleTime()) + " RPM, HS x" + st.headshotMultiplier()));
	}
}
