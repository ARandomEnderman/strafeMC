package dev.strafemc.client.viewmodel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.strafemc.StrafeMc;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.data.AssetCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The end of match line-up (CS2's end_of_match prefab, endofmatch-characters, the uimodel celebration graphs): who
 * plays what, where they stand and how they're drawn in the world.
 * <p>
 * Clips: each agent's celebration (items_game default_cheer: stretch = ava, dropdown = ricksaw, swat_female = mae,
 * punching = doctor, mask_f = darryl, guerilla = crasswater, guerilla02 = vypa, swagger = muhlik, gendarmerie = rouchard,
 * scuba_female = goggles, scuba_male = wet_sox; the graphs uimodel_celebration+celebrate_&lt;set&gt;) or, on the losing
 * side, its default_defeat (&lt;set&gt;_defeat): animation/anims/ui_anims/end_round/celebrate|defeat/ct|t/&lt;set&gt;_start
 * and its idles in turn. As CS2's script does, a cheer someone earlier in the line already plays, or none, is left
 * empty, which is the first celebration, WALKUP: the main menu's walk-up with their weapon
 * (ui_anims/main_menu/&lt;side&gt;/&lt;side&gt;_main_menu_&lt;weapon&gt;_walkup, then its idle).
 * <p>
 * Places (prefab space, units, +y toward the line from the camera): the line runs from end_of_match_lineup_start
 * (-98, -90) facing 320 deg to end_of_match_lineup_end (162, -90) facing 220 deg; the first in the list (you) at the
 * start end, each at (i + 1) / (n + 1) along it as CS2's stats cards are spaced; facing turns with it.
 */
public final class EomLineup {
	private static final String BASE = "animation/anims/ui_anims/", DIR = "agent_eom/v2";
	/** Each celebration / defeat set's idle clips (by number), as CS2's files have them. */
	private static final Map<String, int[]> SETS = new LinkedHashMap<>();

	static {
		put("end_round/celebrate/ct/celebrate_ava", 1, 2, 3, 4);
		put("end_round/celebrate/ct/celebrate_goggles", 1, 2, 3, 4, 5);
		put("end_round/celebrate/ct/celebrate_mae", 1, 2, 3, 4);
		put("end_round/celebrate/ct/celebrate_ricksaw", 1, 2, 3, 4);
		put("end_round/celebrate/ct/celebrate_rouchard", 1, 2, 3, 4);
		put("end_round/celebrate/ct/celebrate_wet_sox", 1, 2, 3);
		put("end_round/celebrate/t/celebrate_crasswater", 1, 2, 3, 4, 5);
		put("end_round/celebrate/t/celebrate_darryl", 1, 2, 3, 4, 5, 6);
		put("end_round/celebrate/t/celebrate_doctor", 1, 2, 3, 4, 5);
		put("end_round/celebrate/t/celebrate_muhlik", 1, 2, 3, 4);
		put("end_round/celebrate/t/celebrate_vypa", 1, 2, 3, 4);
		put("end_round/defeat/ct/defeat_ava", 1, 2);
		put("end_round/defeat/ct/defeat_goggles", 1, 2);
		put("end_round/defeat/ct/defeat_mae", 1, 2, 3);
		put("end_round/defeat/ct/defeat_ricksaw", 1, 2);
		put("end_round/defeat/ct/defeat_rouchard", 1, 2);
		put("end_round/defeat/ct/defeat_wet_sox", 2, 3, 4);
		put("end_round/defeat/t/defeat_crasswater", 1, 2);
		put("end_round/defeat/t/defeat_darryl", 1, 2);
		put("end_round/defeat/t/defeat_doctor", 1, 2);
		put("end_round/defeat/t/defeat_muhlik", 1, 2);
		put("end_round/defeat/t/defeat_vypa", 1, 2);
	}

