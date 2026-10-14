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
	/** Zeus x27: the game tick it's charged again (ZeusRecharge). */
	public static DataComponentType<Integer> RECHARGE;
	/** USP-S / M4A1-S: 0 = silencer off (absent: on, as CS2 hands them out). */
	public static DataComponentType<Integer> SILENCED;
	/** Rounds left in a magazine item. Absent = full. */
	public static DataComponentType<Integer> ROUNDS;

	public static Item AK47, MAG_AK47, KNIFE_FALCHION, SURF_RAMP_ITEM, SURF_SLOPE_ITEM, SURF_RAMP_LYING_ITEM, HEGRENADE;
	/** Every grenade item, by type. */
	public static final java.util.Map<Grenade, Item> GRENADES = new java.util.EnumMap<>(Grenade.class);
	public static Item KEVLAR, ASSAULTSUIT, HEALTHSHOT, GUN_WRAP, KNIFE_WRAP;
	public static final java.util.Map<GloveItem.Kind, Item> GLOVES = new java.util.EnumMap<>(GloveItem.Kind.class);
	/** Every gun, its magazine item and every knife, by weapon class (WeaponTable). */
	public static final java.util.Map<String, Item> GUNS = new java.util.LinkedHashMap<>(), MAGS = new java.util.LinkedHashMap<>(),
			KNIVES = new java.util.LinkedHashMap<>();
	public static net.minecraft.world.level.block.Block SURF_RAMP, SURF_SLOPE, SURF_RAMP_LYING;

	private WeaponItems() {}

	public static void register() {
		CLIP = component("clip");
		ROUNDS = component("rounds");
		RECHARGE = component("recharge");
		SILENCED = component("silenced");
		for (WeaponTable.Entry e : WeaponTable.guns()) {
			String mag = "mag_" + e.shortName();
			GUNS.put(e.cls(), item(e.cls(), p -> new GunItem(p, e.cls(), mag)));
			// full mags stack (partly used ones differ in rounds). FLAGGED: shotguns too, though CS2 reloads them shell by shell
			MAGS.put(e.cls(), item(mag, p -> new MagazineItem(p.stacksTo(64), e.cls())));
		}
		for (WeaponTable.Entry e : WeaponTable.knives()) KNIVES.put(e.cls(), item(e.cls(), p -> new KnifeItem(p, e.cls())));
		AK47 = GUNS.get("weapon_ak47");
		MAG_AK47 = MAGS.get("weapon_ak47");
		KNIFE_FALCHION = KNIVES.get("weapon_knife_falchion");
		for (Grenade g : Grenade.values()) GRENADES.put(g, item(g.cls, p -> new GrenadeItem(p.stacksTo(16), g)));
		HEGRENADE = GRENADES.get(Grenade.HE);
		KEVLAR = item("item_kevlar", p -> new ArmorItem(p.stacksTo(16), false));
		ASSAULTSUIT = item("item_assaultsuit", p -> new ArmorItem(p.stacksTo(16), true));
		HEALTHSHOT = item(HealthshotItem.CLS, p -> new HealthshotItem(p.stacksTo(16)));
		for (GloveItem.Kind k : GloveItem.Kind.values()) GLOVES.put(k, item(k.id, p -> new GloveItem(p.stacksTo(1), k)));
		GUN_WRAP = item("skin_wrap", p -> new SkinWrapItem(p.stacksTo(4), false));
		KNIFE_WRAP = item("knife_wrap", p -> new SkinWrapItem(p.stacksTo(4), true));
		ResourceKey<net.minecraft.world.entity.EntityType<?>> grenadeKey = ResourceKey.create(Registries.ENTITY_TYPE, id("grenade"));
		GrenadeEntity.TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, grenadeKey,
				net.minecraft.world.entity.EntityType.Builder.<GrenadeEntity>of(GrenadeEntity::new, net.minecraft.world.entity.MobCategory.MISC)
						.sized(0.1f, 0.1f).clientTrackingRange(8).updateInterval(1).noSummon().noSave().build(grenadeKey));
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
		ResourceKey<net.minecraft.world.level.block.Block> lyingKey = ResourceKey.create(Registries.BLOCK, id("surf_ramp_lying"));
		SURF_RAMP_LYING = Registry.register(BuiltInRegistries.BLOCK, lyingKey, new dev.strafemc.world.SurfRampLyingBlock(
				net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().setId(lyingKey).strength(1.5f)
						.sound(net.minecraft.world.level.block.SoundType.STONE).noOcclusion()));
		ResourceKey<Item> lyingItem = ResourceKey.create(Registries.ITEM, id("surf_ramp_lying"));
		SURF_RAMP_LYING_ITEM = Registry.register(BuiltInRegistries.ITEM, lyingItem,
				new net.minecraft.world.item.BlockItem(SURF_RAMP_LYING, new Item.Properties().setId(lyingItem).useBlockDescriptionPrefix()));
		// guns (with their magazines), knives, utility and blocks: a tab each
		tab("guns", () -> new ItemStack(AK47), e -> {
			for (var g : GUNS.entrySet()) {
				e.accept(g.getValue());
				if (WeaponStats.of(g.getKey()) == null || WeaponStats.of(g.getKey()).clip() > 1) e.accept(MAGS.get(g.getKey()));
			}
		});
		tab("knives", () -> new ItemStack(KNIVES.get("weapon_knife_karambit")), e -> { for (Item k : KNIVES.values()) e.accept(k); });
		tab("utility", () -> new ItemStack(GRENADES.get(Grenade.SMOKE)), e -> {
			for (Item g : GRENADES.values()) e.accept(g);
			e.accept(KEVLAR);
			e.accept(ASSAULTSUIT);
			e.accept(HEALTHSHOT);
			e.accept(SURF_RAMP_ITEM);
			e.accept(SURF_SLOPE_ITEM);
			e.accept(SURF_RAMP_LYING_ITEM);
		});
		// a tab each for gloves, knife wraps and gun wraps (agents get theirs when they exist)
		tab("gloves", () -> GloveItem.stack(GLOVES.get(GloveItem.Kind.SPORT), GloveSkins.of(GloveItem.Kind.SPORT).get(0).kit()),
				e -> { for (GloveSkins.Skin s : GloveSkins.ALL) e.accept(GloveItem.stack(GLOVES.get(s.kind()), s.kit())); });
		tab("knife_wraps", () -> SkinWrapItem.stack(KNIFE_WRAP, "knife:fade"),
				e -> { for (SkinWraps.Wrap w : SkinWraps.all()) if (w.knife()) e.accept(SkinWrapItem.stack(KNIFE_WRAP, w.id())); });
		tab("gun_wraps", () -> SkinWrapItem.stack(GUN_WRAP, "gun:asiimov"),
				e -> { for (SkinWraps.Wrap w : SkinWraps.all()) if (!w.knife()) e.accept(SkinWrapItem.stack(GUN_WRAP, w.id())); });
	}

	private static void tab(String name, java.util.function.Supplier<ItemStack> icon,
			java.util.function.Consumer<net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTabOutput> fill) {
		ResourceKey<CreativeModeTab> key = ResourceKey.create(Registries.CREATIVE_MODE_TAB, id(name));
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, key, net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab.builder()
				.title(Component.translatable("itemGroup.strafemc." + name)).icon(icon).build());
		net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents.modifyOutputEvent(key).register(fill::accept);
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
