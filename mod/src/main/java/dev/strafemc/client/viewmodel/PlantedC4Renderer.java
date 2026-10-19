package dev.strafemc.client.viewmodel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.strafemc.StrafeMc;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.data.AssetCache;
import dev.strafemc.weapon.C4Item;
import dev.strafemc.weapon.PlantedC4;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/**
 * A planted C4: CS2's C4 model in its rest pose, lying where it was planted, turned the way its planter faced. Without
 * the CS2 files nothing is drawn but its hitbox's shadow.
 */
public final class PlantedC4Renderer extends EntityRenderer<PlantedC4, PlantedC4Renderer.State> {
	public static final class State extends EntityRenderState {
		float yaw;
		boolean lit;
	}

	/** The model in its rest pose, its centre and floor, and per part the triangles of its LED (CS2's self-illum mask). */
	private record Model(VmModel model, float[][] pos, float[][] nrm, float cx, float minY, float cz, int[][] led) {}

	/** CS2's mask of what glows on the C4: its LED. */
	private static final String LED_MASK = "weapons/models/c4/materials/c4_led_selfillum_psd_d8363a2a.vtex";
	/** How long the LED stays lit after each beep (FLAGGED ours). */
	private static final long BLINK_NS = 150_000_000L;

	/** A beep at `at`: the bomb there blinks. */
	public static void beeped(net.minecraft.world.phys.Vec3 at) {
		var level = net.minecraft.client.Minecraft.getInstance().level;
		if (level == null) return;
		for (var e : level.getEntitiesOfClass(PlantedC4.class, new net.minecraft.world.phys.AABB(at, at).inflate(1)))
			e.lastBeepNs = System.nanoTime();
	}

	/** Drawn a little over CS2's true size (it reads small next to Minecraft's blocks), as the grenades are (FLAGGED). */
	private static final float WORLD_SCALE = 1.5f;
	private static volatile Model model;
	private static volatile boolean loading;

	public PlantedC4Renderer(EntityRendererProvider.Context ctx) {
		super(ctx);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(PlantedC4 e, State s, float partial) {
		super.extractRenderState(e, s, partial);
		s.yaw = e.getYRot();
		s.lit = !e.defused() && System.nanoTime() - e.lastBeepNs < BLINK_NS;
	}

	@Override
	public void submit(State s, PoseStack ps, SubmitNodeCollector out, CameraRenderState cam) {
		super.submit(s, ps, out, cam);
		Model m = model();
		if (m == null) return;
		float k = (float) (39.3701 / StrafeConfig.unitsPerBlock) * WORLD_SCALE; // meters -> units -> blocks
		ps.pushPose();
		ps.mulPose(Axis.YP.rotationDegrees(-s.yaw));
		ps.scale(k, k, k);
		ps.translate(-m.cx(), -m.minY(), -m.cz());
		int light = s.lightCoords;
		for (int pi = 0; pi < m.model().parts.size(); pi++) {
			VmModel.Part p = m.model().parts.get(pi);
			if (p.alphaMode.equals("BLEND")) continue;
			Identifier tex = Viewmodel.INSTANCE.texture(p.texture, !p.alphaMode.equals("MASK"));
			if (tex == null) continue;
			float[] pos = m.pos()[pi], nrm = m.nrm()[pi];
			out.submitCustomGeometry(ps, p.alphaMode.equals("MASK") ? RenderTypes.entityCutout(tex) : VmRender.opaque(tex), (pose, vc) -> {
				int[] idx = p.idx;
				for (int i = 0; i + 2 < idx.length; i += 3)
					for (int c : new int[] {idx[i], idx[i + 1], idx[i + 2], idx[i + 2]}) // entity render types draw quads
						vc.addVertex(pose, pos[c * 3], pos[c * 3 + 1], pos[c * 3 + 2]).setColor(-1).setUv(p.uv[c * 2], p.uv[c * 2 + 1])
								.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nrm[c * 3], nrm[c * 3 + 1], nrm[c * 3 + 2]);
			});
		}
		if (s.lit) { // the LED, lit red over itself, a hair out so it isn't hidden
			for (int pi = 0; pi < m.model().parts.size(); pi++) {
				int[] led = m.led()[pi];
				VmModel.Part p = m.model().parts.get(pi);
				Identifier tex = led == null || led.length == 0 ? null : Viewmodel.INSTANCE.texture(p.texture, true);
				if (tex == null) continue;
				float[] pos = m.pos()[pi], nrm = m.nrm()[pi];
				out.submitCustomGeometry(ps, RenderTypes.entityTranslucentEmissive(tex), (pose, vc) -> {
					for (int i = 0; i + 2 < led.length; i += 3)
						for (int c : new int[] {led[i], led[i + 1], led[i + 2], led[i + 2]})
							vc.addVertex(pose, pos[c * 3] + nrm[c * 3] * 0.0004f, pos[c * 3 + 1] + nrm[c * 3 + 1] * 0.0004f, pos[c * 3 + 2] + nrm[c * 3 + 2] * 0.0004f)
									.setColor(0xFFFF2A1A).setUv(p.uv[c * 2], p.uv[c * 2 + 1]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0)
									.setNormal(pose, nrm[c * 3], nrm[c * 3 + 1], nrm[c * 3 + 2]);
				});
			}
		}
		ps.popPose();
	}

