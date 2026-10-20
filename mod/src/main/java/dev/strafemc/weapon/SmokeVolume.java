package dev.strafemc.weapon;

import dev.strafemc.config.StrafeConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A smoke grenade's smoke, simulated the way CS2 simulates it: a 32 x 32 x 32 grid of 20 unit voxels (640 units
 * across) centred on the detonation, in which smoke is a fluid.
 * <p>
 * When it goes off, up to 40 seed voxels are spread out from the detonation through open space (a breadth-first
 * search over the 26 neighbours that only places a seed where no neighbour has one yet; seeds steeply above the
 * detonation go one voxel higher). Runs of columns with the same top get their seeds nudged up and down in turn, seeds
 * right on top of the detonation are moved to the end of the list, and every column whose lowest seed hangs 4 to 8
 * voxels above a floor gets a line of seeds every second voxel down to it (taken from the highest seeds): that's what
 * pours smoke over ledges. A few more seeds go around the seed furthest above or below the detonation.
 * <p>
 * Ten times a second each seed pumps up to 60 into its voxel (capped at 50 x a boost of up to 3.5 that grows as fewer
 * seeds pump), seeds further than 80 units out only once the smoke around them is thick enough; then every voxel with
 * smoke pulls from its six neighbours (neighbour / 6 x its own room left x 0.9 sideways, 1.25 if the neighbour's flow
 * points at it, 0.79 from below, 1.2 from above, so smoke sinks), wakes its neighbours once it holds more than 5, keeps
 * a flow direction (from its sideways neighbours, slid along walls), drains at the grid's edge and loses 0.5 a step.
 * From 19 s the seeds stop and it thins out. HE blasts push seeds and flow away from them.
 * <p>
 * Server and client run the same simulation on the same blocks (CS2's client also re-runs its server's steps), so
 * only the detonation point is sent. A voxel is solid if its box overlaps a block's collision box. The grid is placed
 * on half-block boundaries (FLAGGED: CS2 centres it on the detonation exactly; aligned, a voxel never straddles a
 * block, so one-block doorways stay two voxels wide).
 */
public final class SmokeVolume {
	public static final int N = 32, CELLS = N * N * N;
	/** CS2 units per voxel. */
	public static final double VOXEL = 20;
	/** Simulation steps per second (CS2's server steps the smoke every 0.1 s). */
	public static final double STEP_SECONDS = 0.1;
	/** Seconds after the detonation when the seeds stop and the smoke thins out (CS2: 19 s). */
	public static final double DISSIPATE_SECONDS = 19;
	static final float INJECT = 60, FULL = 50;

	/** The face neighbours, CS2's order (-x, +x, -y, +y, -z, +z; CS2 axes: z up). */
	static final int[][] DIR6 = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
	/** All 26 neighbours, in CS2's order. */
	static final int[][] DIR26 = {{-1, 0, 0}, {-1, 1, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}, {1, -1, 0}, {0, -1, 0}, {-1, -1, 0},
			{-1, 0, 1}, {-1, 1, 1}, {0, 1, 1}, {1, 1, 1}, {1, 0, 1}, {1, -1, 1}, {0, -1, 1}, {-1, -1, 1}, {0, 0, 1},
			{-1, 0, -1}, {-1, 1, -1}, {0, 1, -1}, {1, 1, -1}, {1, 0, -1}, {1, -1, -1}, {0, -1, -1}, {-1, -1, -1}, {0, 0, -1}};

	/** Morton index (x lowest bit, then y, then z) <-> coordinates: CS2 keeps and steps its voxels in this order. */
	static final int[] MX = new int[CELLS], MY = new int[CELLS], MZ = new int[CELLS];

	static {
		for (int m = 0; m < CELLS; m++) {
			MX[m] = compact(m);
			MY[m] = compact(m >> 1);
			MZ[m] = compact(m >> 2);
		}
	}

	private static int spread(int v) {
		int r = 0;
		for (int i = 0; i < 5; i++) r |= (v >> i & 1) << (3 * i);
		return r;
	}

