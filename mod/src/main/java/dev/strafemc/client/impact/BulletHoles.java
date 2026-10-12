package dev.strafemc.client.impact;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.strafemc.client.sound.CsSounds;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.data.Surfaces;
import dev.strafemc.net.StrafePayloads;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * CS2 bullet holes on Minecraft blocks: on each impact the block's CS2 surface picks a decal group (surfaceproperties),
 * the group picks a weighted decal material (decalgroups.vdata), which brings its texture and world size. Decals lie flat
 * on the hit face (clipped to it), with a random rotation, and vanish when the block changes. Also plays the surface's
 * bullet impact sound.
 */
public final class BulletHoles {
	/** Max decals kept (oldest dropped). Source's r_decals default (CS2 doesn't expose its limit; flagged). */
	private static final int MAX_DECALS = 2048;
	/** CS2's knife-hit decal group (knife_scuff materials). */
	public static final String KNIFE_DECALS = "ManhackCut";
	/** Lift off the face against z-fighting (blocks). */
	private static final double LIFT = 0.002;

	/** normal: the surface normal (the face's for full blocks; the slope's for ramps/stairs, which aren't clipped to faces). */
	private record Decal(BlockPos block, BlockState state, Direction face, Vec3 at, String material, double sizeRand, double rot, int order,
						 Vec3 normal) {
		boolean onFace() {
			return Math.abs(normal.x) > 0.999 || Math.abs(normal.y) > 0.999 || Math.abs(normal.z) > 0.999;
		}
	}

	private static int counter;

	private static final Deque<Decal> DECALS = new ArrayDeque<>();

	private BulletHoles() {}

