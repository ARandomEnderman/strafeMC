package dev.strafemc.client;

import dev.strafemc.world.SurfSlopeBlock;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.StairsShape;

import java.util.List;
import java.util.function.Predicate;

/**
 * The surf slope's outer corner (the pyramid tip where two slopes meet) drawn as real triangles. JSON block models
 * only have boxes, and a box can't be a triangle: the strips used before gave a jagged ridge. Through Fabric's
 * renderer this emits the exact shape: both sloped faces, the two triangular high sides and the bottom. Vanilla's own
 * path (e.g. the block-breaking cracks) keeps the box model it wraps.
 */
public final class SurfSlopeCornerModel implements BlockStateModel, FabricBlockStateModel {
	private final BlockStateModel original;
	private final int quarterTurns; // the blockstate's y rotation in 90 degree steps (the model's tip starts at east-north)

	private SurfSlopeCornerModel(BlockStateModel original, int quarterTurns) {
		this.original = original;
		this.quarterTurns = quarterTurns;
	}

	/** Wraps the baked model of an outer-corner surf slope state; anything else is returned as it is. */
	public static BlockStateModel wrap(BlockStateModel model, BlockState state) {
		if (!(state.getBlock() instanceof SurfSlopeBlock) || !SurfSlopeBlock.outer(state)) return model;
		// the same rotations as blockstates/surf_slope.json gives the outer corner model
		boolean left = state.getValue(SurfSlopeBlock.SHAPE) == StairsShape.OUTER_LEFT;
		int base = switch (state.getValue(SurfSlopeBlock.FACING)) {
			case EAST -> 0;
			case SOUTH -> 1;
			case WEST -> 2;
			default -> 3; // NORTH
		};
		return new SurfSlopeCornerModel(model, (base + (left ? 0 : 1)) % 4);
	}

	@Override
	public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
		original.collectParts(random, parts);
	}

	@Override
	public Material.Baked particleMaterial() {
		return original.particleMaterial();
	}

	@Override
	public int materialFlags() {
		return original.materialFlags();
	}

	/** Corners (x, y, z in 0..1) of the unrotated shape: height = min(x, 1 - z), tip at x = 1, z = 0. */
	private static final float[][][] FACES = {
			{{0, 0, 0}, {0, 0, 1}, {1, 1, 0}}, // slope rising toward +x (y = x)
			{{0, 0, 1}, {1, 0, 1}, {1, 1, 0}}, // slope rising toward -z (y = 1 - z)
			{{1, 0, 0}, {1, 1, 0}, {1, 0, 1}}, // east side, height 1 - z
			{{0, 0, 0}, {1, 0, 0}, {1, 1, 0}}, // north side, height x
	};
	/** Each face's outward normal, unrotated, and the side it lies on (null: inside the block). */
	private static final float[][] NORMALS = {{-1, 1, 0}, {0, 1, 1}, {1, 0, 0}, {0, 0, -1}};
	private static final Direction[] CULL = {null, null, Direction.EAST, Direction.NORTH};

	@Override
	public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
						  Predicate<Direction> cullTest) {
		Material.Baked material = original.particleMaterial();
		for (int f = 0; f < FACES.length; f++) {
			Direction cull = CULL[f] == null ? null : rotate(CULL[f]);
			if (cull != null && cullTest.test(cull)) continue;
			float[][] tri = new float[3][];
			for (int i = 0; i < 3; i++) tri[i] = rotate(FACES[f][i]);
			float[] n = rotate(NORMALS[f], false);
			triangle(emitter, material, tri, n, cull, f < 2 ? Direction.UP : cull);
		}
		if (!cullTest.test(Direction.DOWN)) { // the bottom, a full square
			float[][] quad = {{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}};
			for (int i = 0; i < 4; i++) {
				emitter.pos(i, quad[i][0], quad[i][1], quad[i][2]);
				emitter.uv(i, quad[i][0], quad[i][2]);
			}
			emitter.cullFace(Direction.DOWN);
			emitter.nominalFace(Direction.DOWN);
			emitter.materialBake(material, MutableQuadView.BAKE_NORMALIZED);
			emitter.color(-1, -1, -1, -1);
			emitter.emit();
		}
	}

	/** One triangle as a quad with its last corner doubled, wound so it faces along n. */
	private static void triangle(QuadEmitter e, Material.Baked material, float[][] t, float[] n, Direction cull, Direction nominal) {
		float ax = t[1][0] - t[0][0], ay = t[1][1] - t[0][1], az = t[1][2] - t[0][2];
		float bx = t[2][0] - t[0][0], by = t[2][1] - t[0][1], bz = t[2][2] - t[0][2];
		float cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
		if (cx * n[0] + cy * n[1] + cz * n[2] < 0) { // counter-clockwise seen from the front
			float[] s = t[1];
			t[1] = t[2];
			t[2] = s;
		}
		for (int i = 0; i < 4; i++) {
			float[] p = t[Math.min(i, 2)];
			e.pos(i, p[0], p[1], p[2]);
			// texture projected along the face's main axis, like a cube face would be
			float u, v;
			if (Math.abs(n[1]) >= Math.abs(n[0]) && Math.abs(n[1]) >= Math.abs(n[2])) {
				u = p[0];
				v = p[2];
			} else if (Math.abs(n[0]) >= Math.abs(n[2])) {
				u = n[0] > 0 ? 1 - p[2] : p[2];
				v = 1 - p[1];
			} else {
				u = n[2] > 0 ? p[0] : 1 - p[0];
				v = 1 - p[1];
			}
			e.uv(i, u, v);
		}
		e.cullFace(cull);
		e.nominalFace(nominal);
		e.materialBake(material, MutableQuadView.BAKE_NORMALIZED);
		e.color(-1, -1, -1, -1);
		e.emit();
	}

	/** A corner turned like the blockstate's y rotation (clockwise seen from above, about the block centre). */
	private float[] rotate(float[] p) {
		return rotate(p, true);
	}

	private float[] rotate(float[] p, boolean point) {
		float x = p[0], z = p[2];
		for (int i = 0; i < quarterTurns; i++) { // east -> south: (x, z) -> (1 - z, x) for points, (-z, x) for directions
			float nx = point ? 1 - z : -z;
			z = x;
			x = nx;
		}
		return new float[] {x, p[1], z};
	}

	private Direction rotate(Direction d) {
		for (int i = 0; i < quarterTurns; i++) d = d.getClockWise();
		return d;
	}
}
