package dev.strafemc.client.viewmodel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.strafemc.StrafeMc;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.data.AssetCache;
import dev.strafemc.weapon.Grenade;
import dev.strafemc.weapon.GrenadeEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.renderer.entity.state.ThrownItemRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thrown grenades in the world: the grenade's CS2 model (from the player's converted files) in its rest pose, spinning
 * while it flies and lying on its side once it rests. Without the CS2 files: the item icon, like any thrown item.
 */
public final class GrenadeRenderer extends ThrownItemRenderer<GrenadeEntity> {
	public static final class State extends ThrownItemRenderState {
		Grenade type = Grenade.HE;
		float spin, spinYaw, yaw;
		boolean resting;
	}

	/**
	 * A loaded model in its rest pose (each part skinned through its bones once: cloth like the molotov's rag hangs
	 * where it belongs) and its centre (model units, meters) so it turns about its middle.
	 */
	private record Model(VmModel model, float[][] pos, float[][] nrm, float cx, float cy, float cz, float halfThickness) {}

	/** True CS2 scale (~5 units = 0.125 blocks for the HE) reads as half size in game: drawn 2x. */
	private static final float WORLD_SCALE = 2f;
	private static final Map<Grenade, Model> MODELS = new ConcurrentHashMap<>();
	private static final Set<Grenade> LOADING = ConcurrentHashMap.newKeySet();

	public GrenadeRenderer(EntityRendererProvider.Context ctx) {
		super(ctx);
	}

	@Override
	public ThrownItemRenderState createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(GrenadeEntity e, ThrownItemRenderState s, float partial) {
		super.extractRenderState(e, s, partial);
		State st = (State) s;
		st.type = e.grenade();
		st.spin = Mth.lerp(partial, e.prevSpin, e.spin);
		st.spinYaw = Mth.lerp(partial, e.prevSpinYaw, e.spinYaw);
		st.yaw = e.heading;
		st.resting = e.resting();
	}

	@Override
	public void submit(ThrownItemRenderState s, PoseStack ps, SubmitNodeCollector out, CameraRenderState cam) {
		State st = (State) s;
		Model m = model(st.type);
		if (m == null) {
			super.submit(s, ps, out, cam);
			return;
		}
		float k = (float) (39.3701 / StrafeConfig.unitsPerBlock) * WORLD_SCALE; // meters -> units -> blocks
		ps.pushPose();
		ps.mulPose(Axis.YP.rotationDegrees(-st.yaw - st.spinYaw)); // throw direction + CS2's random yaw tumble
		if (st.resting) { // on its side, resting on the ground
			ps.mulPose(Axis.ZP.rotationDegrees(90)); // its centre already rests a collision radius above the ground
		} else ps.mulPose(Axis.XP.rotationDegrees(st.spin)); // CS2's 600 deg/s end over end
		ps.scale(k, k, k);
		ps.translate(-m.cx(), -m.cy(), -m.cz());
		int light = s.lightCoords;
		for (int pi = 0; pi < m.model().parts.size(); pi++) {
			VmModel.Part p = m.model().parts.get(pi);
			if (p.alphaMode.equals("BLEND")) continue;
			Identifier tex = Viewmodel.INSTANCE.texture(p.texture, !p.alphaMode.equals("MASK"));
			if (tex == null) continue;
			float[] pos = m.pos()[pi], nrm = m.nrm()[pi];
			// (entityCutout draws both sides: the molotov's rag is one-sided)
			// opaque textures carry metalness in alpha: drawn with the viewmodel's lit type, cutout only for real masks
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

	/** The model if loaded; otherwise starts loading it in the background and returns null (the icon is drawn meanwhile). */
	private static Model model(Grenade g) {
		Model m = MODELS.get(g);
		if (m != null || dev.strafemc.client.Placeholders.active() || !LOADING.add(g)) return m;
		Thread t = new Thread(() -> {
			try {
				Glb glb = Glb.read(AssetCache.exportGlb(g.model, true));
				VmModel vm = VmModel.load(glb, ".body");
				if (vm.parts.isEmpty()) return;
				VmClip rest = new VmClip("rest", glb); // no animation: every node at its rest transform
				org.joml.Matrix4f[] pose = rest.pose(0);
				org.joml.Matrix4f[][] skins = Viewmodel.skins(vm, rest, pose, null);
				float[][] pos = new float[vm.parts.size()][], nrm = new float[vm.parts.size()][];
				float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
				for (int k = 0; k < vm.parts.size(); k++) {
					VmModel.Part p = vm.parts.get(k);
					if (p.texture != null) Viewmodel.downscaled(p.texture);
					org.joml.Matrix4f[] skin = p.skin >= 0 && p.skin < skins.length ? skins[p.skin] : new org.joml.Matrix4f[0];
					float[][] r = Viewmodel.skinVertices(p, skin, new org.joml.Matrix4f());
					pos[k] = r[0];
					nrm[k] = r[1];
					for (int i = 0; i < pos[k].length; i++) {
						min[i % 3] = Math.min(min[i % 3], pos[k][i]);
						max[i % 3] = Math.max(max[i % 3], pos[k][i]);
					}
				}
				MODELS.put(g, new Model(vm, pos, nrm, (min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2, (max[0] - min[0]) / 2));
			} catch (Exception e) {
				StrafeMc.LOG.warn("[strafemc] grenade model {} failed: {}", g.model, e.toString());
			}
		}, "strafemc-grenade-model");
		t.setDaemon(true);
		t.start();
		return null;
	}
}