	public static void onImpact(StrafePayloads.BulletImpact m) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		if (dev.strafemc.client.Placeholders.active()) { // no CS2 files: vanilla particles + a drawn stand-in hole
			dev.strafemc.client.Placeholders.impact(m);
			addDecal(mc, m, DecalLibrary.PLACEHOLDER, new Random(m.seed()));
			return;
		}
		Surfaces.Surface s = Surfaces.get(m.surface());
		if (!m.knife() && s != null && s.bulletImpactSound() != null && !s.bulletImpactSound().isEmpty()) // knife: its own HitWall sound
			CsSounds.play(s.bulletImpactSound(), m.x(), m.y(), m.z(), 1.0);
		if (s != null && s.impactEffect() != null && !s.impactEffect().isEmpty()) // the surface's impact particles, out of the surface
			dev.strafemc.client.particle.Effects.at(s.impactEffect(), new Vec3(m.x(), m.y(), m.z()), new Vec3(m.nx(), m.ny(), m.nz()), tint(mc, BlockPos.of(m.block())));
		String group = m.knife() ? KNIFE_DECALS : s == null ? null : s.decalGroup();
		if (group == null || group.isEmpty()) return;
		Random rng = new Random(m.seed());
		String mat = DecalLibrary.pick(group, rng);
		if (mat == null) return;
		DecalLibrary.material(mat); // start loading early
		addDecal(mc, m, mat, rng);
	}

	private static void addDecal(Minecraft mc, StrafePayloads.BulletImpact m, String mat, Random rng) {
		BlockPos bp = BlockPos.of(m.block());
		Direction face = Direction.from3DDataValue(m.face());
		double sizeRand = rng.nextDouble();
		// knife scuffs lie roughly level, like a slash (+-15 degrees); bullet holes turn any way
		double rot = m.knife() ? (rng.nextDouble() - 0.5) * Math.toRadians(30) : rng.nextDouble() * Math.PI * 2;
		if (m.knife() && face.getAxis() == Direction.Axis.X) rot += Math.PI / 2; // on east/west walls the texture's u runs vertically
		DECALS.addLast(new Decal(bp, mc.level.getBlockState(bp), face, new Vec3(m.x(), m.y(), m.z()), mat, sizeRand, rot, counter++,
				new Vec3(m.nx(), m.ny(), m.nz())));
		while (DECALS.size() > MAX_DECALS) DECALS.removeFirst();
	}

	/** The hit block's colour for CS2's impact tint (CP4): Minecraft's map colour of the block, the closest analogue of CS2's material colour. */
	private static org.joml.Vector3f tint(Minecraft mc, BlockPos pos) {
		int c = mc.level.getBlockState(pos).getMapColor(mc.level, pos).col;
		return new org.joml.Vector3f((c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f);
	}

	/** Shot number -> the decal its prediction placed (dropped if the server says the shot went elsewhere). */
	private static final java.util.Map<Integer, Decal> PREDICTED = new java.util.LinkedHashMap<>() {
		@Override
		protected boolean removeEldestEntry(java.util.Map.Entry<Integer, Decal> e) {
			return size() > 64;
		}
	};

	/** The shooter's own predicted impact, remembered by shot number. */
	public static void onPredicted(StrafePayloads.BulletImpact m, int shot) {
		Decal before = DECALS.peekLast();
		onImpact(m);
		Decal added = DECALS.peekLast();
		if (added != null && added != before) PREDICTED.put(shot, added);
	}

	/** The server corrected shot n: its predicted hole goes (the real impact arrives separately). */
	public static void dropPredicted(int shot) {
		Decal d = PREDICTED.remove(shot);
		if (d != null) DECALS.remove(d);
	}

	public static void clear() {
		PREDICTED.clear();
		DECALS.clear();
	}

	public static void render(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || DECALS.isEmpty()) return;
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		PoseStack ps = ctx.poseStack();
		double u = StrafeConfig.unitsPerBlock;
		DECALS.removeIf(d -> mc.level.getBlockState(d.block) != d.state); // block broken or changed
		for (Decal d : DECALS) {
			if (d.at.distanceToSqr(cam) > 96 * 96) continue;
			DecalLibrary.Material m = DecalLibrary.material(d.material);
			if (m == null) continue;
			// size from the material: DecalWorldWidth/Height (Hammer units) +- DecalSizeVariance units (interpretation flagged:
			// a fraction gave 0.4x..1.6x holes on concrete; units gives ~0.8x..1.2x), aspect kept
			double base = Math.max(m.width(), 1e-3);
			double scale = Math.max(0.5, (base + m.sizeVariance() * (2 * d.sizeRand - 1)) / base) * (d.material.contains("manhackcut") ? 1 : StrafeConfig.bulletHoleScale); // the 0.6x preference is for bullet holes only
			double hw = m.width() * scale / u / 2, hh = m.height() * scale / u / 2;
			if (!d.onFace()) { // slope: a rotated quad on the slope plane
				List<double[]> q = slopeQuad(d, hw, hh);
				int lightS = LevelRenderer.getLightCoords(mc.level, BlockPos.containing(d.at.add(d.normal.scale(0.3))));
				ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.entityTranslucent(m.texture()), (pose, vc) -> {
					for (int i = 0; i < 4; i++) vertex(vc, pose, q.get(i), cam, d.normal, lightS);
				});
				continue;
			}
			// the hole continues onto neighbouring blocks whose same face is exposed in the same plane (as CS2's projection)
			List<List<double[]>> polys = new ArrayList<>();
			Direction.Axis ax = d.face.getAxis();
			for (int a = -1; a <= 1; a++)
				for (int b = -1; b <= 1; b++) {
					BlockPos cell = switch (ax) {
						case X -> d.block.offset(0, a, b);
						case Y -> d.block.offset(a, 0, b);
						default -> d.block.offset(a, b, 0);
					};
					if (!cell.equals(d.block) && (!mc.level.getBlockState(cell).isFaceSturdy(mc.level, cell, d.face)
							|| mc.level.getBlockState(cell.relative(d.face)).isFaceSturdy(mc.level, cell.relative(d.face), d.face.getOpposite())))
						continue;
					List<double[]> poly = clipped(d, cell, hw, hh);
					if (poly.size() >= 3) polys.add(poly);
				}
			if (polys.isEmpty()) continue;
			int light = LevelRenderer.getLightCoords(mc.level, d.block.relative(d.face));
			Vec3 n = new Vec3(d.face.getStepX(), d.face.getStepY(), d.face.getStepZ());
			ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.entityTranslucent(m.texture()), (pose, vc) -> {
				for (List<double[]> poly : polys)
					for (int i = 1; i + 1 < poly.size(); i++) { // fan as quads (a, b, c, c)
						vertex(vc, pose, poly.get(0), cam, n, light);
						vertex(vc, pose, poly.get(i), cam, n, light);
						vertex(vc, pose, poly.get(i + 1), cam, n, light);
						vertex(vc, pose, poly.get(i + 1), cam, n, light);
					}
			});
		}
	}

	private static void vertex(VertexConsumer vc, PoseStack.Pose pose, double[] p, Vec3 cam, Vec3 n, int light) {
		vc.addVertex(pose, (float) (p[0] - cam.x), (float) (p[1] - cam.y), (float) (p[2] - cam.z)).setColor(-1).setUv((float) p[3], (float) p[4])
				.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
	}

	/** Decal quad on an arbitrary plane (slopes), lifted along the normal; points are x,y,z,u,v. */
	private static List<double[]> slopeQuad(Decal d, double hw, double hh) {
		Vec3 n = d.normal;
		Vec3 t1 = n.cross(Math.abs(n.y) < 0.99 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize(), t2 = n.cross(t1).normalize();
		double c = Math.cos(d.rot), s = Math.sin(d.rot);
		Vec3 a = t1.scale(c).add(t2.scale(s)), b = t2.scale(c).subtract(t1.scale(s));
		Vec3 o = d.at.add(n.scale(LIFT + (d.order % 64) * 0.00015));
		double[][] k = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};
		List<double[]> out = new ArrayList<>();
		for (double[] q : k) {
			Vec3 p = o.add(a.scale(q[0] * hw)).add(b.scale(q[1] * hh));
			out.add(new double[] {p.x, p.y, p.z, q[2], q[3]});
		}
		return out;
	}

	/** The rotated decal rectangle on the hit face, clipped to the face's square (Sutherland-Hodgman); points are x,y,z,u,v. */
	private static List<double[]> clipped(Decal d, BlockPos cellPos, double hw, double hh) {
		Direction f = d.face;
		// face plane axes (a, b) and the fixed coordinate
		Direction.Axis ax = f.getAxis();
		int ia = ax == Direction.Axis.X ? 1 : 0, ib = ax == Direction.Axis.Z ? 1 : 2, in = ax.ordinal();
		double[] c = {d.at.x, d.at.y, d.at.z};
		double plane = Math.abs(c[in] - Math.round(c[in])) < 1e-3 ? Math.round(c[in]) : c[in]; // the hit point is on the face
		// each decal sits a little higher than the previous ones (64 layers), so overlapping holes and the block never z-fight
		double lift = (LIFT + (d.order % 64) * 0.00015) * (f.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1);
		double cos = Math.cos(d.rot), sin = Math.sin(d.rot);
		double[][] corners = {{-hw, -hh, 0, 1}, {hw, -hh, 1, 1}, {hw, hh, 1, 0}, {-hw, hh, 0, 0}};
		List<double[]> poly = new ArrayList<>();
		for (double[] k : corners) poly.add(new double[] {c[ia] + k[0] * cos - k[1] * sin, c[ib] + k[0] * sin + k[1] * cos, k[2], k[3]});
		double minA = cellPos.get(ax == Direction.Axis.X ? Direction.Axis.Y : Direction.Axis.X), minB = cellPos.get(ax == Direction.Axis.Z ? Direction.Axis.Y : Direction.Axis.Z);
		poly = clip(poly, 0, minA, true);
		poly = clip(poly, 0, minA + 1, false);
		poly = clip(poly, 1, minB, true);
		poly = clip(poly, 1, minB + 1, false);
		List<double[]> out = new ArrayList<>();
		for (double[] p : poly) {
			double[] w = new double[5];
			w[ia] = p[0];
			w[ib] = p[1];
			w[in] = plane + lift;
			w[3] = p[2];
			w[4] = p[3];
			out.add(w);
		}
		// wind counter-clockwise as seen from outside the face
		boolean flip = (f == Direction.DOWN || f == Direction.SOUTH || f == Direction.WEST);
		if (flip) java.util.Collections.reverse(out);
		return out;
	}

	private static List<double[]> clip(List<double[]> in, int axis, double bound, boolean keepAbove) {
		List<double[]> out = new ArrayList<>();
		for (int i = 0; i < in.size(); i++) {
			double[] p = in.get(i), q = in.get((i + 1) % in.size());
			boolean pin = keepAbove ? p[axis] >= bound : p[axis] <= bound, qin = keepAbove ? q[axis] >= bound : q[axis] <= bound;
			if (pin) out.add(p);
			if (pin != qin) {
				double t = (bound - p[axis]) / (q[axis] - p[axis]);
				double[] r = new double[p.length];
				for (int k = 0; k < p.length; k++) r[k] = p[k] + (q[k] - p[k]) * t;
				out.add(r);
			}
		}
		return out;
	}
}
