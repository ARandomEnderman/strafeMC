package dev.strafemc.weapon;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * Magazines (FLAGGED: our recipe, CS2 gives ammo with the gun): a gun + an iron ingot + gunpowder, in any order, makes
 * one full magazine for that gun and the gun stays; a magazine + gunpowder fills it up again. Not for the Zeus.
 */
public class MagazineRecipe extends CustomRecipe {
	public static final MagazineRecipe INSTANCE = new MagazineRecipe();
	public static final RecipeSerializer<MagazineRecipe> SERIALIZER = new RecipeSerializer<>(MapCodec.unit(INSTANCE),
			StreamCodec.<RegistryFriendlyByteBuf, MagazineRecipe>unit(INSTANCE));

	/** What the grid makes: a full magazine of the gun's (or of the refilled magazine's) class, or empty. */
	private static ItemStack result(CraftingInput in) {
		ItemStack gun = null, mag = null;
		int iron = 0, powder = 0, other = 0;
		for (ItemStack s : in.items()) {
			if (s.isEmpty()) continue;
			if (s.getItem() instanceof GunItem && !(s.getItem() instanceof KnifeItem) && gun == null) gun = s;
			else if (s.getItem() instanceof MagazineItem && mag == null) mag = s;
			else if (s.is(Items.IRON_INGOT)) iron++;
			else if (s.is(Items.GUNPOWDER)) powder++;
			else other++;
		}
		if (other != 0 || powder != 1) return ItemStack.EMPTY;
		String cls;
		if (gun != null && mag == null && iron == 1) cls = ((GunItem) gun.getItem()).weaponClass;
		else if (mag != null && gun == null && iron == 0) cls = ((MagazineItem) mag.getItem()).weaponClass;
		else return ItemStack.EMPTY;
		var item = WeaponItems.MAGS.get(cls);
		return item == null || cls.equals("weapon_taser") ? ItemStack.EMPTY : new ItemStack(item);
	}

	@Override
	public boolean matches(CraftingInput in, Level level) {
		return !result(in).isEmpty();
	}

	@Override
	public ItemStack assemble(CraftingInput in) {
		return result(in);
	}

	/** The gun comes back as it was: the magazine is made for it, not from it. */
	@Override
	public NonNullList<ItemStack> getRemainingItems(CraftingInput in) {
		NonNullList<ItemStack> out = NonNullList.withSize(in.size(), ItemStack.EMPTY);
		for (int i = 0; i < in.size(); i++)
			if (in.getItem(i).getItem() instanceof GunItem) out.set(i, in.getItem(i).copyWithCount(1));
		return out;
	}

	@Override
	public RecipeSerializer<MagazineRecipe> getSerializer() {
		return SERIALIZER;
	}
}
