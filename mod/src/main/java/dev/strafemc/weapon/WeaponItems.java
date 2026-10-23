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

import java.util.List;
import java.util.function.Function;

/** CS2 weapon items and their magazines. Numbers come from weapons.vdata at runtime; nothing about Valve's assets is bundled. */
public final class WeaponItems {
	/** Rounds currently loaded in a gun. Absent = a fresh gun with a full magazine. */
	public static DataComponentType<Integer> CLIP, REFILL_AT;
	/** Zeus x27: the game tick it's charged again (ZeusRecharge). */
	public static DataComponentType<Integer> RECHARGE;
	/** USP-S / M4A1-S: 0 = silencer off (absent: on, as CS2 hands them out). */
	public static DataComponentType<Integer> SILENCED;
	/** FAMAS / Glock-18: 1 = burst mode (absent: off). It stays with the gun, like CS2's. */
	public static DataComponentType<Integer> BURST;
	/** A skin wrap's pattern seed (CS2's pattern template), on wraps whose finish has a pattern: 0 for wraps given out as they are; cases will roll it. */
	public static DataComponentType<Integer> PATTERN;
	/** A wrap's wear (float), as its int bits (Float.floatToIntBits). Absent = Factory New, 0. */
	public static DataComponentType<Integer> WEAR;
	/** A StatTrak wrap's Confirmed Kills (StatTrak). Absent = not StatTrak. */
	public static DataComponentType<Integer> STATTRAK;
	/** A standard graffiti's tint (CS2 graffiti_tints id). Absent: none. */
	public static DataComponentType<Integer> TINT;
	/** A money voucher's dollars. Absent: not rolled yet (fresh from a chest). */
	public static DataComponentType<Integer> VOUCHER;
	/** An unsealed graffiti's sprays left (it starts at SprayItem.USES); absent: still sealed. */
	public static DataComponentType<Integer> OPENED;
	/** Rounds left in a magazine item. Absent = full. */
	public static DataComponentType<Integer> ROUNDS;

	public static Item AK47, MAG_AK47, KNIFE_FALCHION, SURF_RAMP_ITEM, SURF_SLOPE_ITEM, SURF_RAMP_LYING_ITEM, SPAWN_POINT_ITEM, BOMB_SITE_ITEM, END_OF_MATCH_ITEM, HEGRENADE;
	/** Every grenade item, by type. */
	public static final java.util.Map<Grenade, Item> GRENADES = new java.util.EnumMap<>(Grenade.class);
	public static Item STRAFE_PEARL;
	public static Item KEVLAR, ASSAULTSUIT, HEALTHSHOT, GUN_WRAP, KNIFE_WRAP, WEAPON_CASE, CASE_KEY, GUN_BASE, MAG_POUCH, STORAGE_UNIT, SPRAY, VOUCHER_ITEM, STICKER, CHARM, CHARM_DETACHMENT, NAME_TAG, STATTRAK_SWAP, C4, DEFUSE_KIT, BULK_OPENER, SHOP_REFRESH, ZONE_TOOL;
	public static final java.util.Map<GloveItem.Kind, Item> GLOVES = new java.util.EnumMap<>(GloveItem.Kind.class);
	/** Every gun, its magazine item and every knife, by weapon class (WeaponTable). */
	public static final java.util.Map<String, Item> GUNS = new java.util.LinkedHashMap<>(), MAGS = new java.util.LinkedHashMap<>(),
			KNIVES = new java.util.LinkedHashMap<>();
	public static net.minecraft.world.level.block.Block SURF_RAMP, SURF_SLOPE, SURF_RAMP_LYING, SPAWN_POINT, BOMB_SITE, END_OF_MATCH;

	private WeaponItems() {}

