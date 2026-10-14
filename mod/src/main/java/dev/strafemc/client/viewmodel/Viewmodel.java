package dev.strafemc.client.viewmodel;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.strafemc.StrafeMc;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.data.AssetCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * First-person CS2 viewmodel: the real arms (glove model) and weapon model, skinned on the CPU to the real viewmodel
 * animation clips (draw / idle / shoot / reload / inspect), drawn in place of Minecraft's hand. All assets are exported
 * from the user's CS2 copy by VRF into the local cache.
 *
 * Space: VRF glTF (y up, z forward, x left; meters) -> Minecraft camera space (vmAxes, default "-x,y,-z"), scaled by
 * 39.37 units per meter / unitsPerBlock. The weapon skeleton is mounted on the viewmodel's "wpn" bone (its root is static).
 */
public final class Viewmodel {
	public static final Viewmodel INSTANCE = new Viewmodel();
	/** Default first-person arms (CS2 glove model that also contains the bare arms). */
	public static final String ARMS = "agents/models/shared/arms/glove_fingerless/glove_fingerless.vmdl_c";

	/** mesh: the model's mesh node to draw; clips: the animations loaded (files via GunServer.clipPath). */
	private record Weapon(String cls, String model, String mesh, String[] clips) {}

	private static final Map<String, Weapon> WEAPONS = withGrenades(fromTable(Map.of(
			dev.strafemc.weapon.HealthshotItem.CLS, new Weapon(dev.strafemc.weapon.HealthshotItem.CLS, "weapons/models/healthshot/weapon_healthshot.vmdl_c",
					".healthshot_content_src", new String[] {"draw", "idle", "inject"}))));

	/** Every gun and knife of the weapon table: its model (mesh picked by VmModel, "auto") and every clip it names. */
	private static Map<String, Weapon> fromTable(Map<String, Weapon> base) {
		Map<String, Weapon> m = new java.util.HashMap<>(base);
		for (var list : java.util.List.of(dev.strafemc.weapon.WeaponTable.guns(), dev.strafemc.weapon.WeaponTable.knives()))
			for (var e : list) {
				// one name per clip file (the table also lists aliases like idle -> idle1)
				java.util.Map<String, String> byPath = new java.util.LinkedHashMap<>();
				for (var c : e.clips().entrySet()) byPath.putIfAbsent(c.getValue(), c.getKey());
				java.util.Set<String> names = new java.util.LinkedHashSet<>(byPath.values());
				names.addAll(java.util.List.of("draw", "idle"));
				for (String k : new String[] {"shoot1", "reload", "lookat01"}) if (e.clips().containsKey(k)) names.add(k);
				m.put(e.cls(), new Weapon(e.cls(), e.model(), VmModel.AUTO_MESH, names.toArray(new String[0])));
			}
		return m;
	}

	/** Every CS2 grenade: its model's ".body" mesh and the grenade animations. */
	private static Map<String, Weapon> withGrenades(Map<String, Weapon> base) {
		Map<String, Weapon> m = new java.util.HashMap<>(base);
		for (dev.strafemc.weapon.Grenade g : dev.strafemc.weapon.Grenade.values())
			m.put(g.cls, new Weapon(g.cls, g.model, ".body", new String[] {"draw", "idle", "lookat01", "lookat02", "pullpin", "throwcharge_high", "throwcharge_mid",
					"throwcharge_low", "throw_overhand", "throw_underhand"}));
		return Map.copyOf(m);
	}

	private volatile Loaded loaded;
	private final ViewmodelMotion motion = new ViewmodelMotion();
	/** Set after any rendering error: the vanilla hand is used from then on (logged once). */
	private volatile boolean broken;
	/** Weapons being loaded right now (or that failed: not retried), by class. */
	private final java.util.Set<String> loading = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private final Map<Path, Identifier> textures = new java.util.concurrent.ConcurrentHashMap<>();
	/** The camera rotation GameRenderer.renderItemInHand starts the hand pose from (inverted); captured by a mixin. */
	public static final Matrix4f HAND_VIEW = new Matrix4f();
	private VmClip clip;
	private long clipStart;
	/** Clip time at clipStart and playback rate since then (m_flWeaponActionPlaybackRate: CS2 silent reload plays at 0.5). */
	private float clipBase, rate = 1;

	private static final class Loaded {
		String cls;
		VmModel arms, gun;
		Map<String, VmClip> clips = new HashMap<>();
		/** Model attachments (vmdl MDAT m_attachments): bone, offset in the bone frame (meters), rotation. */
		Map<String, Attachment> attachments = new HashMap<>();
	}

	private record Attachment(String bone, org.joml.Vector3f offset, org.joml.Quaternionf rotation) {}

	/** Camera-space transforms (blocks; +X of the attachment = its forward) of the weapon's attachments this frame. */
	public final Map<String, Matrix4f> attachmentTransforms = new java.util.concurrent.ConcurrentHashMap<>();

