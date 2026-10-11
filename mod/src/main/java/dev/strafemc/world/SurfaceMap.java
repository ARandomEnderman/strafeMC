package dev.strafemc.world;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Which CS2 surface property a Minecraft block behaves as (bullet impacts, decals, penetration). Minecraft is the base
 * game, so this is the one place where a mapping is chosen: by the block's sound type, refined by its id.
 */
public final class SurfaceMap {
	private SurfaceMap() {}

	public static String surface(BlockState s) {
		String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
		SoundType t = s.getSoundType();
		if (id.contains("glass") || t == SoundType.GLASS) return "glass";
		if (id.contains("leaves") || id.contains("vine")) return "foliage";
		if (id.contains("ladder")) return "Wood_Ladder";
		if (id.contains("concrete")) return "concrete";
		if (id.contains("brick")) return "brick";
		if (id.contains("terracotta") || id.contains("glazed")) return "tile";
		if (id.contains("snow") || t == SoundType.SNOW || t == SoundType.POWDER_SNOW) return "snow";
		if (id.contains("ice")) return "ice";
		if (id.contains("wool") || id.contains("carpet") || t == SoundType.WOOL) return "carpet";
		if (id.contains("chain")) return "chain";
		if (id.contains("iron_bars")) return "metalgrate";
		if (t == SoundType.WOOD || t == SoundType.NETHER_WOOD || t == SoundType.BAMBOO_WOOD || t == SoundType.CHERRY_WOOD
				|| t == SoundType.SCAFFOLDING || t == SoundType.BAMBOO) return "Wood";
		if (t == SoundType.METAL || t == SoundType.COPPER || t == SoundType.ANVIL || t == SoundType.LANTERN
				|| t == SoundType.NETHERITE_BLOCK || t == SoundType.HEAVY_CORE) return "metal";
		if (t == SoundType.GRAVEL) return "gravel";
		if (t == SoundType.SAND || t == SoundType.SUSPICIOUS_SAND) return "sand";
		if (t == SoundType.MUD || t == SoundType.MUD_BRICKS || t == SoundType.PACKED_MUD) return "mud";
		if (t == SoundType.GRASS || t == SoundType.MOSS || t == SoundType.MOSS_CARPET) return id.contains("block") ? "grass" : "foliage";
		if (t == SoundType.ROOTED_DIRT || id.contains("dirt") || id.contains("farmland") || id.contains("path") || id.contains("podzol")
				|| id.contains("mycelium") || id.contains("clay")) return "dirt";
		if (t == SoundType.SLIME_BLOCK || t == SoundType.HONEY_BLOCK) return "slipperyslime";
		if (id.contains("smooth") || id.contains("polished") || id.contains("quartz") || id.contains("prismarine")) return "concrete";
		if (t == SoundType.STONE || t == SoundType.DEEPSLATE || t == SoundType.TUFF || t == SoundType.CALCITE || t == SoundType.BASALT
				|| t == SoundType.NETHERRACK || t == SoundType.DRIPSTONE_BLOCK) return "rock";
		return "default";
	}
}
