package dev.strafemc.client.weapon;

import dev.strafemc.client.particle.Effects;
import dev.strafemc.client.particle.PWorld;
import dev.strafemc.client.particle.Snapshot;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.net.StrafePayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A fire's look, as CS2's client draws its infernos: one ground fire effect (inferno_fx/molotov_groundfire, or the
 * incendiary's) for the whole fire, fed with point lists ("snapshots") worked out from the flames the server sends:
 * <ul>
 * <li>each flame grows to a random size (70-90 units; incendiary 50-70) over 2 s and shrinks away over 2 s when it
 * goes;</li>
 * <li>CP10: the outline, two points per direction (15 directions, 24 degrees apart) a flame owns (no other flame is
 * nearer that way), pulled in from walls traced once per flame;</li>
 * <li>CP9: where the outline meets a wall (the flames that climb it);</li>
 * <li>CP11: 15 fill points per flame at random distances, kept off walls and one per 30-unit cell;</li>
 * <li>CP12: the same next to walls (the scorch and wall layers), CP1: one point per flame.</li>
 * </ul>
 * Points are refreshed ten times a second while anything changes. In simulation space (Source units, z up).
 */
public final class InfernoClient {
	private InfernoClient() {}

	static final String MOLOTOV = dev.strafemc.weapon.GrenadeEffects.MOLOTOV_FIRE_EFFECT, INCENDIARY = dev.strafemc.weapon.GrenadeEffects.INCENDIARY_FIRE_EFFECT,
			REMNANT = dev.strafemc.weapon.GrenadeEffects.FIRE_REMNANT_EFFECT;
	static final int MAX_DRAWABLES = 64, DIRS = 15;
	/** Flames grow / shrink by half their size a second; the outline and fill reach at least 60 units. */
	static final float GROWTH = 0.5f, MIN_REACH = 60;
	/** Fill points stay this far from walls (outline: half that); outline points sit this high over the flame. */
	static final float WALL_GAP = 10, OUTLINE_HEIGHT = 40;
	/** A networked flame and a drawn one are the same flame within this distance. */
	static final float SAME = 12;
	/** Fill points: one per cell of this size (FLAGGED: from the client's grid). */
	static final float CELL = 30;
	/** CP3: duration, height (FLAGGED: ours), reach of ground traces (x1.25 / x2 in the effects). */
	static final float CP3_HEIGHT = 128, CP3_TRACE = 70;
	static final int GROWING = 0, BURNING = 1, DYING = 2, DEAD = 3, UNSEEN = 4;

	static final class Drawable {
		final Vector3f pos, parent, normal;
		int state = GROWING;
		float since, size;
		final float maxSize, rand;
		final boolean flag;
		boolean traced;
		/** Per direction: outline reach (fraction of maxSize, 5 units off a wall) and fill reach (10 units off). */
		final float[] outline = new float[DIRS], fill = new float[DIRS];
		float minOutline = 1;
		/** Directions this flame owns (no other flame nearer that way). */
		int owned;
		/** One wall plane (normal, d) when the walls it found agree; none when they don't. */
		Vector3f plane;
		float planeD;
		boolean noPlane;

		Drawable(Vector3f pos, Vector3f parent, Vector3f normal, float now, float maxSize, float rand, boolean flag) {
			this.pos = pos;
			this.parent = parent;
			this.normal = normal;
			this.since = now;
			this.maxSize = maxSize;
			this.rand = rand;
			this.flag = flag;
			java.util.Arrays.fill(outline, 1);
			java.util.Arrays.fill(fill, 1);
		}

		float reach() {
			return Math.max(MIN_REACH, size);
		}
	}

	static final class Fire {
		final int id;
		final boolean incendiary;
		final Vec3 originMc;
		final Vector3f origin;
		final float lifetime;
		final float startedAt;
		final List<Drawable> drawables = new ArrayList<>();
		Effects.Handle fx;
		/** The last points handed to the effect (the remnant takes CP1, CP9 and CP11 when the fire ends). */
		Snapshot cp1, cp9, cp11;
		boolean changed = true, ended, caughtUp;
		float nextThink;

		Fire(StrafePayloads.InfernoState m, float now) {
			id = m.id();
			incendiary = m.incendiary();
			originMc = new Vec3(m.x(), m.y(), m.z());
			origin = Effects.toSim(originMc);
			lifetime = m.lifetime();
			startedAt = now - m.age();
		}
	}

	private static final Map<Integer, Fire> FIRES = new HashMap<>();
	private static final Random RANDOM = new Random();

	static float now() {
		return System.nanoTime() / 1e9f;
	}

	/** Loads the fire effects ahead of time (a fire's effect can't wait for its files). */
	public static void warm() {
		Effects.warm(MOLOTOV);
		Effects.warm(INCENDIARY);
		Effects.warm(REMNANT);
	}

	public static void onState(StrafePayloads.InfernoState m) {
		float now = now();
		synchronized (FIRES) {
			Fire f = FIRES.get(m.id());
			if (f == null) {
				if (m.end() != StrafePayloads.InfernoState.BURNING) return; // never saw it burn
				f = new Fire(m, now);
				FIRES.put(m.id(), f);
			}
			synchronize(f, m, now);
			if (m.end() != StrafePayloads.InfernoState.BURNING && !f.ended) end(f);
		}
	}

	/** CS2's SynchronizeDrawables: new flames start growing, flames gone out die at once, flames no longer sent shrink away. */
	private static void synchronize(Fire f, StrafePayloads.InfernoState m, float now) {
		for (Drawable d : f.drawables) if (d.state == BURNING) d.state = UNSEEN;
		float[] a = m.fires();
		int n = StrafePayloads.InfernoState.FLOATS;
		for (int i = 0; i + n <= a.length; i += n) {
			Vector3f pos = Effects.toSim(f.originMc.add(a[i], a[i + 1], a[i + 2]));
			Drawable d = null;
			for (Drawable q : f.drawables)
				if (Math.abs(q.pos.x - pos.x) <= SAME && Math.abs(q.pos.y - pos.y) <= SAME && Math.abs(q.pos.z - pos.z) <= SAME) {
					d = q;
					q.pos.set(pos);
					break;
				}
			boolean burning = a[i + 9] != 0;
			if (!burning) {
				if (d != null && d.state != DEAD) {
					d.state = DEAD;
					f.changed = true;
				}
				continue;
			}
			if (d == null) {
				if (f.drawables.size() >= MAX_DRAWABLES) continue;
				Vector3f parent = Effects.toSim(f.originMc.add(a[i + 3], a[i + 4], a[i + 5]));
				Vector3f normal = new Vector3f(a[i + 6], -a[i + 8], a[i + 7]);
				float max = f.incendiary ? 50 + RANDOM.nextFloat() * 20 : 70 + RANDOM.nextFloat() * 20;
				f.drawables.add(new Drawable(pos, parent, normal, now, max, 0.04f + RANDOM.nextFloat() * 0.02f, RANDOM.nextInt(101) < 50));
				f.changed = true;
				Vec3 at = Effects.fromSim(pos);
				if (f.drawables.size() > 1) dev.strafemc.client.sound.CsSounds.play("Inferno.Fire.Ignite", at.x, at.y, at.z, 1.0);
			} else if (d.state == UNSEEN) d.state = BURNING;
		}
		for (Drawable d : f.drawables)
			if (d.state == UNSEEN) {
				d.state = DYING;
				d.since = now;
				f.changed = true;
			}
	}

	/** The fire is over: its effect plays its end and the remnant (smouldering scorch) takes over. */
	private static void end(Fire f) {
		f.ended = true;
		if (f.fx != null) {
			Effects.Handle remnant = Effects.driven(REMNANT, f.originMc, 30);
			if (remnant != null) {
				remnant.controlPointValue(3, new Vector3f(f.lifetime, CP3_HEIGHT, CP3_TRACE));
				remnant.snapshot(1, f.cp1);
				remnant.snapshot(9, f.cp9);
				remnant.snapshot(11, f.cp11);
			}
			f.fx.endCap();
		}
	}

	/** Every client tick: flames grow and shrink, the effect starts once loaded, points refresh ten times a second. */
	public static void tick() {
		float now = now();
		synchronized (FIRES) {
			FIRES.values().removeIf(f -> {
				if (f.ended) return f.fx == null || !f.fx.alive();
				if (now - f.startedAt > f.lifetime + 30) { // never heard the end: let it go
					if (f.fx != null) f.fx.endCap();
					return true;
				}
				return false;
			});
			for (Fire f : FIRES.values()) {
				if (f.ended) continue;
				if (now < f.nextThink) continue;
				f.nextThink = now + 0.1f;
				think(f, now);
			}
		}
	}

	private static void think(Fire f, float now) {
		boolean sizes = false;
		for (Drawable d : f.drawables) {
			float before = d.size;
			if (d.state == GROWING) {
				d.size = (now - d.since) * d.maxSize * GROWTH;
				if (d.size > d.maxSize) {
					d.size = d.maxSize;
					d.state = BURNING;
					d.since = now;
				}
			} else if (d.state == DYING) {
				d.size = d.maxSize - d.maxSize * GROWTH * (now - d.since);
				if (d.size <= 0) {
					d.size = 0;
					d.state = DEAD;
				}
			}
			if (d.size != before) sizes = true;
		}
		if (f.fx == null) {
			f.fx = Effects.driven(f.incendiary ? INCENDIARY : MOLOTOV, f.originMc, 10);
			if (f.fx == null) return; // still loading
			f.fx.controlPointValue(3, new Vector3f(f.lifetime, CP3_HEIGHT, CP3_TRACE));
			f.changed = true;
		}
		if (!sizes && !f.changed) return;
		if (sizes || f.changed) ownership(f);
		Snapshot[] outline = outline(f);
		f.fx.snapshot(10, outline[0]);
		f.fx.snapshot(9, outline[1]);
		f.cp9 = outline[1];
		fill(f);
		f.changed = false;
		if (!f.caughtUp) { // CS2 runs a fire's effect ahead to its age (someone who arrives while it burns)
			f.caughtUp = true;
			float behind = now - f.startedAt;
			if (behind > 0.25f) f.fx.skip(Math.min(behind, f.lifetime));
		}
	}

	/** Which of the 15 directions each flame owns: the point that far out (its reach) is nearest to it. */
	private static void ownership(Fire f) {
		List<Drawable> ds = f.drawables;
		for (int i = 0; i < ds.size(); i++) {
			Drawable d = ds.get(i);
			d.owned = 0;
			float r = d.reach();
			for (int k = 0; k < DIRS; k++) {
				double a = Math.toRadians(360.0 / DIRS * k);
				float x = d.pos.x + (float) Math.cos(a) * r, y = d.pos.y + (float) Math.sin(a) * r, z = d.pos.z;
				int best = -1;
				float bestD = Float.MAX_VALUE;
				for (int j = 0; j < ds.size(); j++) {
					Drawable o = ds.get(j);
					float dist = (float) Math.sqrt((x - o.pos.x) * (x - o.pos.x) + (y - o.pos.y) * (y - o.pos.y) + (z - o.pos.z) * (z - o.pos.z));
					if (dist < bestD) {
						bestD = dist;
						best = j;
					}
				}
				if (best == i) d.owned |= 1 << k;
			}
		}
	}

	/** CS2's outline pass: walls traced once per flame, then outline (CP10) and wall-climbing (CP9) points. */
	private static Snapshot[] outline(Fire f) {
		PWorld w = Effects.world();
		List<float[]> out = new ArrayList<>(), climb = new ArrayList<>();
		boolean walled = false; // CS2 keeps this across flames once a wall was hit
		Random rnd = new Random();
		for (Drawable d : f.drawables) {
			if (d.state == DEAD) continue;
			float reach = d.reach(), max = d.maxSize;
			if (!d.traced) {
				PWorld.Hit up = w.trace(d.pos, new Vector3f(d.pos).add(0, 0, 60), false);
				float upFrac = up == null ? 1 : up.fraction();
				boolean upBlocked = up != null;
				Vector3f planeN = null, planeP = null;
				int hits = 0;
				for (int k = 0; k < DIRS; k++) {
					double a = Math.toRadians(24.0 * k);
					float cx = (float) Math.cos(a), cy = (float) Math.sin(a);
					float startZ = upFrac * 30 * (walled ? 1 : 0) * (upBlocked ? 1 : 0) + d.pos.z + 30;
					float endZ = Math.max(upFrac, 0.5f) * 30 * (walled ? 1 : 0) + startZ;
					Vector3f from = new Vector3f(d.pos.x, d.pos.y, startZ), to = new Vector3f(d.pos.x + cx * max, d.pos.y + cy * max, endZ);
					PWorld.Hit h = w.trace(from, to, false);
					float frac = h == null ? 1 : h.fraction();
					if (h != null && h.inside()) { // started in a wall: no outline for this flame
						java.util.Arrays.fill(d.outline, 0);
						java.util.Arrays.fill(d.fill, 0);
						d.owned = 0;
						break;
					}
					walled |= frac < 1;
					float fillFrac = 1 - WALL_GAP / reach, outFrac = frac;
					if (frac < 1) {
						Vector3f n = h.normal();
						float facing = -(cx * n.x + cy * n.y) * max; // how squarely it meets the wall, in units
						fillFrac = pullBack(frac, WALL_GAP, facing);
						outFrac = pullBack(frac, WALL_GAP * 0.5f, facing);
						d.minOutline = Math.min(d.minOutline, outFrac);
						Vector3f p = new Vector3f(from).lerp(to, frac);
						if (planeN == null) {
							planeN = new Vector3f(n);
							planeP = p;
						} else if (new Vector3f(planeN).normalize().cross(n).length() > 0.1f) d.noPlane = true; // FLAGGED: CS2's tolerance
						else {
							planeN.add(n);
							planeP.add(p);
						}
						hits++;
					}
					d.outline[k] = outFrac;
					d.fill[k] = fillFrac;
				}
				if (planeN != null && !d.noPlane) {
					d.plane = planeN.normalize();
					planeP.div(hits);
					d.planeD = -d.plane.dot(planeP);
				}
				d.traced = true;
			}
			for (int k = 0; k < DIRS; k++) {
				if ((d.owned >> k & 1) == 0) continue;
				double a = Math.toRadians(24.0 * k), b = Math.toRadians(12.0 * (2 * k - 1));
				float r = Math.min(reach, d.outline[k] * max);
				float z = d.pos.z + OUTLINE_HEIGHT;
				out.add(new float[] {d.pos.x + (float) Math.cos(a) * r, d.pos.y + (float) Math.sin(a) * r, z, (float) Math.cos(a), (float) Math.sin(a), 0, reach});
				out.add(new float[] {d.pos.x + (float) Math.cos(b) * r, d.pos.y + (float) Math.sin(b) * r, z, (float) Math.cos(b), (float) Math.sin(b), 0, reach});
				if (reach > r) { // the wall cuts this way short: flames climb it
					Vector3f n = new Vector3f((float) Math.cos(a), (float) Math.sin(a), rnd.nextFloat() / Math.max(1, r)).normalize();
					climb.add(new float[] {d.pos.x, d.pos.y, z, n.x, n.y, n.z, reach});
				}
			}
		}
		return new Snapshot[] {points(out), points(climb)};
	}

	/** CS2: a reach that ends `frac` of the way at a wall, pulled back to keep `gap` units off it. */
	private static float pullBack(float frac, float gap, float facing) {
		return frac * facing > gap ? frac - gap / facing : frac;
	}

	private static Snapshot points(List<float[]> pts) {
		Snapshot s = new Snapshot(pts.size());
		Vector3f[] pos = s.vectors(0), nor = s.vectors(21);
		float[] rad = s.scalars(3);
		for (int i = 0; i < pts.size(); i++) {
			float[] p = pts.get(i);
			pos[i].set(p[0], p[1], p[2]);
			nor[i].set(p[3], p[4], p[5]);
			rad[i] = p[6];
		}
		return s;
	}

	/** CS2's fill pass: CP11 fill points, CP12 points by walls (or each flame's centre), CP1 one point per flame. */
	private static void fill(Fire f) {
		List<Drawable> ds = f.drawables;
		int n = ds.size();
		Snapshot cp1 = new Snapshot(n), cp11 = new Snapshot(n * DIRS), cp12 = new Snapshot(n * DIRS);
		Vector3f origin = f.origin;
		for (Snapshot s : new Snapshot[] {cp1, cp11, cp12}) for (Vector3f v : s.vectors(0)) v.set(origin); // unused points sit at the start
		Set<Long> cells = new HashSet<>(), cells2 = new HashSet<>();
		int i11 = 0, i12 = 0;
		for (int idx = n - 1; idx >= 0; idx--) { // CS2 walks its flames from the last
			Drawable d = ds.get(idx);
			Random rnd = new Random(idx);
			Vector3f spread = new Vector3f(d.pos).sub(d.parent);
			if (spread.lengthSquared() > 0) spread.normalize();
			float reach = d.reach(), r0 = reach, max = d.maxSize;
			boolean clear = d.state != DEAD;
			if (clear) for (float o : d.outline) if (o < 1) clear = false;
			if (d.state == DEAD) clear = true;
			for (int k = 0; k < DIRS; k++) {
				double a = Math.toRadians(24.0 * k);
				reach = Math.min(reach, d.fill[k] * max);
				float u = rnd.nextFloat();
				float dist = (float) Math.sqrt(0.1 + 0.55 * Math.pow(u, 0.9)) * reach;
				float x = d.pos.x + (float) Math.cos(a) * dist, y = d.pos.y + (float) Math.sin(a) * dist, z = d.pos.z;
				float wall = d.plane != null ? d.plane.x * x + d.plane.y * y + d.plane.z * z + d.planeD : Float.MAX_VALUE;
				int slot11 = i11++;
				if (d.state == DEAD) continue;
				boolean keep = wall > WALL_GAP && (cells.add(cell(x, y)) || cells2.add(cell(x * 0.71f - y * 0.7f, x * 0.72f + y * 0.715f)));
				int slot12 = clear ? -1 : i12++;
				if (!keep) continue;
				float scratch = Math.min(wall, reach - dist);
				set(cp11, slot11, x, y, z, spread, r0, d.fill[k], scratch);
				if (slot12 >= 0) set(cp12, slot12, x, y, z, spread, r0, d.fill[k], scratch);
			}
			if (clear) { // its centre, then empty points
				set(cp12, i12, d.pos.x, d.pos.y, d.pos.z, spread, reach, 1, reach);
				i12 += DIRS;
			}
			// CS2 keeps the flame's position relative to the fire in CP1's normal
			if (d.state != DEAD) set(cp1, n - 1 - idx, d.pos.x, d.pos.y, d.pos.z, new Vector3f(d.pos).sub(origin), reach, 0, d.minOutline);
		}
		f.fx.snapshot(1, cp1);
		f.fx.snapshot(11, cp11);
		f.fx.snapshot(12, cp12);
		f.cp1 = cp1;
		f.cp11 = cp11;
	}

	private static long cell(float x, float y) {
		return (long) Math.floor(x / CELL) << 32 ^ ((long) Math.floor(y / CELL) & 0xFFFFFFFFL);
	}

	private static void set(Snapshot s, int i, float x, float y, float z, Vector3f normal, float radius, float alpha, float scratch) {
		if (i >= s.count) return;
		s.vectors(0)[i].set(x, y, z);
		s.vectors(21)[i].set(normal);
		s.scalars(3)[i] = radius;
		s.scalars(7)[i] = alpha;
		s.scalars(18)[i] = scratch;
	}

	/** Where flames burn (Minecraft positions), for the stand-in flames of players without CS2's effects. */
	public static List<Vec3> flames() {
		List<Vec3> out = new ArrayList<>();
		synchronized (FIRES) {
			for (Fire f : FIRES.values())
				if (!f.ended) for (Drawable d : f.drawables) if (d.state != DEAD) out.add(Effects.fromSim(d.pos));
		}
		return out;
	}

	/** Debugging: each fire's flames relative to its start (units), with state and size. */
	public static List<String> describe() {
		List<String> out = new ArrayList<>();
		synchronized (FIRES) {
			for (Fire f : FIRES.values()) {
				out.add("fire " + f.id + (f.incendiary ? " incendiary" : " molotov") + " flames " + f.drawables.size());
				if (f.cp11 != null) {
					int used = 0;
					float lx = 1e9f, hx = -1e9f, ly = 1e9f, hy = -1e9f;
					for (int i = 0; i < f.cp11.count; i++) {
						if (f.cp11.scalars(18)[i] <= 0) continue;
						used++;
						Vector3f p = f.cp11.vectors(0)[i];
						lx = Math.min(lx, p.x - f.origin.x);
						hx = Math.max(hx, p.x - f.origin.x);
						ly = Math.min(ly, p.y - f.origin.y);
						hy = Math.max(hy, p.y - f.origin.y);
					}
					out.add(String.format("  cp11 %d/%d x %.0f..%.0f y %.0f..%.0f", used, f.cp11.count, lx, hx, ly, hy));
				}
				for (Drawable d : f.drawables)
					out.add(String.format("  %.0f %.0f %.0f state %d size %.0f/%.0f owned %s minOutline %.2f", d.pos.x - f.origin.x, d.pos.y - f.origin.y,
							d.pos.z - f.origin.z, d.state, d.size, d.maxSize, Integer.toBinaryString(d.owned), d.minOutline));
			}
		}
		return out;
	}

	public static void clear() {
		synchronized (FIRES) {
			FIRES.clear();
		}
	}
}
