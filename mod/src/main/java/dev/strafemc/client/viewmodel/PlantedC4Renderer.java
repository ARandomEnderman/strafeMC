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
	}

	private record Model(VmModel model, float[][] pos, float[][] nrm, float cx, float minY, float cz) {}

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
				float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
				for (int i = 0; i < vm.parts.size(); i++) {
					VmModel.Part p = vm.parts.get(i);
					if (p.texture != null) Viewmodel.downscaled(p.texture);
					org.joml.Matrix4f[] skin = p.skin >= 0 && p.skin < skins.length ? skins[p.skin] : new org.joml.Matrix4f[0];
					float[][] r = Viewmodel.skinVertices(p, skin, new org.joml.Matrix4f());
					pos[i] = r[0];
					nrm[i] = r[1];
					if (p.alphaMode.equals("BLEND")) continue; // CS2's hidden helper geometry: not part of the bomb's size
					for (int j = 0; j < pos[i].length; j++) {
						min[j % 3] = Math.min(min[j % 3], pos[i][j]);
						max[j % 3] = Math.max(max[j % 3], pos[i][j]);
					}
				}
				model = new Model(vm, pos, nrm, (min[0] + max[0]) / 2, min[1], (min[2] + max[2]) / 2);
			} catch (Exception e) {
				StrafeMc.LOG.warn("[strafemc] C4 model failed: {}", e.toString());
			}
		}, "strafemc-c4-model");
		t.setDaemon(true);
		t.start();
		return null;
	}
}
