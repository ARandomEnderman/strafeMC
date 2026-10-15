package dev.strafemc.weapon;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A bot's way somewhere (A* over the blocks a player can stand in): flat steps in 8 directions (diagonals only past
 * two free sides), one block up (a jump), down up to three. Where it can't find a way (too far, closed in), the
 * nearest reachable spot toward the goal.
 */
final class BotPath {
	private static final int MAX_NODES = 4000, MAX_DROP = 3;

	private BotPath() {}

	private record Node(BlockPos pos, double g, double f, Node from) {}

	/** Feet positions from start to (near) goal, or empty. */
	static List<BlockPos> find(ServerLevel level, BlockPos start, BlockPos goal) {
		start = ground(level, start);
		if (start == null) return List.of();
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
		Map<BlockPos, Double> best = new HashMap<>();
		Node first = new Node(start, 0, h(start, goal), null), closest = first;
		open.add(first);
		best.put(start, 0.0);
		int n = 0;
		while (!open.isEmpty() && n++ < MAX_NODES) {
			Node c = open.poll();
			if (c.g > best.getOrDefault(c.pos, Double.MAX_VALUE)) continue;
			if (h(c.pos, goal) < h(closest.pos, goal)) closest = c;
			if (c.pos.distManhattan(goal) <= 1) {
				closest = c;
				break;
			}
			for (int dx = -1; dx <= 1; dx++)
				for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dz == 0) continue;
					boolean diag = dx != 0 && dz != 0;
					if (diag && (!free(level, c.pos.offset(dx, 0, 0)) || !free(level, c.pos.offset(0, 0, dz)))) continue;
					BlockPos next = step(level, c.pos, dx, dz);
					if (next == null) continue;
					double g = c.g + (diag ? 1.414 : 1) + (next.getY() > c.pos.getY() ? 0.6 : 0) + (next.getY() < c.pos.getY() ? 0.2 : 0);
					if (g >= best.getOrDefault(next, Double.MAX_VALUE)) continue;
					best.put(next, g);
					open.add(new Node(next, g, g + h(next, goal), c));
				}
		}
		List<BlockPos> out = new ArrayList<>();
		for (Node c = closest; c != null; c = c.from) out.add(c.pos);
		Collections.reverse(out);
		return out;
	}

	private static double h(BlockPos a, BlockPos b) {
		return Math.sqrt(a.distSqr(b));
	}

	/** Where moving one block over from feet position p lands: level, one up (headroom), or down a few; else null. */
	private static BlockPos step(ServerLevel level, BlockPos p, int dx, int dz) {
		BlockPos side = p.offset(dx, 0, dz);
		if (standable(level, side)) return side;
		BlockPos up = side.above();
		if (standable(level, up) && free(level, p.above(2))) return up;
		if (!free(level, side) || !free(level, side.above())) return null;
		for (int d = 1; d <= MAX_DROP; d++) {
			BlockPos down = side.below(d);
			if (standable(level, down)) return down;
			if (!free(level, down)) return null;
		}
		return null;
	}

	/** A player fits here (two free blocks) on something solid. */
	static boolean standable(ServerLevel level, BlockPos p) {
		return free(level, p) && free(level, p.above()) && !level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty()
				&& level.getFluidState(p).isEmpty();
	}

	private static boolean free(ServerLevel level, BlockPos p) {
		return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
	}

	/** The standable position at or just below p (feet in the air for a moment), or null. */
	private static BlockPos ground(ServerLevel level, BlockPos p) {
		for (int d = 0; d <= 3; d++) if (standable(level, p.below(d))) return p.below(d);
		return standable(level, p.above()) ? p.above() : null;
	}
}