	/** The model if loaded; otherwise starts loading it in the background and returns null. */
	private static Model model() {
		Model m = model;
		if (m != null || loading || dev.strafemc.client.Placeholders.active()) return m;
		loading = true;
		Thread t = new Thread(() -> {
			try {
				Glb glb = Glb.read(AssetCache.exportGlb(C4Item.MODEL, true));
				VmModel vm = VmModel.load(glb, VmModel.AUTO_MESH);
				if (vm.parts.isEmpty()) return;
				VmClip rest = new VmClip("rest", glb);
				org.joml.Matrix4f[] pose = rest.pose(0);
				org.joml.Matrix4f[][] skins = Viewmodel.skins(vm, rest, pose, null);
				float[][] pos = new float[vm.parts.size()][], nrm = new float[vm.parts.size()][];
				int[][] led = new int[vm.parts.size()][];
				java.awt.image.BufferedImage ledMask = GloveSkinCache.load(LED_MASK);
				float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
				for (int i = 0; i < vm.parts.size(); i++) {
					VmModel.Part p = vm.parts.get(i);
					if (p.texture != null) Viewmodel.downscaled(p.texture);
					org.joml.Matrix4f[] skin = p.skin >= 0 && p.skin < skins.length ? skins[p.skin] : new org.joml.Matrix4f[0];
					float[][] r = Viewmodel.skinVertices(p, skin, new org.joml.Matrix4f());
					pos[i] = r[0];
					nrm[i] = r[1];
					if (p.alphaMode.equals("BLEND")) continue;
					if (ledMask != null) { // triangles whose middle is on the LED
						java.util.List<Integer> l = new java.util.ArrayList<>();
						for (int ti = 0; ti + 2 < p.idx.length; ti += 3) {
							double u = 0, v = 0;
							for (int c = 0; c < 3; c++) {
								u += p.uv[p.idx[ti + c] * 2] / 3;
								v += p.uv[p.idx[ti + c] * 2 + 1] / 3;
							}
							if ((GloveSkinBaker.sample(ledMask, u - Math.floor(u), v - Math.floor(v)) >> 16 & 255) > 127)
								for (int c = 0; c < 3; c++) l.add(p.idx[ti + c]);
						}
						led[i] = l.stream().mapToInt(Integer::intValue).toArray();
					} // CS2's hidden helper geometry: not part of the bomb's size
					for (int j = 0; j < pos[i].length; j++) {
						min[j % 3] = Math.min(min[j % 3], pos[i][j]);
						max[j % 3] = Math.max(max[j % 3], pos[i][j]);
					}
				}
				model = new Model(vm, pos, nrm, (min[0] + max[0]) / 2, min[1], (min[2] + max[2]) / 2, led);
			} catch (Exception e) {
				StrafeMc.LOG.warn("[strafemc] C4 model failed: {}", e.toString());
			}
		}, "strafemc-c4-model");
		t.setDaemon(true);
		t.start();
		return null;
	}
}
