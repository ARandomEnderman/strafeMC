package dev.strafemc.client.viewmodel;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Third-person agents posed headless (AgentModels.blend), drawn flat-shaded from the front and the side (body grey, gun
 * red) into one picture, a row per move: gradlew vmCheck -PvmMain=dev.strafemc.client.viewmodel.AgentPoseCheck
 * -PvmArgs=<out.png>[,<agent model>]
 */
public final class AgentPoseCheck {
	private static final int CELL = 300;
	private static final float PX_PER_M = 140;

	private record Move(String label, String type, String gun, double angle, double speed, boolean crouch, boolean air, double phase, float pitch) {}

	public static void main(String[] a) throws Exception {
		dev.strafemc.config.StrafeConfig.load();
		String model = a.length > 1 ? a[1] : AgentModels.CT_DEFAULT;
		Map<String, VmClip> clips = AgentModels.readClips();
		System.out.println("clips " + clips.size() + " of " + AgentModels.clipNames().size());
		List<VmModel> body = AgentModels.readBody(model);
		for (var e : Viewmodel.attachments(model + "_c").entrySet())
			System.out.println("attachment " + e.getKey() + " on " + e.getValue().bone() + " " + e.getValue().offset() + " " + e.getValue().rotation());
		VmClip skel = clips.get(AgentModels.clipName("rifle", "idle"));
		List<Move> moves = List.of(new Move("rifle idle", "rifle", "weapon_ak47", 0, 0, false, false, 0, 0),
				new Move("rifle run n", "rifle", "weapon_ak47", 0, 250, false, false, 0.25, 0),
				new Move("rifle run e", "rifle", "weapon_ak47", 90, 250, false, false, 0.25, 0),
				new Move("rifle crouch", "rifle", "weapon_ak47", 0, 0, true, false, 0, 0),
				new Move("rifle air", "rifle", "weapon_ak47", 0, 0, false, true, 0, 0),
				new Move("rifle look down 60", "rifle", "weapon_ak47", 0, 0, false, false, 0, 60),
				new Move("pistol walk n", "pistol", "weapon_glock", 0, 130, false, false, 0.5, 0),
				new Move("knife run n", "knife", "weapon_knife", 0, 250, false, false, 0.5, 0));
		BufferedImage img = new BufferedImage(CELL * 2, CELL * moves.size(), BufferedImage.TYPE_INT_RGB);
		for (int row = 0; row < moves.size(); row++) {
			Move m = moves.get(row);
			Matrix4f[] pose = AgentModels.blend(clips, m.type(), m.angle(), m.speed(), m.crouch(), m.air(), m.phase(), 0.3, m.pitch());
			if (pose == null) {
				System.out.println(m.label() + ": no pose");
				continue;
			}
			float[][] zf = {new float[CELL * CELL], new float[CELL * CELL]};
			for (float[] z : zf) java.util.Arrays.fill(z, -1e9f);
			for (VmModel vm : body) {
				Matrix4f[][] skins = Viewmodel.skins(vm, skel, pose, null);
				for (VmModel.Part p : vm.parts) {
					if (p.idx == null) continue;
					float[][] r = Viewmodel.skinVertices(p, p.skin >= 0 && p.skin < skins.length ? skins[p.skin] : new Matrix4f[0], new Matrix4f());
					draw(img, zf, row, r[0], p.idx, null, 0xB0B0B0);
				}
			}
			AgentModels.Gun g = AgentModels.readGun(dev.strafemc.weapon.WeaponTable.get(m.gun()).model());
			var att = AgentModels.handAttachment(model);
			Matrix4f at = AgentModels.gunAt(skel, pose, g, att);
			for (int i = 0; i < g.model().parts.size(); i++) {
				VmModel.Part p = g.model().parts.get(i);
				if (p.idx != null && at != null) draw(img, zf, row, g.pos()[i], p.idx, at, 0xE04040);
			}
			var gfx = img.createGraphics();
			gfx.setColor(java.awt.Color.WHITE);
			gfx.drawString(m.label() + "  (front | side, facing right)", 6, row * CELL + 14);
			gfx.dispose();
			Integer hand = skel.node("hand_R"), head = skel.node("head_0");
			System.out.println(m.label() + ": head " + pos(pose, head) + " hand_R " + pos(pose, hand));
		}
		Path out = Path.of(a[0]);
		Files.createDirectories(out.toAbsolutePath().getParent());
		ImageIO.write(img, "png", out.toFile());
		System.out.println("wrote " + out.toAbsolutePath());
	}

