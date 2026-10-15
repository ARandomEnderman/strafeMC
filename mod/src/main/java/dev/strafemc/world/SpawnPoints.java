package dev.strafemc.world;

import dev.strafemc.StrafeMc;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The world's spawn blocks (SpawnBlock), saved in <world>/strafemc_spawns.txt as "dimension x y z" lines. Entries are
 * checked when used, so a block removed while its chunk was unloaded simply drops out.
 */
public final class SpawnPoints {
	private static final Set<String> POINTS = new LinkedHashSet<>();
	private static MinecraftServer server;

	private SpawnPoints() {}

	private static Path file(MinecraftServer s) {
		return s.getWorldPath(LevelResource.ROOT).resolve("strafemc_spawns.txt");
	}

	public static synchronized void load(MinecraftServer s) {
		server = s;
		POINTS.clear();
		try {
			Path f = file(s);
			if (Files.exists(f)) for (String l : Files.readAllLines(f)) if (!l.isBlank()) POINTS.add(l.trim());
		} catch (Exception e) {
			StrafeMc.LOG.warn("[strafemc] spawn points unreadable: {}", e.toString());
		}
	}

	private static void save() {
		if (server == null) return;
		try {
			Files.write(file(server), POINTS);
		} catch (Exception e) {
			StrafeMc.LOG.warn("[strafemc] spawn points not saved: {}", e.toString());
		}
	}

	private static String key(ServerLevel level, BlockPos pos) {
		return level.dimension().identifier() + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	static synchronized void add(ServerLevel level, BlockPos pos) {
		if (POINTS.add(key(level, pos))) save();
	}

	static synchronized void remove(ServerLevel level, BlockPos pos) {
		if (POINTS.remove(key(level, pos))) save();
	}

	/**
	 * Puts a player on top of a random spawn block (facing as they were); false if there's none. Stale entries (the
	 * block gone) are dropped on the way.
	 */
	public static synchronized boolean respawn(ServerPlayer p) {
		Pick at = pick();
		return at != null && p.teleportTo(at.level(), at.pos().x, at.pos().y, at.pos().z, Set.of(), p.getYRot(), p.getXRot(), true);
	}

	/** A spawn block's top. */
	public record Pick(ServerLevel level, net.minecraft.world.phys.Vec3 pos) {}

	/** On top of a random spawn block, or null if there's none (stale entries dropped on the way). */
	public static synchronized Pick pick() {
		if (server == null || POINTS.isEmpty()) return null;
		List<String> all = new ArrayList<>(POINTS);
		java.util.Collections.shuffle(all);
		for (String k : all) {
			String[] f = k.split(" ");
			if (f.length != 4) {
				POINTS.remove(k);
				continue;
			}
			ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(f[0])));
			BlockPos pos = new BlockPos(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]));
			if (level == null || !(level.getBlockState(pos).getBlock() instanceof SpawnBlock)) {
				POINTS.remove(k);
				save();
				continue;
			}
			return new Pick(level, new net.minecraft.world.phys.Vec3(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5));
		}
		return null;
	}
}
