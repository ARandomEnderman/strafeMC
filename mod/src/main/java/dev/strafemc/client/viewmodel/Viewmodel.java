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

	/** A weapon's model and mesh filter ({model, mesh}), or null. */
	static String[] weaponModel(String cls) {
		Weapon w = WEAPONS.get(cls);
		return w == null ? null : new String[] {w.model(), w.mesh()};
	}

	/** Where a model's attachment sits in model space at rest (glb metres), or null: the charm's default spot ("keychain"). */
	static float[] restPoint(String model, VmModel m, String attachment) {
		Attachment a = attachments(model).get(attachment);
		if (a == null) return null;
		for (VmModel.Skin s : m.skins) {
			int j = s.indexOf(a.bone());
			if (j < 0) continue;
			org.joml.Vector3f p = s.bind(j).transformPosition(new org.joml.Vector3f(a.offset()));
			return new float[] {p.x, p.y, p.z};
		}
		return null;
	}

	private static final Map<String, Weapon> WEAPONS = withGrenades(fromTable(Map.of(
			dev.strafemc.weapon.HealthshotItem.CLS, new Weapon(dev.strafemc.weapon.HealthshotItem.CLS, "weapons/models/healthshot/weapon_healthshot.vmdl_c",
					".healthshot_content_src", new String[] {"draw", "idle", "inject"}),
			dev.strafemc.weapon.C4Item.CLS, new Weapon(dev.strafemc.weapon.C4Item.CLS, dev.strafemc.weapon.C4Item.MODEL, VmModel.AUTO_MESH,
					new String[] {"draw", "idle", "plant", "lookat01"}))));

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
			if (st.getItem() instanceof dev.strafemc.weapon.GunItem k && cache.get(k.weaponClass) instanceof Loaded kl) gun(kl); // its wrap's finish
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

	/** Weapon models in a finish (key: weapon#kit#pattern), baked and loaded in the background on first use. */
	private final Map<String, VmModel> finishes = new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Set<String> finishLoading = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** The weapon model to draw: in its loadout wrap's finish once baked, the plain weapon until then (and without one). */
	private VmModel gun(Loaded l) {
		if (l.cls.equals(dev.strafemc.weapon.C4Item.CLS)) { // the C4 wears no finish: its stickers are on the one in your hand
			var p = net.minecraft.client.Minecraft.getInstance().player;
			String[] stickers = p == null ? null : dev.strafemc.weapon.StickerSlots.get(p.getMainHandItem());
			boolean any = false;
			if (stickers != null) for (String s : stickers) any |= !s.isEmpty();
			if (!any) return l.gun;
			String key = l.cls + "#plain#0#" + String.join(",", stickers);
			VmModel m = finishes.get(key);
			if (m != null) return holo(l, m);
			loadFinish(l, null, 0, key, stickers);
			return l.gun;
		}
		boolean knife = l.cls.contains("knife") || l.cls.equals("weapon_bayonet");
		String kit = knife ? dev.strafemc.client.WrapChoice.knifeKit(l.cls) : dev.strafemc.client.WrapChoice.gunKit(l.cls);
		if (kit == null) return l.gun;
		int pattern = knife ? dev.strafemc.client.WrapChoice.knifePattern() : dev.strafemc.client.WrapChoice.gunPattern(l.cls);
		String[] stickers = knife ? null : dev.strafemc.client.WrapChoice.gunStickers(l.cls);
		if (stickers == null) return gun(l, kit, pattern);
		String key = l.cls + "#" + kit + "#" + pattern + "#" + String.join(",", stickers);
		VmModel m = finishes.get(key);
		if (m != null) return holo(l, m);
		loadFinish(l, kit, pattern, key, stickers);
		VmModel plain = finishes.get(l.cls + "#" + kit + "#" + pattern); // until the stickers are on: the finish without them
		return plain != null ? plain : l.gun;
	}

	/** Finishes with holo / lenticular stickers: their pictures along the spectrum (step 0: the finish's own). */
	private final Map<VmModel, Path[]> holoSteps = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * A finish with holo stickers shows the step of their spectrum that the view picks: CS2 moves along it by
	 * dot(view, normal) (+ the light's, fixed here), so turning the gun (inspecting, moving) slides holo colours and flips
	 * lenticular frames. The normal is the gun's sticker side (its model x) as posed last frame; one step per
	 * 1 / HOLO_STEPS of a turn of the spectrum.
	 */
	private VmModel holo(Loaded l, VmModel m) {
		Path[] steps = holoSteps.get(m);
		if (steps == null || lastGunSkin == null || lastToView == null || l.gun.parts.isEmpty()) return m;
		int si = l.gun.parts.get(0).skin;
		if (si < 0 || si >= lastGunSkin.length || lastGunSkin[si].length == 0) return m;
		org.joml.Vector3f side = new Matrix4f(lastToView).mul(lastGunSkin[si][0]).transformDirection(new org.joml.Vector3f(1, 0, 0));
		double along = side.lengthSquared() < 1e-9 ? 0 : side.normalize().z * HOLO_SPEED; // towards the viewer: +z
		int k = (int) Math.floor((along - Math.floor(along)) * steps.length) % steps.length;
		for (VmModel.Part p : m.parts)
			for (Path s : steps)
				if (s.equals(p.texture)) {
					p.texture = steps[k];
					break;
				}
		return m;
	}

	/** How many turns of the spectrum one full swing of the view across the sticker side makes (FLAGGED ours). */
	private static final double HOLO_SPEED = 1.5;

	/** The weapon in a given finish (baked once, the plain weapon until then). */
	private VmModel gun(Loaded l, String kit, int pattern) {
		String key = l.cls + "#" + kit + "#" + pattern;
		VmModel m = finishes.get(key);
		if (m != null) return m;
		loadFinish(l, kit, pattern, key, null);
		return l.gun;
	}

	/** Bakes (once, cached on disk) and loads a weapon in a finish; a weapon that can't wear finishes keeps its own look. */
	private void loadFinish(Loaded l, String kit, int pattern, String key, String[] stickers) {
		Weapon w = WEAPONS.get(l.cls);
		if (w == null || !finishLoading.add(key)) return;
		String label = dev.strafemc.weapon.SkinWraps.WEAPON_NAMES.getOrDefault(l.cls, l.cls.replace("weapon_", "").replace('_', ' ')) + " (" + kit + ")";
		dev.strafemc.client.AssetLoading.begin(label);
		Thread t = new Thread(() -> {
			try {
				// a paint kit with use_legacy_model puts the weapon on its legacy mesh (with that mesh's own material and inputs)
				Glb glb = Glb.read(AssetCache.exportGlb(w.model, true));
				boolean legacy = kit != null && WeaponSkinCache.legacy(kit) && VmModel.hasMesh(glb, ".body_legacy");
				VmModel m = VmModel.load(glb, legacy ? ".body_legacy" : w.mesh);
				var paintMat = WeaponSkinCache.stickerable(l.cls, w.model, m.parts);
				java.util.List<VmModel.Part> paintable = new java.util.ArrayList<>();
				Path base = null;
				for (VmModel.Part part : m.parts)
					if (paintMat != null && part.texture != null && part.texture.getFileName().toString().startsWith(paintMat.color())) {
						paintable.add(part);
						base = part.texture;
					}
				if (base != null) downscaled(base);
				Path tex = base == null ? null : stickers != null ? WeaponSkinCache.ensureStickers(l.cls, paintMat.material(), kit, pattern, paintable, base, stickers)
						: WeaponSkinCache.ensure(l.cls, paintMat.material(), kit, pattern, paintable, base);
				// holo / lenticular stickers: the finish's steps along their spectrum, shown by the view (holo())
				Path[] steps = null;
				if (tex != null && stickers != null && Files.exists(WeaponSkinCache.holoVariant(tex, 1).resolveSibling(
						WeaponSkinCache.holoVariant(tex, 1).getFileName().toString().replace(".png", ".vm1024b.png")))) {
					steps = new Path[WeaponSkinCache.HOLO_STEPS];
					for (int k = 0; k < steps.length; k++) {
						steps[k] = WeaponSkinCache.holoVariant(tex, k);
						if (k > 0 && !textures.containsKey(steps[k]) && !decoded.containsKey(steps[k])) decoded.put(steps[k], decode(steps[k], true));
					}
				}
				if (tex == null) { // no finish for this weapon (CS2's default knives have no composite inputs)
					finishes.put(key, l.gun);
					return;
				}
				float pearl = kit == null ? 0 : (float) WeaponSkinCache.paintParams(kit).f("g_flPearlescentScale", 0);
				for (VmModel.Part part : m.parts) {
					if (paintable.contains(part)) {
						part.texture = tex;
						part.pearl = pearl;
					}
					if (part.texture == null) continue;
					// the Zeus charge meter is drawn by its texture's alpha (digits and bars), as on the plain weapon
					if (part.texture.getFileName().toString().startsWith("taser_numerals")) part.alphaMode = "MASK";
					if (!paintable.contains(part)) downscaled(part.texture); // the legacy mesh's other textures
					part.metal = metalPerVertex(part);
					if (!textures.containsKey(part.texture) && !decoded.containsKey(part.texture))
						decoded.put(part.texture, decode(part.texture, !part.alphaMode.equals("MASK")));
				}
				if (steps != null) holoSteps.put(m, steps);
				finishes.put(key, m);
			} catch (Exception e) {
				finishes.put(key, l.gun);
				StrafeMc.LOG.warn("[strafemc] finish {} failed: {}", key, e.toString());
			} finally {
				dev.strafemc.client.AssetLoading.end(label);
			}
		}, "strafemc-finish");
		t.setDaemon(true);
		t.start();
	}

	/** CS2's StatTrak modules: guns' (housing + lit display), knives' (engraved digits only). */
	private static final String STATTRAK_GUN = "weapons/models/shared/stattrak/stattrak_module.vmdl_c",
			STATTRAK_KNIFE = "weapons/models/shared/stattrak/stattrak_module_knife.vmdl_c";
	/** VRF bakes its Source-to-glTF turn into the module's vertices (the weapon root bone carries the same turn: (-.5,-.5,-.5,.5)); this undoes it, into the attachment's frame. */
	private static final org.joml.Quaternionf FROM_GLTF = new org.joml.Quaternionf(0.5f, 0.5f, 0.5f, 0.5f);

	/** A loaded module: its housing, its display quads (digits) and their UVs as exported. */
	private static final class Module {
		final VmModel body = new VmModel(), display = new VmModel();
		final Map<VmModel.Part, float[]> baseUv = new HashMap<>();
		int shown = Integer.MIN_VALUE;

		/**
		 * Puts a count on the display, as CS2's weapon vertex shader does with g_nStatTrakValue: a display vertex has
		 * V < 0 and its digit slot k = floor(U) - 1 (0-5, left to right); q = value / 10^(5-k); the atlas cell (row 0,
		 * 1/16 wide) is q % 10 + 1, or cell 0 (an unlit 0) for leading zeros (q == 0, except the last digit); U moves
		 * from cell k to that cell. The sampler wraps, so U and V go into 0-1 here.
		 */
		void show(int value) {
			if (value == shown) return;
			shown = value;
			int[] pow = {100_000, 10_000, 1_000, 100, 10, 1};
			for (var e : baseUv.entrySet()) {
				float[] base = e.getValue(), uv = e.getKey().uv;
				for (int i = 0; i + 1 < base.length; i += 2) {
					float u = base[i], v = base[i + 1];
					if (v < 0) {
						float slot = (float) Math.floor(u + 1e-4f) - 1;
						int k = Math.max(0, Math.min(5, (int) slot));
						int q = value / pow[k];
						int cell = (q != 0 || k == 5 ? 1 : 0) + q % 10;
						u = u - slot * 0.0625f + cell * 0.0625f;
					}
					uv[i] = u - (float) Math.floor(u);
					uv[i + 1] = v - (float) Math.floor(v);
				}
			}
		}
	}

	private final Map<Boolean, Module> modules = new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Set<Boolean> moduleLoading = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** The StatTrak module (loaded in the background on first use), or null until it's ready. */
	private Module module(boolean knife) {
		Module m = modules.get(knife);
		if (m != null || !moduleLoading.add(knife)) return m;
		Thread t = new Thread(() -> {
			try {
				Module n = new Module();
				VmModel all = VmModel.load(Glb.read(AssetCache.exportGlb(knife ? STATTRAK_KNIFE : STATTRAK_GUN, true)), null);
				for (VmModel.Part part : all.parts) {
					if (part.texture == null) continue;
					boolean digits = part.texture.getFileName().toString().startsWith("stattrak_digit_atlas");
					if (digits) { // drawn on the CPU (its UVs change with the count), cut out like the knife's engraving
						part.alphaMode = "MASK";
						n.baseUv.put(part, part.uv.clone());
						n.display.parts.add(part);
					} else {
						part.metal = metalPerVertex(part);
						n.body.parts.add(part);
					}
					downscaled(part.texture);
					if (!textures.containsKey(part.texture) && !decoded.containsKey(part.texture))
						decoded.put(part.texture, decode(part.texture, !part.alphaMode.equals("MASK")));
				}
				modules.put(knife, n);
			} catch (Exception e) {
				StrafeMc.LOG.warn("[strafemc] StatTrak module failed: {}", e.toString());
			}
		}, "strafemc-stattrak");
		t.setDaemon(true);
		t.start();
		return null;
	}

	/** The StatTrak module on the weapon's "stattrak" attachment ("stattrak_legacy" on a legacy-model finish), if its wrap is StatTrak. */
	private void statTrak(Loaded l, Matrix4f cam, PoseStack ps, SubmitNodeCollector out, int light) {
		boolean knife = l.cls.contains("knife") || l.cls.equals("weapon_bayonet");
		int kills = dev.strafemc.client.WrapChoice.statTrak(l.cls, knife);
		if (kills < 0) return;
		String kit = knife ? dev.strafemc.client.WrapChoice.knifeKit(l.cls) : dev.strafemc.client.WrapChoice.gunKit(l.cls);
		String name = kit != null && WeaponSkinCache.legacy(kit) && l.attachments.containsKey("stattrak_legacy") ? "stattrak_legacy" : "stattrak";
		Matrix4f at = l.attachments.containsKey(name) ? attachmentTransforms.get(name) : null;
		Module m = at == null ? null : module(knife);
		if (m == null) return;
		m.show(kills);
		Matrix4f place = new Matrix4f(at).rotate(FROM_GLTF), rigid = new Matrix4f();
		emit(m.body, new Matrix4f[0][], place, cam, ps, out, light, rigid);
		emit(m.display, new Matrix4f[0][], place, cam, ps, out, knife ? light : 0xF000F0, rigid); // the gun display glows (F_SELF_ILLUM)
	}

	/** Agents' first-person sleeves by model, loaded in the background on first use. */
	private final Map<String, VmModel> sleeveModels = new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Set<String> sleevesLoading = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** The sleeves of the agent picked for your side (its model's firstperson_sleeves mesh), or null. */
	private VmModel sleeves() {
		var a = dev.strafemc.client.Agents.current();
		if (a == null) return null;
		String model = a.model() + "_c";
		VmModel m = sleeveModels.get(model);
		if (m != null || !sleevesLoading.add(model)) return m;
		Thread t = new Thread(() -> {
			try {
				VmModel s = VmModel.load(Glb.read(AssetCache.exportGlb(model, true)), ".firstperson_sleeves");
				for (VmModel.Part part : s.parts) armsOnly(s, part);
				for (VmModel.Part part : s.parts) {
					if (part.texture == null) continue;
					part.metal = metalPerVertex(part);
					downscaled(part.texture);
					if (!textures.containsKey(part.texture) && !decoded.containsKey(part.texture))
						decoded.put(part.texture, decode(part.texture, !part.alphaMode.equals("MASK")));
				}
				sleeveModels.put(model, s);
			} catch (Exception e) {
				StrafeMc.LOG.warn("[strafemc] agent sleeves {} failed: {}", model, e.toString());
			}
		}, "strafemc-agent");
		t.setDaemon(true);
		t.start();
		return null;
	}

	/**
	 * An agent's sleeves follow the arms only: the mesh is also weighted to body bones (spine, clavicles, shoulder blades)
	 * that weapon animations don't move, which left cloth hanging by the camera (and across the view when a draw swings
	 * the arms up). Each vertex's weight goes to its arm, hand and finger bones alone; triangles with a vertex mostly on
	 * the body are dropped.
	 */
	static void armsOnly(VmModel m, VmModel.Part p) {
		if (p.wts == null || p.jnt == null || p.skin < 0 || p.skin >= m.skins.size()) return;
		String[] joints = m.skins.get(p.skin).joints;
		int verts = p.wts.length / 4;
		boolean[] body = new boolean[verts];
		for (int v = 0; v < verts; v++) {
			double arm = 0;
			for (int q = 0; q < 4; q++) {
				int j = p.jnt[v * 4 + q];
				String n = j >= 0 && j < joints.length ? joints[j] : "";
				if (n.startsWith("arm_") || n.startsWith("hand_") || n.startsWith("finger_")) arm += p.wts[v * 4 + q];
				else p.wts[v * 4 + q] = 0;
			}
			if (arm < 0.5) body[v] = true;
			if (arm > 1e-6) for (int q = 0; q < 4; q++) p.wts[v * 4 + q] /= arm;
		}
		java.util.List<Integer> keep = new java.util.ArrayList<>();
		for (int i = 0; i + 2 < p.idx.length; i += 3)
			if (!body[p.idx[i]] && !body[p.idx[i + 1]] && !body[p.idx[i + 2]]) for (int k = 0; k < 3; k++) keep.add(p.idx[i + k]);
		p.idx = keep.stream().mapToInt(Integer::intValue).toArray();
	}

	/** Charm models by charm id, loaded in the background on first use. */
	private final Map<String, VmModel> charmModels = new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Set<String> charmLoading = java.util.concurrent.ConcurrentHashMap.newKeySet();
	/**
	 * The charm's pendulum in the world (blocks): where its weight is now and was a step ago (Verlet), and when it was
	 * last stepped. Null: hanging at rest from the next frame.
	 */
	private double[] charmBob, charmBobPrev;
	private long lastSwingNs;
	/** Pendulum length (blocks), damping per second, the most it swings away from hanging off the gun (radians). FLAGGED ours. */
	private static final double CHARM_LENGTH = 0.045, CHARM_DAMPING = 3.5, CHARM_MAX_SWING = Math.toRadians(80);
	/** How much of your own movement the charm feels (1: all of it, as a real one would; it swung far too much). FLAGGED ours. */
	private static final double CHARM_MOVE = 0.25;
	/** The charm's frame: where the camera was, and where it is for the charm (moving CHARM_MOVE as far). */
	private double[] charmCamPrev, charmCam;

	private VmModel charmModel(String id) {
		VmModel m = charmModels.get(id);
		if (m != null || !charmLoading.add(id)) return m;
		var c = dev.strafemc.weapon.Charms.get(id);
		if (c == null) return null;
		Thread t = new Thread(() -> {
			try {
				VmModel all = VmModel.load(Glb.read(AssetCache.exportGlb(c.model() + "_c", true)), null);
				for (VmModel.Part part : all.parts) {
					if (part.texture == null) continue;
					part.metal = metalPerVertex(part);
					downscaled(part.texture);
					if (!textures.containsKey(part.texture) && !decoded.containsKey(part.texture))
						decoded.put(part.texture, decode(part.texture, !part.alphaMode.equals("MASK")));
				}
				charmModels.put(id, all);
			} catch (Exception e) {
				StrafeMc.LOG.warn("[strafemc] charm {} failed: {}", id, e.toString());
			}
		}, "strafemc-charm");
		t.setDaemon(true);
		t.start();
		return null;
	}

	/**
	 * The charm on the gun's wrap (or the C4 in hand), hanging from the weapon's "keychain" attachment ("keychain_legacy"
	 * on a legacy-model finish) as a pendulum simulated in the world: gravity pulls its weight down, so it hangs down
	 * whatever the gun does (inspects, draws), and the keychain point moving with you (running, strafing, jumping,
	 * turning) swings it; it never swings further than CHARM_MAX_SWING from hanging off the gun, so it stays clear of
	 * it. FLAGGED ours: the length, damping and limit (CS2 simulates the chain).
	 */
	private void charm(Loaded l, Matrix4f cam, PoseStack ps, SubmitNodeCollector out, int light) {
		boolean knife = l.cls.contains("knife") || l.cls.equals("weapon_bayonet");
		var holder = net.minecraft.client.Minecraft.getInstance().player;
		String entry = knife ? null : l.cls.equals(dev.strafemc.weapon.C4Item.CLS) // the C4's charm is on the one in your hand
				? (holder == null ? null : emptyToNull(dev.strafemc.weapon.StickerSlots.charm(holder.getMainHandItem())))
				: dev.strafemc.client.WrapChoice.gunCharm(l.cls);
		if (entry == null) return;
		String id = dev.strafemc.weapon.StickerSlots.idOf(entry);
		double[] point = dev.strafemc.weapon.StickerSlots.placeOf(entry);
		String kit = l.cls.equals(dev.strafemc.weapon.C4Item.CLS) ? null : dev.strafemc.client.WrapChoice.gunKit(l.cls);
		String name = kit != null && WeaponSkinCache.legacy(kit) && l.attachments.containsKey("keychain_legacy") ? "keychain_legacy" : "keychain";
		Matrix4f at = l.attachments.containsKey(name) ? point != null && point.length == 3 ? pointOnGun(l, name, point) : attachmentTransforms.get(name) : null;
		// the C4: its keychain point is inside the bricks; the charm hangs from just outside its front face there, and swings
		// only on the outside of that face (C4_FACE_OUT: our offset)
		org.joml.Vector3f limitAxis = null;
		if (at != null && l.cls.equals(dev.strafemc.weapon.C4Item.CLS) && (point == null || point.length != 3)) {
			float[] kc = c4Keychain(l);
			if (kc != null) {
				at = pointOnGun(l, name, new double[] {kc[0], kc[1], kc[2]});
				org.joml.Vector3f a0 = at.getTranslation(new org.joml.Vector3f()),
						a1 = pointOnGun(l, name, new double[] {kc[0], kc[1] + 0.01, kc[2]}).getTranslation(new org.joml.Vector3f());
				limitAxis = a1.sub(a0).normalize();
			}
		}
		VmModel m = at == null ? null : charmModel(id);
		if (m == null) return;
		long now = System.nanoTime();
		double dt = lastSwingNs == 0 ? 0 : Math.min(0.05, (now - lastSwingNs) / 1e9);
		lastSwingNs = now;
		Matrix4f base = new Matrix4f(at).rotate(FROM_GLTF);
		org.joml.Vector3f pivot = base.getTranslation(new org.joml.Vector3f());
		org.joml.Vector3f rest = base.transformDirection(new org.joml.Vector3f(0, -1, 0)).normalize(); // hanging straight off the gun
		org.joml.Vector3f limit = limitAxis != null ? limitAxis : rest; // what it may not swing too far from
		var camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
		org.joml.Quaternionf toWorld = new org.joml.Quaternionf(camera.rotation());
		var real = camera.position();
		if (charmCam == null || Math.abs(real.x - charmCamPrev[0]) + Math.abs(real.y - charmCamPrev[1]) + Math.abs(real.z - charmCamPrev[2]) > 2) {
			charmCam = new double[] {real.x, real.y, real.z}; // first frame, or a teleport
			charmBob = null;
		} else for (int i = 0; i < 3; i++) charmCam[i] += ((i == 0 ? real.x : i == 1 ? real.y : real.z) - charmCamPrev[i]) * CHARM_MOVE;
		charmCamPrev = new double[] {real.x, real.y, real.z};
		var cp = new net.minecraft.world.phys.Vec3(charmCam[0], charmCam[1], charmCam[2]);
		org.joml.Vector3f pw = toWorld.transform(new org.joml.Vector3f(pivot)), rw = toWorld.transform(new org.joml.Vector3f(limit));
		double qx = cp.x + pw.x, qy = cp.y + pw.y, qz = cp.z + pw.z;
		if (charmBob == null || Math.abs(charmBob[0] - qx) + Math.abs(charmBob[1] - qy) + Math.abs(charmBob[2] - qz) > 1) { // first frame, or a teleport
			charmBob = new double[] {qx + rw.x * CHARM_LENGTH, qy + rw.y * CHARM_LENGTH, qz + rw.z * CHARM_LENGTH};
			charmBobPrev = charmBob.clone();
		}
		int steps = (int) Math.ceil(dt / 0.005); // small steps: the pendulum is short
		double h = steps == 0 ? 0 : dt / steps, keep = Math.exp(-CHARM_DAMPING * h);
		for (int s = 0; s < steps; s++) {
			double[] next = new double[3];
			for (int i = 0; i < 3; i++) next[i] = charmBob[i] + (charmBob[i] - charmBobPrev[i]) * keep + (i == 1 ? -9.81 * h * h : 0);
			charmBobPrev = charmBob;
			charmBob = constrainCharm(next, qx, qy, qz, rw);
		}
		org.joml.Vector3f dir = new org.joml.Vector3f((float) (charmBob[0] - qx), (float) (charmBob[1] - qy), (float) (charmBob[2] - qz)).normalize();
		org.joml.Vector3f dirCam = new org.joml.Quaternionf(toWorld).conjugate().transform(dir);
		org.joml.Quaternionf swing = new org.joml.Quaternionf().rotationTo(rest, dirCam);
		Matrix4f place = new Matrix4f().translation(pivot).rotate(swing).mul(new Matrix4f(base).setTranslation(0, 0, 0));
		emit(m, new Matrix4f[0][], place, cam, ps, out, light, new Matrix4f());
	}

	/** The C4's charm point: its keychain attachment's spot, lifted onto its front face (model space, glb metres), or null. */
	private float[] c4Keychain(Loaded l) {
		if (c4Keychain != null) return c4Keychain;
		Weapon w = WEAPONS.get(l.cls);
		float[] kc = w == null ? null : restPoint(w.model, l.gun, "keychain");
		if (kc == null) return null;
		float top = -Float.MAX_VALUE; // the front face (+y): the bricks' top
		for (VmModel.Part p : l.gun.parts) {
			if (p.alphaMode.equals("BLEND")) continue;
			for (int i = 1; i < p.pos.length; i += 3) top = Math.max(top, p.pos[i]);
		}
		return c4Keychain = new float[] {kc[0], top + C4_FACE_OUT, kc[2]};
	}

	private float[] c4Keychain;
	/** How far off the C4's front face its charm hangs (m). FLAGGED ours. */
	private static final float C4_FACE_OUT = 0.004f;

	/** The charm's weight held at its length from the keychain point (q) and within CHARM_MAX_SWING of hanging off the gun (rest). */
	private static double[] constrainCharm(double[] b, double qx, double qy, double qz, org.joml.Vector3f rest) {
		double dx = b[0] - qx, dy = b[1] - qy, dz = b[2] - qz, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1e-9) return new double[] {qx + rest.x * CHARM_LENGTH, qy + rest.y * CHARM_LENGTH, qz + rest.z * CHARM_LENGTH};
		dx /= len;
		dy /= len;
		dz /= len;
		double cos = dx * rest.x + dy * rest.y + dz * rest.z, maxCos = Math.cos(CHARM_MAX_SWING);
		if (cos < maxCos) { // too far round: back onto the limit cone, keeping its direction of swing
			double px = dx - rest.x * cos, py = dy - rest.y * cos, pz = dz - rest.z * cos, pl = Math.sqrt(px * px + py * py + pz * pz);
			if (pl < 1e-9) {
				px = 1;
				py = pz = 0;
				pl = 1;
			}
			double sin = Math.sqrt(1 - maxCos * maxCos);
			dx = rest.x * maxCos + px / pl * sin;
			dy = rest.y * maxCos + py / pl * sin;
			dz = rest.z * maxCos + pz / pl * sin;
		}
		return new double[] {qx + dx * CHARM_LENGTH, qy + dy * CHARM_LENGTH, qz + dz * CHARM_LENGTH};
	}

	private static String emptyToNull(String s) {
		return s == null || s.isEmpty() ? null : s;
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
			int j = skin.indexOf(e.getValue().bone());
			if (j < 0) continue;
			// bone world = skin matrix x bind pose (inverse of the inverse bind)
			Matrix4f bone = new Matrix4f(gunSkin[si][j]).mul(skin.bind(j));
			Matrix4f m = new Matrix4f(toView).mul(bone).translate(e.getValue().offset()).rotate(e.getValue().rotation());
			attachmentTransforms.put(e.getKey(), m);
		}
		lastGunSkin = gunSkin;
		lastToView = new Matrix4f(toView);
	}

	/** This frame's weapon skinning and view transform (for a charm hung from its own point). */
	private Matrix4f[][] lastGunSkin;
	private Matrix4f lastToView;

	/** Camera-space transform of a model-space point (glb metres) on the gun as posed this frame, turned like the attachment. */
	private Matrix4f pointOnGun(Loaded l, String attachment, double[] p) {
		Attachment a = l.attachments.get(attachment);
		if (a == null || lastGunSkin == null || l.gun.parts.isEmpty()) return null;
		int si = l.gun.parts.get(0).skin;
		if (si < 0 || si >= l.gun.skins.size() || si >= lastGunSkin.length) return null;
		VmModel.Skin skin = l.gun.skins.get(si);
		int j = skin.indexOf(a.bone());
		if (j < 0) return null;
		// a rest-pose point follows its bone like the mesh does (skin matrix x point), the turn is the attachment's
		org.joml.Vector3f at = new Matrix4f(lastGunSkin[si][j]).transformPosition(new org.joml.Vector3f((float) p[0], (float) p[1], (float) p[2]));
		Matrix4f bone = new Matrix4f(lastGunSkin[si][j]).mul(skin.bind(j));
		org.joml.Quaternionf turn = bone.getNormalizedRotation(new org.joml.Quaternionf()).mul(a.rotation());
		return new Matrix4f(lastToView).translate(at).rotate(turn);
	}

	/** The clip being blended out (CS2 transition), its time when the blend started and how long the blend lasts. */
	/** The pose on screen when the current clip started (bones' local T/R/S by name), blended out; null: none. */
	private java.util.Map<String, float[]> fromPose;
	private float blendDuration;
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
			if (d > 0) {
				// freeze exactly what's on screen (mid-crossfade too), so every new clip blends from it
				float t = Math.min(clipTime(), clip.duration);
				if (clip.name.equals("idle") && clip.duration > 0) t %= clip.duration;
				float w = blendDuration <= 0 ? 1 : (System.nanoTime() - blendStart) / 1e9f / blendDuration;
				fromPose = clip.snapshot(t, fromPose != null && w < 1 ? fromPose : null, Math.max(0, Math.min(1, w)));
			} else fromPose = null;
			blendDuration = d;
			blendStart = System.nanoTime();
		}
		clip = c;
		clipStart = System.nanoTime();
		clipBase = 0;
		rate = 1;
	}

	/**
	 * The draw the next weapon comes out with (a knife's everyday or rare pullout, chosen on equip): its model may not be
	 * loaded yet when it's chosen, so the first frame plays it from here.
	 */
	public volatile String nextDraw = "draw";

	/** Weapon put away: the next render starts from its draw. */
	public void stop() {
		clip = null;
		fromPose = null;
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
		if (clip == null) play(nextDraw);
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
		if (fromPose != null && w < 1) pose = clip.pose(t, fromPose, Math.max(0, w)); // from the pose frozen when this clip began
		else {
			fromPose = null;
			pose = clip.pose(t);
		}
		Matrix4f wpn = mount(clip, pose);

		VmModel arms = arms(l);
		Matrix4f[][] armSkin = skins(arms, clip, pose, null);
		Matrix4f[][] gunSkin = skins(l.gun, clip, pose, wpn);
		hideSilencer(l, gunSkin);
		var player = net.minecraft.client.Minecraft.getInstance().player;
		// CS2's viewmodel_offset_x/y/z (units right / forward / up), then bob + sway, then the model
		float upb = (float) StrafeConfig.unitsPerBlock;
		Matrix4f offsets = new Matrix4f().translate((float) StrafeConfig.viewmodelOffsetX / upb, (float) StrafeConfig.viewmodelOffsetZ / upb,
				(float) -StrafeConfig.viewmodelOffsetY / upb);
		Matrix4f sway = player != null ? motion.offset(player) : new Matrix4f();
		// cl_righthand 0: mirrored into the left hand; bob and sway are the camera's, so they're mirrored back and still
		// lag the same way as you turn
		if (!StrafeConfig.rightHand) sway = new Matrix4f().scale(-1, 1, 1).mul(sway).scale(-1, 1, 1);
		Matrix4f toView = offsets.mul(sway).mul(toView());
		if (!StrafeConfig.rightHand) toView = new Matrix4f().scale(-1, 1, 1).mul(toView);
		// The hand pose arrives as inverse(view rotation) * bobbing. Keep only the bobbing (pure camera space) and undo
		// whatever model-view matrix is active when the features are drawn, so looking around never moves the viewmodel.
		Matrix4f cam = new Matrix4f(HAND_VIEW).mul(ps.last().pose());
		// a weapon mesh without a skeleton (the Medi-Shot) rides on the animated weapon bone as a whole
		Integer weaponNode = clip.node("weapon");
		Matrix4f rigid = weaponNode != null ? new Matrix4f(wpn).mul(pose[weaponNode]) : null;
		emit(arms, armSkin, toView, cam, ps, out, light, null);
		VmModel sleeves = sleeves();
		if (sleeves != null) emit(sleeves, skins(sleeves, clip, pose, null), toView, cam, ps, out, light, null); // the agent's sleeves on the same arms
		emit(gun(l), gunSkin, toView, cam, ps, out, light, rigid); // same skeleton and mesh, its own colour (a wrap's finish)
		updateAttachments(l, gunSkin, toView);
		statTrak(l, cam, ps, out, light);
		charm(l, cam, ps, out, light);
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
		return drawFitted(arms, skin, toView(), null, ps, buffers, width, height, yaw);
	}

	/**
	 * A weapon for the loadout preview, in the finish of the wrap in its loadout slot (knives: the knife wrap): its idle
	 * pose, turned side-on, fitted into width x height and turned by yaw. False until it's loaded (it starts loading).
	 */
	public boolean previewWeapon(String cls, PoseStack ps, net.minecraft.client.renderer.MultiBufferSource buffers, float width, float height, float yaw) {
		return previewWeapon(cls, null, 0, ps, buffers, width, height, yaw, Float.NaN);
	}

	/** Whether a weapon (in this finish) is loaded and baked, so its preview shows it as it is. */
	public boolean previewReady(String cls, String kit, int pattern) {
		Loaded l = cache.get(cls);
		if (l == null) {
			if (WEAPONS.containsKey(cls) && loading.add(cls)) loadAsync(cls);
			return false;
		}
		if (kit == null) return true;
		boolean ready = finishes.containsKey(l.cls + "#" + kit + "#" + pattern);
		if (!ready) gun(l, kit, pattern); // starts the bake
		return ready;
	}

	/**
	 * A weapon in a given finish (kit null: its loadout wrap's) for a preview: side-on, turned by yaw and, when pitch is
	 * a number, tilted by it and fitted for any angle (a case's reveal you turn with the mouse).
	 */
	public boolean previewWeapon(String cls, String kit, int pattern, PoseStack ps, net.minecraft.client.renderer.MultiBufferSource buffers, float width,
								 float height, float yaw, float pitch) {
		if (broken || cls == null) return false;
		Loaded l = cache.get(cls);
		if (l == null) {
			if (WEAPONS.containsKey(cls) && loading.add(cls)) loadAsync(cls);
			return false;
		}
		VmClip c = l.clips.get("idle");
		if (c == null) return false;
		Matrix4f[] pose = c.pose(0);
		Matrix4f wpn = mount(c, pose);
		Matrix4f[][] skin = skins(l.gun, c, pose, wpn);
		Integer weaponNode = c.node("weapon");
		Matrix4f rigid = weaponNode != null ? new Matrix4f(wpn).mul(pose[weaponNode]) : null;
		// side-on, muzzle to the right
		Matrix4f side = new Matrix4f().rotateY((float) (Math.PI / 2)).mul(toView());
		return drawFitted(kit == null ? gun(l) : gun(l, kit, pattern), skin, side, rigid, ps, buffers, width, height, yaw, pitch);
	}

	/**
	 * A weapon as you'd hold it in first person, for the settings preview: its idle animation, the worn gloves, your
	 * viewmodel offsets and hand, seen through viewmodel_fov (vertical) into a width x height picture centred on the
	 * crosshair. The perspective is done per vertex here (the picture is drawn flat). False until it's loaded.
	 */
	public boolean previewFirstPerson(String cls, PoseStack ps, net.minecraft.client.renderer.MultiBufferSource buffers, float width, float height) {
		if (broken || cls == null) return false;
		Loaded l = cache.get(cls);
		if (l == null) {
			if (WEAPONS.containsKey(cls) && loading.add(cls)) loadAsync(cls);
			return false;
		}
		VmClip c = l.clips.get("idle");
		if (c == null) return false;
		float t = c.duration > 0 ? (System.nanoTime() / 1e9f) % c.duration : 0;
		Matrix4f[] pose = c.pose(t);
		Matrix4f wpn = mount(c, pose);
		VmModel arms = arms(l), gun = gun(l);
		Matrix4f[][] armSkin = skins(arms, c, pose, null), gunSkin = skins(l.gun, c, pose, wpn);
		Integer weaponNode = c.node("weapon");
		Matrix4f rigid = weaponNode != null ? new Matrix4f(wpn).mul(pose[weaponNode]) : null;
		float upb = (float) StrafeConfig.unitsPerBlock;
		Matrix4f view = new Matrix4f().translate((float) StrafeConfig.viewmodelOffsetX / upb, (float) StrafeConfig.viewmodelOffsetZ / upb,
				(float) -StrafeConfig.viewmodelOffsetY / upb).mul(toView());
		if (!StrafeConfig.rightHand) view = new Matrix4f().scale(-1, 1, 1).mul(view);
		// the picture is drawn like the loadout preview's (drawFitted: winding as exported), so only the left hand turns triangles round
		boolean mirrored = !StrafeConfig.rightHand;
		float f = (float) (1 / Math.tan(Math.toRadians(StrafeConfig.viewmodelFov) / 2)) * height / 2;
		PoseStack.Pose last = ps.last();
		for (VmModel m : new VmModel[] {arms, gun}) {
			Matrix4f[][] skins = m == arms ? armSkin : gunSkin;
			for (VmModel.Part p : m.parts) {
				if (p.alphaMode.equals("BLEND")) continue;
				Identifier tex = texture(p.texture, !p.alphaMode.equals("MASK"));
				if (tex == null) continue;
				Matrix4f[] s = p.skin >= 0 && p.skin < skins.length ? skins[p.skin] : rigid != null ? new Matrix4f[] {rigid} : new Matrix4f[0];
				float[][] sk = skinVertices(p, s, view);
				float[] sp = sk[0], sn = sk[1];
				for (int i = 0; i + 2 < sp.length; i += 3) { // camera space (looking down -Z) -> picture pixels, y down
					float z = Math.min(-0.01f, sp[i + 2]);
					sp[i] = sp[i] / -z * f;
					sp[i + 1] = -sp[i + 1] / -z * f;
					sp[i + 2] = -(z + 0.5f) * 100; // nearer is further out of the picture: its PoseStack turns z round (scale z by -1)
					sn[i + 1] = -sn[i + 1];
				}
				var vc = buffers.getBuffer(p.alphaMode.equals("MASK") ? RenderTypes.entityCutout(tex) : VmRender.opaque(tex));
				int[] idx = p.idx;
				for (int i = 0; i + 2 < idx.length; i += 3) {
					int b = mirrored ? idx[i + 2] : idx[i + 1], cc = mirrored ? idx[i + 1] : idx[i + 2];
					vertex(vc, last, sp, sn, p, idx[i], 0xF000F0);
					vertex(vc, last, sp, sn, p, b, 0xF000F0);
					vertex(vc, last, sp, sn, p, cc, 0xF000F0);
					vertex(vc, last, sp, sn, p, cc, 0xF000F0);
				}
			}
		}
		return true;
	}

	/** Draws a posed model fitted into width x height (GUI pixels), centred, turned by yaw degrees. */
	private boolean drawFitted(VmModel model, Matrix4f[][] skin, Matrix4f toView, Matrix4f rigid, PoseStack ps, net.minecraft.client.renderer.MultiBufferSource buffers,
							   float width, float height, float yaw) {
		return drawFitted(model, skin, toView, rigid, ps, buffers, width, height, yaw, Float.NaN);
	}

	private boolean drawFitted(VmModel model, Matrix4f[][] skin, Matrix4f toView, Matrix4f rigid, PoseStack ps, net.minecraft.client.renderer.MultiBufferSource buffers,
							   float width, float height, float yaw, float pitch) {
		java.util.List<float[][]> skinned = new java.util.ArrayList<>();
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
		for (VmModel.Part p : model.parts) {
			Matrix4f[] s = p.skin >= 0 && p.skin < skin.length ? skin[p.skin] : rigid != null ? new Matrix4f[] {rigid} : new Matrix4f[0];
			float[][] sk = skinVertices(p, s, toView);
			skinned.add(sk);
			if (p.alphaMode.equals("BLEND")) continue;
			for (int i = 0; i + 2 < sk[0].length; i += 3) {
				minX = Math.min(minX, sk[0][i]); maxX = Math.max(maxX, sk[0][i]);
				minY = Math.min(minY, sk[0][i + 1]); maxY = Math.max(maxY, sk[0][i + 1]);
				minZ = Math.min(minZ, sk[0][i + 2]); maxZ = Math.max(maxZ, sk[0][i + 2]);
			}
		}
		if (minX > maxX) return false;
		// the turn widens the picture: fit what the model covers at its widest turn
		float depth = maxZ - minZ, wide = (maxX - minX) * (float) Math.abs(Math.cos(Math.toRadians(yaw))) + depth * (float) Math.abs(Math.sin(Math.toRadians(yaw)));
		float k = 0.85f * Math.min(width / Math.max(1e-3f, Math.max(maxX - minX, wide)), height / Math.max(1e-3f, maxY - minY));
		ps.pushPose();
		ps.scale(k, -k, k); // camera space (y up) into the picture (y down)
		if (!Float.isNaN(pitch)) { // turned freely: fit its diagonal, so no angle crops it
			float dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
			float fit = 0.9f * Math.min(width, height) / Math.max(1e-3f, (float) Math.sqrt(dx * dx + dy * dy + dz * dz)) / k;
			ps.scale(fit, fit, fit);
			ps.mulPose(com.mojang.math.Axis.XP.rotationDegrees(pitch));
		}
		ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yaw));
		ps.translate(-(minX + maxX) / 2, -(minY + maxY) / 2, -(minZ + maxZ) / 2);
		PoseStack.Pose last = ps.last();
		for (int n = 0; n < model.parts.size(); n++) {
			VmModel.Part p = model.parts.get(n);
			if (p.alphaMode.equals("BLEND")) continue;
			Identifier tex = texture(p.texture, !p.alphaMode.equals("MASK"));
			if (tex == null) continue;
			// opaque textures carry metalness in alpha: the lit viewmodel type, cutout only for real masks
			var vc = buffers.getBuffer(p.alphaMode.equals("MASK") ? RenderTypes.entityCutout(tex) : VmRender.opaque(tex));
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
	 * A USP-S / M4A1-S with its silencer off: the "silencer" bone at scale 0 (how CS2's detach clip ends), except while
	 * the attach / detach clips play (they animate it themselves).
	 */
	private void hideSilencer(Loaded l, Matrix4f[][] gunSkin) {
		var player = net.minecraft.client.Minecraft.getInstance().player;
		if (player == null || clip == null || clip.name.startsWith("silencer_")) return;
		var held = player.getMainHandItem();
		if (!(held.getItem() instanceof dev.strafemc.weapon.GunItem g) || !g.hasSilencer() || g.silenced(held)) return;
		for (int si = 0; si < l.gun.skins.size() && si < gunSkin.length; si++) {
			VmModel.Skin sk = l.gun.skins.get(si);
			for (int j = 0; j < sk.joints.length; j++) {
				if (!sk.joints[j].equals("silencer") || gunSkin[si][j] == null) continue;
				Matrix4f ibm = sk.inverseBind[j];
				gunSkin[si][j] = new Matrix4f(gunSkin[si][j]).mul(sk.bind(j)).scale(0).mul(ibm);
			}
		}
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
			int bj = m.indexOf(base);
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
		String side = base.substring(base.length() - 1);
		int jb = m.indexOf(base), jc = m.indexOf("hand_" + side), ju = m.indexOf("arm_upper_" + side);
		Integer nb = c.node(base), nc = c.node("hand_" + side), nu = c.node("arm_upper_" + side);
		if (jb < 0 || jc < 0 || nb == null || nc == null) return null;
		Matrix4f bindB = m.bind(jb);
		// bindB^-1 is the inverse bind itself
		Matrix4f localT = new Matrix4f(m.inverseBind[jb]).mul(m.bind(j)), localC = new Matrix4f(m.inverseBind[jb]).mul(m.bind(jc));
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
			org.joml.Quaternionf rBind = new Matrix4f(m.inverseBind[ju]).mul(bindB).getUnnormalizedRotation(new org.joml.Quaternionf()).normalize(),
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

	/** The CPU path for one opaque part, when GPU skinning failed this frame (into the render type's own buffer). */
	private void cpuFallback(VmModel.Part p, Matrix4f[] skin, Matrix4f toView, Matrix4f cam, int light, Identifier tex,
							 com.mojang.blaze3d.vertex.VertexConsumer vc) {
		float[][] sk = skinVertices(p, skin, toView);
		PoseStack.Pose pose = new PoseStack.Pose();
		pose.pose().set(RenderSystem.getModelViewMatrix()).invert().mul(cam);
		pose.normal().set(pose.pose()).invert().transpose();
		int[] idx = p.idx;
		for (int i = 0; i + 2 < idx.length; i += 3) {
			vertex(vc, pose, sk[0], sk[1], p, idx[i], light);
			vertex(vc, pose, sk[0], sk[1], p, idx[i + 1], light);
			vertex(vc, pose, sk[0], sk[1], p, idx[i + 2], light);
			vertex(vc, pose, sk[0], sk[1], p, idx[i + 2], light);
		}
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
			// opaque parts: skinned on the GPU from static buffers (only the joints change per frame)
			if (GpuSkin.supports(p, skin.length)) {
				Matrix4f view = new Matrix4f(toView);
				out.submitCustomGeometry(ps, VmRender.opaque(tex), (ignored, vc) -> {
					if (!broken && !GpuSkin.draw(p, skin, view, cam, light, tex)) cpuFallback(p, skin, view, cam, light, tex, vc);
				});
				continue;
			}
			float[][] sk = skinVertices(p, skin, toView);
			float[] sp = sk[0], sn = sk[1];
			// mirrored into the left hand (cl_righthand 0): the triangles turn round, so swap their winding or culling
			// keeps the insides
			boolean mirrored = toView.determinant3x3() < 0;
			boolean lit = !p.alphaMode.equals("MASK"); // opaque parts: our per-pixel lighting (metalness in the texture's alpha)
			out.submitCustomGeometry(ps, lit ? VmRender.opaque(tex) : RenderTypes.entityCutout(tex), (ignored, vc) -> {
				if (broken) return;
				PoseStack.Pose pose = new PoseStack.Pose();
				pose.pose().set(RenderSystem.getModelViewMatrix()).invert().mul(cam);
				pose.normal().set(pose.pose()).invert().transpose();
				int[] idx = p.idx;
				try {
				for (int i = 0; i + 2 < idx.length; i += 3) {
					int b = mirrored ? idx[i + 2] : idx[i + 1], c = mirrored ? idx[i + 1] : idx[i + 2];
					vertex(vc, pose, sp, sn, p, idx[i], light);
					vertex(vc, pose, sp, sn, p, b, light);
					vertex(vc, pose, sp, sn, p, c, light);
					vertex(vc, pose, sp, sn, p, c, light); // entity render types draw quads
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
		int col = p.metal == null || !p.alphaMode.equals("MASK") ? pearlColor(p.pearl) : shade(p.metal[i], sn[i * 3 + 1]); // lit parts: the shader shines
		vc.addVertex(pose, sp[i * 3], sp[i * 3 + 1], sp[i * 3 + 2]).setColor(col).setUv(uv[i * 2] + p.uOffset, uv[i * 2 + 1])
				.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, sn[i * 3], sn[i * 3 + 1], sn[i * 3 + 2]);
	}

	/** White, with a finish's pearlescence in alpha for the viewmodel shader: 255 none, else 128 + 100 x the scale. */
	static int pearlColor(float pearl) {
		if (pearl == 0) return -1;
		int a = Math.max(1, Math.min(254, Math.round(128 + pearl * 100)));
		return a << 24 | 0xFFFFFF;
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
			// opaque materials: alpha isn't transparency; it carries CS2's metalness (the setup's / bake's metal mask, top
			// bit) and roughness (low 7 bits: a finish's own where it covers, else ours by metal) for the viewmodel shader
			if (opaque) {
				Path mask = p.resolveSibling(p.getFileName().toString().replace(".png", ".vm1024m.png"));
				Path roughness = p.resolveSibling(p.getFileName().toString().replace(".png", ".vm1024r.png"));
				BufferedImage m = Files.exists(mask) ? ImageIO.read(mask.toFile()) : null;
				BufferedImage r = Files.exists(roughness) ? ImageIO.read(roughness.toFile()) : null;
				int w = img.getWidth(), h = img.getHeight();
				for (int y = 0; y < h; y++)
					for (int x = 0; x < w; x++) {
						boolean metal = m != null && (m.getRGB(x * m.getWidth() / w, y * m.getHeight() / h) & 255) >= 128;
						double rough = metal ? 0.25 : 0.45;
						if (r != null) {
							int rc = r.getRGB(x * r.getWidth() / w, y * r.getHeight() / h);
							double cover = (rc >> 8 & 255) / 255.0;
							rough += ((rc >> 16 & 255) / 255.0 - rough) * cover;
						}
						int a = (metal ? 128 : 0) + (int) Math.round(Math.max(0, Math.min(1, rough)) * 127);
						img.setPixel(x, y, img.getPixel(x, y) & 0x00FFFFFF | a << 24);
					}
			}
			return img;
		}
	}

	/** The setup's 1024 px colour with CS2's ambient occlusion baked in, or the older plain copy. */
	static Path downscaledPath(Path p) {
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
