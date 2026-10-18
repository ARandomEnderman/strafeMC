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
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.SlotDisplay;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI (when installed): its item list stays off our own screens (the loadout, a weapon case), like CS2's menus; and
 * our custom recipes JEI can't read (case keys, magazines) are shown as fixed examples.
 */
@JeiPlugin
public final class JeiCompat implements IModPlugin {
	@Override
	public Identifier getPluginUid() {
		return Identifier.fromNamespaceAndPath("strafemc", "jei");
	}

	/**
	 * A fixed example of one of our custom (shapeless) recipes, for JEI only: the case key per case, the magazine per
	 * gun and its refill. The real recipes read the grid; JEI can't show those.
	 */
	static final class Example extends CustomRecipe {
		final List<ItemStack> inputs;
		final ItemStack output;

		Example(List<ItemStack> inputs, ItemStack output) {
			this.inputs = inputs;
			this.output = output;
		}

		@Override
		public boolean matches(CraftingInput in, Level level) {
			return false; // display only
		}

		@Override
		public ItemStack assemble(CraftingInput in) {
			return ItemStack.EMPTY;
		}

		@Override
		public RecipeSerializer<? extends CustomRecipe> getSerializer() {
			return dev.strafemc.weapon.MagazineRecipe.SERIALIZER;
		}
	}

	/**
	 * Our variants are one item each, told apart by their custom model data string (which skin, case, key, glove finish):
	 * without this JEI folds them into one entry and the rest go missing.
	 */
	@Override
	public void registerItemSubtypes(mezz.jei.api.registration.ISubtypeRegistration r) {
		var cmd = net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA;
		for (var item : new net.minecraft.world.item.Item[] {dev.strafemc.weapon.WeaponItems.GUN_WRAP, dev.strafemc.weapon.WeaponItems.KNIFE_WRAP,
				dev.strafemc.weapon.WeaponItems.WEAPON_CASE, dev.strafemc.weapon.WeaponItems.CASE_KEY, dev.strafemc.weapon.WeaponItems.SPRAY,
				dev.strafemc.weapon.WeaponItems.STICKER})
			r.registerFromDataComponentTypes(item, cmd);
		for (var gloves : dev.strafemc.weapon.WeaponItems.GLOVES.values()) r.registerFromDataComponentTypes(gloves, cmd);
		r.registerFromDataComponentTypes(dev.strafemc.weapon.WeaponItems.VOUCHER_ITEM, dev.strafemc.weapon.WeaponItems.VOUCHER);
	}

	@Override
	public void registerVanillaCategoryExtensions(IVanillaCategoryExtensionRegistration r) {
		r.getCraftingCategory().addExtension(Example.class, new ICraftingCategoryExtension<Example>() {
			@Override
			public List<SlotDisplay> getIngredients(RecipeHolder<Example> h) {
				return h.value().inputs.stream().<SlotDisplay>map(s -> new SlotDisplay.ItemSlotDisplay(s.getItem())).toList();
			}

			@Override
			public void setRecipe(RecipeHolder<Example> h, IRecipeLayoutBuilder b, ICraftingGridHelper grid, IFocusGroup focuses) {
				grid.createAndSetInputs(b, h.value().inputs.stream().map(List::of).toList(), 0, 0); // shapeless
				grid.createAndSetOutputs(b, List.of(h.value().output));
				b.setShapeless();
			}
		});
	}

	@Override
	public void registerRecipes(IRecipeRegistration r) {
		List<RecipeHolder<CraftingRecipe>> out = new ArrayList<>();
		// case keys: the case + a gold nugget + redstone (the case stays)
		for (var c : dev.strafemc.weapon.Cases.all()) {
			if (dev.strafemc.weapon.Cases.key(c.key()) == null) continue;
			ItemStack cs = dev.strafemc.weapon.CaseItem.stack(dev.strafemc.weapon.WeaponItems.WEAPON_CASE, c.id());
			ItemStack key = dev.strafemc.weapon.KeyItem.stack(dev.strafemc.weapon.WeaponItems.CASE_KEY, c.key());
			out.add(example("case_key/" + c.id(), List.of(cs, new ItemStack(Items.GOLD_NUGGET), new ItemStack(Items.REDSTONE)), key));
		}
		// magazines: the gun + an iron nugget + gunpowder (the gun stays; shotguns loaded by the shell: 8 shells), and an
		// empty magazine + gunpowder refills it
		for (var e : dev.strafemc.weapon.WeaponItems.GUNS.entrySet()) {
			String cls = e.getKey();
			if (cls.equals("weapon_taser") || !(dev.strafemc.weapon.WeaponItems.MAGS.get(cls) instanceof dev.strafemc.weapon.MagazineItem mag)) continue;
			ItemStack full = dev.strafemc.weapon.MagazineItem.withRounds(new ItemStack(mag), Integer.MAX_VALUE);
			if (mag.shells()) full.setCount(8);
			out.add(example("magazine/" + cls, List.of(new ItemStack(e.getValue()), new ItemStack(Items.IRON_NUGGET), new ItemStack(Items.GUNPOWDER)), full));
			if (!mag.shells())
				out.add(example("magazine_refill/" + cls, List.of(dev.strafemc.weapon.MagazineItem.withRounds(new ItemStack(mag), 0), new ItemStack(Items.GUNPOWDER)),
						dev.strafemc.weapon.MagazineItem.withRounds(new ItemStack(mag), Integer.MAX_VALUE)));
		}
		r.addRecipes(RecipeTypes.CRAFTING, out);
	}

	private static RecipeHolder<CraftingRecipe> example(String path, List<ItemStack> inputs, ItemStack output) {
		return new RecipeHolder<>(ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("strafemc", "jei/" + path)), new Example(inputs, output));
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
		// the Storage Unit's screen too (its preview panel sits where JEI's list would)
		r.addGuiContainerHandler(dev.strafemc.client.StorageUnitScreen.class, new mezz.jei.api.gui.handlers.IGuiContainerHandler<dev.strafemc.client.StorageUnitScreen>() {
			@Override
			public java.util.List<net.minecraft.client.renderer.Rect2i> getGuiExtraAreas(dev.strafemc.client.StorageUnitScreen screen) {
				return java.util.List.of(new net.minecraft.client.renderer.Rect2i(0, 0, screen.width, screen.height));
			}
		});
		r.addGuiScreenHandler(dev.strafemc.client.CaseScreen.class, screen -> null);
	}
}
