package dev.strafemc.weapon;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** The Strafe Pearl (ours): thrown like an ender pearl (the same cooldown), it launches you instead of teleporting you. */
public class StrafePearlItem extends Item {
	public StrafePearlItem(Properties p) {
		super(p);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENDER_PEARL_THROW, SoundSource.NEUTRAL, 0.5f,
				0.4f / (level.getRandom().nextFloat() * 0.4f + 0.8f));
		if (level instanceof ServerLevel sl) {
			StrafePearlEntity e = new StrafePearlEntity(level, player, stack);
			e.shootFromRotation(player, player.getXRot(), player.getYRot(), 0f, 1.5f, 1f); // the ender pearl's throw
			sl.addFreshEntity(e);
		}
		player.getCooldowns().addCooldown(stack, 20);
		// strafemc_infinite_pearl: not used up (decided on the server; the client shows what it sends)
		if (!level.isClientSide() && !InfinitePearl.on(player)) stack.consume(1, player);
		return InteractionResult.SUCCESS;
	}
}
