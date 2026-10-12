package dev.strafemc.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lag compensation ("rollback"), the server half: where every living entity near a player was over the last 2 seconds,
 * one snapshot per server tick. A shot is checked against the boxes as they were at the tick the shooter was looking at
 * (CS-style: the shooter's screen always shows others a bit in the past), never more than the server's max rewind.
 */
public final class LagHistory {
	/** A box and eye height (crouching changes it) at the end of a server tick. */
	public record Snap(int tick, AABB box, double eyeHeight) {}

	/** One box found along a rewound ray. */
	public record Hit(Entity entity, Vec3 at, AABB box, double eyeHeight, float tick) {}

	private static final int KEEP_TICKS = 40;
	/** Entities this far from any player aren't recorded (no one can be shooting at them). */
	private static final double RANGE = 128;
	private static final Map<Integer, ArrayDeque<Snap>> HISTORY = new HashMap<>();

	private LagHistory() {}

	/** End of every server tick: record. */
	public static void tick(MinecraftServer server) {
		int t = server.getTickCount();
		Set<Integer> seen = new HashSet<>();
		for (ServerLevel level : server.getAllLevels())
			for (ServerPlayer p : level.players())
				for (Entity e : level.getEntities((Entity) null, p.getBoundingBox().inflate(RANGE), e -> e instanceof LivingEntity && e.isAlive())) {
					if (!seen.add(e.getId())) continue;
					AABB b = e.getBoundingBox();
					ArrayDeque<Snap> q = HISTORY.computeIfAbsent(e.getId(), k -> new ArrayDeque<>());
					q.addLast(new Snap(t, b, e.getEyeY() - b.minY));
					while (q.size() > KEEP_TICKS) q.removeFirst();
				}
		for (Iterator<Map.Entry<Integer, ArrayDeque<Snap>>> it = HISTORY.entrySet().iterator(); it.hasNext(); ) {
			ArrayDeque<Snap> q = it.next().getValue();
			if (q.isEmpty() || q.peekLast().tick() < t - KEEP_TICKS) it.remove();
		}
	}

	public static void clear() {
		HISTORY.clear();
	}

	/** The entity's box at a (fractional) tick, interpolated between snapshots; null if it wasn't recorded then. */
	public static Snap at(int id, float tick) {
		ArrayDeque<Snap> q = HISTORY.get(id);
		if (q == null || q.isEmpty()) return null;
		Snap prev = null;
		for (Snap s : q) {
			if (s.tick() >= tick) {
				if (prev == null || s.tick() == tick) return s.tick() - tick <= 1 ? s : null;
				double a = (tick - prev.tick()) / (double) (s.tick() - prev.tick());
				return new Snap(s.tick(), lerp(prev.box(), s.box(), a), prev.eyeHeight() + (s.eyeHeight() - prev.eyeHeight()) * a);
			}
			prev = s;
		}
		return tick - q.peekLast().tick() <= 1 ? q.peekLast() : null;
	}

	/** First box along eye -> end at the given tick (closest hit), among recorded entities except the shooter. */
	public static Hit trace(ServerLevel level, Entity shooter, Vec3 eye, Vec3 end, float tick) {
		Hit best = null;
		double bestD = Double.MAX_VALUE;
		for (int id : HISTORY.keySet()) {
			if (id == shooter.getId()) continue;
			Snap s = at(id, tick);
			if (s == null) continue;
			Optional<Vec3> p = s.box().clip(eye, end);
			if (p.isEmpty()) continue;
			double d = p.get().distanceToSqr(eye);
			if (d >= bestD) continue;
			Entity e = level.getEntity(id);
			if (e == null || !e.isAlive() || e.isSpectator()) continue;
			best = new Hit(e, p.get(), s.box(), s.eyeHeight(), tick);
			bestD = d;
		}
		return best;
	}

	/**
	 * The shooter's own claim, checked: does the ray pass through that entity's box at any tick in [from, to]
	 * (inflated by slack blocks)? Covers how differently Minecraft clients show entities (players update every 2 ticks,
	 * most mobs every 3, plus smoothing). Returns the earliest-in-path hit, or null.
	 */
	public static Hit claim(ServerLevel level, int id, Vec3 eye, Vec3 end, float from, float to, double slack) {
		Entity e = level.getEntity(id);
		if (e == null || !e.isAlive() || e.isSpectator()) return null;
		Hit best = null;
		for (float t = from; t <= to + 1e-3f; t += 0.5f) {
			Snap s = at(id, t);
			if (s == null) continue;
			Optional<Vec3> p = s.box().inflate(slack).clip(eye, end);
			if (p.isEmpty()) continue;
			if (best == null || p.get().distanceToSqr(eye) < best.at().distanceToSqr(eye)) best = new Hit(e, p.get(), s.box(), s.eyeHeight(), t);
		}
		return best;
	}

	private static AABB lerp(AABB a, AABB b, double t) {
		return new AABB(a.minX + (b.minX - a.minX) * t, a.minY + (b.minY - a.minY) * t, a.minZ + (b.minZ - a.minZ) * t,
				a.maxX + (b.maxX - a.maxX) * t, a.maxY + (b.maxY - a.maxY) * t, a.maxZ + (b.maxZ - a.maxZ) * t);
	}
}
