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
 * (data/strafemc/loot_table/chests/*). Chances are ours (FLAGGED).
 */
public final class StructureLoot {
	/** Vanilla chest tables and the chance of a case / knife wrap / knife in each. */
	private static final Set<String> CHESTS = Set.of("chests/simple_dungeon", "chests/abandoned_mineshaft", "chests/desert_pyramid", "chests/jungle_temple",
			"chests/stronghold_corridor", "chests/stronghold_crossing", "chests/woodland_mansion", "chests/ancient_city", "chests/buried_treasure",
			"chests/shipwreck_treasure", "chests/pillager_outpost", "chests/ruined_portal", "chests/bastion_treasure", "chests/end_city_treasure",
			"chests/trial_chambers/reward", "chests/nether_bridge", "chests/igloo_chest", "chests/underwater_ruin_big");
	private static final Map<String, Float> CHANCE = Map.of("cases", 0.30f, "knives", 0.02f, "gear", 0.05f, "gun_base", 0.10f,
			"vouchers", 0.25f); // vouchers: a money voucher, its dollars rolled when a player first holds it // gear: a grenade, armor or Medi-Shot (guns are crafted only). Knives come plain, wraps from cases only

	private StructureLoot() {}

	public static void register() {
		net.fabricmc.fabric.api.loot.v3.LootTableEvents.MODIFY.register((key, table, source, registries) -> {
			if (!source.isBuiltin() || !key.identifier().getNamespace().equals("minecraft") || !CHESTS.contains(key.identifier().getPath())) return;
			for (var e : CHANCE.entrySet()) {
				ResourceKey<LootTable> ours = ResourceKey.create(Registries.LOOT_TABLE, Identifier.fromNamespaceAndPath("strafemc", "chests/" + e.getKey()));
				table.withPool(LootPool.lootPool().when(LootItemRandomChanceCondition.randomChance(e.getValue())).add(NestedLootTable.lootTableReference(ours)));
			}
		});
	}
}