	/** The cheer each set is (items_game default_cheer), by its set name. */
	private static final Map<String, String> CHEER_SET = Map.ofEntries(Map.entry("stretch", "ava"), Map.entry("dropdown", "ricksaw"),
			Map.entry("swat_female", "mae"), Map.entry("punching", "doctor"), Map.entry("mask_f", "darryl"), Map.entry("guerilla", "crasswater"),
			Map.entry("guerilla02", "vypa"), Map.entry("swagger", "muhlik"), Map.entry("gendarmerie", "rouchard"), Map.entry("scuba_female", "goggles"),
			Map.entry("scuba_male", "wet_sox"));
	/** Agent model file to {default_cheer, default_defeat set} (items_game; the agents with neither have none). */
	private static final Map<String, String[]> AGENT = Map.ofEntries(e("ctm_diver_varianta", "scuba_female", "goggles"),
			e("ctm_diver_variantb", "scuba_male", "wet_sox"), e("ctm_fbi_variantb", "stretch", "ava"), e("ctm_gendarmerie_variantc", "gendarmerie", "rouchard"),
			e("ctm_st6_varianti", "dropdown", "ricksaw"), e("ctm_swat_variante", "swat_female", "mae"), e("tm_balkan_varianth", "punching", "doctor"),
			e("tm_jungle_raider_variantb", "guerilla", "crasswater"), e("tm_jungle_raider_variantb2", "guerilla", "crasswater"),
			e("tm_jungle_raider_variante", "guerilla02", "vypa"), e("tm_leet_variantf", "swagger", "muhlik"), e("tm_professional_varf", "mask_f", "darryl"),
			e("tm_professional_varf1", "mask_f", "darryl"), e("tm_professional_varf2", "mask_f", "darryl"), e("tm_professional_varf3", "mask_f", "darryl"),
			e("tm_professional_varf4", "mask_f", "darryl"));

	private static Map.Entry<String, String[]> e(String model, String cheer, String set) {
		return Map.entry(model, new String[] {cheer, set});
	}

	/** The main menu walk-ups: {walkup, idle} per side and weapon family (the families both sides' files have). */
	private static final Map<String, String[]> WALKUP_CT = new HashMap<>(), WALKUP_T = new HashMap<>();

	static {
		for (String[] w : new String[][] {{"deagle", "deagle_walkup_alt", "pistol01_idle"}, {"elite", "elite_walkup", "elite_idle"},
				{"pistol", "pistol01_walkup", "pistol01_idle"}, {"revolver", "revolver_walkup", "revolver_idle"}, {"famas", "famas_walkup", "famas_idle"},
				{"m4", "rifle_walkup_handrepo_m4", "rifle_idle_handrepo_m4"}, {"m4s", "rifle_m4a1s_walkup_alt", "rifle_idle_handrepo_m4"},
				{"rifle", "rifle_walkup_handrepo_aug", "rifle_idle_handrepo_aug"}, {"awp", "rifle_awp_walkup", "rifle_awp_idle"},
				{"ssg08", "rifle_ssg08_walkup", "rifle_ssg08_idle"}, {"auto", "rifle_scar_walkup", "rifle_scar_idle"}, {"m249", "m249_walkup", "m249_idle"},
				{"negev", "negev_walkup", "negev_idle"}, {"mag7", "mag7_walkup", "mag7_idle"}, {"nova", "nova_walkup", "nova_idle"},
				{"shotgun", "xm1014_walkup", "xm1014_idle"}, {"mp7", "mp7_walkup", "mp7_idle"}, {"mp5", "mp5sd_walkup", "mp7_idle"},
				{"smg", "mp9_walkup", "mp7_idle"}, {"ump", "ump45_walkup", "mp7_idle"}, {"p90", "p90_walkup", "p90_idle"}, {"knife", "knife_walkup", "knife_idle"},
				{"grenade", "frag01_walkup", "frag01_idle"}, {"c4", "knife_walkup", "knife_idle"}})
			WALKUP_CT.put(w[0], new String[] {"main_menu/ct/ct_main_menu_" + w[1], "main_menu/ct/ct_main_menu_" + w[2]});
		for (String[] w : new String[][] {{"deagle", "deagle_walkup_alt", "pistol_idle"}, {"elite", "elite_walkup", "elite_idle"},
				{"pistol", "pistol_walkup", "pistol_idle"}, {"revolver", "revolver_walkup", "revolver_idle"}, {"ak", "rifle02_ak47_walkup_alt", "rifle02_idle"},
				{"awp", "rifle02_walkup_awp_galil", "rifle02_idle_awp_galil"}, {"auto", "rifle02_walkup_g3sg", "rifle02_idle_g3sg"},
				{"rifle", "rifle02_walkup", "rifle02_idle"}, {"m249", "m249_walkup", "m249_idle"}, {"negev", "negev_walkup", "negev_idle"},
				{"shotgun", "shotgun_walkup", "shotgun_idle"}, {"xm1014", "xm1014_walkup", "xm1014_idle"}, {"mp7", "mp7_walkup", "mp7_idle"},
				{"ump", "ump45_walkup", "ump45_idle"}, {"p90", "p90_walkup", "p90_idle"}, {"bizon", "bizon_walkup", "bizon_idle"},
				{"knife", "knife_walkup", "knife_idle"}, {"grenade", "frag_walkup", "frag_idle"}, {"molotov", "molotov_walkup", "molotov_idle"},
				{"c4", "c4_walkup", "c4_idle"}})
			WALKUP_T.put(w[0], new String[] {"main_menu/t/t_main_menu_" + w[1], "main_menu/t/t_main_menu_" + w[2]});
	}