	@SuppressWarnings("unchecked")
	private static Map<String, Attachment> attachments(String vmdl) {
		Map<String, Attachment> out = new HashMap<>();
		try {
			String text = java.nio.file.Files.readString(AssetCache.block(vmdl, "MDAT"));
			int h = text.indexOf("-->"), i = text.indexOf('{', h < 0 ? 0 : h + 3);
			Map<String, Object> d = dev.strafemc.data.Kv3.parseObject(text.substring(i));
			if (d.get("m_attachments") instanceof java.util.List<?> l)
				for (Object o : l) {
					if (!(o instanceof Map<?, ?> e) || !(e.get("value") instanceof Map<?, ?> v)) continue;
					java.util.List<Object> names = (java.util.List<Object>) v.get("m_influenceNames");
					java.util.List<Object> offs = (java.util.List<Object>) v.get("m_vInfluenceOffsets");
					java.util.List<Object> rots = (java.util.List<Object>) v.get("m_vInfluenceRotations");
					if (names == null || offs == null || rots == null || names.isEmpty()) continue;
					java.util.List<Number> off = (java.util.List<Number>) offs.get(0), rot = (java.util.List<Number>) rots.get(0);
					// offsets are in inches in the bone's own frame (verified: muzzle_flash lands on the barrel tip)
					out.put(String.valueOf(e.get("key")), new Attachment(String.valueOf(names.get(0)),
							new org.joml.Vector3f(off.get(0).floatValue(), off.get(1).floatValue(), off.get(2).floatValue()).mul(0.0254f),
							new org.joml.Quaternionf(rot.get(0).floatValue(), rot.get(1).floatValue(), rot.get(2).floatValue(), rot.get(3).floatValue())));
				}
		} catch (Exception e) {
			StrafeMc.LOG.warn("[strafemc] attachments of {} unavailable: {}", vmdl, e.toString());
		}
		return out;
	}

	private Viewmodel() {}

	/** Every weapon loaded so far (switching back is instant). */
	private final Map<String, Loaded> cache = new java.util.concurrent.ConcurrentHashMap<>();

	public boolean ready(String cls) {
		Loaded l = loaded;
		if (l != null && l.cls.equals(cls)) return true;
		Loaded c = cache.get(cls);
		if (c != null) { // another weapon's clip must not drive this skeleton: start from its draw
			loaded = c;
			clip = null;
			return true;
		}
		if (loading.add(cls)) loadAsync(cls);
		return false;
	}

	/**
	 * Loads weapons before they're held, one at a time, most likely first: the held item, the hotbar, the rest of the
	 * inventory, the worn gloves, then every other weapon. Also uploads one finished texture per tick, so switching to
	 * a preloaded weapon draws at once. Client thread, every tick.
	 */
	public void preloadTick(Minecraft mc) {
		if (mc.player == null) return;
		dev.strafemc.client.AssetLoading.queue(() -> (int) WEAPONS.keySet().stream().filter(c -> !cache.containsKey(c) && !loading.contains(c)).count());
		for (Path p : decoded.keySet()) { // one GPU upload per tick
			texture(p, true);
			break;
		}
		if (!AssetCache.available() || loading.stream().anyMatch(c -> !cache.containsKey(c) && !failed.contains(c)) || !glovesLoading.isEmpty() && glovesBusy())
			return;
		var inv = mc.player.getInventory();
		java.util.List<net.minecraft.world.item.ItemStack> order = new java.util.ArrayList<>();
		order.add(mc.player.getMainHandItem());
		for (int i = 0; i < 9; i++) order.add(inv.getItem(i));
		for (int i = 9; i < inv.getContainerSize(); i++) order.add(inv.getItem(i));
		if (preloadGloves()) return; // the worn gloves show on every weapon
		for (var st : order) {
			if (st.getItem() instanceof dev.strafemc.weapon.GunItem g && WEAPONS.containsKey(g.weaponClass) && !cache.containsKey(g.weaponClass)
					&& loading.add(g.weaponClass)) {
				loadAsync(g.weaponClass);
				return;
			}
			if (st.getItem() instanceof dev.strafemc.weapon.GloveItem gl) { // gloves you might put on: model + finish
				String kit = gl.kit(st), key = gl.kind.model + "#" + kit;
				if (!gloves.containsKey(key) && loadGloves(gl.kind.model, kit, key)) return;
			}
		}
		for (String cls : WEAPONS.keySet())
			if (!cache.containsKey(cls) && loading.add(cls)) {
				loadAsync(cls);
				return;
			}
	}

	/** Weapons whose load failed (kept in loading so they aren't retried every tick). */
	private final java.util.Set<String> failed = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** Glove arm models by model path, loaded in the background on first use. */
	private final Map<String, VmModel> gloves = new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Set<String> glovesLoading = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** The arms to draw: the chosen gloves (GloveChoice) in their finish once loaded, the weapon's default arms until then. */
	private VmModel arms(Loaded l) {
		String path = dev.strafemc.client.GloveChoice.model(), kit = dev.strafemc.client.GloveChoice.skin();
		if (path.equals(ARMS)) return l.arms;
		String key = path + "#" + kit;
		VmModel g = gloves.get(key);
		if (g != null) return g;
		loadGloves(path, kit, key);
		return l.arms;
	}

	/** Starts loading the worn gloves if they aren't loaded; true if that started a load. */
	private boolean preloadGloves() {
		String path = dev.strafemc.client.GloveChoice.model(), kit = dev.strafemc.client.GloveChoice.skin();
		if (path.equals(ARMS)) return false;
		String key = path + "#" + kit;
		return !gloves.containsKey(key) && loadGloves(path, kit, key);
	}

	private boolean glovesBusy() {
		for (String k : glovesLoading) if (!gloves.containsKey(k) && !glovesFailed.contains(k)) return true;
		return false;
	}

