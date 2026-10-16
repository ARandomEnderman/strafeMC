package dev.strafemc.client.compat;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.ingredient.ICraftingGridHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.extensions.vanilla.crafting.ICraftingCategoryExtension;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.IVanillaCategoryExtensionRegistration;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.SlotDisplay;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI (when installed): its item list stays off our own screens (the loadout, a weapon case), like CS2's menus; and
 * the case key recipe, a custom one JEI can't read, is shown once per case.
 */
@JeiPlugin
public final class JeiCompat implements IModPlugin {
	@Override
	public Identifier getPluginUid() {
		return Identifier.fromNamespaceAndPath("strafemc", "jei");
	}

	/** One case's key recipe, for JEI only: the case, a gold ingot, a tripwire hook and redstone make its key. */
	static final class CaseKeyDisplay extends dev.strafemc.weapon.CaseKeyRecipe {
		final ItemStack caseStack, key;

		CaseKeyDisplay(ItemStack caseStack, ItemStack key) {
			this.caseStack = caseStack;
			this.key = key;
		}

		List<List<ItemStack>> inputs() {
			return List.of(List.of(caseStack), List.of(new ItemStack(Items.GOLD_INGOT)), List.of(new ItemStack(Items.TRIPWIRE_HOOK)),
					List.of(new ItemStack(Items.REDSTONE)));
		}
	}

	@Override
	public void registerVanillaCategoryExtensions(IVanillaCategoryExtensionRegistration r) {
		r.getCraftingCategory().addExtension(CaseKeyDisplay.class, new ICraftingCategoryExtension<CaseKeyDisplay>() {
			@Override
			public List<SlotDisplay> getIngredients(RecipeHolder<CaseKeyDisplay> h) {
				return List.of(new SlotDisplay.ItemSlotDisplay(h.value().caseStack.getItem()), new SlotDisplay.ItemSlotDisplay(Items.GOLD_INGOT),
						new SlotDisplay.ItemSlotDisplay(Items.TRIPWIRE_HOOK), new SlotDisplay.ItemSlotDisplay(Items.REDSTONE));
			}

			@Override
			public void setRecipe(RecipeHolder<CaseKeyDisplay> h, IRecipeLayoutBuilder b, ICraftingGridHelper grid, IFocusGroup focuses) {
				grid.createAndSetInputs(b, h.value().inputs(), 0, 0); // shapeless
				grid.createAndSetOutputs(b, List.of(h.value().key));
				b.setShapeless();
			}
		});
	}

	@Override
	public void registerRecipes(IRecipeRegistration r) {
		List<RecipeHolder<CraftingRecipe>> out = new ArrayList<>();
		for (var c : dev.strafemc.weapon.Cases.all()) {
			if (dev.strafemc.weapon.Cases.key(c.key()) == null) continue;
			ItemStack cs = dev.strafemc.weapon.CaseItem.stack(dev.strafemc.weapon.WeaponItems.WEAPON_CASE, c.id());
			ItemStack key = dev.strafemc.weapon.KeyItem.stack(dev.strafemc.weapon.WeaponItems.CASE_KEY, c.key());
			out.add(new RecipeHolder<>(ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("strafemc", "jei/case_key/" + c.id())),
					new CaseKeyDisplay(cs, key)));
		}
		r.addRecipes(RecipeTypes.CRAFTING, out);
	}

	@Override
	public void registerGuiHandlers(IGuiHandlerRegistration r) {
		// the loadout is an inventory screen: JEI only keeps out of areas marked as taken, so the whole screen is
		r.addGuiContainerHandler(dev.strafemc.client.LoadoutScreen.class, new mezz.jei.api.gui.handlers.IGuiContainerHandler<dev.strafemc.client.LoadoutScreen>() {
			@Override
			public java.util.List<net.minecraft.client.renderer.Rect2i> getGuiExtraAreas(dev.strafemc.client.LoadoutScreen screen) {
				return java.util.List.of(new net.minecraft.client.renderer.Rect2i(0, 0, screen.width, screen.height));
			}
		});
		r.addGuiScreenHandler(dev.strafemc.client.CaseScreen.class, screen -> null);
	}
}