	private static volatile Map<String, VmClip> clips;
	private static volatile boolean loading, failed;

	private EomLineup() {}

	private static void put(String set, int... idles) {
		SETS.put(set, idles);
	}

	/** A weapon class's walk-up family on a side. */
	static String family(String cls, boolean t) {
		String c = cls == null ? "" : cls.replace("weapon_", "");
		if (c.isEmpty() || c.contains("knife") || c.equals("bayonet")) return "knife";
		if (c.equals("c4")) return "c4";
		if (c.equals("molotov") || c.equals("incgrenade")) return t ? "molotov" : "grenade";
		if (c.endsWith("grenade") || c.equals("flashbang") || c.equals("decoy")) return "grenade";
		if (c.equals("deagle")) return "deagle";
		if (c.equals("elite")) return "elite";
		if (c.equals("revolver")) return "revolver";
		if (List.of("glock", "hkp2000", "usp_silencer", "p250", "fiveseven", "cz75a", "tec9", "taser").contains(c)) return "pistol";
		if (c.equals("awp") || t && c.equals("galilar")) return "awp";
		if (c.equals("ssg08")) return t ? "rifle" : "ssg08";
		if (c.equals("scar20") || c.equals("g3sg1")) return "auto";
		if (c.equals("m249")) return "m249";
		if (c.equals("negev")) return "negev";
		if (c.equals("ak47")) return t ? "ak" : "rifle";
		if (c.equals("m4a1")) return t ? "rifle" : "m4";
		if (c.equals("m4a1_silencer")) return t ? "rifle" : "m4s";
		if (c.equals("famas")) return t ? "rifle" : "famas";
		if (c.equals("xm1014")) return t ? "xm1014" : "shotgun";
		if (c.equals("nova")) return t ? "shotgun" : "nova";
		if (c.equals("mag7") || c.equals("sawedoff")) return t ? "shotgun" : "mag7";
		if (c.equals("mp7")) return "mp7";
		if (c.equals("mp5sd")) return t ? "mp7" : "mp5";
		if (c.equals("mp9") || c.equals("mac10")) return t ? "mp7" : "smg";
		if (c.equals("ump45")) return "ump";
		if (c.equals("p90")) return "p90";
		if (c.equals("bizon")) return t ? "bizon" : "p90";
		return "rifle";
	}

	private static List<String> clipNames() {
		List<String> out = new ArrayList<>();
		for (var en : SETS.entrySet()) {
			out.add(BASE + en.getKey() + "_start");
			for (int i : en.getValue()) out.add(BASE + en.getKey() + String.format("_idle%02d", i));
		}
		Set<String> mm = new HashSet<>();
		for (var m : List.of(WALKUP_CT, WALKUP_T)) for (String[] w : m.values()) for (String c : w) if (mm.add(c)) out.add(BASE + c);
		return out;
	}

