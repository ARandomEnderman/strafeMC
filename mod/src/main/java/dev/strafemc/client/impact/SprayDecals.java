package dev.strafemc.client.impact;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.strafemc.StrafeMc;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.data.AssetCache;
import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.Sprays;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sprayed graffiti (SprayItem): CS2's decal picture (materials/decals/sprays/<material>, converted from the player's own
 * CS2 files the first time) on the sprayed face, tinted for standard graffiti, upright on walls and turned the sprayer's
 * way on floors and ceilings. Each stays 15 minutes, fading out over the last 2 (a newer one sits
 * on top of the ones it covers); a changed block takes its graffiti away.
 */
public final class SprayDecals {
	/** Half the decal's size in blocks: 2 across (CS2's pictures fill about the middle half, so the art is about a block). FLAGGED ours. */
	/** LIFT off the surface, and STACK more for each graffiti it overlaps there, so the newest is on top (no z-fighting). */
	private static final double HALF = 1.0, LIFT = 0.002, STACK = 0.0005;
	/** At most this many at once (the oldest go first); they fade away anyway (LIFE_S). FLAGGED ours. */
	private static final int MAX = 256;
	/** A graffiti stays this long, fading out over the last FADE_S (longer than CS2's per-round life). FLAGGED ours. */
	private static final double LIFE_S = dev.strafemc.weapon.SprayLog.LIFE_S, FADE_S = 2 * 60;

	private record Placed(String material, int color, Vec3 at, Direction face, BlockPos block, BlockState state, Vec3 forward, java.util.UUID owner,
						  long placedAt, double lift) {}

	private static final List<Placed> ALL = new ArrayList<>();
	private static final Map<String, Identifier> TEXTURES = new ConcurrentHashMap<>();
	private static final java.util.Set<String> LOADING = ConcurrentHashMap.newKeySet();

	private SprayDecals() {}

	public static void placed(StrafePayloads.SprayPlaced m) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.level == null) return;
			Sprays.Spray sp = Sprays.get(m.spray());
			if (sp == null) return;
			Sprays.Tint t = sp.tintable() ? Sprays.tint(m.tint()) : null;
			BlockPos block = BlockPos.of(m.block());
			double yaw = Math.toRadians(m.yaw());
			Vec3 at = new Vec3(m.x(), m.y(), m.z());
			Direction face = Direction.from3DDataValue(m.face());
			for (Placed q : ALL) if (q.at().equals(at) && q.owner().equals(m.owner()) && q.material().equals(sp.material())) return; // already up (sent again on join)
			// over every graffiti it overlaps on the same surface: a little further out than the highest of them
			double lift = LIFT;
			for (Placed q : ALL)
				if (q.face() == face && Math.abs(q.at().subtract(at).dot(Vec3.atLowerCornerOf(face.getUnitVec3i()))) < 0.01 && q.at().distanceTo(at) < 2 * HALF * 1.42)
					lift = Math.max(lift, q.lift() + STACK);
			Placed p = new Placed(sp.material(), t != null ? 0xFF000000 | t.color() : -1, at, face, block,
					mc.level.getBlockState(block), new Vec3(-Math.sin(yaw), 0, Math.cos(yaw)), m.owner(), System.nanoTime() - m.ageS() * 1_000_000_000L, lift);
			while (ALL.size() >= MAX) ALL.remove(0);
			ALL.add(p);
			texture(sp.material());
		});
	}

	public static void clear() {
		ALL.clear();
	}

	/** A graffiti's picture (loading it the first time), or null until it's ready. */
	public static Identifier texture(String material) {
		Identifier id = TEXTURES.get(material);
		if (id != null || !LOADING.add(material)) return id;
		String base = "materials/decals/sprays/" + material;
		dev.strafemc.client.Loaders.run(() -> {
			try {
				Path png = StrafeConfig.cacheDir().resolve(base + ".png");
				if (!Files.exists(png)) AssetCache.decompileAll(List.of(base + ".vtex_c"));
				if (!Files.exists(png)) return;
				NativeImage img;
				try (var in = Files.newInputStream(png)) {
					img = NativeImage.read(in);
				}
				Minecraft.getInstance().execute(() -> {
					Identifier tex = Identifier.fromNamespaceAndPath("strafemc", "spray/" + Integer.toHexString(base.hashCode()));
					Minecraft.getInstance().getTextureManager().register(tex, new DynamicTexture(() -> "strafemc graffiti " + material, img));
					TEXTURES.put(material, tex);
				});
			} catch (Exception e) {
				StrafeMc.LOG.warn("[strafemc] graffiti {}: {}", material, e.toString());
			}
		});
		return null;
	}

	public static void render(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || ALL.isEmpty()) return;
		long now = System.nanoTime();
		// block changed (only where it's loaded: right after joining, or far away, the chunk isn't), or faded away
		ALL.removeIf(p -> mc.level.hasChunkAt(p.block()) && mc.level.getBlockState(p.block()) != p.state() || (now - p.placedAt()) / 1e9 >= LIFE_S);
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		PoseStack ps = ctx.poseStack();
		for (Placed p : ALL.toArray(new Placed[0])) {
			if (p.at().distanceToSqr(cam) > 96 * 96) continue;
			Identifier tex = TEXTURES.get(p.material());
			if (tex == null) continue;
			Vec3 n = new Vec3(p.face().getStepX(), p.face().getStepY(), p.face().getStepZ());
			Vec3 up, right;
			if (p.face().getAxis() == Direction.Axis.Y) { // floors and ceilings: the top toward where the sprayer faced
				up = p.forward();
				right = p.face() == Direction.UP ? up.cross(n) : n.cross(up);
			} else {
				up = new Vec3(0, 1, 0);
				right = n.cross(up);
			}
			Vec3 c = p.at().add(n.scale(p.lift()));
			Vec3 tl = c.subtract(right.scale(HALF)).add(up.scale(HALF)), tr = c.add(right.scale(HALF)).add(up.scale(HALF)),
					br = c.add(right.scale(HALF)).subtract(up.scale(HALF)), bl = c.subtract(right.scale(HALF)).subtract(up.scale(HALF));
			int light = LevelRenderer.getLightCoords(mc.level, BlockPos.containing(c.add(n.scale(0.3))));
			double age = (now - p.placedAt()) / 1e9, a = age < LIFE_S - FADE_S ? 1 : Math.max(0, (LIFE_S - age) / FADE_S);
			int color = (int) Math.round(((p.color() >>> 24) & 255) * a) << 24 | (p.color() & 0xFFFFFF); // fading out at the end
			ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.entityTranslucent(tex), (pose, vc) -> {
				vertex(vc, pose, bl, 0, 1, cam, n, light, color);
				vertex(vc, pose, br, 1, 1, cam, n, light, color);
				vertex(vc, pose, tr, 1, 0, cam, n, light, color);
				vertex(vc, pose, tl, 0, 0, cam, n, light, color);
			});
		}
	}

	private static void vertex(VertexConsumer vc, PoseStack.Pose pose, Vec3 p, float u, float v, Vec3 cam, Vec3 n, int light, int color) {
		vc.addVertex(pose, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z)).setColor(color).setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
	}
}
