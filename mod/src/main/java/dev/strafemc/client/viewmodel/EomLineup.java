package dev.strafemc.client.viewmodel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.strafemc.StrafeMc;
import dev.strafemc.data.AssetCache;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The end of match screen's agents (CS2's endofmatch-characters): each one plays CS2's end-of-round clips
 * (animation/anims/ui_anims/end_round/celebrate|defeat/ct|t/&lt;set&gt;_start, then its idle clips one after another),
 * celebrating when their side won, else the defeat set. The set is the agent's own (items_game default_defeat
 * "&lt;name&gt;_defeat": ava, ricksaw, mae, doctor, darryl, crasswater, vypa, muhlik; scuba_female goggles, scuba_male
 * wet_sox, gendarmerie rouchard), else one of their side's sets by their place in the line (FLAGGED: what CS2 gives
 * agents without one isn't in its files).
 */
public final class EomLineup {
	/** Each set's idle clips (by number), as CS2's files have them. */
	private static final Map<String, int[]> SETS = new LinkedHashMap<>();

	static {
		put("celebrate/ct/celebrate_ava", 1, 2, 3, 4);
		put("celebrate/ct/celebrate_goggles", 1, 2, 3, 4, 5);
		put("celebrate/ct/celebrate_mae", 1, 2, 3, 4);
		put("celebrate/ct/celebrate_ricksaw", 1, 2, 3, 4);
		put("celebrate/ct/celebrate_rouchard", 1, 2, 3, 4);
		put("celebrate/ct/celebrate_wet_sox", 1, 2, 3);
		put("celebrate/t/celebrate_crasswater", 1, 2, 3, 4, 5);
		put("celebrate/t/celebrate_darryl", 1, 2, 3, 4, 5, 6);
		put("celebrate/t/celebrate_doctor", 1, 2, 3, 4, 5);
		put("celebrate/t/celebrate_muhlik", 1, 2, 3, 4);
		put("celebrate/t/celebrate_vypa", 1, 2, 3, 4);
		put("defeat/ct/defeat_ava", 1, 2);
		put("defeat/ct/defeat_goggles", 1, 2);
		put("defeat/ct/defeat_mae", 1, 2, 3);
		put("defeat/ct/defeat_ricksaw", 1, 2);
		put("defeat/ct/defeat_rouchard", 1, 2);
		put("defeat/ct/defeat_wet_sox", 2, 3, 4);
		put("defeat/t/defeat_crasswater", 1, 2);
		put("defeat/t/defeat_darryl", 1, 2);
		put("defeat/t/defeat_doctor", 1, 2);
		put("defeat/t/defeat_muhlik", 1, 2);
		put("defeat/t/defeat_vypa", 1, 2);
	}

	private static final String[] CT_NAMES = {"ava", "goggles", "mae", "ricksaw", "rouchard", "wet_sox"}, T_NAMES = {"crasswater", "darryl", "doctor", "muhlik",
			"vypa"};
	/** Agent model to its set name (items_game default_defeat). */
	private static final Map<String, String> AGENT_SET = Map.ofEntries(Map.entry("ctm_diver_varianta", "goggles"), Map.entry("ctm_diver_variantb", "wet_sox"),
			Map.entry("ctm_fbi_variantb", "ava"), Map.entry("ctm_gendarmerie_variantc", "rouchard"), Map.entry("ctm_st6_varianti", "ricksaw"),
			Map.entry("ctm_swat_variante", "mae"), Map.entry("tm_balkan_varianth", "doctor"), Map.entry("tm_jungle_raider_variantb", "crasswater"),
			Map.entry("tm_jungle_raider_variantb2", "crasswater"), Map.entry("tm_jungle_raider_variante", "vypa"), Map.entry("tm_leet_variantf", "muhlik"),
			Map.entry("tm_professional_varf", "darryl"), Map.entry("tm_professional_varf1", "darryl"), Map.entry("tm_professional_varf2", "darryl"),
			Map.entry("tm_professional_varf3", "darryl"), Map.entry("tm_professional_varf4", "darryl"));
	private static final String BASE = "animation/anims/ui_anims/end_round/", DIR = "agent_eom/v1";

	private static volatile Map<String, VmClip> clips;
	private static volatile boolean loading, failed;

	private EomLineup() {}

	private static void put(String set, int... idles) {
		SETS.put(set, idles);
	}

	private static List<String> clipNames() {
		List<String> out = new ArrayList<>();
		for (var e : SETS.entrySet()) {
			out.add(BASE + e.getKey() + "_start");
			for (int i : e.getValue()) out.add(BASE + e.getKey() + String.format("_idle%02d", i));
		}
		return out;
	}

	/** The end-of-round clips, exported once and loaded in the background; null until then. */
	private static Map<String, VmClip> clips() {
		Map<String, VmClip> c = clips;
		if (c != null || loading || failed) return c;
		loading = true;
		Thread t = new Thread(() -> {
			try {
				var g = Glb.read(AssetCache.exportClips(AgentModels.CT_DEFAULT + "_c", DIR, clipNames()));
				Map<String, VmClip> out = new HashMap<>();
				var anims = g.arr("animations");
				for (int i = 0; i < anims.size(); i++) {
					String name = anims.get(i).getAsJsonObject().get("name").getAsString().replace("\\", "/");
					out.put(name, new VmClip(name, g, i));
				}
				StrafeMc.LOG.info("[strafemc] end of match clips: {} of {}", out.size(), clipNames().size());
				clips = out;
			} catch (Exception e) {
				failed = true;
				StrafeMc.LOG.warn("[strafemc] end of match clips failed: {}", e.toString());
			} finally {
				loading = false;
			}
		}, "strafemc-eom-clips");
		t.setDaemon(true);
		t.start();
		return null;
	}