	private static int compact(int m) {
		int r = 0;
		for (int i = 0; i < 5; i++) r |= (m >> (3 * i) & 1) << i;
		return r;
	}

	public static int morton(int x, int y, int z) {
		return spread(x) | spread(y) << 1 | spread(z) << 2;
	}

	static boolean inGrid(int x, int y, int z) {
		return x >= 0 && y >= 0 && z >= 0 && x < N && y < N && z < N;
	}

	/** The voxel grid's centre (blocks), on half-block boundaries. */
	public final Vec3 origin;
	/** Where the grenade went off (blocks). */
	public final Vec3 detonation;
	private final long[] solid = new long[CELLS / 64];
	private final long[][] active = new long[2][CELLS / 64];
	private final float[][] density = new float[2][CELLS];
	private final float[][] flow = new float[2][CELLS * 3];
	private final byte[] seedCount = new byte[CELLS];
	private final List<Seed> seeds = new ArrayList<>();
	private final List<Push> pushes = new ArrayList<>();
	private int cur;
	private boolean dissipating;
	/** Steps run so far. */
	private int steps;

	private static final class Seed {
		int x, y, z;
		float weight;

		Seed(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}

	/**
	 * An HE blast pushing the smoke: its reach is ticks x speed units, shrinking a step at a time; it moves seeds while
	 * ticks >= 29. FLAGGED: CS2's starting values weren't found (the entries come from its server); 45 x 10 units
	 * (450 units, the hole size used before) and gone after 4.5 s.
	 */
	private static final class Push {
		final double x, y, z;
		int ticks = 45;
		final float speed = 10;

		Push(double x, double y, double z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}

		float reach() {
			return ticks * speed;
		}
	}

	private SmokeVolume(Vec3 origin, Vec3 detonation) {
		this.origin = origin;
		this.detonation = detonation;
	}

	static double blocksPerVoxel() {
		return VOXEL / StrafeConfig.unitsPerBlock;
	}

	/** A voxel's centre in Minecraft blocks (CS2 axes: x east, y north, z up). */
	public Vec3 center(int x, int y, int z) {
		double s = blocksPerVoxel();
		return origin.add((x - 15.5) * s, (z - 15.5) * s, -(y - 15.5) * s);
	}

	/** A voxel centre relative to the grid centre, in CS2 units and axes. */
	private static double[] rel(int x, int y, int z) {
		return new double[] {(x - 16) * VOXEL + 10, (y - 16) * VOXEL + 10, (z - 16) * VOXEL + 10};
	}

	/** The voxel holding a point (blocks), or null outside the grid. */
	public int[] voxel(Vec3 p) {
		double s = blocksPerVoxel();
		int x = (int) Math.floor((p.x - origin.x) / s + 16), y = (int) Math.floor(-(p.z - origin.z) / s + 16), z = (int) Math.floor((p.y - origin.y) / s + 16);
		return inGrid(x, y, z) ? new int[] {x, y, z} : null;
	}

	public boolean solid(int x, int y, int z) {
		int m = morton(x, y, z);
		return (solid[m >> 6] >>> (m & 63) & 1) != 0;
	}

	/** This step's density at a voxel, 0..1 (CS2 draws density / 50, clamped). */
	public float density01(int x, int y, int z) {
		return Math.clamp(density[cur][morton(x, y, z)] / FULL, 0, 1);
	}

	/** Is this point (blocks) in smoke: its voxel's density at least `min` (0..1)? */
	public boolean contains(Vec3 p, float min) {
		int[] v = voxel(p);
		return v != null && !solid(v[0], v[1], v[2]) && density01(v[0], v[1], v[2]) >= min;
	}

	/**
	 * Is this point in smoke? FLAGGED: a tenth of full density counts (our threshold for line of sight, fires and
	 * bots; CS2's own checks weren't followed).
	 */
	public boolean contains(Vec3 p) {
		return contains(p, 0.1f);
	}

	public int steps() {
		return steps;
	}

	/** The seconds of smoke simulated so far. */
	public double seconds() {
		return steps * STEP_SECONDS;
	}

	public boolean dissipating() {
		return dissipating;
	}

