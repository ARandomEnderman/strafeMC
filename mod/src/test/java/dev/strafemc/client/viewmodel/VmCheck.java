package dev.strafemc.client.viewmodel;

import dev.strafemc.data.AssetCache;
import org.joml.Matrix4f;

/**
 * Headless viewmodel sanity check through the exact production skinning path (Viewmodel.skins + skinVertices): prints
 * camera-space bounds and the worst triangle-edge stretch versus the bind pose (warped skinning shows up as large ratios).
 * gradlew vmCheck
 */
public final class VmCheck {
	public static void main(String[] a) throws Exception {
		VmModel arms = VmModel.load(Glb.read(AssetCache.exportGlb(Viewmodel.ARMS, true)), ".viewmodel");
		VmModel gun = VmModel.load(Glb.read(AssetCache.exportGlb("weapons/models/ak47/weapon_rif_ak47.vmdl_c", true)), ".body_hd");
		for (VmModel m : new VmModel[] {arms, gun})
			for (VmModel.Part p : m.parts) System.out.printf("part skin=%d joints=%d verts=%d%n", p.skin, p.skin >= 0 ? m.skins.get(p.skin).joints.length : 0, p.pos.length / 3);
		for (String anim : new String[] {"idle", "reload"}) {
			VmClip c = new VmClip(anim, Glb.read(AssetCache.exportGlb("animation/anims/viewmodel/rifle/rifle_ak/" + anim + "_ak.vnmclip_c", false)));
			for (float t : new float[] {0f, c.duration * 0.45f}) {
				Matrix4f[] pose = c.pose(t);
				Matrix4f wpn = Viewmodel.mount(c, pose);
				check(anim + " t=" + t + " arms", arms, Viewmodel.skins(arms, c, pose, null));
				check(anim + " t=" + t + " gun ", gun, Viewmodel.skins(gun, c, pose, wpn));
			}
		}
	}

	static void check(String label, VmModel m, Matrix4f[][] skins) {
		Matrix4f tv = Viewmodel.toView();
		float k = tv.getScale(new org.joml.Vector3f()).x;
		float[] mn = {1e9f, 1e9f, 1e9f}, mx = {-1e9f, -1e9f, -1e9f};
		int n = 0, tris = 0, stretched = 0;
		double worst = 0;
		for (VmModel.Part p : m.parts) {
			Matrix4f[] skin = p.skin >= 0 && p.skin < skins.length ? skins[p.skin] : new Matrix4f[0];
			float[] sp = Viewmodel.skinVertices(p, skin, tv)[0];
			for (int i = 0; i < sp.length / 3; i++, n++)
				for (int q = 0; q < 3; q++) {
					mn[q] = Math.min(mn[q], sp[i * 3 + q]);
					mx[q] = Math.max(mx[q], sp[i * 3 + q]);
				}
			for (int t = 0; t + 2 < p.idx.length; t += 3, tris++)
				for (int e = 0; e < 3; e++) {
					int u = p.idx[t + e], v = p.idx[t + (e + 1) % 3];
					double rest = dist(p.pos, u, v) * k, now = dist(sp, u, v);
					if (rest < 1e-4) continue;
					double r = now / rest;
					worst = Math.max(worst, r);
					if (r > 2 || r < 0.5) stretched++;
				}
		}
		System.out.printf("%-26s %6d verts %6d tris  x[%6.2f..%6.2f] y[%6.2f..%6.2f] z[%6.2f..%6.2f]  worst stretch %.2f, %d edges >2x/<0.5x%n",
				label, n, tris, mn[0], mx[0], mn[1], mx[1], mn[2], mx[2], worst, stretched);
	}

	private static double dist(float[] a, int u, int v) {
		double dx = a[u * 3] - a[v * 3], dy = a[u * 3 + 1] - a[v * 3 + 1], dz = a[u * 3 + 2] - a[v * 3 + 2];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
