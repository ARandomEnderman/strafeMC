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
 * The world's spawn blocks (SpawnBlock) and bomb sites (BombSiteBlock), saved in <world>/strafemc_spawns.txt as
 * "dimension x y z" lines. Entries are checked when used, so a block removed while its chunk was unloaded simply drops
 * out; whose spawn it is or which site lives in the block's state.
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
		return respawn(p, null);
	}

	/** On one of a side's spawn blocks (null: any spawn block). */
	public static synchronized boolean respawn(ServerPlayer p, SpawnBlock.Side side) {
		Pick at = pick(side);
		return at != null && p.teleportTo(at.level(), at.pos().x, at.pos().y, at.pos().z, Set.of(), p.getYRot(), p.getXRot(), true);
	}

	/** A spawn block's top. */
	public record Pick(ServerLevel level, net.minecraft.world.phys.Vec3 pos) {}

	/** On top of a random spawn block, or null if there's none (stale entries dropped on the way). */
	public static synchronized Pick pick() {
		return pick(null);
	}

	/**
	 * Where a player stands over a marker block: the first place above it with room for them, however deep it's buried
	 * (up to the build limit), so the block can be hidden under the floor; on the block itself when there's none.
	 */
	public static net.minecraft.world.phys.Vec3 standOn(ServerLevel level, BlockPos pos) {
		for (int dy = 1; pos.getY() + dy + 1 <= level.getMaxY(); dy++) {
			BlockPos feet = pos.above(dy);
			if (free(level, feet) && free(level, feet.above())) return new net.minecraft.world.phys.Vec3(pos.getX() + 0.5, feet.getY(), pos.getZ() + 0.5);
		}
		return new net.minecraft.world.phys.Vec3(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
	}

	private static boolean free(ServerLevel level, BlockPos p) {
		return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
	}

	/**
	 * Over a random spawn block of a side (T or CT; blocks set to anyone count for both when a side has none of
	 * its own), or any spawn block for null; null if there's none.
	 */
	public static synchronized Pick pick(SpawnBlock.Side side) {
		List<Marker> all = markers();
		java.util.Collections.shuffle(all);
		for (int pass = 0; pass < 2; pass++)
			for (Marker m : all) {
				if (!(m.state().getBlock() instanceof SpawnBlock)) continue;
				SpawnBlock.Side s = m.state().getValue(SpawnBlock.SIDE);
				if (side != null && s != (pass == 0 ? side : SpawnBlock.Side.ANY)) continue;
				return new Pick(m.level(), standOn(m.level(), m.pos()));
			}
		return null;
	}

	/** A spawn block or bomb site in the world. */
	public record Marker(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {}

	/** Every spawn block and bomb site still there (stale entries dropped). */
	public static synchronized List<Marker> markers() {
		List<Marker> out = new ArrayList<>();
		if (server == null) return out;
		boolean changed = false;
		for (String k : new ArrayList<>(POINTS)) {
			String[] f = k.split(" ");
			if (f.length != 4) {
				POINTS.remove(k);
				changed = true;
				continue;
			}
			ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(f[0])));
			BlockPos pos = new BlockPos(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]));
			if (level == null) continue;
			var state = level.getBlockState(pos);
			if (!(state.getBlock() instanceof SpawnBlock) && !(state.getBlock() instanceof BombSiteBlock)) {
				POINTS.remove(k);
				changed = true;
				continue;
			}
			out.add(new Marker(level, pos, state));
		}
		if (changed) save();
		return out;
	}
}