	/** Starts loading what a lineup needs (the clips and each agent's body), so it's there when the screen opens. */
	public static void warm(List<String> models) {
		clips();
		for (String m : models) AgentModels.body(m);
	}

	/** The set an agent plays: its own, else its side's by its place in the line. */
	static String set(String model, boolean t, boolean won, int place) {
		String file = model.substring(model.lastIndexOf('/') + 1).replace(".vmdl", "");
		String name = AGENT_SET.get(file);
		String[] side = t ? T_NAMES : CT_NAMES;
		if (name == null || !java.util.Arrays.asList(side).contains(name)) name = side[Math.floorMod(place, side.length)];
		return (won ? "celebrate/" : "defeat/") + (t ? "t/" : "ct/") + (won ? "celebrate_" : "defeat_") + name;
	}

	/** Its pose at time sec since the screen opened: the start clip, then the idles in turn, round and round. */
	static Matrix4f[] pose(Map<String, VmClip> c, String set, double sec) {
		VmClip start = c.get(BASE + set + "_start");
		if (start != null && sec < start.duration) return start.pose((float) sec);
		double t = sec - (start != null ? start.duration : 0);
		int[] idles = SETS.getOrDefault(set, new int[0]);
		double total = 0;
		for (int i : idles) {
			VmClip k = c.get(BASE + set + String.format("_idle%02d", i));
			if (k != null) total += k.duration;
		}
		if (total <= 0) return start != null ? start.pose(start.duration) : null;
		t %= total;
		for (int i : idles) {
			VmClip k = c.get(BASE + set + String.format("_idle%02d", i));
			if (k == null) continue;
			if (t < k.duration) return k.pose((float) t);
			t -= k.duration;
		}
		return null;
	}

	/** One agent in the line: its model, side, whether its side won, its place (0 = the middle, you). */
	public record Member(String model, boolean t, boolean won, int place) {}

	/**
	 * Draws the line into a picture of width x height (GUI pixels, y down): the first in the middle, the others
	 * alternating right and left a step back, all facing the viewer; sec is the time since the screen opened. False
	 * while something is still loading.
	 */
	public static boolean draw(List<Member> line, double sec, PoseStack ps, MultiBufferSource buffers, float width, float height) {
		Map<String, VmClip> c = clips();
		if (c == null) return false;
		VmClip skeleton = c.get(BASE + "celebrate/ct/celebrate_ava_start");
		if (skeleton == null) return false;
		float k = height * 0.62f / 1.85f; // an agent ~1.85 m tall fills 62% of the height
		boolean drew = false;
		for (Member m : line) {
			List<VmModel> body = AgentModels.body(m.model());
			if (body == null) continue;
			String set = set(m.model(), m.t(), m.won(), m.place());
			Matrix4f[] pose = pose(c, set, sec);
			if (pose == null) continue;
			int slot = m.place() == 0 ? 0 : (m.place() + 1) / 2 * (m.place() % 2 == 1 ? 1 : -1); // 0, 1, -1, 2, -2
			float depth = Math.abs(slot);
			ps.pushPose();
			ps.translate(slot * width * 0.17f, height * 0.42f - depth * height * 0.02f, -depth * 50);
			float s = k * (1 - 0.07f * depth); // a step back looks a little smaller
			ps.scale(s, -s, s);
			ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-slot * 8)); // turned a little toward the middle
			for (VmModel body1 : body) {
				Matrix4f[][] skins = Viewmodel.skins(body1, skeleton, pose, null);
				for (VmModel.Part p : body1.parts) {
					float[][] r = Viewmodel.skinVertices(p, p.skin >= 0 && p.skin < skins.length ? skins[p.skin] : new Matrix4f[0], new Matrix4f());
					part(p, r[0], r[1], ps, buffers);
				}
			}
			ps.popPose();
			drew = true;
		}
		return drew;
	}

	private static void part(VmModel.Part p, float[] pos, float[] nrm, PoseStack ps, MultiBufferSource buffers) {
		if (p.texture == null || p.idx == null || p.alphaMode.equals("BLEND")) return;
		boolean mask = p.alphaMode.equals("MASK");
		Identifier tex = Viewmodel.INSTANCE.texture(p.texture, !mask);
		if (tex == null) return;
		VertexConsumer vc = buffers.getBuffer(mask ? RenderTypes.entityCutout(tex) : VmRender.opaque(tex));
		PoseStack.Pose last = ps.last();
		int[] idx = p.idx;
		for (int i = 0; i + 2 < idx.length; i += 3)
			for (int c : new int[] {idx[i], idx[i + 1], idx[i + 2], idx[i + 2]}) // entity render types draw quads
				vc.addVertex(last, pos[c * 3], pos[c * 3 + 1], pos[c * 3 + 2]).setColor(-1).setUv(p.uv[c * 2], p.uv[c * 2 + 1])
						.setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(last, nrm[c * 3], nrm[c * 3 + 1], nrm[c * 3 + 2]);
	}
}