	private static String pos(Matrix4f[] pose, Integer i) {
		if (i == null || pose[i] == null) return "-";
		Vector3f v = pose[i].getTranslation(new Vector3f());
		return String.format(java.util.Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
	}

	/** Triangles in model space (meters, +Y up, +Z forward), front view (left cell) and side view (right cell). */
	private static void draw(BufferedImage img, float[][] zf, int row, float[] pos, int[] idx, Matrix4f xf, int rgb) {
		Vector3f[] v = new Vector3f[3];
		for (int t = 0; t + 2 < idx.length; t += 3) {
			for (int k = 0; k < 3; k++) {
				int c = idx[t + k];
				v[k] = new Vector3f(pos[c * 3], pos[c * 3 + 1], pos[c * 3 + 2]);
				if (xf != null) xf.transformPosition(v[k]);
			}
			Vector3f n = new Vector3f(v[1]).sub(v[0]).cross(new Vector3f(v[2]).sub(v[0])).normalize();
			for (int view = 0; view < 2; view++) {
				// front: from +Z looking back at the model (its left on the right of the picture); side: from its right (-X), forward to the right
				float[] sx = new float[3], sy = new float[3], sz = new float[3];
				for (int k = 0; k < 3; k++) {
					float x = view == 0 ? v[k].x : v[k].z, depth = view == 0 ? v[k].z : -v[k].x;
					sx[k] = view * CELL + CELL / 2f + x * PX_PER_M;
					sy[k] = row * CELL + CELL - 12 - v[k].y * PX_PER_M;
					sz[k] = depth;
				}
				float shade = 0.25f + 0.75f * Math.abs(view == 0 ? n.z : n.x);
				int col = ((int) ((rgb >> 16 & 255) * shade) << 16) | ((int) ((rgb >> 8 & 255) * shade) << 8) | (int) ((rgb & 255) * shade);
				fill(img, zf[view], view * CELL, row * CELL, sx, sy, sz, col);
			}
		}
	}

	private static void fill(BufferedImage img, float[] z, int cx, int cy, float[] x, float[] y, float[] d, int col) {
		int x0 = (int) Math.max(cx, Math.floor(Math.min(x[0], Math.min(x[1], x[2])))), x1 = (int) Math.min(cx + CELL - 1, Math.ceil(Math.max(x[0], Math.max(x[1], x[2]))));
		int y0 = (int) Math.max(cy, Math.floor(Math.min(y[0], Math.min(y[1], y[2])))), y1 = (int) Math.min(cy + CELL - 1, Math.ceil(Math.max(y[0], Math.max(y[1], y[2]))));
		float area = (x[1] - x[0]) * (y[2] - y[0]) - (x[2] - x[0]) * (y[1] - y[0]);
		if (Math.abs(area) < 1e-6f) return;
		for (int py = y0; py <= y1; py++)
			for (int px = x0; px <= x1; px++) {
				float w0 = ((x[1] - px) * (y[2] - py) - (x[2] - px) * (y[1] - py)) / area;
				float w1 = ((x[2] - px) * (y[0] - py) - (x[0] - px) * (y[2] - py)) / area;
				float w2 = 1 - w0 - w1;
				if (w0 < 0 || w1 < 0 || w2 < 0) continue;
				float dep = w0 * d[0] + w1 * d[1] + w2 * d[2];
				int zi = (py - cy) * CELL + (px - cx);
				if (dep <= z[zi]) continue;
				z[zi] = dep;
				img.setRGB(px, py, col);
			}
	}
}
