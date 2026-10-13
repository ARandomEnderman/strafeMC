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

	/** A loaded model and its centre (model units, meters) so it turns about its middle. */
	private record Model(VmModel model, float cx, float cy, float cz, float halfThickness) {}

	/** True CS2 scale (~5 units = 0.125 blocks for the HE) read as half size in game (user): drawn 2x. */
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
		for (VmModel.Part p : m.model().parts) {
			if (p.alphaMode.equals("BLEND")) continue;
			Identifier tex = Viewmodel.INSTANCE.texture(p.texture, !p.alphaMode.equals("MASK"));
			if (tex == null) continue;
			out.submitCustomGeometry(ps, RenderTypes.entityCutout(tex), (pose, vc) -> {
				int[] idx = p.idx;
				for (int i = 0; i + 2 < idx.length; i += 3)
					for (int c : new int[] {idx[i], idx[i + 1], idx[i + 2], idx[i + 2]}) // entity render types draw quads
						vc.addVertex(pose, p.pos[c * 3], p.pos[c * 3 + 1], p.pos[c * 3 + 2]).setColor(-1).setUv(p.uv[c * 2], p.uv[c * 2 + 1])
								.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, p.nrm[c * 3], p.nrm[c * 3 + 1], p.nrm[c * 3 + 2]);
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
				VmModel vm = VmModel.load(Glb.read(AssetCache.exportGlb(g.model, true)), ".body");
				float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
				for (VmModel.Part p : vm.parts) {
					if (p.texture != null) Viewmodel.downscaled(p.texture);
					for (int i = 0; i < p.pos.length; i++) {
						min[i % 3] = Math.min(min[i % 3], p.pos[i]);
						max[i % 3] = Math.max(max[i % 3], p.pos[i]);
					}
				}
				if (vm.parts.isEmpty()) return;
				MODELS.put(g, new Model(vm, (min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2, (max[0] - min[0]) / 2));
			} catch (Exception e) {
				StrafeMc.LOG.warn("[strafemc] grenade model {} failed: {}", g.model, e.toString());
			}
		}, "strafemc-grenade-model");
		t.setDaemon(true);
		t.start();
		return null;
	}
}
