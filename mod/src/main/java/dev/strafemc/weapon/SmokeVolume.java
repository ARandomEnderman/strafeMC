package dev.strafemc.weapon;

import dev.strafemc.config.StrafeConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.BitSet;

/**
 * A smoke grenade's volume, voxelised like CS2's (its server code): a 32 x 32 x 32 grid of 20 unit voxels (640
 * units across) around the detonation, flood-filled from it through open space, so the smoke goes round corners
 * and through doorways but never through walls. The shape is CS2's (replicated cvars, read from a CS2 server):
 * a torus cl_smoke_torus_ring_radius 61 around a centre cl_smoke_origin_height 68 above the grenade, with tube
 * radius cl_smoke_torus_ring_subradius 88 and a cl_smoke_edge_feather 21 unit soft edge. Server and client run the
 * same fill on the same blocks, so only the detonation point is sent. FLAGGED: no voxel budget was found in CS2's
 * code (the torus bounds the fill here); voxels open if their centre isn't inside a block's collision box.
 */
public final class SmokeVolume {
	public static final int N = 32;
	public static final double VOXEL = 20, RING = 61, SUB = 88, ORIGIN_HEIGHT = 68, FEATHER = 21;

	/** Where the grenade went off, and the grid's lowest corner (both in blocks). */
	public final Vec3 origin, corner;
	/** Filled voxels in flood order (index x + N * (y + N * z)) and each one's flood step from the grenade. */
	public final int[] cells;
	public final int[] steps;
	public final int maxStep;
	private final BitSet filled;

	private SmokeVolume(Vec3 origin, Vec3 corner, int[] cells, int[] steps, int maxStep, BitSet filled) {
		this.origin = origin;
		this.corner = corner;
		this.cells = cells;
		this.steps = steps;
		this.maxStep = maxStep;
		this.filled = filled;
	}

	static double size() {
		return VOXEL / StrafeConfig.unitsPerBlock;
	}

	/** The voxel centre (blocks) of a cell index. */
	public Vec3 center(int cell) {
		double s = size();
		int x = cell % N, y = cell / N % N, z = cell / (N * N);
		return corner.add((x + 0.5) * s, (y + 0.5) * s, (z + 0.5) * s);
	}

	/** Is this point (blocks) inside a filled voxel? */
	public boolean contains(Vec3 p) {
		double s = size();
		int x = (int) Math.floor((p.x - corner.x) / s), y = (int) Math.floor((p.y - corner.y) / s), z = (int) Math.floor((p.z - corner.z) / s);
		return x >= 0 && y >= 0 && z >= 0 && x < N && y < N && z < N && filled.get(x + N * (y + N * z));
	}

	public boolean filled(int x, int y, int z) {
		return x >= 0 && y >= 0 && z >= 0 && x < N && y < N && z < N && filled.get(x + N * (y + N * z));
	}

	/** CS2's smoke density at a point (0..1): 1 inside the torus, fading to 0 over the edge feather. */
	public double density(Vec3 p) {
		double upb = StrafeConfig.unitsPerBlock;
		double dx = (p.x - origin.x) * upb, dz = (p.z - origin.z) * upb, dy = (p.y - origin.y) * upb - ORIGIN_HEIGHT;
		double ring = Math.sqrt(dx * dx + dz * dz) - RING, q = Math.sqrt(ring * ring + dy * dy);
		return Math.max(0, Math.min(1, (SUB - q) / FEATHER));
	}

	/** Floods the grid from the detonation point (blocks). */
	public static SmokeVolume fill(Level level, Vec3 origin) {
		double s = size(), upb = StrafeConfig.unitsPerBlock, half = N / 2.0 * s;
		// snapped to half blocks, so every voxel lies inside one block: a wall's voxels are all solid, none straddles it
		Vec3 corner = new Vec3(Math.floor((origin.x - half) / s) * s, Math.floor((origin.y + ORIGIN_HEIGHT / upb - half) / s) * s,
				Math.floor((origin.z - half) / s) * s);
		SmokeVolume shape = new SmokeVolume(origin, corner, new int[0], new int[0], 0, new BitSet());
		BitSet seen = new BitSet(N * N * N), filled = new BitSet(N * N * N);
		int[] cells = new int[N * N * N], steps = new int[N * N * N];
		int count = 0, maxStep = 0;
		ArrayDeque<int[]> queue = new ArrayDeque<>();
		// the grenade's voxel (or the one above it, if it lies on a block edge)
		for (double up = 0.05; up <= 0.6 && queue.isEmpty(); up += 0.25) {
			Vec3 p = origin.add(0, up, 0);
			int x = (int) Math.floor((p.x - corner.x) / s), y = (int) Math.floor((p.y - corner.y) / s), z = (int) Math.floor((p.z - corner.z) / s);
			int i = x + N * (y + N * z);
			if (open(level, shape.center(i))) {
				seen.set(i);
				queue.add(new int[] {x, y, z, 0});
			}
		}
		int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		while (!queue.isEmpty()) {
			int[] c = queue.poll();
			int i = c[0] + N * (c[1] + N * c[2]);
			filled.set(i);
			cells[count] = i;
			steps[count++] = c[3];
			maxStep = Math.max(maxStep, c[3]);
			for (int[] d : dirs) {
				int x = c[0] + d[0], y = c[1] + d[1], z = c[2] + d[2];
				if (x < 0 || y < 0 || z < 0 || x >= N || y >= N || z >= N) continue;
				int j = x + N * (y + N * z);
				if (seen.get(j)) continue;
				seen.set(j);
				Vec3 at = shape.center(j);
				if (shape.density(at) <= 0 || !open(level, at)) continue;
				queue.add(new int[] {x, y, z, c[3] + 1});
			}
		}
		return new SmokeVolume(origin, corner, java.util.Arrays.copyOf(cells, count), java.util.Arrays.copyOf(steps, count), maxStep, filled);
	}

	/** Is this point outside every block's collision box? */
	private static boolean open(Level level, Vec3 p) {
		BlockPos bp = BlockPos.containing(p);
		var shape = level.getBlockState(bp).getCollisionShape(level, bp);
		if (shape.isEmpty()) return true;
		Vec3 local = p.subtract(bp.getX(), bp.getY(), bp.getZ());
		for (AABB b : shape.toAabbs()) if (b.contains(local)) return false;
		return true;
	}
}
