package dev.strafemc.client.viewmodel;

import dev.strafemc.data.AssetCache;
import org.joml.Matrix4f;

/**
 * Arm skin distortion around the twist bones over whole clips: mean |log(edge length / bind length)| of edges whose
 * vertices are weighted to forearm / upper-arm / twist bones. gradlew vmCheck -PvmMain=dev.strafemc.client.viewmodel.TwistCheck
 */
public final class TwistCheck {
	public static void main(String[] a) throws Exception {
		VmModel arms = VmModel.load(Glb.read(AssetCache.exportGlb(Viewmodel.ARMS, true)), ".viewmodel");
		for (String mode : new String[] {"rigid", "twist"}) {
			Viewmodel.twistOff = mode.equals("rigid");
			double total = 0;
			StringBuilder line = new StringBuilder();
			for (String[] wa : new String[][] {{"weapon_ak47", "draw"}, {"weapon_ak47", "idle"}, {"weapon_ak47", "reload"}, {"weapon_ak47", "lookat01"},
					{"weapon_knife_karambit", "lookat01"}, {"weapon_deagle", "lookat02"}, {"weapon_knife_butterfly", "draw"}}) {
				String anim = wa[0].replace("weapon_", "") + ":" + wa[1];
				VmClip c = new VmClip(wa[1], Glb.read(AssetCache.exportGlb(dev.strafemc.weapon.GunServer.clipPath(wa[0], wa[1]), false)));
				double sum = 0;
				int frames = 24;
				for (int f = 0; f < frames; f++) sum += distortion(arms, c, c.pose(c.duration * f / frames));
				line.append(String.format("  %s %.4f", anim, sum / frames));
				total += sum / frames;
			}
			System.out.printf("%-6s total %.4f%s%n", mode, total, line);
		}
	}

	private static double distortion(VmModel m, VmClip c, Matrix4f[] pose) {
		Matrix4f[][] skins = Viewmodel.skins(m, c, pose, null);
		Matrix4f id = new Matrix4f();
		double sum = 0;
		int n = 0;
		for (VmModel.Part p : m.parts) {
			if (p.skin < 0 || p.jnt == null) continue;
			VmModel.Skin sk = m.skins.get(p.skin);
			float[] sp = Viewmodel.skinVertices(p, skins[p.skin], id)[0];
			boolean[] arm = new boolean[p.pos.length / 3];
			for (int i = 0; i < arm.length; i++)
				for (int k = 0; k < 4; k++) {
					String jn = sk.joints[Math.min(p.jnt[i * 4 + k], sk.joints.length - 1)];
					if (p.wts[i * 4 + k] > 0.05f && jn.startsWith("arm_")) arm[i] = true;
				}
			for (int t = 0; t + 2 < p.idx.length; t += 3)
				for (int e = 0; e < 3; e++) {
					int u = p.idx[t + e], v = p.idx[t + (e + 1) % 3];
					if (!arm[u] || !arm[v]) continue;
					double b = dist(p.pos, u, v), now = dist(sp, u, v);
					if (b < 1e-6 || now < 1e-9) continue;
					sum += Math.abs(Math.log(now / b));
					n++;
				}
		}
		return n == 0 ? 0 : sum / n;
	}

	private static double dist(float[] a, int i, int j) {
		double x = a[i * 3] - a[j * 3], y = a[i * 3 + 1] - a[j * 3 + 1], z = a[i * 3 + 2] - a[j * 3 + 2];
		return Math.sqrt(x * x + y * y + z * z);
	}
}