	private final java.util.Set<String> glovesFailed = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private boolean loadGloves(String path, String kit, String key) {
		if (!glovesLoading.add(key)) return false;
		String label = kit.isEmpty() ? "gloves" : "gloves (" + kit.replace('_', ' ') + ")";
		dev.strafemc.client.AssetLoading.begin(label);
		Thread t = new Thread(() -> {
			try {
				VmModel m = VmModel.load(Glb.read(AssetCache.exportGlb(path, true)), ".viewmodel");
				// CS2's paintable gloves carry a 1x1 grey placeholder colour: wear the baked finish instead
				java.nio.file.Path skin = kit.isEmpty() ? null : GloveSkinCache.ensure(kit);
				for (VmModel.Part part : m.parts)
					if (skin != null && part.texture != null && part.texture.getFileName().toString().contains("_vmat_g_tcolor")) {
						part.texture = skin;
						part.metal = null;
						if (!textures.containsKey(skin) && !decoded.containsKey(skin)) decoded.put(skin, decode(skin, true));
					} else if (part.texture != null) {
						downscaled(part.texture);
						part.metal = metalPerVertex(part);
						if (!textures.containsKey(part.texture) && !decoded.containsKey(part.texture))
							decoded.put(part.texture, decode(part.texture, !part.alphaMode.equals("MASK")));
					}
				gloves.put(key, m);
			} catch (Exception e) {
				glovesFailed.add(key);
				StrafeMc.LOG.warn("[strafemc] gloves {} failed: {}", key, e.toString());
			} finally {
				dev.strafemc.client.AssetLoading.end(label);
			}
		}, "strafemc-gloves");
		t.setDaemon(true);
		t.start();
		return true;
	}

	/** Reads every weapon's clip data (blocking: call off-thread), so first draws/reloads don't wait for the converter. */
	public static void warmClips() {
		for (Weapon w : WEAPONS.values())
			for (String a : w.clips) dev.strafemc.data.AnimClip.load(dev.strafemc.weapon.GunServer.clipPath(w.cls, a));
	}

	/** Textures decoded by the loader thread, waiting for their (render thread) upload. */
	private final Map<Path, NativeImage> decoded = new java.util.concurrent.ConcurrentHashMap<>();

	private void loadAsync(String cls) {
		Weapon w = WEAPONS.get(cls);
		if (w == null) {
			failed.add(cls);
			return;
		}
		String label = dev.strafemc.weapon.SkinWraps.WEAPON_NAMES.getOrDefault(cls, cls.replace("weapon_", ""));
		dev.strafemc.client.AssetLoading.begin(label);
		Thread t = new Thread(() -> {
			try {
				Loaded l = new Loaded();
				l.cls = cls;
				l.arms = VmModel.load(Glb.read(AssetCache.exportGlb(ARMS, true)), ".viewmodel");
				l.gun = VmModel.load(Glb.read(AssetCache.exportGlb(w.model, true)), w.mesh);
				l.attachments = attachments(w.model);
				for (String a : w.clips) {
					String p = dev.strafemc.weapon.GunServer.clipPath(cls, a);
					l.clips.put(a, new VmClip(a, Glb.read(AssetCache.exportGlb(p, false))));
				}
				for (String a : w.clips) dev.strafemc.data.AnimClip.load(dev.strafemc.weapon.GunServer.clipPath(cls, a));
				var entry = dev.strafemc.weapon.WeaponTable.get(cls); // the inspect odds, so the first inspect doesn't wait for them
				if (entry != null && entry.inspectGraph() != null) dev.strafemc.data.InspectGraph.options(entry.inspectGraph());
				for (VmModel m : new VmModel[] {l.arms, l.gun})
					for (VmModel.Part part : m.parts)
						if (part.texture != null) {
							// the Zeus charge meter is drawn by its texture's alpha (digits and bars), not as an opaque block
							if (part.texture.getFileName().toString().startsWith("taser_numerals")) part.alphaMode = "MASK";
							downscaled(part.texture);
							part.metal = metalPerVertex(part);
							if (!textures.containsKey(part.texture) && !decoded.containsKey(part.texture))
								decoded.put(part.texture, decode(part.texture, !part.alphaMode.equals("MASK")));
						}
				cache.put(cls, l);
				StrafeMc.LOG.info("[strafemc] viewmodel ready for {} ({} clips)", cls, l.clips.size());
			} catch (Exception e) {
				StrafeMc.LOG.error("[strafemc] viewmodel for " + cls + " failed", e);
				failed.add(cls);
			} finally {
				dev.strafemc.client.AssetLoading.end(label);
			}
		}, "strafemc-viewmodel");
		t.setDaemon(true);
		t.start();
	}

	/** World-of-the-bone (camera space) transforms of the weapon's attachments for this frame. */
	private void updateAttachments(Loaded l, Matrix4f[][] gunSkin, Matrix4f toView) {
		if (l.gun.parts.isEmpty()) return;
		int si = l.gun.parts.get(0).skin;
		if (si < 0 || si >= l.gun.skins.size()) return;
		VmModel.Skin skin = l.gun.skins.get(si);
		for (var e : l.attachments.entrySet()) {
			int j = java.util.Arrays.asList(skin.joints).indexOf(e.getValue().bone());
			if (j < 0) continue;
			// bone world = skin matrix x bind pose (inverse of the inverse bind)
			Matrix4f bone = new Matrix4f(gunSkin[si][j]).mul(new Matrix4f(skin.inverseBind[j]).invert());
			Matrix4f m = new Matrix4f(toView).mul(bone).translate(e.getValue().offset()).rotate(e.getValue().rotation());
			attachmentTransforms.put(e.getKey(), m);
		}
	}

	/** The clip being blended out (CS2 transition), its time when the blend started and how long the blend lasts. */
	private VmClip fromClip;
	private float fromTime, blendDuration;
	private long blendStart;

