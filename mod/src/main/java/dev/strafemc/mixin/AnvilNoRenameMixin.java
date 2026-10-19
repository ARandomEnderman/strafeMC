package dev.strafemc.mixin;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** strafeMC's weapons, wraps and the C4 can't be renamed (or changed) on an anvil: a Name Tag names them, as in CS2. */
@Mixin(AnvilMenu.class)
public abstract class AnvilNoRenameMixin extends ItemCombinerMenu {
	protected AnvilNoRenameMixin(MenuType<?> type, int id, Inventory inv, ContainerLevelAccess access, net.minecraft.world.inventory.ItemCombinerMenuSlotDefinition slots) {
		super(type, id, inv, access, slots);
	}

	@Inject(method = "createResult", at = @At("TAIL"))
	private void strafemc$noRename(CallbackInfo ci) {
		ItemStack in = inputSlots.getItem(0);
		if (!in.isEmpty() && BuiltInRegistries.ITEM.getKey(in.getItem()).getNamespace().equals("strafemc")
				&& (in.getItem() instanceof dev.strafemc.weapon.GunItem || in.getItem() instanceof dev.strafemc.weapon.SkinWrapItem))
			resultSlots.setItem(0, ItemStack.EMPTY);
	}
}
