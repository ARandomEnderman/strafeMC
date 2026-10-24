package dev.strafemc.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.strafemc.mode.ZoneToolItem;
import dev.strafemc.mode.Zones;
import dev.strafemc.net.StrafePayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Draws the game mode zones (bomb sites, buy zones) while the Zone Tool is in hand: each box's edges in its kind's
 * colour, its sides faintly filled, full bright and seen through nothing but drawn over the world as it is.
 */
public final class ZoneRender {
	private static volatile List<Zones.Zone> zones = List.of();
	private static final Identifier WHITE = Identifier.withDefaultNamespace("textures/block/white_concrete.png");
	private static final float EDGE = 0.04f;

	private ZoneRender() {}

	/** Whether a player stands in their side's drawn buy zone; null when that side has none drawn (spawn blocks decide). */
	public static Boolean inBuyZone(net.minecraft.world.entity.player.Player p) {
		Zones.Type own = dev.strafemc.weapon.CsTeam.isT(p) ? Zones.Type.BUY_T : Zones.Type.BUY_CT;
		boolean any = false;
		for (Zones.Zone z : zones) {
			if (z.type() != own) continue;
			any = true;
			if (z.contains(p)) return true;
		}
		return any ? Boolean.FALSE : null;
	}

	/** Whether an entity stands in a drawn bomb site (the server's zones as last sent; bomb sites exist only as zones). */
	public static boolean inSite(net.minecraft.world.entity.Entity e) {
		for (Zones.Zone z : zones) if (z.type().site() && z.contains(e)) return true;
		return false;
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(StrafePayloads.ZoneList.TYPE, (msg, ctx) -> {
			try {
				JsonArray a = JsonParser.parseString(msg.json()).getAsJsonArray();
				java.util.List<Zones.Zone> out = new java.util.ArrayList<>();
				for (var e : a) {
					var o = e.getAsJsonObject();
					out.add(new Zones.Zone(o.get("dim").getAsString(), Zones.Type.of(o.get("type").getAsString()), o.get("x0").getAsInt(), o.get("y0").getAsInt(),
							o.get("z0").getAsInt(), o.get("x1").getAsInt(), o.get("y1").getAsInt(), o.get("z1").getAsInt()));
				}
				zones = List.copyOf(out);
			} catch (Exception e) {
				zones = List.of();
			}
		});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> zones = List.of());
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(ZoneRender::render);
	}

	private static boolean holding(Minecraft mc) {
		return mc.player != null && (mc.player.getMainHandItem().getItem() instanceof ZoneToolItem || mc.player.getOffhandItem().getItem() instanceof ZoneToolItem);
	}

	private static void render(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || zones.isEmpty() || !holding(mc)) return;
		String dim = mc.level.dimension().identifier().toString();
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		PoseStack ps = ctx.poseStack();
		for (Zones.Zone z : zones) {
			if (!z.dim().equals(dim)) continue;
			Vec3 lo = new Vec3(z.x0(), z.y0(), z.z0()).subtract(cam), hi = new Vec3(z.x1() + 1, z.y1() + 1, z.z1() + 1).subtract(cam);
			if (lo.add(hi).scale(0.5).lengthSqr() > 160 * 160) continue;
			int edge = z.type().color, fill = (z.type().color & 0xFFFFFF) | 0x28000000;
			ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.entityTranslucentEmissive(WHITE), (pose, vc) -> {
				box(vc, pose, lo, hi, fill);
				// the twelve edges as thin bars
				double[] xs = {lo.x, hi.x}, ys = {lo.y, hi.y}, zs = {lo.z, hi.z};
				for (double y : ys)
					for (double zz : zs) box(vc, pose, new Vec3(lo.x, y - EDGE, zz - EDGE), new Vec3(hi.x, y + EDGE, zz + EDGE), edge);
				for (double x : xs)
					for (double zz : zs) box(vc, pose, new Vec3(x - EDGE, lo.y, zz - EDGE), new Vec3(x + EDGE, hi.y, zz + EDGE), edge);
				for (double x : xs)
					for (double y : ys) box(vc, pose, new Vec3(x - EDGE, y - EDGE, lo.z), new Vec3(x + EDGE, y + EDGE, hi.z), edge);
			});
		}
	}

	/** A box's six sides, both ways round (seen from inside too). */
	private static void box(VertexConsumer vc, PoseStack.Pose pose, Vec3 a, Vec3 b, int color) {
		double[][] f = {
				{a.x, a.y, a.z, b.x, a.y, a.z, b.x, a.y, b.z, a.x, a.y, b.z}, {a.x, b.y, a.z, a.x, b.y, b.z, b.x, b.y, b.z, b.x, b.y, a.z},
				{a.x, a.y, a.z, a.x, b.y, a.z, b.x, b.y, a.z, b.x, a.y, a.z}, {a.x, a.y, b.z, b.x, a.y, b.z, b.x, b.y, b.z, a.x, b.y, b.z},
				{a.x, a.y, a.z, a.x, a.y, b.z, a.x, b.y, b.z, a.x, b.y, a.z}, {b.x, a.y, a.z, b.x, b.y, a.z, b.x, b.y, b.z, b.x, a.y, b.z}};
		for (double[] q : f) {
			for (int i = 0; i < 4; i++) v(vc, pose, q, i, color);
			for (int i = 3; i >= 0; i--) v(vc, pose, q, i, color);
		}
	}

	private static void v(VertexConsumer vc, PoseStack.Pose pose, double[] q, int i, int color) {
		vc.addVertex(pose, (float) q[i * 3], (float) q[i * 3 + 1], (float) q[i * 3 + 2]).setColor(color).setUv(0.5f, 0.5f)
				.setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, 0, 1, 0);
	}
}