	/**
	 * CS2's transition times from the viewmodel graphs (viewmodel_gun / viewmodel_knife): into a draw is a cut, so are
	 * shots and knife attacks (also one knife attack into the next); inspects blend in over 0.3 s (0.2 s on knives and
	 * after a draw) and out to idle over 0.3 s; a shot settles to idle over 0.1 s; everything else 0.2 s.
	 */
	private static float blendTime(String from, String to, boolean knife) {
		boolean toAttack = to.startsWith("shoot") || to.startsWith("light_") || to.startsWith("heavy_");
		boolean fromAttack = from.startsWith("shoot") || from.startsWith("light_") || from.startsWith("heavy_");
		if (to.equals("draw")) return 0;
		if (from.equals("pullpin") && to.startsWith("throwcharge")) return 0.1f;
		if (to.startsWith("throw_")) return 0; // a quick throw cuts in from the pin pull (a blend swung the hand backwards)
		if (toAttack) return 0;
		if (to.startsWith("lookat")) return knife || from.equals("draw") ? 0.2f : 0.3f;
		if (to.equals("idle")) {
			if (from.startsWith("lookat")) return 0.3f;
			if (fromAttack) return knife ? 0.2f : 0.1f;
		}
		return 0.2f;
	}

	/** Starts an animation ("draw", "idle", "shoot1", "reload", "lookat01"), blending from the current one like CS2. */
	public void play(String anim) {
		Loaded l = loaded;
		if (l == null) return;
		VmClip c = l.clips.get(anim);
		if (c == null) { // another name for a loaded clip (the T knife's inspect is lookat01 = lookat01_default): same file
			String path = dev.strafemc.weapon.GunServer.clipPath(l.cls, anim);
			if (path != null)
				for (var e : l.clips.entrySet())
					if (path.equals(dev.strafemc.weapon.GunServer.clipPath(l.cls, e.getKey()))) c = e.getValue();
		}
		if (c == null) return;
		if (clip != null && clip != c || clip == c && !anim.equals("idle")) {
			float d = clip == null ? 0 : blendTime(clip.name, anim, l.cls.contains("knife"));
			fromClip = d > 0 ? clip : null;
			fromTime = d > 0 ? Math.min(clipTime(), clip.duration) : 0;
			if (fromClip != null && fromClip.name.equals("idle") && fromClip.duration > 0) fromTime %= fromClip.duration;
			blendDuration = d;
			blendStart = System.nanoTime();
		}
		clip = c;
		clipStart = System.nanoTime();
		clipBase = 0;
		rate = 1;
	}

	/** Weapon put away: the next render starts from its draw. */
	public void stop() {
		clip = null;
		fromClip = null;
	}

	private float clipTime() {
		return clipBase + (System.nanoTime() - clipStart) / 1e9f * rate;
	}

	/** The current clip's time in seconds. */
	public float time() {
		return clip == null ? 0 : clipTime();
	}

	/** Jumps the current clip to a time (no blend). */
	public void seek(float t) {
		clipBase = t;
		clipStart = System.nanoTime();
	}

	/** Changes the playback rate of the current clip without a jump in time. */
	public void setRate(float r) {
		if (r == rate) return;
		clipBase = clipTime();
		clipStart = System.nanoTime();
		rate = r;
	}

	/** Renders the viewmodel; false if not ready (vanilla hand renders instead). */
	public boolean render(String cls, PoseStack ps, SubmitNodeCollector out, int light) {
		if (broken || !ready(cls)) return false;
		Loaded l = loaded;
		if (clip == null) play("draw");
		float t = clipTime();
		// clips that end in a held pose (a grenade's charged throw: single-frame poses) stay until the next animation
		// (and pulling the pin, which hands over to the charged pose without dropping to idle in between)
		if (t > clip.duration && !clip.name.equals("idle") && !clip.name.startsWith("throwcharge") && !clip.name.equals("pullpin")) {
			play("idle");
			t = 0;
		}
		if (clip.name.equals("idle") && clip.duration > 0) t %= clip.duration;
		Matrix4f[] pose;
		float w = blendDuration <= 0 ? 1 : (System.nanoTime() - blendStart) / 1e9f / blendDuration;
		if (fromClip != null && w < 1) pose = clip.pose(t, fromClip, fromTime, Math.max(0, w)); // the old clip held where it was
		else {
			fromClip = null;
			pose = clip.pose(t);
		}
		Matrix4f wpn = mount(clip, pose);

		VmModel arms = arms(l);
		Matrix4f[][] armSkin = skins(arms, clip, pose, null);
		Matrix4f[][] gunSkin = skins(l.gun, clip, pose, wpn);
		var player = net.minecraft.client.Minecraft.getInstance().player;
		// CS2's viewmodel_offset_x/y/z (units right / forward / up), then bob + sway, then the model
		float upb = (float) StrafeConfig.unitsPerBlock;
		Matrix4f offsets = new Matrix4f().translate((float) StrafeConfig.viewmodelOffsetX / upb, (float) StrafeConfig.viewmodelOffsetZ / upb,
				(float) -StrafeConfig.viewmodelOffsetY / upb);
		Matrix4f toView = player != null ? offsets.mul(motion.offset(player)).mul(toView()) : offsets.mul(toView());
		if (!StrafeConfig.rightHand) toView = new Matrix4f().scale(-1, 1, 1).mul(toView); // cl_righthand 0: mirrored into the left hand
		// The hand pose arrives as inverse(view rotation) * bobbing. Keep only the bobbing (pure camera space) and undo
		// whatever model-view matrix is active when the features are drawn, so looking around never moves the viewmodel.
		Matrix4f cam = new Matrix4f(HAND_VIEW).mul(ps.last().pose());
		// a weapon mesh without a skeleton (the Medi-Shot) rides on the animated weapon bone as a whole
		Integer weaponNode = clip.node("weapon");
		Matrix4f rigid = weaponNode != null ? new Matrix4f(wpn).mul(pose[weaponNode]) : null;
		emit(arms, armSkin, toView, cam, ps, out, light, null);
		emit(l.gun, gunSkin, toView, cam, ps, out, light, rigid);
		updateAttachments(l, gunSkin, toView);
		dev.strafemc.client.particle.Effects.renderView(ps, out, cam);
		return true;
	}

