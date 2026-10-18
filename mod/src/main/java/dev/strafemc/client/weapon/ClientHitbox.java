package dev.strafemc.client.weapon;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Which body part a bullet hits, from the entity's real model as it's drawn this frame: the model is posed by its own
 * renderer (render state, animation, rotations, scale) and every cube is tested against the ray; the nearest cube's part
 * name picks the CS2 hitgroup (head, chest / stomach by where on the body, arms, legs). Works for every mob with a
 * model; the server checks the answer against its hit.
 */
public final class ClientHitbox {
	private ClientHitbox() {}

	/** A SubmitNodeCollector that keeps nothing (every call returns a default; order() returns itself). */
	private static final net.minecraft.client.renderer.SubmitNodeCollector NOOP = noop(net.minecraft.client.renderer.SubmitNodeCollector.class);

	@SuppressWarnings("unchecked")
	private static <T> T noop(Class<T> iface) {
		Object[] self = new Object[1];
		self[0] = java.lang.reflect.Proxy.newProxyInstance(ClientHitbox.class.getClassLoader(), new Class<?>[] {iface}, (proxy, m, args) -> {
			Class<?> rt = m.getReturnType();
			if (m.getDeclaringClass() == Object.class) return switch (m.getName()) {
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == args[0];
				default -> "strafemc no-op collector";
			};
			if (rt.isInstance(proxy)) return proxy;
			if (rt == boolean.class) return false;
			if (rt == int.class || rt == short.class || rt == byte.class) return 0;
			if (rt == long.class) return 0L;
			if (rt == float.class) return 0f;
			if (rt == double.class) return 0d;
			if (rt == char.class) return (char) 0;
			return null;
		});
		return (T) self[0];
	}

	/** The hitgroup along eye + t dir (t up to maxT blocks) on e's model, or null if the ray misses every cube. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static String part(LivingEntity e, Vec3 eye, Vec3 dir, double maxT) {
		try {
			Minecraft mc = Minecraft.getInstance();
			EntityRenderer<?, ?> r = mc.getEntityRenderDispatcher().getRenderer(e);
			if (!(r instanceof LivingEntityRenderer lr)) return null;
			float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
			LivingEntityRenderState st = (LivingEntityRenderState) ((EntityRenderer) lr).createRenderState(e, pt);
			try { // baby and variant models (a calf, a cold pig): the renderer picks its model while drawing, so let it, drawing nothing
				lr.submit(st, new PoseStack(), NOOP, new net.minecraft.client.renderer.state.level.CameraRenderState());
			} catch (RuntimeException ignored) { // then the model it drew last
			}
			var model = lr.getModel();
			model.setupAnim(st);
			// the renderer's own steps (LivingEntityRenderer.submit), in world space
			PoseStack ps = new PoseStack();
			Vec3 pos = e.getPosition(pt);
			ps.translate(pos.x, pos.y, pos.z);
			ps.scale(st.scale, st.scale, st.scale);
			var inv = (dev.strafemc.mixin.client.LivingEntityRendererInvoker) lr;
			inv.strafemc$setupRotations(st, ps, st.bodyRot, st.scale);
			ps.scale(-1, -1, 1);
			inv.strafemc$scale(st, ps);
			ps.translate(0, -1.501f, 0);
			double[] best = {maxT};
			String[] bestPath = {null};
			float[] bestY = {0};
			model.root().visit(ps, (pose, path, index, cube) -> {
				Matrix4f toLocal = new Matrix4f(pose.pose()).invert();
				Vector3f o = toLocal.transformPosition(new Vector3f((float) eye.x, (float) eye.y, (float) eye.z));
				Vector4f d4 = toLocal.transform(new Vector4f((float) dir.x, (float) dir.y, (float) dir.z, 0));
				// cube corners are in pixels (1/16 of a model unit)
				float[] lo = {cube.minX / 16, cube.minY / 16, cube.minZ / 16}, hi = {cube.maxX / 16, cube.maxY / 16, cube.maxZ / 16};
				float[] oo = {o.x, o.y, o.z}, dd = {d4.x, d4.y, d4.z};
				double tmin = 0, tmax = best[0];
				for (int a = 0; a < 3; a++) {
					if (Math.abs(dd[a]) < 1e-9) {
						if (oo[a] < lo[a] || oo[a] > hi[a]) return;
						continue;
					}
					double t1 = (lo[a] - oo[a]) / dd[a], t2 = (hi[a] - oo[a]) / dd[a];
					if (t1 > t2) {
						double x = t1;
						t1 = t2;
						t2 = x;
					}
					tmin = Math.max(tmin, t1);
					tmax = Math.min(tmax, t2);
					if (tmin > tmax) return;
				}
				if (tmin < best[0]) {
					best[0] = tmin;
					bestPath[0] = path;
					float y = (float) (oo[1] + dd[1] * tmin);
					bestY[0] = (y - lo[1]) / Math.max(1e-4f, hi[1] - lo[1]);
				}
			});
			return bestPath[0] == null ? null : group(bestPath[0], bestY[0]);
		} catch (RuntimeException ex) { // an odd modded renderer: the server's height bands decide
			return null;
		}
	}

	/** A model part's path ("root/body/head") as a CS2 hitgroup; the body splits at its middle (top: chest, bottom: stomach). */
	static String group(String path, float yInCube) {
		String p = path.toLowerCase(java.util.Locale.ROOT);
		String last = p.substring(p.lastIndexOf('/') + 1);
		for (String h : new String[] {"head", "hat", "beak", "nose", "jaw", "mouth", "ear", "horn", "bill", "skull", "snout", "face", "hood", "antenna",
				"eye", "muzzle", "crest", "wattle", "red_thing"})
			if (last.contains(h) || p.contains("/" + h + "/")) return "head";
		if (last.contains("arm") || last.contains("sleeve") || last.contains("hand")) return "arms";
		if (last.contains("leg") || last.contains("pants") || last.contains("foot") || last.contains("feet")) return "legs";
		if (last.contains("body") || last.contains("torso") || last.contains("jacket") || last.contains("chest")) return yInCube < 0.5f ? "chest" : "stomach";
		return "chest"; // tails, wings, fins: CS2 has no such parts (FLAGGED: counted as the body, 1x)
	}
}