	// ---------------------------------------------------------------- setup

	/** A smoke going off at `at` (blocks): solid voxels found, seeds placed. */
	public static SmokeVolume create(Level level, Vec3 at) {
		double half = blocksPerVoxel();
		Vec3 origin = new Vec3(Math.round(at.x / half) * half, Math.round(at.y / half) * half, Math.round(at.z / half) * half);
		SmokeVolume v = new SmokeVolume(origin, at);
		v.findSolid(level);
		v.placeSeeds();
		return v;
	}

	/** Every voxel whose box overlaps a block's collision box. */
	private void findSolid(Level level) {
		double s = blocksPerVoxel(), h = s / 2 - 1e-4;
		BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
		for (int m = 0; m < CELLS; m++) {
			Vec3 c = center(MX[m], MY[m], MZ[m]);
			AABB box = new AABB(c.x - h, c.y - h, c.z - h, c.x + h, c.y + h, c.z + h);
			boolean hit = false;
			for (int bx = (int) Math.floor(box.minX); bx <= (int) Math.floor(box.maxX) && !hit; bx++)
				for (int by = (int) Math.floor(box.minY); by <= (int) Math.floor(box.maxY) && !hit; by++)
					for (int bz = (int) Math.floor(box.minZ); bz <= (int) Math.floor(box.maxZ) && !hit; bz++) {
						bp.set(bx, by, bz);
						var shape = level.getBlockState(bp).getCollisionShape(level, bp);
						if (shape.isEmpty()) continue;
						for (AABB b : shape.toAabbs())
							if (b.move(bx, by, bz).intersects(box)) {
								hit = true;
								break;
							}
					}
			if (hit) solid[m >> 6] |= 1L << (m & 63);
		}
	}

	private boolean open(int x, int y, int z) {
		return inGrid(x, y, z) && !solid(x, y, z);
	}

	private static int pack(int x, int y, int z) {
		return (x & 255) | (y & 255) << 8 | (z & 255) << 16;
	}

	/** CS2's seed placement (see the class comment), from the detonation's voxel. */
	private void placeSeeds() {
		int[] start = voxel(detonation);
		if (start == null) return;
		if (solid(start[0], start[1], start[2])) { // resting on a block edge: the voxel above, else any open neighbour
			int[] up = {start[0], start[1], start[2] + 1};
			if (open(up[0], up[1], up[2])) start = up;
			else {
				int[] found = null;
				for (int[] d : DIR26)
					if (found == null && open(start[0] + d[0], start[1] + d[1], start[2] + d[2])) found = new int[] {start[0] + d[0], start[1] + d[1], start[2] + d[2]};
				if (found == null) return;
				start = found;
			}
		}
		int[] colMin = new int[N * N], colMax = new int[N * N];
		Arrays.fill(colMin, -1);
		Arrays.fill(colMax, -1);
		ArrayDeque<int[]> queue = new ArrayDeque<>();
		Set<Integer> visited = new HashSet<>();
		queue.add(start);
		visited.add(pack(start[0], start[1], start[2]));
		int placed = 0;
		while (!queue.isEmpty() && placed < 40) {
			int[] p = queue.poll();
			int sum = 0;
			for (int[] d : DIR26) {
				int x = p[0] + d[0], y = p[1] + d[1], z = p[2] + d[2];
				if (!inGrid(x, y, z)) continue;
				sum += seedCount[morton(x, y, z)];
				if (visited.add(pack(x, y, z)) && !solid(x, y, z)) queue.add(new int[] {x, y, z});
			}
			if (seedCount[morton(p[0], p[1], p[2])] != 0 || sum != 0) continue;
			// seeds steeply above the detonation (within ~37 degrees of straight up) go up 29 units
			double[] r = rel(p[0], p[1], p[2]);
			double len = Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
			double up = len < 1e-17 ? 1 : r[2] / len;
			if (up <= 0.8) up = 0;
			int tz = (int) ((r[2] + up * 29) * 0.05 + 16);
			int[] s = p;
			if (tz != p[2] && open(p[0], p[1], tz)) s = new int[] {p[0], p[1], tz};
			int col = p[0] * N + p[1];
			if (colMin[col] < 0 || s[2] < colMin[col]) colMin[col] = s[2];
			if (colMax[col] < 0 || colMax[col] < s[2] || colMax[col] > 32) colMax[col] = s[2];
			seeds.add(new Seed(s[0], s[1], s[2]));
			seedCount[morton(s[0], s[1], s[2])]++;
			placed++;
		}
		jitterTops(colMin, colMax);
		pourOverLedges(colMin);
		if (seeds.size() > 39) aroundTop();
	}

