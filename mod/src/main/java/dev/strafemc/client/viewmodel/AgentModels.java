package dev.strafemc.client.viewmodel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.strafemc.StrafeMc;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.data.AssetCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Players (and bots) drawn as CS2 agents instead of Minecraft's player model, when turned on (StrafeConfig.agentModels,
 * off by default): each player wears the agent they picked for their side (MenuLoadout.AGENTS), else the side's
 * default (tm_phoenix / ctm_sas). The body moves with CS2's own third-person clips (animation/anims/world/<type>/
 * _default_<type>: idle, walk / run / crouch in eight directions, in the air) for the weapon type in hand, blended by
 * the direction and speed the player moves at; the look pitch bends the spine and head. The gun in hand is CS2's model
 * on the weapon_hand_r bone (bone-merged as CS2 does: the gun's "weapon" bone onto it).
 */
public final class AgentModels {
	public static final String T_DEFAULT = "agents/models/tm_phoenix/tm_phoenix.vmdl", CT_DEFAULT = "agents/models/ctm_sas/ctm_sas.vmdl";
	/** Every agent shares CS2's player skeleton: the clips are exported once, from one of them. */
	private static final String CLIP_SOURCE = CT_DEFAULT, CLIP_DIR = "agent_anims/v1";
	private static final String[] TYPES = {"rifle", "pistol", "knife"}, DIRS = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};
	/** CS2 move speeds (units/s) the clips are played at their own rate for. FLAGGED ours: the clips carry no root motion. */
	private static final double WALK = 130, RUN = 250, CROUCH = 85;

	private static volatile Map<String, VmClip> clips;
	private static volatile boolean clipsLoading;
	private static final Map<String, List<VmModel>> BODIES = new ConcurrentHashMap<>();
	private static final Map<String, Gun> GUNS = new ConcurrentHashMap<>();
	private static final Set<String> LOADING = ConcurrentHashMap.newKeySet(), FAILED = ConcurrentHashMap.newKeySet();
	/** Per entity: its place in the walk cycle (0..1) and when it was last drawn (System.nanoTime). */
	private static final Map<Integer, double[]> PHASE = new ConcurrentHashMap<>();

	/** A gun at rest: its parts' vertices in its own model space, and its "weapon" bone (what goes on weapon_hand_r). */
	record Gun(VmModel model, float[][] pos, float[][] nrm, Matrix4f weaponBone) {}

	private AgentModels() {}

	static String clipName(String type, String move) {
		return "animation/anims/world/" + type + "/_default_" + type + "/" + move + "_" + type;
	}

	static List<String> clipNames() {
		List<String> out = new ArrayList<>();
		for (String t : TYPES) {
			out.add(clipName(t, "idle"));
			out.add(clipName(t, "idle_crouch"));
			out.add(clipName(t, "inair_stand"));
			for (String d : DIRS) for (String m : new String[] {"walk", "run", "crouch"}) out.add(clipName(t, m + "_" + d));
		}
		return out;
	}

	/**
	 * Draws the player as their agent instead of Minecraft's model; false (Minecraft draws it) when agent models are off,
	 * the player can't be seen as one, or the agent / clips are still loading.
	 */
	public static boolean submit(AvatarRenderState s, PoseStack ps, SubmitNodeCollector out) {
		if (!StrafeConfig.agentModels || s.isInvisible || s.isSpectator || dev.strafemc.client.Placeholders.active()) return false;
		var mc = Minecraft.getInstance();
		Entity e = mc.level == null ? null : mc.level.getEntity(s.id);
		if (!(e instanceof net.minecraft.world.entity.LivingEntity le)) return false;
		Map<String, VmClip> c = clips();
		List<VmModel> body = body(model(le));
		if (c == null || body == null) return false;
		String held = heldClass(le);
		Matrix4f[] pose = pose(le, s, c, held);
		VmClip skeleton = c.get(clipName("rifle", "idle"));
		if (pose == null || skeleton == null) return false;
		float k = (float) (39.3701 / StrafeConfig.unitsPerBlock); // meters -> units -> blocks
		ps.pushPose();
		ps.mulPose(Axis.YP.rotationDegrees(-(s.bodyRot + s.yRot))); // the model faces +Z: turned to where the player looks
		ps.scale(k, k, k);
		int light = s.lightCoords;
		for (VmModel m : body) {
			Matrix4f[][] skins = Viewmodel.skins(m, skeleton, pose, null);
			for (VmModel.Part p : m.parts) {
				float[][] r = Viewmodel.skinVertices(p, p.skin >= 0 && p.skin < skins.length ? skins[p.skin] : new Matrix4f[0], new Matrix4f());
				part(p, r[0], r[1], ps, out, light);
			}
		}
		Gun g = held == null ? null : gun(held);
		Matrix4f at = g == null ? null : gunAt(skeleton, pose, g, handAttachment(model(le)));
		if (at != null) {
			ps.pushPose();
			ps.mulPose(at);
			for (int i = 0; i < g.model().parts.size(); i++) part(g.model().parts.get(i), g.pos()[i], g.nrm()[i], ps, out, light);
			ps.popPose();
		}
		ps.popPose();
		return true;
	}

	private static void part(VmModel.Part p, float[] pos, float[] nrm, PoseStack ps, SubmitNodeCollector out, int light) {
		if (p.texture == null || p.idx == null || p.alphaMode.equals("BLEND")) return;
		boolean mask = p.alphaMode.equals("MASK");
		Identifier tex = Viewmodel.INSTANCE.texture(p.texture, !mask);
		if (tex == null) return;
		out.submitCustomGeometry(ps, mask ? RenderTypes.entityCutout(tex) : VmRender.opaque(tex), (pose, vc) -> {
			int[] idx = p.idx;
			for (int i = 0; i + 2 < idx.length; i += 3)
				for (int c : new int[] {idx[i], idx[i + 1], idx[i + 2], idx[i + 2]}) // entity render types draw quads
					vc.addVertex(pose, pos[c * 3], pos[c * 3 + 1], pos[c * 3 + 2]).setColor(-1).setUv(p.uv[c * 2], p.uv[c * 2 + 1])
							.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nrm[c * 3], nrm[c * 3 + 1], nrm[c * 3 + 2]);
		});
	}

	/** The weapon class in the entity's main hand (a gun, knife, grenade or the C4), or null. */
	private static String heldClass(net.minecraft.world.entity.LivingEntity e) {
		return e.getMainHandItem().getItem() instanceof dev.strafemc.weapon.GunItem g ? g.weaponClass : null;
	}

	/** Which clip set fits what's in hand: pistols and the Zeus "pistol", guns "rifle", knives, grenades, the C4 and empty hands "knife". */
	static String type(String cls) {
		if (cls == null) return "knife";
		var w = dev.strafemc.weapon.WeaponTable.get(cls);
		if (w == null || w.knife()) return "knife";
		int g = dev.strafemc.weapon.WeaponTable.group(cls);
		if (g == 0 || cls.equals("weapon_taser")) return "pistol";
		return g < dev.strafemc.weapon.WeaponTable.GROUPS.size() ? "rifle" : "knife";
	}

	/** The agent model this entity wears: the one its player picked for their side, else that side's default. */
	private static String model(net.minecraft.world.entity.LivingEntity e) {
		boolean t = dev.strafemc.weapon.CsTeam.isT(e);
		String id = e instanceof net.minecraft.world.entity.player.Player p ? dev.strafemc.weapon.MenuLoadout.agent(p, t) : "";
		dev.strafemc.client.Agents.Agent a = dev.strafemc.client.Agents.get(id);
		return a != null ? a.model() : t ? T_DEFAULT : CT_DEFAULT;
	}

	/** This entity's pose now: its move relative to where it looks, its walk cycle kept per entity. */
	private static Matrix4f[] pose(net.minecraft.world.entity.LivingEntity e, AvatarRenderState s, Map<String, VmClip> c, String held) {
		String type = type(held);
		double dx = e.getX() - e.xo, dz = e.getZ() - e.zo;
		double speed = Math.sqrt(dx * dx + dz * dz) * 20 * StrafeConfig.unitsPerBlock; // units per second
		double yaw = Math.toRadians(s.bodyRot + s.yRot);
		// the move relative to where the player faces: 0 = forward (n), 90 = to the right (e), CS2's clip directions
		double fwd = -Math.sin(yaw) * dx + Math.cos(yaw) * dz, right = -Math.cos(yaw) * dx - Math.sin(yaw) * dz;
		double angle = (Math.toDegrees(Math.atan2(right, fwd)) + 360) % 360;
		boolean crouch = s.isCrouching, air = !e.onGround() && !e.isPassenger();
		long now = System.nanoTime();
		double[] ph = PHASE.computeIfAbsent(e.getId(), id -> new double[] {0, now});
		double dt = Math.min(0.1, (now - ph[1]) / 1e9);
		ph[1] = now;
		VmClip a = c.get(clipName(type, move(crouch, speed) + "_" + DIRS[(int) Math.floor(angle / 45) % 8]));
		if (a == null) return null;
		double nominal = crouch ? CROUCH : speed < (WALK + RUN) / 2 ? WALK : RUN;
		ph[0] = (ph[0] + dt * Math.max(0.5, Math.min(1.6, speed / nominal)) / Math.max(0.1, a.duration)) % 1;
		return blend(c, type, angle, speed, crouch, air, ph[0], now / 1e9, s.xRot);
	}

	private static String move(boolean crouch, double speed) {
		return crouch ? "crouch" : speed < (WALK + RUN) / 2 ? "walk" : "run";
	}

	/**
	 * The skeleton's pose (model space, meters, +Z forward, +Y up) for a move: angle in degrees (0 forward, 90 right),
	 * speed in units/s, phase of the walk cycle (0..1), the idle clip's time in seconds, look pitch in degrees (down
	 * positive). The two direction clips around the angle blended, then the idle clip over them while slow, or the
	 * in-air pose; the pitch bends spine, neck and head.
	 */
	static Matrix4f[] blend(Map<String, VmClip> c, String type, double angle, double speed, boolean crouch, boolean air, double phase, double idleTime,
			float pitchDeg) {
		String move = move(crouch, speed);
		int sector = (int) Math.floor(angle / 45) % 8;
		float frac = (float) (angle / 45 - Math.floor(angle / 45));
		VmClip a = c.get(clipName(type, move + "_" + DIRS[sector])), b = c.get(clipName(type, move + "_" + DIRS[(sector + 1) % 8]));
		VmClip idle = c.get(clipName(type, crouch ? "idle_crouch" : "idle")), inair = c.get(clipName(type, "inair_stand"));
		if (a == null || b == null || idle == null) return null;
		float moving = (float) Math.min(1, speed / 40); // idle -> moving over the first 40 u/s. FLAGGED ours
		Map<String, float[]> snap = a.snapshot((float) (phase * a.duration), null, 1);
		snap = b.snapshot((float) (phase * b.duration), snap, frac);
		Matrix4f[] pose;
		if (air && inair != null) pose = inair.pose(0, snap, 0.85f); // mostly the in-air pose, legs still a little in step
		else pose = idle.pose((float) (idleTime % Math.max(0.1, idle.duration)), snap, 1 - moving);
		removeRootMotion(idle, pose);
		pitch(idle, pose, pitchDeg);
		return pose;
	}

	/** The clips play in place: whatever the root still moves is taken out (the entity's own position moves it). */
	private static void removeRootMotion(VmClip skel, Matrix4f[] pose) {
		Integer root = skel.node("root_motion");
		if (root == null || pose[root] == null) return;
		org.joml.Vector3f t = pose[root].getTranslation(new org.joml.Vector3f());
		Matrix4f back = new Matrix4f().translation(-t.x, 0, -t.z);
		for (int i = 0; i < pose.length; i++) if (pose[i] != null) pose[i] = new Matrix4f(back).mul(pose[i]);
	}

	/** Bends spine_2, neck_0 and head_0 forward / back with the look pitch (30 / 30 / 40 %), about the body's sideways axis. FLAGGED ours. */
	private static void pitch(VmClip skel, Matrix4f[] pose, float pitchDeg) {
		String[] bones = {"spine_2", "neck_0", "head_0"};
		float[] share = {0.3f, 0.3f, 0.4f};
		for (int b = 0; b < bones.length; b++) {
			Integer i = skel.node(bones[b]);
			if (i == null || pose[i] == null) continue;
			org.joml.Vector3f p = pose[i].getTranslation(new org.joml.Vector3f());
			Matrix4f r = new Matrix4f().translation(p).rotateX((float) Math.toRadians(pitchDeg * share[b])).translate(-p.x, -p.y, -p.z);
			for (int d = 0; d < pose.length; d++) if (pose[d] != null && descends(skel, d, i)) pose[d] = new Matrix4f(r).mul(pose[d]);
		}
	}

	private static boolean descends(VmClip skel, int node, int ancestor) {
		for (int n = node, guard = 0; n >= 0 && guard < 256; n = skel.parent(n), guard++) if (n == ancestor) return true;
		return false;
	}

	/** The agents' weapon_hand_r attachment (bone, offset, turn; read from the model, the same on every agent). */
	private static volatile Viewmodel.Attachment hand;

	/** The model's weapon_hand_r attachment: where CS2 puts the weapon in the right hand. */
	static Viewmodel.Attachment handAttachment(String model) {
		Viewmodel.Attachment a = hand;
		if (a == null) hand = a = Viewmodel.attachments(model + "_c").get("weapon_hand_r");
		return a;
	}

	/** The weapon_hand_r attachment's frame for a pose (model space), or null. */
	static Matrix4f handFrame(VmClip skeleton, Matrix4f[] pose, Viewmodel.Attachment a) {
		Integer bone = a == null ? null : skeleton.node(a.bone());
		if (bone == null || pose[bone] == null) return null;
		return new Matrix4f(pose[bone]).translate(a.offset()).rotate(a.rotation());
	}

	/** Where a gun goes for a pose: its "weapon" bone onto the weapon_hand_r attachment (model space), or null. */
	static Matrix4f gunAt(VmClip skeleton, Matrix4f[] pose, Gun g, Viewmodel.Attachment a) {
		Matrix4f f = handFrame(skeleton, pose, a);
		return f == null ? null : f.mul(new Matrix4f(g.weaponBone()).invert());
	}

	// ---------- loading (CS2 files, converted on first use) ----------

	/** Exports (once) and reads the third-person clips, by name. */
	static Map<String, VmClip> readClips() throws Exception {
		Glb g = Glb.read(AssetCache.exportClips(CLIP_SOURCE + "_c", CLIP_DIR, clipNames()));
		Map<String, VmClip> out = new HashMap<>();
		var anims = g.arr("animations");
		for (int i = 0; i < anims.size(); i++) {
			String name = anims.get(i).getAsJsonObject().get("name").getAsString().replace("\\", "/");
			out.put(name, new VmClip(name, g, i));
		}
		return out;
	}

	/** An agent model with its skeleton and skins (and one short clip, which brings them along), exported once. */
	public static java.nio.file.Path skinnedGlb(String model) throws java.io.IOException {
		return AssetCache.exportWithClips(model + "_c", "agent_models/v1", List.of(clipName("rifle", "idle")), true);
	}

	/** Exports (once) and reads an agent's third-person meshes (body, gloves). */
	static List<VmModel> readBody(String model) throws Exception {
		Glb g = Glb.read(skinnedGlb(model));
		List<VmModel> out = new ArrayList<>();
		for (String mesh : new String[] {".thirdperson_body", ".thirdperson_default_gloves"})
			if (VmModel.hasMesh(g, mesh)) out.add(VmModel.load(g, mesh));
		if (out.isEmpty()) throw new IllegalStateException("no third-person meshes in " + model);
		return out;
	}

	/** A weapon model in its bind pose (vertices in its own space) and its "weapon" bone. */
	static Gun readGun(String model) throws Exception {
		Glb glb = Glb.read(AssetCache.exportGlb(model, true));
		VmModel vm = VmModel.load(glb, VmModel.AUTO_MESH);
		VmClip rest = new VmClip("rest", glb, Integer.MAX_VALUE); // no clip: the bind pose
		Matrix4f[] pose = rest.pose(0);
		Matrix4f[][] skins = Viewmodel.skins(vm, rest, pose, null);
		float[][] pos = new float[vm.parts.size()][], nrm = new float[vm.parts.size()][];
		for (int i = 0; i < vm.parts.size(); i++) {
			VmModel.Part p = vm.parts.get(i);
			float[][] r = Viewmodel.skinVertices(p, p.skin >= 0 && p.skin < skins.length ? skins[p.skin] : new Matrix4f[0], new Matrix4f());
			pos[i] = r[0];
			nrm[i] = r[1];
		}
		Integer w = rest.node("weapon");
		return new Gun(vm, pos, nrm, w != null ? new Matrix4f(pose[w]) : new Matrix4f());
	}

	/** Its textures read ahead (off the render thread), so the first frame doesn't decode them. */
	private static void warm(VmModel m) throws Exception {
		for (VmModel.Part p : m.parts) {
			if (p.texture == null) continue;
			Viewmodel.downscaled(p.texture);
			Viewmodel.INSTANCE.predecode(p.texture, !p.alphaMode.equals("MASK"));
		}
	}

	/** The clips once loaded (in the background, the first time an agent is drawn); null until then. */
	static Map<String, VmClip> clips() {
		Map<String, VmClip> c = clips;
		if (c != null || clipsLoading || FAILED.contains("clips")) return c;
		clipsLoading = true;
		background("strafemc-agent-clips", () -> {
			try {
				Map<String, VmClip> out = readClips();
				StrafeMc.LOG.info("[strafemc] agent clips: {} of {}", out.size(), clipNames().size());
				clips = out;
			} catch (Exception e) {
				FAILED.add("clips");
				StrafeMc.LOG.warn("[strafemc] agent clips failed: {}", e.toString());
			} finally {
				clipsLoading = false;
			}
		});
		return null;
	}

	/** An agent's third-person meshes, loaded in the background; null until then. */
	static List<VmModel> body(String model) {
		List<VmModel> b = BODIES.get(model);
		if (b != null || FAILED.contains(model) || !LOADING.add(model)) return b;
		background("strafemc-agent-model", () -> {
			try {
				List<VmModel> out = readBody(model);
				for (VmModel m : out) warm(m);
				BODIES.put(model, out);
			} catch (Exception e) {
				FAILED.add(model);
				StrafeMc.LOG.warn("[strafemc] agent model {} failed: {}", model, e.toString());
			} finally {
				LOADING.remove(model);
			}
		});
		return null;
	}

	/** A weapon's third-person model, loaded in the background; null until then (or for none). */
	static Gun gun(String cls) {
		Gun g = GUNS.get(cls);
		if (g != null || FAILED.contains(cls) || !LOADING.add(cls)) return g;
		var entry = dev.strafemc.weapon.WeaponTable.get(cls);
		if (entry == null || entry.model() == null) {
			FAILED.add(cls);
			LOADING.remove(cls);
			return null;
		}
		background("strafemc-agent-gun", () -> {
			try {
				Gun r = readGun(entry.model());
				warm(r.model());
				GUNS.put(cls, r);
			} catch (Exception e) {
				FAILED.add(cls);
				StrafeMc.LOG.warn("[strafemc] third-person {} failed: {}", cls, e.toString());
			} finally {
				LOADING.remove(cls);
			}
		});
		return null;
	}

	private static void background(String name, Runnable r) {
		Thread t = new Thread(r, name);
		t.setDaemon(true);
		t.start();
	}

	/** Forgets entities not drawn for a while (their walk cycles). */
	public static void tidy() {
		long now = System.nanoTime();
		PHASE.values().removeIf(v -> now - (long) v[1] > 10_000_000_000L);
	}
}