	public static void register() {
		CLIP = component("clip");
		ROUNDS = component("rounds");
		REFILL_AT = component("refill_at"); // a mag pouch's pending gunpowder refill (game tick)
		RECHARGE = component("recharge");
		SILENCED = component("silenced");
		BURST = component("burst");
		PATTERN = component("pattern");
		WEAR = component("wear");
		STATTRAK = component("stattrak");
		TINT = component("tint");
		VOUCHER = component("voucher");
		OPENED = component("opened");
		StickerSlots.register();
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
		GUN_WRAP = item("skin_wrap", p -> new SkinWrapItem(p.stacksTo(1), false));
		KNIFE_WRAP = item("knife_wrap", p -> new SkinWrapItem(p.stacksTo(1), true));
		WEAPON_CASE = item("weapon_case", p -> new CaseItem(p.stacksTo(64)));
		// a gun's receiver: found in structure chests, needed to craft every gun but the light pistols (FLAGGED: ours)
		GUN_BASE = item("gun_base", p -> new Item(p.stacksTo(16)));
		CASE_KEY = item("case_key", p -> new KeyItem(p.stacksTo(64)));
		MAG_POUCH = item("mag_pouch", MagPouchItem::new);
		MagPouchItem.register();
		STRAFE_PEARL = item("strafe_pearl", p -> new StrafePearlItem(p.stacksTo(16)));
		STORAGE_UNIT = item("storage_unit", p -> new StorageUnitItem(p.stacksTo(1).fireResistant())); // never burns: it holds your skins
		SPRAY = item("spray", p -> new SprayItem(p.stacksTo(1)));
		VOUCHER_ITEM = item("voucher", p -> new VoucherItem(p.stacksTo(64)));
		STICKER = item("sticker", p -> new StickerItem(p.stacksTo(64)));
		CHARM = item("charm", p -> new CharmItem(p.stacksTo(64)));
		CHARM_DETACHMENT = item("charm_detachment", p -> new Item(p.stacksTo(64)));
		NAME_TAG = item("name_tag", p -> new NameTagItem(p.stacksTo(64)));
		STATTRAK_SWAP = item("stattrak_swap_tool", p -> new NameTagItem(p.stacksTo(64), true));
		BULK_OPENER = item("bulk_opener", p -> new NameTagItem(p.stacksTo(64), 2));
		SHOP_REFRESH = item("shop_refresh", p -> new ShopRefreshItem(p.stacksTo(64)));
		dev.strafemc.mode.ZoneToolItem.registerComponent();
		ZONE_TOOL = item("zone_tool", p -> new dev.strafemc.mode.ZoneToolItem(p.stacksTo(1)));
		C4 = item(C4Item.CLS, p -> new C4Item(p.stacksTo(1)));
		DEFUSE_KIT = item("defuse_kit", p -> new Item(p.stacksTo(1)));
		StorageUnitItem.register();
		Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, id("case_key"), CaseKeyRecipe.SERIALIZER);
		Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, id("magazine"), MagazineRecipe.SERIALIZER);
		ResourceKey<net.minecraft.world.entity.EntityType<?>> grenadeKey = ResourceKey.create(Registries.ENTITY_TYPE, id("grenade"));
		GrenadeEntity.TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, grenadeKey,
				net.minecraft.world.entity.EntityType.Builder.<GrenadeEntity>of(GrenadeEntity::new, net.minecraft.world.entity.MobCategory.MISC)
						.sized(0.1f, 0.1f).clientTrackingRange(8).updateInterval(1).noSummon().noSave().build(grenadeKey));
		ResourceKey<net.minecraft.world.entity.EntityType<?>> c4Key = ResourceKey.create(Registries.ENTITY_TYPE, id("planted_c4"));
		PlantedC4.TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, c4Key,
				net.minecraft.world.entity.EntityType.Builder.<PlantedC4>of(PlantedC4::new, net.minecraft.world.entity.MobCategory.MISC)
						.sized(0.45f, 0.15f).clientTrackingRange(10).updateInterval(2).noSummon().noSave().build(c4Key));
		ResourceKey<net.minecraft.world.entity.EntityType<?>> pearlKey = ResourceKey.create(Registries.ENTITY_TYPE, id("strafe_pearl"));
		StrafePearlEntity.TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, pearlKey,
				net.minecraft.world.entity.EntityType.Builder.<StrafePearlEntity>of(StrafePearlEntity::new, net.minecraft.world.entity.MobCategory.MISC)
						.sized(0.25f, 0.25f).clientTrackingRange(4).updateInterval(10).noSave().build(pearlKey));
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
		ResourceKey<net.minecraft.world.level.block.Block> spawnKey = ResourceKey.create(Registries.BLOCK, id("spawn_point"));
		SPAWN_POINT = Registry.register(BuiltInRegistries.BLOCK, spawnKey, new dev.strafemc.world.SpawnBlock(
				net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().setId(spawnKey).strength(1.5f)
						.sound(net.minecraft.world.level.block.SoundType.STONE)));
		ResourceKey<Item> spawnItem = ResourceKey.create(Registries.ITEM, id("spawn_point"));
		SPAWN_POINT_ITEM = Registry.register(BuiltInRegistries.ITEM, spawnItem,
				new net.minecraft.world.item.BlockItem(SPAWN_POINT, new Item.Properties().setId(spawnItem).useBlockDescriptionPrefix()));
		ResourceKey<net.minecraft.world.level.block.Block> siteKey = ResourceKey.create(Registries.BLOCK, id("bomb_site"));
		BOMB_SITE = Registry.register(BuiltInRegistries.BLOCK, siteKey, new dev.strafemc.world.BombSiteBlock(
				net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().setId(siteKey).strength(1.5f)
						.sound(net.minecraft.world.level.block.SoundType.STONE)));
		ResourceKey<Item> siteItem = ResourceKey.create(Registries.ITEM, id("bomb_site"));
		BOMB_SITE_ITEM = Registry.register(BuiltInRegistries.ITEM, siteItem,
				new net.minecraft.world.item.BlockItem(BOMB_SITE, new Item.Properties().setId(siteItem).useBlockDescriptionPrefix()));
		ResourceKey<net.minecraft.world.level.block.Block> eomKey = ResourceKey.create(Registries.BLOCK, id("end_of_match"));
		END_OF_MATCH = Registry.register(BuiltInRegistries.BLOCK, eomKey, new dev.strafemc.world.EndOfMatchBlock(
				net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().setId(eomKey).strength(1.5f)
						.sound(net.minecraft.world.level.block.SoundType.STONE)));
		ResourceKey<Item> eomItem = ResourceKey.create(Registries.ITEM, id("end_of_match"));
		END_OF_MATCH_ITEM = Registry.register(BuiltInRegistries.ITEM, eomItem,
				new net.minecraft.world.item.BlockItem(END_OF_MATCH, new Item.Properties().setId(eomItem).useBlockDescriptionPrefix()));
		// the tabs, in order; tabs with sections show each under a banner (CreativeSections)
		sectioned("guns", () -> new ItemStack(AK47), List.of(
				new CreativeSections.Section("utility", "Utility", l -> {
					l.add(new ItemStack(MAG_POUCH));
					l.add(new ItemStack(GUN_BASE));
				}),
				new CreativeSections.Section("guns", "Guns", l -> { for (Item g : GUNS.values()) l.add(new ItemStack(g)); }),
				new CreativeSections.Section("mags", "Magazines", l -> {
					for (var g : GUNS.keySet())
						if (WeaponStats.of(g) == null || WeaponStats.of(g).clip() > 1) l.add(new ItemStack(MAGS.get(g)));
				})));
		tab("knives", () -> new ItemStack(KNIVES.get("weapon_knife_karambit")), e -> { for (Item k : KNIVES.values()) e.accept(k); });
		sectioned("cases", () -> CaseItem.stack(WEAPON_CASE, "crate_valve_1"), List.of(
				new CreativeSections.Section("weapon_cases", "Weapon Cases", l -> { for (Cases.Case c : Cases.all()) l.add(CaseItem.stack(WEAPON_CASE, c.id())); }),
				new CreativeSections.Section("keys", "Weapon Case Keys", l -> {
					l.add(CaseKeys.universal());
					for (Cases.Key k : Cases.keys()) l.add(KeyItem.stack(CASE_KEY, k.id()));
				}),
				new CreativeSections.Section("capsules", "Capsules", l -> { for (Cases.Case c : Cases.capsules("sticker")) l.add(CaseItem.stack(WEAPON_CASE, c.id())); }),
				new CreativeSections.Section("graffiti_boxes", "Graffiti Boxes", l -> { for (Cases.Case c : Cases.capsules("spray")) l.add(CaseItem.stack(WEAPON_CASE, c.id())); }),
				new CreativeSections.Section("collections", "Collections", l -> { for (Cases.Case c : Cases.capsules("charm")) l.add(CaseItem.stack(WEAPON_CASE, c.id())); })));
		sectioned("utility", () -> new ItemStack(GRENADES.get(Grenade.SMOKE)), List.of(
				new CreativeSections.Section("grenades", "Grenades", l -> { for (Item g : GRENADES.values()) l.add(new ItemStack(g)); }),
				new CreativeSections.Section("other", "Other", l -> {
					for (Item i : new Item[] {C4, DEFUSE_KIT, KEVLAR, ASSAULTSUIT, HEALTHSHOT, STRAFE_PEARL, STORAGE_UNIT, NAME_TAG, CHARM_DETACHMENT, STATTRAK_SWAP, BULK_OPENER, SHOP_REFRESH,
							SURF_RAMP_ITEM, SURF_SLOPE_ITEM, SURF_RAMP_LYING_ITEM, SPAWN_POINT_ITEM, BOMB_SITE_ITEM, END_OF_MATCH_ITEM, ZONE_TOOL})
						l.add(new ItemStack(i));
					for (int d : new int[] {10, 50, 100, 500, 1000}) l.add(VoucherItem.stack(VOUCHER_ITEM, d));
				})));
		tab("gloves", () -> GloveItem.stack(GLOVES.get(GloveItem.Kind.SPORT), GloveSkins.of(GloveItem.Kind.SPORT).get(0).kit()),
				e -> { for (GloveSkins.Skin s : GloveSkins.ALL) e.accept(GloveItem.stack(GLOVES.get(s.kind()), s.kit())); });
		tab("knife_wraps", () -> SkinWrapItem.stack(KNIFE_WRAP, "knife:fade"),
				e -> { for (SkinWraps.Wrap w : SkinWraps.all()) if (w.knife()) e.accept(SkinWrapItem.stack(KNIFE_WRAP, w.id())); });
		tab("gun_wraps", () -> SkinWrapItem.stack(GUN_WRAP, "gun:asiimov.ak47"),
				e -> { for (SkinWraps.Wrap w : SkinWraps.all()) if (!w.knife()) e.accept(SkinWrapItem.stack(GUN_WRAP, w.id())); });
		tab("stickers", () -> Stickers.all().isEmpty() ? new ItemStack(STICKER) : StickerItem.stack(STICKER, Stickers.all().get(0).id()),
				e -> { for (Stickers.Sticker s : Stickers.all()) e.accept(StickerItem.stack(STICKER, s.id())); });
		tab("sprays", () -> SprayItem.stack(SPRAY, "spray_std_axes_crossed", 2),
				e -> { for (Sprays.Spray s : Sprays.all()) e.accept(SprayItem.stack(SPRAY, s.id(), s.tintable() ? 2 : 0)); });
		tab("charms", () -> Charms.all().isEmpty() ? new ItemStack(CHARM) : CharmItem.stack(CHARM, Charms.all().get(0).id()),
				e -> { for (Charms.Charm c : Charms.all()) e.accept(CharmItem.stack(CHARM, c.id())); });
	}

	/** A tab laid out in sections, each under its banner (CreativeSections). */
	private static void sectioned(String name, java.util.function.Supplier<ItemStack> icon, List<CreativeSections.Section> sections) {
		tab(name, icon, e -> CreativeSections.fill(name, tabId(name), sections, e::accept));
	}

	/** The tabs left to right, then the next row: Fabric places modded tabs by their ids, so each id leads with its place. */
	private static final List<String> TAB_ORDER = List.of("guns", "knives", "cases", "utility", "gloves", "charms", "knife_wraps", "gun_wraps",
			"stickers", "sprays");

	private static net.minecraft.resources.Identifier tabId(String name) {
		int i = TAB_ORDER.indexOf(name);
		return id(String.format(java.util.Locale.ROOT, "%02d_%s", i < 0 ? 99 : i + 1, name));
	}

	private static void tab(String name, java.util.function.Supplier<ItemStack> icon,
			java.util.function.Consumer<net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTabOutput> fill) {
		ResourceKey<CreativeModeTab> key = ResourceKey.create(Registries.CREATIVE_MODE_TAB, tabId(name));
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
