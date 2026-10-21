package dev.strafemc.weapon;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;

import java.util.Map;
import java.util.Set;

/**
 * CS2 loot in Minecraft's structure chests: weapon cases, gear (grenades, armor, the Medi-Shot) and, rarer, CS2 knives
 * (data/strafemc/loot_table/chests/*), more in trial chamber vaults. Chances are ours (FLAGGED).
 */
public final class StructureLoot {
	/** Vanilla chest tables and the chance of a case / knife wrap / knife in each. */
	private static final Set<String> CHESTS = Set.of("chests/simple_dungeon", "chests/abandoned_mineshaft", "chests/desert_pyramid", "chests/jungle_temple",
			"chests/stronghold_corridor", "chests/stronghold_crossing", "chests/woodland_mansion", "chests/ancient_city", "chests/buried_treasure",
			"chests/shipwreck_treasure", "chests/pillager_outpost", "chests/ruined_portal", "chests/bastion_treasure", "chests/end_city_treasure",
			"chests/nether_bridge", "chests/igloo_chest", "chests/underwater_ruin_big");
	private static final Map<String, Float> CHANCE = Map.of("cases", 0.30f, "knives", 0.02f, "gear", 0.05f, "gun_base", 0.10f,
			"vouchers", 0.35f); // vouchers: a money voucher, its dollars rolled when a player first holds it // gear: a grenade, armor or Medi-Shot (guns are crafted only). Knives come plain, wraps from cases only

	/**
	 * Trial chamber vaults (their reward tables): more and better strafeMC loot than chests, more again from ominous
	 * vaults: sticker capsules (two) or a graffiti box, the Universal Key. FLAGGED ours.
	 */
	private static final Map<String, Map<String, Float>> VAULTS = Map.of(
			"chests/trial_chambers/reward", Map.of("cases", 0.5f, "knives", 0.03f, "gear", 0.10f, "gun_base", 0.10f, "vouchers", 0.35f,
					"capsules", 0.30f, "keys", 0.15f),
			"chests/trial_chambers/reward_ominous", Map.of("cases", 0.9f, "knives", 0.08f, "gear", 0.20f, "gun_base", 0.20f, "vouchers", 0.6f,
					"capsules", 0.5f, "keys", 0.35f));

	private StructureLoot() {}

	/** Items that are worth money in strafeMC (what the shop, upgrader and trade ups deal in). */
	static boolean economy(net.minecraft.world.item.ItemStack s) {
		var i = s.getItem();
		return i instanceof CaseItem || i instanceof KeyItem || i instanceof VoucherItem || i instanceof SkinWrapItem || i instanceof GloveItem
				|| i instanceof StickerItem || i instanceof CharmItem || i instanceof SprayItem;
	}

	public static void register() {
		// a server without drops (ServerRules): chests keep their gear but no CS2 cases, keys, capsules, vouchers or skins
		net.fabricmc.fabric.api.loot.v3.LootTableEvents.MODIFY_DROPS.register((table, context, drops) -> {
			if (!dev.strafemc.server.ServerRules.drops()) drops.removeIf(StructureLoot::economy);
		});
		net.fabricmc.fabric.api.loot.v3.LootTableEvents.MODIFY.register((key, table, source, registries) -> {
			if (!source.isBuiltin() || !key.identifier().getNamespace().equals("minecraft")) return;
			Map<String, Float> chances = CHESTS.contains(key.identifier().getPath()) ? CHANCE : VAULTS.get(key.identifier().getPath());
			if (chances == null) return;
			for (var e : chances.entrySet()) {
				ResourceKey<LootTable> ours = ResourceKey.create(Registries.LOOT_TABLE, Identifier.fromNamespaceAndPath("strafemc", "chests/" + e.getKey()));
				table.withPool(LootPool.lootPool().when(LootItemRandomChanceCondition.randomChance(e.getValue())).add(NestedLootTable.lootTableReference(ours)));
			}
		});
	}
}
