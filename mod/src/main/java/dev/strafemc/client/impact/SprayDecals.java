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
 * way on floors and ceilings. The last few per player are kept; a changed block takes its graffiti away.
 */
public final class SprayDecals {
	/** Half the decal's size in blocks: 2 across (CS2's pictures fill about the middle half, so the art is about a block). FLAGGED ours. */
	private static final double HALF = 1.0, LIFT = 0.003;
	private static final int PER_PLAYER = 3;

	private record Placed(String material, int color, Vec3 at, Direction face, BlockPos block, BlockState state, Vec3 forward, java.util.UUID owner) {}

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
			Placed p = new Placed(sp.material(), t != null ? 0xFF000000 | t.color() : -1, new Vec3(m.x(), m.y(), m.z()), Direction.from3DDataValue(m.face()), block,
					mc.level.getBlockState(block), new Vec3(-Math.sin(yaw), 0, Math.cos(yaw)), m.owner());
			// the sprayer's oldest goes once they have more than PER_PLAYER
			List<Placed> mine = new ArrayList<>();
			for (Placed q : ALL) if (q.owner().equals(m.owner())) mine.add(q);
			while (mine.size() >= PER_PLAYER) ALL.remove(mine.remove(0));
			ALL.add(p);
			texture(sp.material());
		});
	}

	public static void clear() {
		ALL.clear();
	}

	private static Identifier texture(String material) {
		Identifier id = TEXTURES.get(material);
		if (id != null || !LOADING.add(material)) return id;
		String base = "materials/decals/sprays/" + material;
		Thread t = new Thread(() -> {
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
		}, "strafemc-graffiti");
		t.setDaemon(true);
		t.start();
		return null;
	}

	public static void render(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || ALL.isEmpty()) return;
		ALL.removeIf(p -> mc.level.getBlockState(p.block()) != p.state()); // block broken or changed
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
			Vec3 c = p.at().add(n.scale(LIFT));
			Vec3 tl = c.subtract(right.scale(HALF)).add(up.scale(HALF)), tr = c.add(right.scale(HALF)).add(up.scale(HALF)),
					br = c.add(right.scale(HALF)).subtract(up.scale(HALF)), bl = c.subtract(right.scale(HALF)).subtract(up.scale(HALF));
			int light = LevelRenderer.getLightCoords(mc.level, BlockPos.containing(c.add(n.scale(0.3))));
			ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.entityTranslucent(tex), (pose, vc) -> {
				vertex(vc, pose, bl, 0, 1, cam, n, light, p.color());
				vertex(vc, pose, br, 1, 1, cam, n, light, p.color());
				vertex(vc, pose, tr, 1, 0, cam, n, light, p.color());
				vertex(vc, pose, tl, 0, 0, cam, n, light, p.color());
			});
		}
	}

	private static void vertex(VertexConsumer vc, PoseStack.Pose pose, Vec3 p, float u, float v, Vec3 cam, Vec3 n, int light, int color) {
		vc.addVertex(pose, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z)).setColor(color).setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
	}
}
