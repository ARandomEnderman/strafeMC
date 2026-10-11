package dev.strafemc.client.movement;

import java.util.List;

/**
 * Swept axis-aligned hull vs convex solids (boxes and stair ramps), Source TraceHull-style:
 * returns the fraction of the move that is free, the plane normal hit, and start-solid.
 * Separating-axis sweep: the set of axes per solid covers every face/edge-cross direction of hull+solid.
 * All coordinates in Hammer units, Minecraft axes (y up).
 */
public final class HullTrace {
	/** Source DIST_EPSILON: we stop this far short of surfaces so the next trace doesn't start touching. */
	public static final double DIST_EPSILON = 0.03125;
	private static final double TOUCH = 1e-6;

	/** Convex solid: separating axes (unit) + its vertices. */
	public record Solid(double[][] axes, double[][] verts) {
		static final double[][] BOX_AXES = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};

		public static Solid box(double x0, double y0, double z0, double x1, double y1, double z1) {
			return new Solid(BOX_AXES, new double[][] {
					{x0, y0, z0}, {x1, y0, z0}, {x0, y1, z0}, {x1, y1, z0},
					{x0, y0, z1}, {x1, y0, z1}, {x0, y1, z1}, {x1, y1, z1}});
		}

		/**
		 * 45-degree ramp prism filling a block of size s at (bx,by,bz), rising toward direction (fx,fz)
		 * (a unit cardinal vector). Low edge at the side opposite (fx,fz), high edge on the (fx,fz) side.
		 */
		public static Solid ramp(double bx, double by, double bz, double s, int fx, int fz) {
			return ramp(bx, by, bz, s, s, s, fx, fz);
		}

		/**
		 * Ramp prism with its own depth (run, along the rise direction) and height (rise); width across is s (a merged row
		 * of ramps passes the whole row's width).
		 * Steeper than 45 degrees (rise > run) means a normal.y below sv_standable_normal: surfable, not walkable.
		 */
		public static Solid ramp(double bx, double by, double bz, double s, double run, double rise, int fx, int fz) {
			// local u along rise direction (0..s), w across; map back to world
			double[][] v = new double[6][];
			double[][] local = {{0, 0, 0}, {run, 0, 0}, {run, rise, 0}, {0, 0, s}, {run, 0, s}, {run, rise, s}}; // u, y, w
			for (int i = 0; i < 6; i++) {
				double u = local[i][0], y = local[i][1], w = local[i][2];
				double x, z;
				if (fx != 0) {
					x = fx > 0 ? bx + u : bx + run - u;
					z = bz + w;
				} else {
					z = fz > 0 ? bz + u : bz + run - u;
					x = bx + w;
				}
				v[i] = new double[] {x, by + y, z};
			}
			double nl = Math.hypot(rise, run);
			double[] n = {-fx * rise / nl, run / nl, -fz * rise / nl}; // ramp surface normal, facing up and toward the low side
			return new Solid(new double[][] {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}, n}, v);
		}
	}

	/** True if the hull at (x,y,z) overlaps any solid (deeper than touching). */
	public static boolean overlaps(double x, double y, double z, double hw, double h, List<Solid> solids) {
		double cx = x, cy = y + h / 2, cz = z, hh = h / 2;
		outer:
		for (Solid s : solids) {
			for (double[] a : s.axes) {
				double rad = hw * Math.abs(a[0]) + hh * Math.abs(a[1]) + hw * Math.abs(a[2]);
				double p0 = cx * a[0] + cy * a[1] + cz * a[2];
				double smin = Double.MAX_VALUE, smax = -Double.MAX_VALUE;
				for (double[] q : s.verts) {
					double d = q[0] * a[0] + q[1] * a[1] + q[2] * a[2];
					smin = Math.min(smin, d);
					smax = Math.max(smax, d);
				}
				if (p0 <= smin - rad + TOUCH || p0 >= smax + rad - TOUCH) continue outer;
			}
			return true;
		}
		return false;
	}

	public static final class Result {
		public double fraction = 1, nx, ny, nz;
		public boolean startSolid, hit;
		public double ex, ey, ez; // end position (feet)
	}

	/**
	 * Sweeps a hull (feet at x,y,z; half width hw; height h) by (dx,dy,dz) against solids.
	 * Solids the hull already overlaps (deeper than touching) are ignored so the player can always get out.
	 */
	public static Result trace(double x, double y, double z, double hw, double h, double dx, double dy, double dz, List<Solid> solids) {
		Result r = new Result();
		double cx = x, cy = y + h / 2, cz = z, hh = h / 2;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		int stuck = 0;
		for (Solid s : solids) {
			double tEnter = -Double.MAX_VALUE, tExit = Double.MAX_VALUE;
			double bnx = 0, bny = 0, bnz = 0;
			boolean separatedAtStart = false, miss = false;
			for (double[] a : s.axes) {
				double rad = hw * Math.abs(a[0]) + hh * Math.abs(a[1]) + hw * Math.abs(a[2]);
				double p0 = cx * a[0] + cy * a[1] + cz * a[2];
				double v = dx * a[0] + dy * a[1] + dz * a[2];
				double smin = Double.MAX_VALUE, smax = -Double.MAX_VALUE;
				for (double[] q : s.verts) {
					double d = q[0] * a[0] + q[1] * a[1] + q[2] * a[2];
					if (d < smin) smin = d;
					if (d > smax) smax = d;
				}
				double lo = smin - rad, hi = smax + rad; // hull centre must be inside (lo,hi) to overlap on this axis
				boolean below = p0 <= lo + TOUCH, above = p0 >= hi - TOUCH;
				if (below || above) {
					separatedAtStart = true;
					// separated (or just touching) on this axis and not moving toward the solid: can never collide
					if ((below && v <= 0) || (above && v >= 0)) {
						miss = true;
						break;
					}
				}
				if (Math.abs(v) < 1e-12) {
					if (p0 <= lo + TOUCH || p0 >= hi - TOUCH) {
						miss = true;
						break;
					}
					continue;
				}
				double t1 = (lo - p0) / v, t2 = (hi - p0) / v;
				double en = Math.min(t1, t2), ex = Math.max(t1, t2);
				if (en > tEnter) {
					tEnter = en;
					double sgn = v > 0 ? -1 : 1; // normal faces against the motion
					bnx = a[0] * sgn;
					bny = a[1] * sgn;
					bnz = a[2] * sgn;
				}
				if (ex < tExit) tExit = ex;
				if (tEnter > tExit) {
					miss = true;
					break;
				}
			}
			if (miss) continue;
			if (!separatedAtStart) {
				stuck++;
				continue; // already inside this solid: ignore it
			}
			if (tEnter > 1 || tExit < 0 || tEnter > tExit) continue;
			double t = Math.max(0, tEnter);
			if (t < r.fraction) {
				r.fraction = t;
				r.nx = bnx;
				r.ny = bny;
				r.nz = bnz;
				r.hit = true;
			}
		}
		r.startSolid = stuck > 0 && solids.size() > 0 && r.fraction == 0;
		if (r.hit && len > 0) r.fraction = Math.max(0, r.fraction - DIST_EPSILON / len);
		r.ex = x + dx * r.fraction;
		r.ey = y + dy * r.fraction;
		r.ez = z + dz * r.fraction;
		return r;
	}
}
