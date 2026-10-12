package dev.strafemc.client.weapon;

import com.mojang.blaze3d.platform.InputConstants;
import dev.strafemc.StrafeMc;
import dev.strafemc.bridge.Cs2Bridge;
import dev.strafemc.client.console.ConsoleKeys;
import dev.strafemc.client.movement.MovementController;
import dev.strafemc.client.sound.CsSounds;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.data.AnimClip;
import dev.strafemc.net.StrafePayloads;
import dev.strafemc.weapon.GunItem;
import dev.strafemc.weapon.GunServer;
import dev.strafemc.weapon.MagazineItem;
import dev.strafemc.weapon.WeaponStats;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Client side of CS2 guns: trigger input, deploy delay, CS inaccuracy + spread, reload and inspect, and the real
 * animation-timed sounds (draw/reload/inspect clip events). The MC server (GunServer) is authoritative for ammo and hits.
 */
public final class GunClient {
	public static final GunClient INSTANCE = new GunClient();

	public static KeyMapping RELOAD;

	private record Scheduled(long at, String event) {}

	private final List<Scheduled> sounds = new ArrayList<>();
	private final Random rng = new Random();
	private ItemStack lastHeld = ItemStack.EMPTY;
	private int lastSlot = -1;
	private long deployUntil, nextFire, inspectUntil, lastNs;
	/** Current reload in animation time (null = none): CS2 silent reload, shared logic with the server. */
	private dev.strafemc.weapon.ReloadTimeline reload;
	private double reloadLockout;
	/** Reload foley in clip time (event, time). */
	private final List<Object[]> reloadSounds = new ArrayList<>();
	private boolean triggerWasDown;
	private double penalty;
	/** CS2 recoil: aim punch (bullets), view punch (camera only), recoil index into the spray table. */
	private final dev.strafemc.weapon.AimPunch aimPunch = new dev.strafemc.weapon.AimPunch();
	private final double[] viewPunch = new double[2];
	private float recoilIndex;
	/** View punch decay rate when CS2's view_punch_decay cvar isn't known (CS:GO default; flagged). */
	private static final double VIEW_PUNCH_DECAY_DEFAULT = 18;

	private GunClient() {}

