package dev.strafemc.weapon;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

/** CS armor (Kevlar Vest / Kevlar + Helmet): use it to put it on, like buying it in CS. */
public class ArmorItem extends Item {
	public final boolean helmet;

	public ArmorItem(Properties p, boolean helmet) {
		super(p);
		this.helmet = helmet;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!(player instanceof ServerPlayer p)) return InteractionResult.SUCCESS;
		CsArmor.Armor a = CsArmor.of(p);
		if (a.value() >= 100 && (a.helmet() || !helmet)) return InteractionResult.FAIL; // nothing to gain
		CsArmor.set(p, 100, helmet || a.helmet());
		if (!p.getAbilities().instabuild) player.getItemInHand(hand).shrink(1);
		GunServer.soundAt(p.level(), "Player.PickupWeapon", p.position(), p);
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
	}
}