	/**
	 * The worn gloves for the loadout preview: posed like in first person (a loaded weapon's idle pose, the weapon left
	 * out), centred and scaled to fit width x height (GUI pixels), turned by yaw degrees. False until something is loaded.
	 */
	public boolean previewArms(PoseStack ps, net.minecraft.client.renderer.MultiBufferSource buffers, float width, float height, float yaw) {
		Loaded l = loaded != null ? loaded : cache.values().stream().findFirst().orElse(null);
		if (broken || l == null) return false;
		VmModel arms = arms(l);
		if (arms == l.arms && !dev.strafemc.client.GloveChoice.model().equals(ARMS) && !gloves.containsKey(dev.strafemc.client.GloveChoice.model() + "#"
				+ dev.strafemc.client.GloveChoice.skin())) return false; // the worn gloves are still loading
		VmClip c = l.clips.get("idle");
		if (c == null) return false;
		Matrix4f[] pose = c.pose(0);
		Matrix4f[][] skin = skins(arms, c, pose, null);
		Matrix4f toView = toView();
		java.util.List<float[][]> skinned = new java.util.ArrayList<>();
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
		for (VmModel.Part p : arms.parts) {
			Matrix4f[] s = p.skin >= 0 && p.skin < skin.length ? skin[p.skin] : new Matrix4f[0];
			float[][] sk = skinVertices(p, s, toView);
			skinned.add(sk);
			for (int i = 0; i + 2 < sk[0].length; i += 3) {
				minX = Math.min(minX, sk[0][i]); maxX = Math.max(maxX, sk[0][i]);
				minY = Math.min(minY, sk[0][i + 1]); maxY = Math.max(maxY, sk[0][i + 1]);
				minZ = Math.min(minZ, sk[0][i + 2]); maxZ = Math.max(maxZ, sk[0][i + 2]);
			}
		}
		if (minX > maxX) return false;
		float k = 0.85f * Math.min(width / Math.max(1e-3f, maxX - minX), height / Math.max(1e-3f, maxY - minY));
		ps.pushPose();
		ps.scale(k, -k, k); // camera space (y up) into the picture (y down)
		ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yaw));
		ps.translate(-(minX + maxX) / 2, -(minY + maxY) / 2, -(minZ + maxZ) / 2);
		PoseStack.Pose last = ps.last();
		for (int n = 0; n < arms.parts.size(); n++) {
			VmModel.Part p = arms.parts.get(n);
			if (p.alphaMode.equals("BLEND")) continue;
			Identifier tex = texture(p.texture, !p.alphaMode.equals("MASK"));
			if (tex == null) continue;
			var vc = buffers.getBuffer(RenderTypes.entityCutout(tex));
			float[] sp = skinned.get(n)[0], sn = skinned.get(n)[1];
			int[] idx = p.idx;
			for (int i = 0; i + 2 < idx.length; i += 3) {
				vertex(vc, last, sp, sn, p, idx[i], 0xF000F0);
				vertex(vc, last, sp, sn, p, idx[i + 1], 0xF000F0);
				vertex(vc, last, sp, sn, p, idx[i + 2], 0xF000F0);
				vertex(vc, last, sp, sn, p, idx[i + 2], 0xF000F0);
			}
		}
		ps.popPose();
		return true;
	}

	/**
	 * The Zeus charge meter (its material's texture offset expression): recharging, the display window slides along the
	 * red-to-yellow percentage strip by floor(charge x 99) / 128 of the 128 px texture; charged, it shows the cyan part at
	 * 0.9. FLAGGED: which of the expression's two inputs means "charged" is our reading (the cyan part reads as ready).
	 */
	private static float zeusMeter() {
		var p = net.minecraft.client.Minecraft.getInstance().player;
		var l = net.minecraft.client.Minecraft.getInstance().level;
		if (p == null || l == null) return 0.9f;
		var s = p.getMainHandItem();
		if (!s.has(dev.strafemc.weapon.WeaponItems.RECHARGE)) return 0.9f;
		return (float) Math.floor(dev.strafemc.weapon.ZeusRecharge.progress(s, l.getGameTime()) * 99) / 128f;
	}

	/**
	 * The weapon skeleton rides on the viewmodel's "wpn" bone: CS2 treats the weapon root bone as the wpn bone. Both carry
	 * the same axis conversion in the export, so the mount is wpn * inverse(weapon root) (not wpn * weapon, which applies it twice).
	 */
	static Matrix4f mount(VmClip c, Matrix4f[] pose) {
		Integer w = c.node("wpn"), r = c.node("weapon");
		Matrix4f m = w != null ? new Matrix4f(pose[w]) : new Matrix4f();
		if (r != null) m.mul(new Matrix4f(pose[r]).invert());
		return m;
	}

	/** Per-joint skinning matrices: animated joint (clip space; weapon joints relative to the wpn mount) x inverse bind. */
	static Matrix4f[][] skins(VmModel m, VmClip c, Matrix4f[] pose, Matrix4f mount) {
		Matrix4f[][] out = new Matrix4f[m.skins.size()][];
		for (int i = 0; i < out.length; i++) out[i] = skin(m.skins.get(i), c, pose, mount);
		return out;
	}

	static Matrix4f[] skin(VmModel.Skin m, VmClip c, Matrix4f[] pose, Matrix4f mount) {
		Matrix4f[] s = new Matrix4f[m.joints.length];
		for (int j = 0; j < m.joints.length; j++) skinJoint(m, c, pose, mount, s, j, 0);
		return s;
	}

	private static Matrix4f skinJoint(VmModel.Skin m, VmClip c, Matrix4f[] pose, Matrix4f mount, Matrix4f[] s, int j, int depth) {
		if (s[j] != null) return s[j];
		String name = m.joints[j];
		if (c.node(name) == null && !name.contains("_TWIST") && m.parent != null && m.parent[j] >= 0 && depth < 64)
			// not animated by the clip (cloth like the molotov rag): it keeps its rest pose relative to its parent
			return s[j] = skinJoint(m, c, pose, mount, s, m.parent[j], depth + 1);
		Integer n = c.node(name);
		Matrix4f ibm = m.inverseBind[j];
		if (n == null && name.contains("_TWIST")) {
			Matrix4f tw = twistJoint(m, c, pose, j, name);
			if (tw != null) return s[j] = mount != null ? new Matrix4f(mount).mul(tw).mul(ibm) : tw.mul(ibm);
			// fallback: ride rigidly on their bone, with that bone's bind matrix
			String base = name.substring(0, name.indexOf("_TWIST"));
			n = c.node(base);
			int bj = java.util.Arrays.asList(m.joints).indexOf(base);
			if (bj >= 0) ibm = m.inverseBind[bj];
		}
		if (n == null && mount != null) n = c.node("weapon");
		Matrix4f jm = n != null ? new Matrix4f(pose[n]) : new Matrix4f();
		if (mount != null) jm = new Matrix4f(mount).mul(jm);
		return s[j] = jm.mul(ibm);
	}

	/**
	 * Forearm twist helpers (arm_lower_*_TWIST, _TWIST1): the clips don't animate them and the model has no constraint
	 * for them, so CS2 computes them in code. Here (FLAGGED: the standard swing-twist distribution, not CS2's code):
	 * each takes the hand's roll about the forearm in proportion to how far along the forearm it sits, and undoes the
	 * forearm's own roll against the upper arm for the rest, so the twist spreads from the elbow to the wrist instead of
	 * pinching at both. Positions come from the bind pose. Measured over the AK's draw / idle / reload / inspect: arm
	 * skin distortion about half of riding them rigidly (TwistCheck). Upper-arm helpers stay rigid (they barely move
	 * the first-person arms). Returns the joint's animated matrix, or null to ride rigidly.
	 */
	static boolean twistOff; // TwistCheck's comparison

	private static Matrix4f twistJoint(VmModel.Skin m, VmClip c, Matrix4f[] pose, int j, String name) {
		String base = name.substring(0, name.indexOf("_TWIST"));
		if (twistOff || !base.startsWith("arm_lower")) return null;
		java.util.List<String> joints = java.util.Arrays.asList(m.joints);
		String side = base.substring(base.length() - 1);
		int jb = joints.indexOf(base), jc = joints.indexOf("hand_" + side), ju = joints.indexOf("arm_upper_" + side);
		Integer nb = c.node(base), nc = c.node("hand_" + side), nu = c.node("arm_upper_" + side);
		if (jb < 0 || jc < 0 || nb == null || nc == null) return null;
		Matrix4f bindB = new Matrix4f(m.inverseBind[jb]).invert(), bindT = new Matrix4f(m.inverseBind[j]).invert(),
				bindC = new Matrix4f(m.inverseBind[jc]).invert();
		Matrix4f localT = new Matrix4f(bindB).invert().mul(bindT), localC = new Matrix4f(bindB).invert().mul(bindC);
		org.joml.Vector3f axis = localC.getTranslation(new org.joml.Vector3f());
		float len = axis.length();
		if (len < 1e-5f) return null;
		axis.div(len);
		float f = Math.max(0, Math.min(1, localT.getTranslation(new org.joml.Vector3f()).dot(axis) / len)); // how far toward the wrist
		// the hand's roll about the forearm, relative to the bind pose, in the forearm's frame
		org.joml.Quaternionf hNow = new Matrix4f(pose[nb]).invert().mul(pose[nc]).getUnnormalizedRotation(new org.joml.Quaternionf()).normalize(),
				hBind = localC.getUnnormalizedRotation(new org.joml.Quaternionf()).normalize();
		float angle = twistAngle(hNow.mul(hBind.invert()), axis) * f;
		if (ju >= 0 && nu != null) { // the forearm's own roll against the upper arm, undone toward the elbow
			Matrix4f bindU = new Matrix4f(m.inverseBind[ju]).invert();
			org.joml.Quaternionf rBind = new Matrix4f(bindU).invert().mul(bindB).getUnnormalizedRotation(new org.joml.Quaternionf()).normalize(),
					rNow = new Matrix4f(pose[nu]).invert().mul(pose[nb]).getUnnormalizedRotation(new org.joml.Quaternionf()).normalize();
			angle -= twistAngle(rBind.invert().mul(rNow), axis) * (1 - f);
		}
		return new Matrix4f(pose[nb]).rotate(angle, axis).mul(localT);
	}

	/** The rotation angle of q about axis (the twist part of a swing-twist split), in -pi..pi. */
	private static float twistAngle(org.joml.Quaternionf q, org.joml.Vector3f axis) {
		float proj = q.x * axis.x + q.y * axis.y + q.z * axis.z, w = q.w;
		if (w < 0) { // the shorter way round
			proj = -proj;
			w = -w;
		}
		return (float) (2 * Math.atan2(proj, w));
	}

	static Matrix4f toView() {
		String[] ax = StrafeConfig.vmAxes.split(",");
		float k = (float) (39.3701 / StrafeConfig.unitsPerBlock);
		Matrix4f m = new Matrix4f().zero();
		for (int row = 0; row < 3; row++) {
			String a = ax[row].trim();
			float sign = a.startsWith("-") ? -1 : 1;
			int col = switch (a.replace("-", "")) {
				case "x" -> 0;
				case "y" -> 1;
				default -> 2;
			};
			m.setRowColumn(row, col, sign * k);
		}
		m.m33(1);
		return m;
	}

	/** Skins one model on the CPU into camera space; static so the headless check runs the exact production math. */
	static float[][] skinVertices(VmModel.Part p, Matrix4f[] skin, Matrix4f toView) {
		int verts = p.pos.length / 3;
		// per joint: toView * skin as a row-major 3x4 block (once per frame), then a weighted blend per vertex
		int nj = Math.max(1, skin.length);
		float[] jm = new float[nj * 12];
		for (int j = 0; j < nj; j++) {
			Matrix4f c = skin.length > 0 ? new Matrix4f(toView).mul(skin[j]) : new Matrix4f(toView);
			int o = j * 12; // JOML mCR = column C, row R
			jm[o] = c.m00(); jm[o + 1] = c.m10(); jm[o + 2] = c.m20(); jm[o + 3] = c.m30();
			jm[o + 4] = c.m01(); jm[o + 5] = c.m11(); jm[o + 6] = c.m21(); jm[o + 7] = c.m31();
			jm[o + 8] = c.m02(); jm[o + 9] = c.m12(); jm[o + 10] = c.m22(); jm[o + 11] = c.m32();
		}
		float[] sp = new float[verts * 3], sn = new float[verts * 3], b = new float[12];
		for (int i = 0; i < verts; i++) {
			java.util.Arrays.fill(b, 0);
			if (p.jnt != null && skin.length > 0) {
				for (int k = 0; k < 4; k++) {
					float w = p.wts[i * 4 + k];
					if (w <= 0) continue;
					int o = Math.min(p.jnt[i * 4 + k], nj - 1) * 12;
					for (int q = 0; q < 12; q++) b[q] += jm[o + q] * w;
				}
			} else System.arraycopy(jm, 0, b, 0, 12);
			float x = p.pos[i * 3], y = p.pos[i * 3 + 1], z = p.pos[i * 3 + 2];
			sp[i * 3] = b[0] * x + b[1] * y + b[2] * z + b[3];
			sp[i * 3 + 1] = b[4] * x + b[5] * y + b[6] * z + b[7];
			sp[i * 3 + 2] = b[8] * x + b[9] * y + b[10] * z + b[11];
			float nx = p.nrm[i * 3], ny = p.nrm[i * 3 + 1], nz = p.nrm[i * 3 + 2];
			float tx = b[0] * nx + b[1] * ny + b[2] * nz, ty = b[4] * nx + b[5] * ny + b[6] * nz, tz = b[8] * nx + b[9] * ny + b[10] * nz;
			float l = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
			if (l > 1e-6f) { tx /= l; ty /= l; tz /= l; }
			sn[i * 3] = tx;
			sn[i * 3 + 1] = ty;
			sn[i * 3 + 2] = tz;
		}
		return new float[][] {sp, sn};
	}

	private void emit(VmModel m, Matrix4f[][] skins, Matrix4f toView, Matrix4f cam, PoseStack ps, SubmitNodeCollector out, int light, Matrix4f rigid) {
		for (VmModel.Part p : m.parts) {
			if (p.texture != null && p.texture.getFileName().toString().startsWith("taser_numerals")) p.uOffset = zeusMeter();
			if (p.alphaMode.equals("BLEND")) continue; // sticker/decal overlays (sticker_gaps) need CS2's composite shader
			Identifier tex = texture(p.texture, !p.alphaMode.equals("MASK"));
			if (tex == null) continue;
			Matrix4f[] skin = p.skin >= 0 && p.skin < skins.length ? skins[p.skin] : rigid != null ? new Matrix4f[] {rigid} : new Matrix4f[0];
			float[][] sk = skinVertices(p, skin, toView);
			float[] sp = sk[0], sn = sk[1];
			out.submitCustomGeometry(ps, RenderTypes.entityCutout(tex), (ignored, vc) -> {
				if (broken) return;
				PoseStack.Pose pose = new PoseStack.Pose();
				pose.pose().set(RenderSystem.getModelViewMatrix()).invert().mul(cam);
				pose.normal().set(pose.pose()).invert().transpose();
				int[] idx = p.idx;
				try {
				for (int i = 0; i + 2 < idx.length; i += 3) {
					vertex(vc, pose, sp, sn, p, idx[i], light);
					vertex(vc, pose, sp, sn, p, idx[i + 1], light);
					vertex(vc, pose, sp, sn, p, idx[i + 2], light);
					vertex(vc, pose, sp, sn, p, idx[i + 2], light); // entity render types draw quads
				}
				} catch (RuntimeException e) { // never crash the game over the viewmodel: fall back to the vanilla hand
					broken = true;
					StrafeMc.LOG.error("[strafemc] viewmodel rendering failed, using the vanilla hand", e);
				}
			});
		}
	}

	private static void vertex(VertexConsumer vc, PoseStack.Pose pose, float[] sp, float[] sn, VmModel.Part p, int i, int light) {
		float[] uv = p.uv;
		int col = p.metal == null ? -1 : shade(p.metal[i], sn[i * 3 + 1]);
		vc.addVertex(pose, sp[i * 3], sp[i * 3 + 1], sp[i * 3 + 2]).setColor(col).setUv(uv[i * 2] + p.uOffset, uv[i * 2 + 1])
				.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, sn[i * 3], sn[i * 3 + 1], sn[i * 3 + 2]);
	}

	/** Uploads a cached texture once (render thread). */
	Identifier texture(Path png, boolean opaque) {
		if (png == null) return null;
		return textures.computeIfAbsent(png, p -> {
			try {
				NativeImage ready = decoded.remove(p);
				NativeImage img = ready != null ? ready : decode(p, opaque);
				Identifier id = Identifier.fromNamespaceAndPath("strafemc", "vm/" + Integer.toHexString(p.toString().hashCode()));
				Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "strafemc viewmodel " + p.getFileName(), img));
				return id;
			} catch (Exception e) {
				StrafeMc.LOG.warn("[strafemc] viewmodel texture {} failed: {}", p, e.toString());
				return null;
			}
		});
	}

	/** Reads a viewmodel texture (any thread). */
	private static NativeImage decode(Path p, boolean opaque) throws java.io.IOException {
		try (InputStream in = Files.newInputStream(downscaledPath(p))) {
			NativeImage img = NativeImage.read(in);
			// CS2 color textures carry the paint/skin mask in alpha: for opaque materials it isn't transparency
			if (opaque)
				for (int y = 0; y < img.getHeight(); y++)
					for (int x = 0; x < img.getWidth(); x++) img.setPixel(x, y, img.getPixel(x, y) | 0xFF000000);
			return img;
		}
	}

	/** The setup's 1024 px colour with CS2's ambient occlusion baked in, or the older plain copy. */
	private static Path downscaledPath(Path p) {
		Path c = p.resolveSibling(p.getFileName().toString().replace(".png", ".vm1024c.png"));
		return Files.exists(c) ? c : p.resolveSibling(p.getFileName().toString().replace(".png", ".vm1024b.png"));
	}

	/** Samples CS2's metalness mask (made by the setup) at each vertex's UV; null if the material has no metal. */
	static float[] metalPerVertex(VmModel.Part part) {
		Path mask = part.texture.resolveSibling(part.texture.getFileName().toString().replace(".png", ".vm1024m.png"));
		if (!Files.exists(mask)) return null;
		try {
			BufferedImage m = ImageIO.read(mask.toFile());
			int w = m.getWidth(), h = m.getHeight(), n = part.uv.length / 2;
			float[] out = new float[n];
			boolean any = false;
			for (int i = 0; i < n; i++) {
				double u = part.uv[i * 2] - Math.floor(part.uv[i * 2]), v = part.uv[i * 2 + 1] - Math.floor(part.uv[i * 2 + 1]);
				out[i] = (m.getRGB(Math.min(w - 1, (int) (u * w)), Math.min(h - 1, (int) (v * h))) & 255) / 255f;
				any |= out[i] > 0.05f;
			}
			return any ? out : null;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Metal has no diffuse colour in CS2: it shows its colour as a tint on what it reflects. Without reflections, a
	 * stand-in environment by the camera-space normal: bright sky above, dark ground below (FLAGGED: ours).
	 */
	private static int shade(float metal, float ny) {
		if (metal <= 0.05f) return -1;
		float t = Math.max(0, Math.min(1, (ny + 0.3f) / 1.3f)), env = 0.3f + 1.1f * t * t * (3 - 2 * t);
		float k = Math.min(1, 1 + (env - 1) * metal);
		int c = Math.round(k * 255);
		return 0xFF000000 | c << 16 | c << 8 | c;
	}

	/**
	 * CS2 textures are up to 4096 px; keep a 1024 px copy for the viewmodel. Box filter per channel without premultiplying:
	 * CS2 color alpha is metalness, and a premultiplied (Java2D) scale wipes the color wherever it is 0.
	 */
	static void downscaled(Path p) throws Exception {
		Path out = downscaledPath(p);
		if (Files.exists(out) || !Files.exists(p)) return;
		BufferedImage in = ImageIO.read(p.toFile());
		int w = in.getWidth(), h = in.getHeight(), max = 1024;
		int f = 1;
		while (Math.max(w, h) / f > max) f *= 2;
		int ow = w / f, oh = h / f;
		int[] src = in.getRGB(0, 0, w, h, null, 0, w); // non-premultiplied ARGB
		int[] dst = new int[ow * oh];
		int n = f * f;
		for (int y = 0; y < oh; y++)
			for (int x = 0; x < ow; x++) {
				long sa = 0, sr = 0, sg = 0, sb = 0;
				for (int dy = 0; dy < f; dy++)
					for (int dx = 0; dx < f; dx++) {
						int c = src[(y * f + dy) * w + x * f + dx];
						sa += c >>> 24;
						sr += (c >> 16) & 255;
						sg += (c >> 8) & 255;
						sb += c & 255;
					}
				dst[y * ow + x] = (int) (sa / n) << 24 | (int) (sr / n) << 16 | (int) (sg / n) << 8 | (int) (sb / n);
			}
		BufferedImage img = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_ARGB);
		img.setRGB(0, 0, ow, oh, dst, 0, ow);
		ImageIO.write(img, "png", out.toFile());
	}
}