	/** The line-up's clips, exported once and loaded in the background; null until then. */
	static Map<String, VmClip> clips() {
		Map<String, VmClip> c = clips;
		if (c != null || loading || failed) return c;
		loading = true;
		Thread th = new Thread(() -> {
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
			} catch (Exception ex) {
				failed = true;
				StrafeMc.LOG.warn("[strafemc] end of match clips failed: {}", ex.toString());
			} finally {
				loading = false;
			}
		}, "strafemc-eom-clips");
		th.setDaemon(true);
		th.start();
		return null;
	}

	/** One in the line: their agent model, entity (for their skin), name, side, whether their side won, weapon class. */
	public record Member(String model, int entity, String name, boolean t, boolean won, String weapon) {}

	/** What a member plays: {first clip, idle clips...} (base-relative names), picked as CS2 does down the line. */
	static List<String[]> plays(List<Member> line) {
		List<String[]> out = new ArrayList<>();
		Set<String> used = new HashSet<>();
		for (Member m : line) {
			String file = m.model().substring(m.model().lastIndexOf('/') + 1).replace(".vmdl", "");
			String[] a = AGENT.get(file);
			String cheer = a == null ? "" : m.won() ? a[0] : a[1] + "_defeat";
			if (!cheer.isEmpty() && !used.add(cheer)) cheer = ""; // someone earlier plays it: WALKUP
			String set = cheer.isEmpty() ? null : (m.won() ? "end_round/celebrate/" + (m.t() ? "t/" : "ct/") + "celebrate_" + CHEER_SET.get(a[0])
					: "end_round/defeat/" + (m.t() ? "t/" : "ct/") + "defeat_" + a[1]);
			if (set != null && SETS.containsKey(set)) {
				List<String> idles = new ArrayList<>();
				for (int i : SETS.get(set)) idles.add(set + String.format("_idle%02d", i));
				String[] seq = new String[idles.size() + 1];
				seq[0] = set + "_start";
				for (int i = 0; i < idles.size(); i++) seq[i + 1] = idles.get(i);
				out.add(seq);
			} else {
				String[] w = (m.t() ? WALKUP_T : WALKUP_CT).getOrDefault(family(m.weapon(), m.t()), (m.t() ? WALKUP_T : WALKUP_CT).get("knife"));
				out.add(new String[] {w[0], w[1]});
			}
		}
		return out;
	}

	/** The pose sec seconds in: the first clip once, then the idles in turn, round and round; null if a clip is missing. */
	static Matrix4f[] pose(Map<String, VmClip> c, String[] seq, double sec) {
		VmClip first = c.get(BASE + seq[0]);
		if (first == null) return null;
		if (sec < first.duration || seq.length == 1) return first.pose((float) Math.min(sec, first.duration));
		double t = sec - first.duration, total = 0;
		for (int i = 1; i < seq.length; i++) {
			VmClip k = c.get(BASE + seq[i]);
			if (k != null) total += k.duration;
		}
		if (total <= 0) return first.pose(first.duration);
		t %= total;
		for (int i = 1; i < seq.length; i++) {
			VmClip k = c.get(BASE + seq[i]);
			if (k == null) continue;
			if (t < k.duration) return k.pose((float) t);
			t -= k.duration;
		}
		return first.pose(first.duration);
	}

	// ---------- the scene: CS2's end_of_match prefab in Minecraft blocks ----------

	/** Prefab units to blocks. */
	private static double u() {
		return 1 / StrafeConfig.unitsPerBlock;
	}

	/** A prefab point (x right, y away from the camera; units) to the world, for the prefab placed at o facing yaw (MC degrees). */
	static Vec3 world(Vec3 o, float yaw, double x, double y, double z) {
		double r = Math.toRadians(yaw);
		double fx = -Math.sin(r), fz = Math.cos(r), rx = -Math.cos(r), rz = -Math.sin(r);
		double k = u();
		return new Vec3(o.x + (rx * x + fx * y) * k, o.y + z * k, o.z + (rz * x + fz * y) * k);
	}

	/** A prefab yaw (Source degrees: 90 = away from the camera) to an MC yaw for the prefab facing yaw. */
	static float yawOf(float prefabYaw, double sourceYaw) {
		return prefabYaw + (float) (90 - sourceYaw);
	}

	/** Where member i of n stands (world) and which way they face (MC yaw). */
	static Object[] place(Vec3 o, float yaw, int i, int n) {
		double t = (i + 1.0) / (n + 1.0);
		Vec3 at = world(o, yaw, -98 + 260 * t, -90, 0);
		return new Object[] {at, yawOf(yaw, 320 - 100 * t)};
	}

	/**
	 * The end_of_match_camera over its 5 s: the path from (-47.4, -280.7) facing 70 deg through (32, -300) facing 90 to
	 * (111.4, -280.7) facing 110, 32 units up, with its tangents, easing out at the end (ease_out 100). {position, yaw}.
	 */
	static Object[] camera(Vec3 o, float yaw, double sec) {
		double s = Math.min(1, sec / 5.0);
		s = 1 - (1 - s) * (1 - s); // ease out
		double[][] p = {{-47.4, -280.7}, {32, -300}, {111.4, -280.7}}, out = {{3.006783, -27.07086}, {-0.000002, -27.237331}}, in = {{0.000002, 27.237331},
				{3.006781, 27.07086}};
		int seg = s < 0.5 ? 0 : 1;
		double f = s < 0.5 ? s * 2 : (s - 0.5) * 2;
		// Hermite between the nodes; Hammer tangents point from the node (in: backwards), x3 as a cubic's derivative
		double[] a = p[seg], b = p[seg + 1], ta = {out[seg][0] * 3, out[seg][1] * 3}, tb = {-in[seg][0] * 3, -in[seg][1] * 3};
		double h1 = 2 * f * f * f - 3 * f * f + 1, h2 = -2 * f * f * f + 3 * f * f, h3 = f * f * f - 2 * f * f + f, h4 = f * f * f - f * f;
		double x = h1 * a[0] + h2 * b[0] + h3 * ta[0] + h4 * tb[0], y = h1 * a[1] + h2 * b[1] + h3 * ta[1] + h4 * tb[1];
		double camYaw = s < 0.5 ? 70 + 20 * f : 90 + 20 * f;
		return new Object[] {world(o, yaw, x, y, 32), yawOf(yaw, camYaw)};
	}

	// ---------- drawing ----------

	/** An agent in its pose at a world spot (camera-relative pose stack), with its weapon in hand. False while loading. */
	static boolean submitAgent(Member m, String[] seq, double sec, Vec3 at, float yawDeg, Vec3 cam, PoseStack ps, SubmitNodeCollector out, int light) {
		Map<String, VmClip> c = clips();
		List<VmModel> body = AgentModels.body(m.model());
		if (c == null || body == null) return false;
		VmClip skeleton = c.get(BASE + seq[0]);
		Matrix4f[] pose = pose(c, seq, sec);
		if (skeleton == null || pose == null) return false;
		float k = (float) (39.3701 / StrafeConfig.unitsPerBlock);
		ps.pushPose();
		ps.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
		ps.mulPose(Axis.YP.rotationDegrees(-yawDeg));
		ps.scale(k, k, k);
		for (VmModel bm : body) {
			Matrix4f[][] skins = Viewmodel.skins(bm, skeleton, pose, null);
			for (VmModel.Part p : bm.parts) {
				float[][] r = Viewmodel.skinVertices(p, p.skin >= 0 && p.skin < skins.length ? skins[p.skin] : new Matrix4f[0], new Matrix4f());
				AgentModels.part(p, r[0], r[1], ps, out, light);
			}
		}
		AgentModels.Gun g = m.weapon() == null ? null : AgentModels.gun(m.weapon());
		Matrix4f gat = g == null ? null : AgentModels.gunAt(skeleton, pose, g, AgentModels.handAttachment(m.model()));
		if (gat != null) {
			ps.pushPose();
			ps.mulPose(gat);
			for (int i = 0; i < g.model().parts.size(); i++) AgentModels.part(g.model().parts.get(i), g.pos()[i], g.nrm()[i], ps, out, light);
			ps.popPose();
		}
		ps.popPose();
		return true;
	}

	private static net.minecraft.client.model.player.PlayerModel wide, slim;

	/**
	 * Without agent models: the Minecraft player model in the member's own skin, posed from the same clip. Each limb
	 * points the way its CS2 bones do: arm shoulder to hand (upper and lower arm averaged out), leg hip to ankle, body
	 * neck to pelvis, head neck to head; the whole body turned the way the hips face. False while loading.
	 */
	static boolean submitMinecraft(Member m, String[] seq, double sec, Vec3 at, float yawDeg, Vec3 cam, PoseStack ps, SubmitNodeCollector out, int light) {
		Map<String, VmClip> c = clips();
		if (c == null) return false;
		VmClip skel = c.get(BASE + seq[0]);
		Matrix4f[] pose = pose(c, seq, sec);
		if (skel == null || pose == null) return false;
		var mc = Minecraft.getInstance();
		net.minecraft.world.entity.player.PlayerSkin skin = skin(m);
		boolean isSlim = skin.model() == net.minecraft.world.entity.player.PlayerModelType.SLIM;
		if (wide == null) {
			wide = new net.minecraft.client.model.player.PlayerModel(mc.getEntityModels().bakeLayer(net.minecraft.client.model.geom.ModelLayers.PLAYER), false);
			slim = new net.minecraft.client.model.player.PlayerModel(mc.getEntityModels().bakeLayer(net.minecraft.client.model.geom.ModelLayers.PLAYER_SLIM), true);
		}
		var model = isSlim ? slim : wide;
		model.root().getAllParts().forEach(net.minecraft.client.model.geom.ModelPart::resetPose);
		Vector3f pelvis = pos(skel, pose, "pelvis"), neck = pos(skel, pose, "neck_0"), head = pos(skel, pose, "head_0");
		Vector3f lu = pos(skel, pose, "leg_upper_L"), ru = pos(skel, pose, "leg_upper_R");
		if (pelvis == null || neck == null || head == null || lu == null || ru == null) return false;
		// the skeleton's right side to Minecraft's model right (-x), y down, forward -z
		float sx = ru.x > lu.x ? -1 : 1;
		// the hips' facing: the whole model turns with it
		float hipYaw = (float) Math.toDegrees(Math.atan2(-(ru.z - lu.z), -sx * (ru.x - lu.x)));
		limb(model.body, mcDir(sx, sub(pelvis, neck)));
		limb(model.head, mcDir(sx, sub(neck, head)));
		limb(model.rightArm, mcDir(sx, sub(pos(skel, pose, "hand_R"), pos(skel, pose, "arm_upper_R"))));
		limb(model.leftArm, mcDir(sx, sub(pos(skel, pose, "hand_L"), pos(skel, pose, "arm_upper_L"))));
		limb(model.rightLeg, mcDir(sx, sub(pos(skel, pose, "ankle_R"), ru)));
		limb(model.leftLeg, mcDir(sx, sub(pos(skel, pose, "ankle_L"), lu)));
		ps.pushPose();
		ps.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
		ps.mulPose(Axis.YP.rotationDegrees(180 - yawDeg + hipYaw));
		ps.scale(-1, -1, 1);
		ps.translate(0, -1.501, 0);
		out.submitModelPart(model.root(), ps, RenderTypes.entityTranslucent(skin.body().texturePath()), light, OverlayTexture.NO_OVERLAY, null);
		ps.popPose();
		return true;
	}

	/** A member's Minecraft skin: their entity's if it's here, their player list entry's, else Minecraft's default. */
	private static net.minecraft.world.entity.player.PlayerSkin skin(Member m) {
		var mc = Minecraft.getInstance();
		var e = mc.level == null ? null : mc.level.getEntity(m.entity());
		if (e instanceof net.minecraft.client.entity.ClientAvatarEntity a) return a.getSkin();
		var info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(m.name());
		if (info != null) return info.getSkin();
		return net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkin();
	}

	private static Vector3f pos(VmClip skel, Matrix4f[] pose, String bone) {
		Integer i = skel.node(bone);
		return i == null || pose[i] == null ? null : pose[i].getTranslation(new Vector3f());
	}

	private static Vector3f sub(Vector3f a, Vector3f b) {
		return a == null || b == null ? null : new Vector3f(a).sub(b);
	}

	/** A direction in the skeleton's space (y up, +z forward) to Minecraft's model space (y down, -z forward, right -x). */
	private static Vector3f mcDir(float sx, Vector3f d) {
		if (d == null || d.lengthSquared() < 1e-8f) return null;
		return new Vector3f(sx * d.x, -d.y, -d.z).normalize();
	}

	/** Turns a part so its hanging direction (0, 1, 0) points along v: xRot = asin(v.z), zRot = atan2(-v.x, v.y). */
	private static void limb(net.minecraft.client.model.geom.ModelPart part, Vector3f v) {
		if (v == null) return;
		part.xRot = (float) Math.asin(Math.max(-1, Math.min(1, v.z)));
		part.zRot = (float) Math.atan2(-v.x, v.y);
	}

	/** Starts loading what a line-up needs (the clips and each agent's body), so it's there when the match ends. */
	public static void warm(List<String> models) {
		clips();
		if (StrafeConfig.agentModels) for (String md : models) AgentModels.body(md);
	}
}
