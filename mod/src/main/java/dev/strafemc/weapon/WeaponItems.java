package dev.strafemc.weapon;

import com.mojang.serialization.Codec;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.Function;

/** CS2 weapon items and their magazines. Numbers come from weapons.vdata at runtime; nothing about Valve's assets is bundled. */
public final class WeaponItems {
	/** Rounds currently loaded in a gun. Absent = a fresh gun with a full magazine. */
	public static DataComponentType<Integer> CLIP;
	/** Rounds left in a magazine item. Absent = full. */
	public static DataComponentType<Integer> ROUNDS;

	public static Item AK47, MAG_AK47, KNIFE_FALCHION, SURF_RAMP_ITEM, SURF_SLOPE_ITEM;
	public static net.minecraft.world.level.block.Block SURF_RAMP, SURF_SLOPE;

	private WeaponItems() {}

	public static void register() {
		CLIP = component("clip");
		ROUNDS = component("rounds");
		AK47 = item("weapon_ak47", p -> new GunItem(p, "weapon_ak47", "mag_ak47"));
		MAG_AK47 = item("mag_ak47", p -> new MagazineItem(p.stacksTo(64), "weapon_ak47")); // full mags stack (partly used ones differ in rounds)
		KNIFE_FALCHION = item("weapon_knife_falchion", p -> new KnifeItem(p, "weapon_knife_falchion"));
		ResourceKey<net.minecraft.world.level.block.Block> rampKey = ResourceKey.create(Registries.BLOCK, id("surf_ramp"));
		SURF_RAMP = Registry.register(BuiltInRegistries.BLOCK, rampKey, new dev.strafemc.world.SurfRampBlock(
				net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().setId(rampKey).strength(1.5f)
						.sound(net.minecraft.world.level.block.SoundType.STONE).noOcclusion()));
		ResourceKey<Item> rampItem = ResourceKey.create(Registries.ITEM, id("surf_ramp"));
		SURF_RAMP_ITEM = Registry.register(BuiltInRegistries.ITEM, rampItem,
				new net.minecraft.world.item.BlockItem(SURF_RAMP, new Item.Properties().setId(rampItem).useBlockDescriptionPrefix()));
		ResourceKey<net.minecraft.world.level.block.Block> slopeKey = ResourceKey.create(Registries.BLOCK, id("surf_slope"));
		SURF_SLOPE = Registry.register(BuiltInRegistries.BLOCK, slopeKey, new dev.strafemc.world.SurfSlopeBlock(
				net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().setId(slopeKey).strength(1.5f)
						.sound(net.minecraft.world.level.block.SoundType.STONE).noOcclusion()));
		ResourceKey<Item> slopeItem = ResourceKey.create(Registries.ITEM, id("surf_slope"));
		SURF_SLOPE_ITEM = Registry.register(BuiltInRegistries.ITEM, slopeItem,
				new net.minecraft.world.item.BlockItem(SURF_SLOPE, new Item.Properties().setId(slopeItem).useBlockDescriptionPrefix()));
		ResourceKey<CreativeModeTab> tab = ResourceKey.create(Registries.CREATIVE_MODE_TAB, id("weapons"));
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, tab, net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab.builder()
				.title(Component.translatable("itemGroup.strafemc.weapons"))
				.icon(() -> new ItemStack(AK47)).build());
		net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents.modifyOutputEvent(tab).register(e -> {
			e.accept(AK47);
			e.accept(MAG_AK47);
			e.accept(KNIFE_FALCHION);
			e.accept(SURF_RAMP_ITEM);
			e.accept(SURF_SLOPE_ITEM);
		});
	}

	private static DataComponentType<Integer> component(String name) {
		return Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id(name),
				DataComponentType.<Integer>builder().persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());
	}

	private static Item item(String name, Function<Item.Properties, Item> factory) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(name));
		return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(new Item.Properties().setId(key).stacksTo(1)));
	}

	static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath("strafemc", path);
	}
}
