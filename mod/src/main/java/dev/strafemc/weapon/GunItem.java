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

	/** The client's render thread and its stats lookup (the server's synced numbers), set by the client init. */
	public static volatile Thread clientThread;
	public static volatile java.util.function.Function<String, WeaponStats> clientStats;

	/**
	 * Stats as the calling side must see them: on the client thread the server's synced numbers (a player without CS2
	 * files has no local ones), elsewhere (dedicated or integrated server) the server's own data.
	 */
	public static WeaponStats statsOf(String cls) {
		var f = clientStats;
		if (f != null && Thread.currentThread() == clientThread) return f.apply(cls);
		return GunServer.stats(cls);
	}

	/** Rounds loaded; a gun that never had its clip set starts with a full magazine. */
	public int clip(ItemStack s) {
		Integer c = s.get(WeaponItems.CLIP);
		if (c != null) return c;
		WeaponStats st = stats();
		return st == null ? 0 : st.clip();
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