	/** Along each row, columns with the same top as the column before get their seed moved by 0, +1, -1, 0, ... voxels. */
	private void jitterTops(int[] colMin, int[] colMax) {
		int cycle = 1;
		for (int y = 0; y < N; y++) {
			int prev = 0xffff, empty = 0;
			for (int x = 0; x < N; x++) {
				int col = x * N + y, top = colMax[col];
				if (top < 0 || top >= 32) {
					if (++empty > 1) prev = 0xffff;
					continue;
				}
				int z = top;
				if (top == prev) {
					int nz = (z + cycle - 1) & 255;
					cycle = (cycle + 1) % 3;
					if (!inGrid(x, y, nz) || solid(x, y, nz)) continue;
					int m = morton(x, y, z), nm = morton(x, y, nz);
					if (seedCount[nm] == 0) {
						seedCount[m] = 0;
						seedCount[nm] = 1;
						for (Seed s : seeds)
							if (s.x == x && s.y == y && s.z == z) {
								s.z = nz;
								break;
							}
						int old = colMax[col];
						colMax[col] = nz;
						if (colMin[col] == old) colMin[col] = nz;
					}
					z = nz;
				}
				prev = z;
				empty = 0;
			}
		}
	}

	/**
	 * Seeds within 40 units sideways and 0..80 above the detonation go to the end of the list; the seeds are sorted by
	 * height; every column whose lowest seed is 4..8 open voxels above a floor gets seeds every second voxel down to
	 * it, each replacing the highest remaining seed.
	 */
	private void pourOverLedges(int[] colMin) {
		List<int[]> later = new ArrayList<>();
		for (int i = 0; i < seeds.size(); i++) {
			Seed s = seeds.get(i);
			double[] r = rel(s.x, s.y, s.z);
			double dz = Math.max(0, r[2]);
			if (Math.sqrt(r[0] * r[0] + r[1] * r[1]) < 40 && dz <= 80) {
				seedCount[morton(s.x, s.y, s.z)] = 0;
				later.add(new int[] {s.x, s.y, s.z});
				seeds.set(i, seeds.get(seeds.size() - 1));
				seeds.remove(seeds.size() - 1);
				i--;
			}
		}
		seeds.sort((a, b) -> a.z - b.z); // FLAGGED: CS2 sorts by height with its own sort (order of equal heights may differ)
		outer:
		for (int y = 0; y < N; y++)
			for (int x = 0; x < N; x++) {
				int z0 = colMin[x * N + y];
				if (z0 < 0 || z0 >= 32) continue;
				int low = z0;
				for (int k = 0; k < 9; k++) {
					int z = z0 - k - 1;
					if (z < 0 || solid(x, y, z)) break;
					low--;
				}
				int drop = z0 - low;
				if (drop >= 4 && drop <= 8)
					for (int z = z0 - 3; z >= low; z -= 2) {
						if (!seeds.isEmpty()) {
							Seed last = seeds.remove(seeds.size() - 1);
							seedCount[morton(last.x, last.y, last.z)] = 0;
						}
						later.add(new int[] {x, y, z});
					}
				if (later.size() > 42) break outer;
			}
		for (int[] c : later) {
			seeds.add(new Seed(c[0], c[1], c[2]));
			seedCount[morton(c[0], c[1], c[2])]++;
		}
	}