	public static void register() {
		RELOAD = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.strafemc.reload", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, ConsoleKeys.CATEGORY));
		// CS: F inspects. While a CS gun is held, Minecraft's swap-hands key (F by default) inspects instead.
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			if (mc.player == null || !(mc.player.getMainHandItem().getItem() instanceof GunItem)) return;
			while (mc.options.keySwapOffhand.consumeClick()) INSTANCE.inspect();
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (RELOAD.consumeClick()) INSTANCE.reload(true);
			INSTANCE.watchReloadKey();
		});
		// warm the AK's clip data off-thread so the first reload/inspect doesn't hitch
		Thread t = new Thread(() -> {
			for (String a : new String[] {"draw", "reload", "lookat01", "shoot1"}) AnimClip.load(GunServer.clipPath("weapon_ak47", a));
			preloadEffects("weapon_ak47");
			dev.strafemc.data.InspectGraph.options("knife_falchion");
			for (String a : new String[] {"draw", "lookat01", "lookat02", "light_miss1", "light_hit1", "heavy_miss1", "heavy_hit1"})
				AnimClip.load(GunServer.clipPath("weapon_knife_falchion", a));
			try {
				for (String d : new String[] {"sounds/weapons/knife/", "sounds/weapons/knife_falchion/"}) {
					java.nio.file.Path marker = StrafeConfig.cacheDir().resolve(d + ".extracted");
					if (java.nio.file.Files.exists(marker)) continue;
					dev.strafemc.data.AssetCache.extractFolder(d);
					java.nio.file.Files.createDirectories(marker.getParent());
					java.nio.file.Files.writeString(marker, "ok");
				}
			} catch (Exception ex) {
				StrafeMc.LOG.warn("[strafemc] knife sounds not pre-extracted: {}", ex.toString());
			}
		}, "strafemc-clips");
		t.setDaemon(true);
		t.start();
	}

	public static boolean holdingGun(LocalPlayer p) {
		return p != null && p.getMainHandItem().getItem() instanceof GunItem;
	}

	/** Max speed of the held CS weapon (NaN if none), for movement. */
	public static double heldMaxSpeed(LocalPlayer p) {
		if (p != null && p.getMainHandItem().getItem() instanceof GunItem g) {
			WeaponStats s = dev.strafemc.client.ClientGameplay.stats(g.weaponClass);
			if (s != null) return s.maxSpeed();
		}
		return Double.NaN;
	}

	public void onFrame(Minecraft mc) {
		LocalPlayer p = mc.player;
		long now = System.nanoTime();
		double dt = lastNs == 0 ? 0 : Math.min(0.25, (now - lastNs) / 1e9);
		lastNs = now;
		if (p == null) return;
		ItemStack held = p.getMainHandItem();
		int slot = p.getInventory().getSelectedSlot();
		if (slot != lastSlot || !ItemStack.isSameItem(held, lastHeld)) {
			lastSlot = slot;
			lastHeld = held;
			sounds.clear();
			CsSounds.stopAnim();
			inspectUntil = 0;
			reload = null;
			recoilIndex = 0;
			aimPunch.reset(now / 1e9);
			viewPunch[0] = viewPunch[1] = 0;
			reloadSounds.clear();
			if (!(held.getItem() instanceof GunItem)) dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.stop(); // put away: draw again next time
			if (held.getItem() instanceof GunItem g) { // (knives have no WeaponStats: m_flDeployDuration 1.0 from their vdata)
				WeaponStats ds = dev.strafemc.client.ClientGameplay.stats(g.weaponClass);
				deployUntil = now + sec(ds != null ? ds.deployDuration() : 1.0); // no attacking while drawing
				dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.play("draw");
				schedule(g, "draw", now);
			}
		}
		lastHeld = held;
		playDue(p, now);
		updateReload(p, now, dt);
		if (held.getItem() instanceof dev.strafemc.weapon.KnifeItem k) {
			knifeFrame(mc, p, k, now);
			return;
		}
		if (!(held.getItem() instanceof GunItem g)) return;
		WeaponStats w = dev.strafemc.client.ClientGameplay.stats(g.weaponClass);
		if (w == null) return;
		updatePenalty(w, dt);
		double t = now / 1e9;
		aimPunch.advance(t);
		if (now >= nextFire) recoilIndex = dev.strafemc.weapon.Recoil.decayIndex(recoilIndex, dt);
		double vd = Math.exp(-viewPunchDecay() * dt);
		viewPunch[0] *= vd;
		viewPunch[1] *= vd;

		boolean trigger = mc.screen == null && mc.options.keyAttack.isDown();
		if (trigger && now >= deployUntil && !reloadLocked() && now >= nextFire) {
			int inf = infiniteAmmo();
			// the server's clip update can trail a full-auto burst by a few shots: count locally while firing
			int clip = g.clip(held);
			if (predictedClip >= 0 && now - lastShot < sec(0.5)) clip = Math.min(clip, predictedClip);
			if (clip <= 0 && inf != 1) {
				if (!triggerWasDown) {
					CsSounds.playOn("Default.ClipEmpty_Rifle", p, 1.0, false);
					reload(false); // CS reloads when you pull the trigger on an empty magazine
				}
			} else if (w.fullAuto() || !triggerWasDown) {
				predictedClip = clip - 1;
				lastShot = now;
				shoot(p, w, inf == 1 ? w.clip() : predictedClip);
				nextFire = Math.max(now, nextFire) + sec(w.cycleTime());
			}
		}
		triggerWasDown = trigger;
	}

	private long knifeNextLight, knifeNextHeavy;
	private int knifeSwing;

	/** CS knife: left = slash, right = stab (held = repeats when ready), animation by hit/miss/backstab, server decides damage. */
	private void knifeFrame(Minecraft mc, LocalPlayer p, dev.strafemc.weapon.KnifeItem k, long now) {
		if (mc.screen != null || now < deployUntil) return;
		boolean heavy = mc.options.keyUse.isDown() && now >= knifeNextHeavy, light = !heavy && mc.options.keyAttack.isDown() && now >= knifeNextLight;
		if (!heavy && !light) return;
		GunServer.KnifeHit hit = GunServer.knifeTrace(p, heavy, 0);
		boolean didHit = hit.entity() != null || hit.block() != null;
		double[] cd = GunServer.KNIFE_COOLDOWN[(heavy ? 2 : 0) + (didHit ? 1 : 0)];
		knifeNextLight = now + sec(cd[0]);
		knifeNextHeavy = now + sec(cd[1]);
		knifeSwing++;
		String anim = heavy ? (hit.backstab() ? "heavy_backstab" : didHit ? "heavy_hit1" : "heavy_miss1")
				: hit.backstab() ? (knifeSwing % 2 == 0 ? "light_backstab" : "light_backstab2")
				: (didHit ? "light_hit" : "light_miss") + (knifeSwing % 2 + 1);
		sounds.clear();
		CsSounds.stopAnim();
		inspectUntil = 0;
		dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.play(anim);
		schedule(k, anim, now); // the clip carries the swish / hit / catch sounds
		if (hit.block() != null) CsSounds.playOn("Weapon_Knife.HitWall", p, 1.0, false);
		if (ClientPlayNetworking.canSend(StrafePayloads.Knife.TYPE)) ClientPlayNetworking.send(new StrafePayloads.Knife(heavy));
	}

	/** Base inaccuracy from stance/movement, recovering toward it with the weapon's recovery time (CS model). */
	private void updatePenalty(WeaponStats w, double dt) {
		MovementController m = MovementController.INSTANCE;
		double target = dev.strafemc.weapon.InaccuracyModel.target(w, m.onLadder(), m.onGround(), m.ducked(), m.horizontalSpeed());
		dev.strafemc.weapon.Recoil rc = dev.strafemc.client.ClientGameplay.recoil(w.cls());
		double rec = rc != null ? rc.recoveryTime(m.onGround(), m.ducked(), recoilIndex) : m.ducked() ? w.recoveryTimeCrouch() : w.recoveryTimeStand();
		penalty = dev.strafemc.weapon.InaccuracyModel.recover(penalty, target, rec, dt);
	}

	/**
	 * CS low-ammo warning: WEAPON_SOUND_NEARLYEMPTY plays with the shot once the magazine is at or below this fraction.
	 * Not exposed by CS2 data; CS:GO's value (flagged).
	 */
	private static final double NEARLY_EMPTY_FRACTION = 0.2;
	private int predictedClip = -1;
	/** This player's spread seed and next shot number from the server (ShotSeed); a local seed until it arrives. */
	private long shotSeed = new java.util.Random().nextLong();
	private int shotNumber;

	public void setShotSeed(long seed, int next) {
		shotSeed = seed;
		shotNumber = next;
	}
	private long lastShot;

	private void shoot(LocalPlayer p, WeaponStats w, int roundsLeft) {
		// predicted exactly like the server will decide it: same formula, the server's seed, the next shot number
		double t = System.nanoTime() / 1e9;
		aimPunch.advance(t);
		int shot = shotNumber++;
		Vec3 d = dev.strafemc.weapon.ShotMath.direction(p.getXRot(), p.getYRot(), aimPunch.angle[0], aimPunch.angle[1], penalty, w.spread(), shotSeed, shot);
		if (ClientPlayNetworking.canSend(StrafePayloads.Fire.TYPE))
			ClientPlayNetworking.send(new StrafePayloads.Fire(p.getYRot(), p.getXRot(), shot, (float) penalty, (float) aimPunch.angle[0], (float) aimPunch.angle[1]));
		if (inspectUntil != 0) { // a shot cancels the inspect: its remaining foley too, and F works again right away
			inspectUntil = 0;
			sounds.clear();
			CsSounds.stopAnim();
		}
		dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.play("shoot1");
		effects(p, w, d);
		predictImpact(p, w, d, shot);
		if (w.shootSound() != null) CsSounds.playOn(w.shootSound(), p, 1.0, false);
		if (w.nearlyEmptySound() != null && w.clip() > 0 && roundsLeft <= w.clip() * NEARLY_EMPTY_FRACTION)
			CsSounds.playOn(w.nearlyEmptySound(), p, 1.0, false);
		penalty += w.inaccuracyFire();
		dev.strafemc.weapon.Recoil rc = dev.strafemc.client.ClientGameplay.recoil(w.cls());
		if (rc != null) {
			int i = ((int) recoilIndex) & (dev.strafemc.weapon.Recoil.TABLE - 1);
			double[] vp = aimPunch.kick(t, rc.angle[0][i], rc.magnitude[0][i]);
			viewPunch[0] += vp[0];
			viewPunch[1] += vp[1];
			recoilIndex += 1;
		}
		if (StrafeConfig.debug) StrafeMc.LOG.info("[strafemc] fire {} inaccuracy={} spread={}", w.cls(), String.format("%.5f", penalty), w.spread());
	}

	/** Reload key held since the reload started (silent reload); cleared, and the server told, when it's let go. */
	private boolean reloadKeyHeld;

	void watchReloadKey() {
		if (reloadKeyHeld && !RELOAD.isDown()) {
			reloadKeyHeld = false;
			if (ClientPlayNetworking.canSend(StrafePayloads.Reload.TYPE)) ClientPlayNetworking.send(new StrafePayloads.Reload(StrafePayloads.Reload.RELEASE));
		}
	}

	/**
	 * CS2 shot effects: the shoot clip's particle events (muzzle flash at the muzzle_flash attachment) and, every
	 * m_nTracerFrequency-th shot of a burst, the weapon's m_szTracerParticle from the muzzle to where the bullet lands.
	 * Which shots of a burst carry the tracer (index % frequency == 0) is assumed (flagged).
	 */
	private void effects(LocalPlayer p, WeaponStats w, Vec3 dir) {
		AnimClip c = AnimClip.load(GunServer.clipPath(w.cls(), "shoot1"));
		if (c != null)
			for (AnimClip.Event e : c.events())
				if (e.kind().equals("CNmParticleEvent") && e.extra().startsWith("muzzleflash"))
					dev.strafemc.client.particle.Effects.view(e.name(), "muzzle_flash");
		java.util.Map<String, Object> vd = dev.strafemc.client.ClientGameplay.weapon(w.cls());
		if (vd == null) return;
		Object tracer = vd.get("m_szTracerParticle");
		int freq = vd.get("m_nTracerFrequency") instanceof Number n ? n.intValue() : 0;
		if (tracer == null || freq <= 0 || ((int) recoilIndex) % freq != 0) return;
		Minecraft mc = Minecraft.getInstance();
		Vec3 eye = p.getEyePosition();
		Vec3 end = eye.add(dir.scale(w.range() / StrafeConfig.unitsPerBlock));
		var hit = p.level().clip(new net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.NONE, p));
		if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) end = hit.getLocation();
		Vec3 start = muzzleWorld(mc);
		dev.strafemc.client.particle.Effects.tracer(tracer.toString(), start != null ? start : eye, end);
	}

	private static final java.util.Random IMPACT_RNG = new java.util.Random();

	/** The shooter's own bullet impact, shown immediately (as CS predicts it) instead of after the server round trip. */
	private static void predictImpact(LocalPlayer p, WeaponStats w, Vec3 dir, int shot) {
		Vec3 eye = p.getEyePosition(), end = eye.add(dir.scale(w.range() / StrafeConfig.unitsPerBlock));
		var hit = p.level().clip(new net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.NONE, p));
		if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return;
		var ent = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(p, eye, hit.getLocation(),
				new net.minecraft.world.phys.AABB(eye, hit.getLocation()).inflate(1), e -> e != p && e.isPickable() && !e.isSpectator(),
				eye.distanceToSqr(hit.getLocation()));
		if (ent != null) return; // the bullet stops in an entity first
		dev.strafemc.client.impact.BulletHoles.onPredicted(GunServer.impactMessage(p.level(), hit, eye, IMPACT_RNG.nextInt()), shot);
	}

	/** Background-loads a weapon's shot effects (muzzle flash events of its shoot clip, tracer) and the surface impact effects. */
	static void preloadEffects(String cls) {
		AnimClip c = AnimClip.load(GunServer.clipPath(cls, "shoot1"));
		if (c != null) for (AnimClip.Event e : c.events()) if (e.kind().equals("CNmParticleEvent")) dev.strafemc.client.particle.Effects.warm(e.name());
		for (int i = 0; i < 600 && !dev.strafemc.data.WeaponData.loaded(); i++)
			try {
				Thread.sleep(100);
			} catch (InterruptedException ex) {
				return;
			}
		java.util.Map<String, Object> vd = dev.strafemc.data.WeaponData.get(cls);
		if (vd != null && vd.get("m_szTracerParticle") != null) dev.strafemc.client.particle.Effects.warm(vd.get("m_szTracerParticle").toString());
		for (int i = 0; i < 600 && !dev.strafemc.data.Surfaces.loaded(); i++)
			try {
				Thread.sleep(100);
			} catch (InterruptedException ex) {
				return;
			}
		try { // bullet impact sounds (sounds/physics/surfaces) up front, so the first hit isn't late
			java.nio.file.Path marker = StrafeConfig.cacheDir().resolve("sounds/physics/surfaces/.extracted");
			if (!java.nio.file.Files.exists(marker)) {
				dev.strafemc.data.AssetCache.extractFolder("sounds/physics/surfaces/");
				java.nio.file.Files.createDirectories(marker.getParent());
				java.nio.file.Files.writeString(marker, "ok");
			}
		} catch (Exception ex) {
			StrafeMc.LOG.warn("[strafemc] impact sounds not pre-extracted: {}", ex.toString());
		}
		for (String s : new String[] {"default", "concrete", "rock", "tile", "Wood", "metal", "metalgrate", "chain", "glass", "dirt", "mud", "grass",
				"foliage", "sand", "gravel", "snow", "carpet"}) {
			dev.strafemc.data.Surfaces.Surface sf = dev.strafemc.data.Surfaces.get(s);
			if (sf != null) {
				dev.strafemc.client.particle.Effects.warm(sf.impactEffect());
				dev.strafemc.client.impact.DecalLibrary.prefetchGroup(sf.decalGroup());
			}
		}
	}

	/** The viewmodel's muzzle_flash attachment in world space (camera space -> world), or null. */
	private static Vec3 muzzleWorld(Minecraft mc) {
		org.joml.Matrix4f a = dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.attachmentTransforms.get("muzzle_flash");
		if (a == null) return null;
		org.joml.Vector3f m = a.getTranslation(new org.joml.Vector3f());
		var cam = mc.gameRenderer.getMainCamera();
		org.joml.Vector3fc f = cam.forwardVector(), u = cam.upVector(), l = cam.leftVector();
		Vec3 c = cam.position();
		// camera space: x right, y up, -z forward
		return new Vec3(c.x - l.x() * m.x + u.x() * m.y - f.x() * m.z, c.y - l.y() * m.x + u.y() * m.y - f.y() * m.z,
				c.z - l.z() * m.x + u.z() * m.y - f.z() * m.z);
	}

	private static double viewPunchDecay() {
		String v = dev.strafemc.client.ClientGameplay.cvar("view_punch_decay");
		try {
			return v == null ? VIEW_PUNCH_DECAY_DEFAULT : Double.parseDouble(v);
		} catch (NumberFormatException e) {
			return VIEW_PUNCH_DECAY_DEFAULT;
		}
	}

	/** Camera offset in MC degrees [pitch, yaw]: aim punch x0.45 plus view punch (CS2 client view code). */
	public double[] viewOffset() {
		aimPunch.advance(System.nanoTime() / 1e9);
		double pitch = aimPunch.angle[0] * dev.strafemc.weapon.AimPunch.VIEW_TRACKING + viewPunch[0];
		double yaw = aimPunch.angle[1] * dev.strafemc.weapon.AimPunch.VIEW_TRACKING + viewPunch[1];
		return new double[] {pitch, -yaw};
	}

	/** Ends an inspect early: back to idle, its foley cut. */
	private void cancelInspect() {
		if (inspectUntil == 0 || System.nanoTime() >= inspectUntil) return;
		inspectUntil = 0;
		sounds.clear();
		CsSounds.stopAnim();
		dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.play("idle");
	}

	public void reload(boolean byKey) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null || !(p.getMainHandItem().getItem() instanceof GunItem g)) return;
		// CS: R during an inspect cancels it when there's nothing to reload (full magazine, knife)
		WeaponStats w = dev.strafemc.client.ClientGameplay.stats(g.weaponClass);
		if (byKey && (g instanceof dev.strafemc.weapon.KnifeItem || w == null || g.clip(p.getMainHandItem()) >= w.clip())) {
			cancelInspect();
			return;
		}
		if (w == null) return;
		long now = System.nanoTime();
		if (reload != null || now < deployUntil || g.clip(p.getMainHandItem()) >= w.clip()) return;
		if (infiniteAmmo() != 2 && !hasMagazine(p, g)) return;
		reloadKeyHeld = byKey;
		double silentEnd = 0;
		reloadSounds.clear();
		AnimClip rc = AnimClip.load(GunServer.clipPath(g.weaponClass, "reload"));
		if (rc != null)
			for (AnimClip.Event e : rc.events()) {
				if (e.name().equals("WPN_RELOAD_SILENT")) silentEnd = e.time() + e.duration();
				else if (e.isSound() && !e.name().isEmpty()) reloadSounds.add(new Object[] {e.name(), e.time()});
			}
		// the silent window is gameplay (it decides who hears the reload): the server's timing wins over the local clip
		silentEnd = dev.strafemc.client.ClientGameplay.timing(g.weaponClass, "reloadSilentEnd", silentEnd);
		reload = new dev.strafemc.weapon.ReloadTimeline(silentEnd, byKey);
		reloadLockout = w.reloadLockout();
		if (ClientPlayNetworking.canSend(StrafePayloads.Reload.TYPE))
			ClientPlayNetworking.send(new StrafePayloads.Reload(byKey ? StrafePayloads.Reload.START_HELD : StrafePayloads.Reload.START));
		sounds.clear();
		CsSounds.stopAnim();
		inspectUntil = 0;
		dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.play("reload");
	}

	public void inspect() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null || !(p.getMainHandItem().getItem() instanceof GunItem g)) return;
		long now = System.nanoTime();
		if (reloadLocked() || now < deployUntil || now < inspectUntil || now < Math.min(knifeNextLight, knifeNextHeavy)) return;
		// which inspect (knives: lookat01 / rare lookat02) comes from the weapon's CS2 inspect graph weights
		String anim = g instanceof dev.strafemc.weapon.KnifeItem
				? dev.strafemc.data.InspectGraph.pick(g.weaponClass.substring("weapon_".length()), rng, "lookat01") : "lookat01";
		AnimClip c = AnimClip.load(GunServer.clipPath(g.weaponClass, anim));
		if (c == null) return;
		inspectUntil = now + sec(c.duration());
		schedule(g, anim, now);
		dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.play(anim);
	}

	/** Schedules the sound events of a weapon animation clip at their real times. */
	private void schedule(GunItem g, String anim, long start) {
		String path = GunServer.clipPath(g.weaponClass, anim);
		if (path == null) return;
		AnimClip c = AnimClip.load(path);
		if (c == null) return;
		for (AnimClip.Event e : c.events()) if (e.isSound() && !e.name().isEmpty()) sounds.add(new Scheduled(start + sec(e.time()), e.name()));
	}

	private boolean reloadLocked() {
		return reload != null && reload.animTime < reloadLockout;
	}

	/** Advances the reload: silent-reload state, viewmodel speed, foley (suppressed while stealthy). */
	private void updateReload(LocalPlayer p, long now, double dt) {
		if (reload == null) return;
		reload.advance(now / 1e9, dt, RELOAD.isDown() && reloadKeyHeld);
		dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.setRate((float) reload.rate);
		for (var it = reloadSounds.iterator(); it.hasNext(); ) {
			Object[] s = it.next();
			if (reload.animTime < (Double) s[1]) continue;
			it.remove();
			CsSounds.playOn((String) s[0], p, 1.0, reload.stealthy);
		}
		if (reload.animTime >= reloadLockout && reloadSounds.isEmpty()) {
			reload = null;
			dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.setRate(1f);
		}
	}

	private void playDue(LocalPlayer p, long now) {
		if (sounds.isEmpty()) return;
		var it = sounds.iterator();
		while (it.hasNext()) {
			Scheduled s = it.next();
			if (now >= s.at) {
				CsSounds.playAnim(s.event);
				it.remove();
			}
		}
	}

	public boolean reloading() {
		return reloadLocked();
	}

	private static boolean hasMagazine(LocalPlayer p, GunItem g) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.getItem() instanceof MagazineItem m && m.weaponClass.equals(g.weaponClass) && m.rounds(s) > 0) return true;
		}
		return false;
	}

	public static int magazineCount(LocalPlayer p, GunItem g) {
		int n = 0;
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.getItem() instanceof MagazineItem m && m.weaponClass.equals(g.weaponClass) && m.rounds(s) > 0) n++;
		}
		return n;
	}

	public static int infiniteAmmo() {
		String v = dev.strafemc.client.ClientGameplay.cvar("sv_infinite_ammo");
		try {
			return v == null ? 0 : (int) Double.parseDouble(v);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static long sec(double s) {
		return (long) (s * 1e9);
	}
}
