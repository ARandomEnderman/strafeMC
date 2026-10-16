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
 * A case's own key: the case + a gold nugget + redstone, in any order; the case stays (FLAGGED: our
 * recipe, CS2 sells keys).
 */
public class CaseKeyRecipe extends CustomRecipe {
	public static final CaseKeyRecipe INSTANCE = new CaseKeyRecipe();
	public static final RecipeSerializer<CaseKeyRecipe> SERIALIZER = new RecipeSerializer<>(MapCodec.unit(INSTANCE),
			StreamCodec.<RegistryFriendlyByteBuf, CaseKeyRecipe>unit(INSTANCE));

	/** The case in the grid when the rest of the recipe is there exactly, else null. */
	private static ItemStack caseIn(CraftingInput in) {
		ItemStack found = null;
		int gold = 0, redstone = 0, other = 0;
		for (ItemStack s : in.items()) {
			if (s.isEmpty()) continue;
			if (s.getItem() instanceof CaseItem && found == null) found = s;
			else if (s.is(Items.GOLD_NUGGET)) gold++;
			else if (s.is(Items.REDSTONE)) redstone++;
			else other++;
		}
		if (found == null || gold != 1 || redstone != 1 || other != 0) return null;
		Cases.Case c = Cases.get(CaseItem.caseId(found));
		return c == null || Cases.key(c.key()) == null ? null : found;
	}

	@Override
	public boolean matches(CraftingInput in, Level level) {
		return caseIn(in) != null;
	}

	@Override
	public ItemStack assemble(CraftingInput in) {
		ItemStack cs = caseIn(in);
		return cs == null ? ItemStack.EMPTY : KeyItem.stack(WeaponItems.CASE_KEY, Cases.get(CaseItem.caseId(cs)).key());
	}

	/** The case comes back: the key is made for it, not from it. */
	@Override
	public NonNullList<ItemStack> getRemainingItems(CraftingInput in) {
		NonNullList<ItemStack> out = NonNullList.withSize(in.size(), ItemStack.EMPTY);
		for (int i = 0; i < in.size(); i++)
			if (in.getItem(i).getItem() instanceof CaseItem) out.set(i, in.getItem(i).copyWithCount(1));
		return out;
	}

	@Override
	public RecipeSerializer<CaseKeyRecipe> getSerializer() {
		return SERIALIZER;
	}
}