	/** Around the seed (within 60 units sideways) furthest above or below the detonation: one above it, and beside it. */
	private void aroundTop() {
		Seed top = null;
		double best = -1;
		for (Seed s : seeds) {
			double[] r = rel(s.x, s.y, s.z);
			if (r[0] * r[0] + r[1] * r[1] < 3600 && (top == null || Math.abs(r[2]) > best)) {
				best = Math.abs(r[2]);
				top = s;
			}
		}
		if (top == null) return;
		int x = top.x, y = top.y, z = top.z;
		if (!clear(x, y, z, 0, 0, 1)) return;
		seeds.add(new Seed(x, y, z + 1));
		if (clear(x, y, z, 1, 0, 0)) seeds.add(new Seed(x + 1, y, z));
		if (clear(x, y, z, -1, 0, 0)) seeds.add(new Seed(x - 1, y, z));
		if (clear(x, y, z, 0, 1, 0)) seeds.add(new Seed(x, y + 1, z));
		else if (clear(x, y, z, 0, -1, 0)) seeds.add(new Seed(x, y - 1, z));
	}

	/** A voxel and the next one in a direction are both open. */
	private boolean clear(int x, int y, int z, int dx, int dy, int dz) {
		return open(x, y, z) && open(x + dx, y + dy, z + dz);
	}

	// ---------------------------------------------------------------- steps

	/** An HE blast at `at` (blocks) pushes this smoke. */
	public void blast(Vec3 at) {
		double upb = StrafeConfig.unitsPerBlock;
		synchronized (pushes) {
			pushes.add(new Push((at.x - origin.x) * upb, -(at.z - origin.z) * upb, (at.y - origin.y) * upb));
		}
	}

	private boolean isActive(int f, int m) {
		return (active[f][m >> 6] >>> (m & 63) & 1) != 0;
	}

	private void activate(int f, int m) {
		active[f][m >> 6] |= 1L << (m & 63);
	}

	/** Runs the steps due by `seconds` after the detonation. */
	public void runUntil(double seconds) {
		while ((steps + 1) * STEP_SECONDS <= seconds) step();
	}

	/** One 0.1 s step (CS2's server smoke think). */
	public void step() {
		steps++;
		if (!dissipating && seconds() > DISSIPATE_SECONDS) dissipating = true;
		List<Push> ps;
		synchronized (pushes) {
			ps = new ArrayList<>(pushes);
		}
		inject(ps); // dissipating: empties the seeds' voxels once, then the seeds are gone
		if (dissipating) seeds.clear();
		int f = cur;
		for (int w = 0; w < CELLS / 64; w++) {
			long bits = active[f][w];
			while (bits != 0) {
				int b = Long.numberOfTrailingZeros(bits);
				bits &= bits - 1;
				update(w * 64 + b, ps);
			}
		}
		int nx = 1 - f;
		for (int w = 0; w < CELLS / 64; w++) {
			long bits = active[f][w];
			while (bits != 0) {
				int m = w * 64 + Long.numberOfTrailingZeros(bits);
				bits &= bits - 1;
				density[f][m] = density[nx][m];
				System.arraycopy(flow[nx], m * 3, flow[f], m * 3, 3);
			}
		}
		for (int w = 0; w < CELLS / 64; w++) {
			active[f][w] |= active[nx][w];
			active[nx][w] = active[f][w];
		}
		cur = nx;
		synchronized (pushes) {
			pushes.removeIf(p -> --p.ticks <= 0);
		}
	}

	/** The seeds pump smoke into their voxels (CS2's smoke volume step, first part). */
	private void inject(List<Push> ps) {
		int pumping = 0;
		for (Seed s : seeds) if (s.weight < 0.01f) pumping++;
		float boost = Math.max(1, (1 - pumping / 40f) * 3.5f);
		int f = cur, nx = 1 - f;
		for (Seed s : seeds) {
			double[] r = rel(s.x, s.y, s.z);
			boolean pushed = false;
			double px = r[0], py = r[1], pz = r[2];
			for (Push p : ps) {
				if (p.ticks < 29) continue;
				double dx = r[0] - p.x, dy = r[1] - p.y, dz = r[2] - p.z, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (d >= p.reach()) continue;
				if (d > 1e-17) {
					px += dx / d * 30;
					py += dy / d * 30;
					pz += dz / d * 30;
				}
				if (Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]) <= 80) s.weight = 0.5f; // the seed's own distance
				else {
					s.weight = 1;
					pushed = true;
				}
			}
			if (pushed) {
				int tx = (int) (px * 0.05 + 16), ty = (int) (py * 0.05 + 16), tz = (int) (pz * 0.05 + 16);
				if ((tx != s.x || ty != s.y || tz != s.z) && open(tx, ty, tz)) {
					seedCount[morton(s.x, s.y, s.z)]--;
					seedCount[morton(tx, ty, tz)]++;
					s.x = tx;
					s.y = ty;
					s.z = tz;
					r = rel(s.x, s.y, s.z);
				}
				s.weight = 1;
			}
			int m = morton(s.x, s.y, s.z);
			if (!dissipating) {
				double dist = Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
				if (dist <= 80) s.weight *= 0.8f;
				else {
					boolean fed = fed(s);
					if (s.weight > 0) s.weight = fed ? s.weight * 0.8f : 1;
					else if (!fed) s.weight = 1;
				}
				density[f][m] = Math.min(density[f][m] + (1 - s.weight) * INJECT, boost * FULL);
			} else {
				density[f][m] = 0;
				seedCount[m] = 0;
			}
			activate(f, m);
			density[nx][m] = density[f][m];
			System.arraycopy(flow[f], m * 3, flow[nx], m * 3, 3);
		}
	}

	/** Is the smoke around a seed thick enough for it to pump (its six neighbours' density / 6 above 0.2)? */
	private boolean fed(Seed s) {
		float sum = 0;
		for (int[] d : DIR6) {
			int x = s.x + d[0], y = s.y + d[1], z = s.z + d[2];
			if (!inGrid(x, y, z)) continue;
			int n = morton(x, y, z);
			if (isActive(cur, n)) sum += Math.clamp(density[cur][n] / FULL, 0, 1);
		}
		return sum / 6 > 0.2f;
	}

	/** One voxel's step (CS2's per-voxel smoke update). */
	private void update(int m, List<Push> ps) {
		int f = cur, nx = 1 - f;
		int x = MX[m], y = MY[m], z = MZ[m];
		if (solid(x, y, z)) {
			density[nx][m] = 0;
			return;
		}
		float room = 1 - Math.clamp(density[nx][m] / FULL, 0, 1);
		double[] r = rel(x, y, z);
		// HE blasts reaching this voxel: their average outward direction
		double ax = 0, ay = 0, az = 0;
		int reached = 0;
		for (Push p : ps) {
			double dx = r[0] - p.x, dy = r[1] - p.y, dz = r[2] - p.z, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (d < p.reach()) {
				if (d > 1e-17) {
					ax += dx / d;
					ay += dy / d;
					az += dz / d;
				}
				reached++;
			}
		}
		float[] around = new float[6];
		float inflow = 0;
		int walls = 0;
		float[] wall = new float[18];
		float[] fl = flow[f];
		for (int k = 0; k < 6; k++) {
			int[] d = DIR6[k];
			int qx = x + d[0], qy = y + d[1], qz = z + d[2];
			if (!inGrid(qx, qy, qz) || solid(qx, qy, qz)) {
				around[k] = 0;
				wall[walls * 3] = -d[0];
				wall[walls * 3 + 1] = -d[1];
				wall[walls * 3 + 2] = -d[2];
				walls++;
				continue;
			}
			int n = morton(qx, qy, qz);
			float dn = density[f][n];
			around[k] = dn;
			float weight = 1;
			switch (k) {
				case 0, 1, 2, 3 -> {
					// the neighbour's flow pointing back at this voxel pushes more across
					float toward = -(d[0] * fl[n * 3] + d[1] * fl[n * 3 + 1] + d[2] * fl[n * 3 + 2]);
					weight = toward > 0.2f ? 1.25f : 0.9f;
				}
				case 4 -> weight = 0.79f;
				default -> weight = 1.2f;
			}
			float moved = dn / 6 * room * weight;
			density[nx][n] = Math.max(0, density[nx][n] - moved);
			inflow += moved;
			if (!isActive(f, n) && density[f][m] > 5) activate(nx, n);
		}
		if (x == 0 || y == 0 || z == 0 || x == N - 1 || y == N - 1 || z == N - 1) { // the grid's edge drains
			density[nx][m] *= 0.3f;
			return;
		}
		density[nx][m] += inflow;
		double vx = around[0] - around[1], vy = around[2] - around[3], vz = 0;
		if (reached != 0) {
			double len = Math.sqrt(ax * ax + ay * ay + az * az);
			if (len > 1e-17) {
				ax /= len;
				ay /= len;
				az /= len;
			}
			if (Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]) < 80) density[nx][m] = Math.max(Math.min(density[nx][m], 15), 0);
			vx = ax;
			vy = ay;
			vz = az;
		}
		for (int k = 0; k < walls; k++) { // slide along walls
			double wx = wall[k * 3], wy = wall[k * 3 + 1], wz = wall[k * 3 + 2], t = -(vx * wx + vy * wy + vz * wz);
			vx += t * wx;
			vy += t * wy;
			vz += t * wz;
		}
		double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
		if (len < 1e-17 || len > 1e17) vx = vy = vz = 0;
		else {
			vx /= len;
			vy /= len;
			vz /= len;
		}
		float[] out = flow[nx];
		out[m * 3] = (float) vx;
		out[m * 3 + 1] = (float) vy;
		out[m * 3 + 2] = (float) vz;
		density[nx][m] = Math.max(0, density[nx][m] - (dissipating ? 0.5f : 1f) * 0.5f);
	}

	// ---------------------------------------------------------------- the texture

	/**
	 * This step's voxels as CS2's client writes its smoke texture (RGBA per voxel, x + 32 * (y + 32 * z)): R/B last
	 * frame's values moved over, G this frame's density (0..255 = 0..50) dilated into solid neighbours, A the exact
	 * density (0 in solid voxels). Returns the voxel bounds of the smoke (minX, minY, minZ, maxX, maxY, maxZ), or null
	 * if there is none.
	 */
	public int[] writeTexture(byte[] tex) {
		int f = cur;
		int[] b = {N, N, N, -1, -1, -1};
		Set<Integer> dilated = new HashSet<>();
		List<int[]> spread = new ArrayList<>();
		for (int w = 0; w < CELLS / 64; w++) {
			long bits = active[f][w];
			while (bits != 0) {
				int m = w * 64 + Long.numberOfTrailingZeros(bits);
				bits &= bits - 1;
				int x = MX[m], y = MY[m], z = MZ[m];
				int value = (int) (Math.clamp(density[f][m] / FULL, 0, 1) * 255) & 255;
				for (int[] d : DIR6) {
					int qx = x + d[0], qy = y + d[1], qz = z + d[2];
					if (!inGrid(qx, qy, qz) || !solid(qx, qy, qz)) continue;
					if (value != 0) grow(b, qx, qy, qz);
					if (dilated.add(qx | qy << 8 | qz << 16)) spread.add(new int[] {qx, qy, qz, value});
				}
				int o = (x + N * (y + N * z)) * 4;
				tex[o] = tex[o + 1];
				tex[o + 2] = tex[o + 3];
				tex[o + 1] = (byte) value;
				tex[o + 3] = (byte) value;
				if (value != 0) grow(b, x, y, z);
			}
		}
		for (int[] s : spread) {
			int o = (s[0] + N * (s[1] + N * s[2])) * 4;
			tex[o] = tex[o + 1];
			tex[o + 1] = (byte) s[3];
			tex[o + 2] = tex[o + 3];
			tex[o + 3] = 0;
		}
		return b[3] < 0 ? null : b;
	}

	private static void grow(int[] b, int x, int y, int z) {
		b[0] = Math.min(b[0], x);
		b[1] = Math.min(b[1], y);
		b[2] = Math.min(b[2], z);
		b[3] = Math.max(b[3], x);
		b[4] = Math.max(b[4], y);
		b[5] = Math.max(b[5], z);
	}
}
