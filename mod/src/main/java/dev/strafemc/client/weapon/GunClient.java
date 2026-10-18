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

	public static KeyMapping RELOAD, BUY;

	private record Scheduled(long at, String event) {}

	private final List<Scheduled> sounds = new ArrayList<>();
	private final Random rng = new Random();
	private ItemStack lastHeld = ItemStack.EMPTY;
	private int lastSlot = -1;
	private long deployUntil, nextFire, inspectUntil, lastNs;
	/** strafeMC's inspect key (Options > Controls, F by default). */
	private static net.minecraft.client.KeyMapping INSPECT;
	/** Current reload in animation time (null = none): CS2 silent reload, shared logic with the server. */
	private dev.strafemc.weapon.ReloadTimeline reload;
	private double reloadLockout;
	/** Reload foley in clip time (event, time). */
	private final List<Object[]> reloadSounds = new ArrayList<>();
	private boolean triggerWasDown;
	/** The last round was just fired: the gun reloads by itself as soon as it could fire again. */
	private boolean emptied;
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
		BUY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.strafemc.buymenu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, ConsoleKeys.CATEGORY));
		// CS: F inspects. While a CS gun is held, Minecraft's swap-hands key (F by default) inspects instead.
		// CS2's inspect (F by default) is a strafeMC key of its own; while a CS weapon is held, Minecraft's swap-hands on
		// the same key is held back so it doesn't swap too
		INSPECT = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(new net.minecraft.client.KeyMapping(
				"key.strafemc.inspect", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, org.lwjgl.glfw.GLFW.GLFW_KEY_F,
				dev.strafemc.client.console.ConsoleKeys.CATEGORY));
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			boolean gun = mc.player != null && mc.player.getMainHandItem().getItem() instanceof GunItem;
			if (gun && mc.options.keySwapOffhand.same(INSPECT)) while (mc.options.keySwapOffhand.consumeClick()) {}
			while (INSPECT.consumeClick()) if (gun) INSTANCE.inspect();
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (RELOAD.consumeClick()) INSTANCE.reload(true);
			dev.strafemc.client.BuyMenuScreen.watchSpawn(mc);
			dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.preloadTick(mc); // inventory first, then everything else
			while (BUY.consumeClick()) if (mc.screen == null && mc.player != null) dev.strafemc.client.BuyMenuScreen.open(mc);
			INSTANCE.watchReloadKey();
		});
		// warm the AK's clip data off-thread so the first reload/inspect doesn't hitch
		Thread t = new Thread(() -> {
			for (String a : new String[] {"draw", "reload", "lookat01", "shoot1"}) AnimClip.load(GunServer.clipPath("weapon_ak47", a));
			preloadEffects("weapon_ak47");
			for (String fx : dev.strafemc.weapon.GrenadeEffects.EFFECTS) dev.strafemc.client.particle.Effects.warm(fx);
			dev.strafemc.client.particle.Effects.warm(MOLOTOV_HELD);
			dev.strafemc.data.InspectGraph.options("knife_falchion");
			for (String a : new String[] {"draw", "lookat01", "lookat02", "light_miss1", "light_hit1", "heavy_miss1", "heavy_hit1"})
				AnimClip.load(GunServer.clipPath("weapon_knife_falchion", a));
			dev.strafemc.client.viewmodel.Viewmodel.warmClips(); // every other weapon's clips too: no converter run on a weapon switch
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

	/** Odds of a knife's rare draw_ clip over its everyday draw2_ (CS2 sets deploy_variation in code; the odds are a guess - FLAGGED). */
	private static final double RARE_DRAW_CHANCE = 0.1;

	/**
	 * The draw clip: knives with two deploys pick by deploy_variation in their CS2 graph: draw2_ is the everyday one,
	 * draw_ the rare secret pullout (the user, in game, 2026-10-14: the other way round was flipped).
	 */
	private String drawClip(GunItem g) {
		String draw2 = GunServer.clipPath(g.weaponClass, "draw2");
		if (draw2 == null || !draw2.contains("draw2")) return "draw";
		return rng.nextDouble() < RARE_DRAW_CHANCE ? "draw" : "draw2";
	}

	/** Draws the held weapon again (switchhands: CS2 redeploys it), with its deploy delay; the server redeploys too. */
	public static void redeploy() {
		INSTANCE.lastSlot = -1;
		if (ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE)) ClientPlayNetworking.send(new StrafePayloads.Action("redeploy", ""));
	}

	/**
	 * Where the view (and so every shot) starts: the camera's eye, the feet plus getEyeHeight() (CS2's view offset under
	 * Source movement). Entity.getEyePosition() reads Minecraft's stored pose eye height (1.62) instead, 0.04 blocks
	 * above the camera, which put every bullet a little over the crosshair.
	 */
	static Vec3 eye(net.minecraft.world.entity.player.Player p) {
		return new Vec3(p.getX(), p.getY() + p.getEyeHeight(), p.getZ());
	}

	public static boolean holdingGun(LocalPlayer p) {
		return p != null && p.getMainHandItem().getItem() instanceof GunItem;
	}

	/** Max speed of the held CS weapon (NaN if none), for movement. */
	public static double heldMaxSpeed(LocalPlayer p) {
		if (p != null && p.getMainHandItem().getItem() instanceof GunItem g) {
			// scoped: CS2's scoped max speed (the AWP walks at 100)
			// the R8: CS2's two speeds are 180 while the hammer is being cocked (the primary's charge) and 220 otherwise
			int mode = g.weaponClass.equals("weapon_revolver") ? (INSTANCE.revolverReadyAt != 0 ? 0 : 1) : g.modeOf(p.getMainHandItem(), INSTANCE.zoom, false);
			WeaponStats s = dev.strafemc.client.ClientGameplay.stats(g.weaponClass, mode);
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
		releaseGrenade(p, now);
		ItemStack held = p.getMainHandItem();
		int slot = p.getInventory().getSelectedSlot();
		boolean equipped = false;
		if (slot != lastSlot || !ItemStack.isSameItem(held, lastHeld)) {
			equipped = held.getItem() instanceof GunItem;
			lastSlot = slot;
			lastHeld = held;
			grenadeState = GRENADE_IDLE;
			GrenadeClient.preview(null);
			dev.strafemc.client.particle.Effects.stopHeld("molotov");
			sounds.clear();
			CsSounds.stopAnim();
			inspectUntil = 0;
			reload = null;
			silencerUntil = 0; // switching away cancels a silencer being screwed on / off
			resetZoom(); // and unzooms (the server does the same)
			burstLeft = 0;
			revolverReadyAt = 0;
			recoilIndex = 0;
			aimPunch.reset(now / 1e9);
			viewPunch[0] = viewPunch[1] = 0;
			reloadSounds.clear();
			if (!(held.getItem() instanceof GunItem)) dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.stop(); // put away: draw again next time
			if (held.getItem() instanceof GunItem g) { // (knives have no WeaponStats: m_flDeployDuration 1.0 from their vdata)
				WeaponStats ds = dev.strafemc.client.ClientGameplay.stats(g.weaponClass);
				deployUntil = now + sec(ds != null ? ds.deployDuration() : 1.0); // no attacking while drawing
				String draw = drawClip(g);
				// the chosen draw is kept for the first frame too: the model may still be the last weapon's right now
				dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.nextDraw = draw;
				dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.stop();
				dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.play(draw);
				schedule(g, draw, now);
			}
		}
		lastHeld = held;
		// CS2: inspect held while the weapon comes out inspects it straight away (over the draw)
		if (equipped && INSPECT.isDown()) inspect();
		playDue(p, now);
		inspectHold(now);
		updateReload(p, now, dt);
		if (held.getItem() instanceof dev.strafemc.weapon.KnifeItem k) {
			knifeFrame(mc, p, k, now);
			return;
		}
		if (held.getItem() instanceof dev.strafemc.weapon.GrenadeItem gi) {
			grenadeFrame(mc, p, gi, now);
			return;
		}
		if (held.getItem() instanceof dev.strafemc.weapon.HealthshotItem hs) {
			healthshotFrame(mc, hs, now);
			return;
		}
		if (!(held.getItem() instanceof GunItem g)) return;
		// silencer on, burst mode, scoped: CS2's alt numbers
		WeaponStats w = dev.strafemc.client.ClientGameplay.stats(g.weaponClass, g.modeOf(held, zoom, false));
		if (w == null) return;
		updatePenalty(w, dt);
		double t = now / 1e9;
		aimPunch.advance(t);
		if (now >= nextFire) recoilIndex = dev.strafemc.weapon.Recoil.decayIndex(recoilIndex, dt);
		double vd = Math.exp(-viewPunchDecay() * dt);
		viewPunch[0] *= vd;
		viewPunch[1] *= vd;

		boolean trigger = mc.screen == null && mc.options.keyAttack.isDown();
		// right-click on a USP-S / M4A1-S: screw the silencer on or off (CS2's secondary attack); it flips at the clip's
		// WPN_SILENCER_ATTACH / _DETACH event, and the gun can't shoot or reload until then (the server does the same)
		boolean use = mc.screen == null && mc.options.keyUse.isDown();
		if (use && !useWasDown && !w.q().revolver() && !g.hasSilencer() && now >= deployUntil && now >= silencerUntil) secondary(p, g, held, w, now);
		if (use && !useWasDown && g.hasSilencer() && now >= deployUntil && reload == null && now >= silencerUntil && now >= nextFire) {
			boolean on = g.silenced(held);
			String anim = on ? "silencer_detach" : "silencer_attach";
			AnimClip c = AnimClip.peek(GunServer.clipPath(g.weaponClass, anim));
			silencerUntil = now + sec(c != null ? c.timeOf(on ? "WPN_SILENCER_DETACH" : "WPN_SILENCER_ATTACH", 3.4) : 3.4);
			cancelInspect();
			dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.play(anim);
			schedule(g, anim, now);
			if (ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE)) ClientPlayNetworking.send(new StrafePayloads.Action("silencer", ""));
		}
		useWasDown = use;
		// every gun (not just full-auto ones) starts its reload by itself once its last round is out and it could fire again
		if (emptied && now >= nextFire && burstLeft == 0 && reload == null && !reloadLocked()) {
			emptied = false;
			int left = g.clip(held);
			if (predictedClip >= 0 && now - lastShot < sec(0.5)) left = Math.min(left, predictedClip);
			if (StrafeConfig.debug) StrafeMc.LOG.info("[strafemc] auto reload {}: clip {} predicted {} -> {}", g.weaponClass, g.clip(held), predictedClip,
					left <= 0 && infiniteAmmo() != 1 ? "reload" : "not empty");
			if (left <= 0 && infiniteAmmo() != 1) reload(false);
		}
		// a sniper goes back to its zoom once the bolt is worked (unless reloading or empty)
		if (resumeZoom > 0 && now >= nextFire) {
			if (reload == null && g.clip(held) > 0) setZoom(p, dev.strafemc.client.ClientGameplay.stats(g.weaponClass), resumeZoom, false);
			resumeZoom = 0;
		}
		// the rest of a burst fires by itself, m_flTimeBetweenBurstShots apart
		if (burstLeft > 0 && now >= burstNextAt) {
			int clip = g.clip(held);
			if (predictedClip >= 0 && now - lastShot < sec(0.5)) clip = Math.min(clip, predictedClip);
			if (clip <= 0 && infiniteAmmo() != 1 || reload != null) burstLeft = 0;
			else {
				burstLeft--;
				burstNextAt += sec(w.q().burstGap());
				predictedClip = clip - 1;
				lastShot = now;
				shoot(p, w, infiniteAmmo() == 1 ? w.clip() : predictedClip, false);
			}
		}
		if (w.q().revolver()) {
			revolverFrame(p, g, held, w, trigger, use, now);
			triggerWasDown = trigger;
			return;
		}
		if (trigger && now >= deployUntil && !reloadLocked() && now >= nextFire && now >= silencerUntil) {
			int inf = infiniteAmmo();
			// the server's clip update can trail a full-auto burst by a few shots: count locally while firing
			int clip = g.clip(held);
			if (predictedClip >= 0 && now - lastShot < sec(0.5)) clip = Math.min(clip, predictedClip);
			if (clip <= 0 && inf != 1) {
				if (!triggerWasDown) {
					CsSounds.playOn("Default.ClipEmpty_Rifle", p, 1.0, false);
					reload(false); // CS reloads when you pull the trigger on an empty magazine
				} else if (w.fullAuto() && reload == null) reload(false); // and when a full-auto spray empties it, trigger still held
			} else if (w.fullAuto() && !(w.q().burst() && g.burst(held)) || !triggerWasDown) { // a burst per click
				predictedClip = clip - 1;
				lastShot = now;
				boolean burst = w.q().burst() && g.burst(held);
				long at = Math.max(now, nextFire);
				shoot(p, w, inf == 1 ? w.clip() : predictedClip, false);
				nextFire = at + sec(burst ? w.q().burstCycle() : w.cycleTime()); // a burst's cycle time runs from its first shot
				if (burst) {
					burstLeft = GunServer.BURST_SHOTS - 1;
					burstNextAt = at + sec(w.q().burstGap());
				}
			}
		}
		triggerWasDown = trigger;
	}

	/**
	 * Right-click (CS2's secondary attack) on guns without a silencer: scoped guns step through their zoom levels and back
	 * to none; the FAMAS and Glock-18 switch burst fire on and off (with CS2's notice).
	 */
	private void secondary(LocalPlayer p, GunItem g, ItemStack held, WeaponStats w, long now) {
		if (w.q().zoomLevels() > 0) {
			if (reload != null || now < nextSecondary) return;
			nextSecondary = now + sec(ZOOM_INTERVAL);
			resumeZoom = 0;
			cancelInspect();
			setZoom(p, dev.strafemc.client.ClientGameplay.stats(g.weaponClass), (zoom + 1) % (w.q().zoomLevels() + 1), true);
		} else if (w.q().burst()) {
			boolean on = !g.burst(held);
			held.set(dev.strafemc.weapon.WeaponItems.BURST, on ? 1 : 0); // predicted; the server sets the same
			if (ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE)) ClientPlayNetworking.send(new StrafePayloads.Action("burst", ""));
			String msg = on ? dev.strafemc.client.CsText.get("Cstrike_TitlesTXT_Switch_To_BurstFire", "Switched to burst-fire mode")
					: w.fullAuto() ? dev.strafemc.client.CsText.get("Cstrike_TitlesTXT_Switch_To_FullAuto", "Switched to automatic")
					: dev.strafemc.client.CsText.get("Cstrike_TitlesTXT_Switch_To_SemiAuto", "Switched to semi-automatic");
			dev.strafemc.client.movement.MovementController.notice(msg);
			// CS2's switch click: Weapon.AutoSemiAutoSwitch into burst, _Auto out of it (pairing FLAGGED)
			CsSounds.playOn(on ? "Weapon.AutoSemiAutoSwitch" : "Weapon.AutoSemiAutoSwitch_Auto", p, 1.0, false);
		}
	}

	/** " BURST" after the speed line while the held FAMAS / Glock-18 is in burst mode, else empty. */
	public static String fireModeTag(LocalPlayer p) {
		ItemStack held = p.getMainHandItem();
		if (!(held.getItem() instanceof GunItem g)) return "";
		WeaponStats w = dev.strafemc.client.ClientGameplay.stats(g.weaponClass);
		return ""; // CS2 shows burst on the ammo panel (AmmoHud's fire mode icon), not here
	}

	/**
	 * The R8 Revolver: holding the trigger cocks the hammer (prepare clip) and fires once it's back (REVOLVER_PREPARE),
	 * again and again while held; letting go first lowers it. Right-click fans the hammer: shoot_alt with CS2's alt
	 * numbers (less accurate, m_flCycleTime[1]).
	 */
	private void revolverFrame(LocalPlayer p, GunItem g, ItemStack held, WeaponStats w, boolean trigger, boolean use, long now) {
		boolean ready = now >= deployUntil && !reloadLocked() && now >= nextFire;
		int inf = infiniteAmmo();
		int clip = g.clip(held);
		if (predictedClip >= 0 && now - lastShot < sec(0.5)) clip = Math.min(clip, predictedClip);
		if ((trigger || use) && ready && clip <= 0 && inf != 1) {
			if (!triggerWasDown && !useWasDownRevolver) {
				CsSounds.playOn("Default.ClipEmpty_Rifle", p, 1.0, false);
				reload(false);
			}
		} else if (use && ready && revolverReadyAt == 0) {
			WeaponStats alt = dev.strafemc.client.ClientGameplay.stats(g.weaponClass, 1);
			if (alt != null) {
				predictedClip = clip - 1;
				lastShot = now;
				shoot(p, alt, inf == 1 ? w.clip() : predictedClip, true);
				nextFire = now + sec(alt.cycleTime());
			}
		} else if (trigger && ready) {
			if (revolverReadyAt == 0) {
				revolverReadyAt = now + sec(REVOLVER_PREPARE);
				cancelInspect();
				dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.play("prepare");
				schedule(g, "prepare", now);
			} else if (now >= revolverReadyAt) {
				revolverReadyAt = 0;
				predictedClip = clip - 1;
				lastShot = now;
				shoot(p, w, inf == 1 ? w.clip() : predictedClip, false);
				nextFire = now + sec(w.cycleTime());
			}
		} else if (!trigger && revolverReadyAt != 0) { // let go before the shot: the hammer goes back down
			revolverReadyAt = 0;
			sounds.clear();
			CsSounds.stopAnim();
			dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.play("idle");
		}
		useWasDownRevolver = use;
	}

	private boolean useWasDownRevolver;

	private long knifeNextLight, knifeNextHeavy;
	/** A silencer being screwed on / off: no shooting or reloading until it flips. */
	private long silencerUntil;
	private boolean useWasDown;

	// scopes: the zoom level (0 = none), the FOV moving from zoomFromFov to zoomToFov (CS2 degrees) over zoomTime from
	// zoomAt; resumeZoom: the level a sniper goes back to once its bolt is worked (m_bUnzoomsAfterShot)
	private int zoom, resumeZoom;
	private long zoomAt, nextSecondary;
	private double zoomFromFov = 90, zoomToFov = 90, zoomTime;
	/** Time between right-clicks on a scoped gun (CS:GO's m_flNextSecondaryAttack + 0.3 for scopes; FLAGGED as CS2's). */
	private static final double ZOOM_INTERVAL = 0.3;
	// burst fire: shots of the burst still to come and when the next is due
	private int burstLeft;
	private long burstNextAt;
	/** R8: when the hammer is back and the held trigger fires (0: not cocking). */
	private long revolverReadyAt;
	/** R8: holding the trigger cocks the hammer this long before the shot (CS:GO's 0.234375 s; FLAGGED as CS2's). */
	private static final double REVOLVER_PREPARE = 0.234375;

	/** The CS2 FOV right now (90: not zoomed), moving to the zoom level's FOV over its zoom time. */
	private double zoomFovCs() {
		double f = zoomTime <= 0 ? 1 : Math.min(1, (System.nanoTime() - zoomAt) / 1e9 / zoomTime);
		return zoomFromFov + (zoomToFov - zoomFromFov) * f;
	}

	/**
	 * The world FOV while scoped: CS2's zoom FOV is relative to its 90 degree default, so the view narrows by the same
	 * ratio of half-angle tangents from the player's own FOV.
	 */
	public float zoomFov(float fov) {
		double cs = zoomFovCs();
		if (cs >= 89.999) return fov;
		double k = Math.tan(Math.toRadians(cs / 2)) / Math.tan(Math.toRadians(45));
		return (float) Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(fov / 2)) * k));
	}

	/** Mouse turning while scoped: zoom_sensitivity_ratio x zoomed FOV / 90, like CS2. */
	public double zoomSensitivity() {
		return zoom > 0 ? StrafeConfig.zoomSensitivityRatio * zoomToFov / 90.0 : 1;
	}

	/** Scoped with a sniper: the scope overlay instead of the viewmodel (m_bHideViewModelWhenZoomed). */
	public boolean scopeOverlay() {
		if (zoom <= 0) return false;
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null || !(p.getMainHandItem().getItem() instanceof GunItem g)) return false;
		java.util.Map<String, Object> vd = dev.strafemc.client.ClientGameplay.weapon(g.weaponClass);
		return vd != null && Boolean.TRUE.equals(vd.get("m_bHideViewModelWhenZoomed"));
	}

	/** The inaccuracy now (the scope's reticle blur follows it). */
	public double inaccuracy() {
		return penalty;
	}

	/** Changes the zoom level: the FOV moves over the level's m_flZoomTime, with CS2's zoom sounds; the server is told. */
	private void setZoom(LocalPlayer p, WeaponStats w, int level, boolean sound) {
		if (level == zoom) return;
		zoomFromFov = zoomFovCs();
		zoomToFov = w == null ? 90 : w.q().zoomFov(level);
		zoomTime = w == null ? 0 : w.q().zoomTime()[Math.min(level, 2)];
		zoomAt = System.nanoTime();
		if (sound && w != null) {
			String snd = level > zoom ? w.q().zoomInSound() : w.q().zoomOutSound();
			if (snd != null) CsSounds.playOn(snd, p, 1.0, false);
		}
		zoom = level;
		if (ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE)) ClientPlayNetworking.send(new StrafePayloads.Action("zoom", String.valueOf(level)));
	}

	/** Unzoomed at once (weapon switch). */
	private void resetZoom() {
		zoom = resumeZoom = 0;
		zoomFromFov = zoomToFov = 90;
		zoomTime = 0;
	}

	// grenades: idle -> pulling the pin -> charged (held) -> thrown -> the next one is drawn
	private static final int GRENADE_IDLE = 0, GRENADE_PULLING = 1, GRENADE_CHARGED = 2, GRENADE_THROWN = 3;
	private int grenadeState;
	private long pinDoneAt, throwDoneAt;
	private long pinAnimDoneAt, ragLitAt;
	private float throwStrength = 1;
	/**
	 * Throw strength moves toward the buttons held (left 1, both 0.5, right 0) at this rate per second instead of
	 * jumping, so releasing both buttons a moment apart still throws at about 0.5 (FLAGGED: CS:GO's approach rate as we
	 * know it).
	 */
	private static final float STRENGTH_RATE = 2f;
	private long strengthNs;
	private String chargeAnim;
	private boolean throwQueued;
	private long chargedSince, pinPulledAt;
	private dev.strafemc.weapon.Grenade pinType;

	/** Seconds the pin has been out of the grenade in hand (pulling or charged), or -1. */
	public double pinSeconds() {
		return grenadeState == GRENADE_PULLING || grenadeState == GRENADE_CHARGED ? (System.nanoTime() - pinPulledAt) / 1e9 : -1;
	}

	public dev.strafemc.weapon.Grenade pinGrenade() {
		return grenadeState == GRENADE_PULLING || grenadeState == GRENADE_CHARGED ? pinType : null;
	}
	/** Show the trajectory preview after holding a charged grenade this long (seconds). */
	private static final double PREVIEW_AFTER = 2.0;

	/**
	 * CS grenade handling: holding left and/or right pulls the pin (its animation and sounds); while held, the charge pose
	 * shows the throw: left = overhand (strength 1), right = underhand (0), both = in between (0.5). Releasing throws
	 * (if the pin is still being pulled, as soon as it's out). The server spawns the grenade.
	 */
	private void grenadeFrame(Minecraft mc, LocalPlayer p, dev.strafemc.weapon.GrenadeItem g, long now) {
		var vm = dev.strafemc.client.viewmodel.Viewmodel.INSTANCE;
		if (grenadeState == GRENADE_THROWN) {
			if (now < throwDoneAt) return;
			grenadeState = GRENADE_IDLE; // another one in the stack (or creative): draw it
			deployUntil = now + sec(0.4);
			vm.play("draw");
			schedule(g, "draw", now);
			return;
		}
		if (mc.screen != null || now < deployUntil) return;
		boolean left = mc.options.keyAttack.isDown(), right = mc.options.keyUse.isDown(), held = left || right;
		if (held) {
			float target = left && right ? 0.5f : left ? 1f : 0f;
			if (grenadeState == GRENADE_IDLE) throwStrength = target; // a new pull starts where the buttons say
			else {
				float step = STRENGTH_RATE * Math.min(0.1f, (now - strengthNs) / 1e9f);
				throwStrength += Math.max(-step, Math.min(step, target - throwStrength));
			}
		}
		strengthNs = now;
		switch (grenadeState) {
			case GRENADE_IDLE -> {
				if (!held) return;
				vm.play("pullpin");
				schedule(g, "pullpin", now);
				pinDoneAt = now + sec(pinSeconds(g, left));
				pinAnimDoneAt = now + sec(clipSeconds(g, "pullpin", 0.8));
				grenadeState = GRENADE_PULLING;
				ragLitAt = 0;
				pinPulledAt = now;
				pinType = g.type;
				throwQueued = false;
				chargeAnim = null;
			}
			case GRENADE_PULLING -> {
				lightRag(g, now);
				if (!held) throwQueued = true;
				if (now < pinDoneAt) return;
				if (throwQueued) { // a quick tap: thrown as soon as the pin can go, the pin animation cut short
					throwGrenade(p, g, now);
					return;
				}
				if (now < pinAnimDoneAt) return; // still held: the charge pose once the pin is out
				grenadeState = GRENADE_CHARGED;
				chargedSince = now;
			}
			case GRENADE_CHARGED -> {
				if (!held) {
					throwGrenade(p, g, now);
					return;
				}
				lightRag(g, now);
				if (now - chargedSince >= sec(PREVIEW_AFTER)) { // the path this throw would take, as the server will fly it
					var l = dev.strafemc.weapon.GrenadeFlight.launch(p.level(), p, eye(p), p.getYRot(), p.getXRot(), throwStrength,
							dev.strafemc.client.movement.MovementController.INSTANCE.velocityUnits(p), 750);
					GrenadeClient.preview(dev.strafemc.weapon.GrenadeFlight.simulate(p.level(), p, l, dev.strafemc.weapon.GrenadeFlight.gravity(), 4, g.type));
				} else GrenadeClient.preview(null);
				String anim = throwStrength >= 1 ? "throwcharge_high" : throwStrength <= 0 ? "throwcharge_low" : "throwcharge_mid";
				if (!anim.equals(chargeAnim)) {
					vm.play(anim);
					chargeAnim = anim;
				}
			}
			default -> {}
		}
	}

	/**
	 * The burning rag: CS2's particle event sits at the start of the 1 s charge clip, which its graph runs from the pin
	 * pull on (FLAGGED: our reading), and fires each time that clip loops (the effect emits for 1.25 s, from 0.4 s, so
	 * they overlap).
	 */
	private void lightRag(dev.strafemc.weapon.GrenadeItem g, long now) {
		if (g.type == dev.strafemc.weapon.Grenade.MOLOTOV && now >= ragLitAt) { // only the molotov has a rag (the incendiary clips have no flame event)
			dev.strafemc.client.particle.Effects.viewHeld("molotov", MOLOTOV_HELD, "molotov_particle");
			ragLitAt = now + sec(clipSeconds(g, "throwcharge_low", 1.0));
		}
	}

	/** Medi-Shot: when the current injection's WPN_HEALTHSHOT_INJECT event is due (0: none running) and when it ends. */
	private long injectAt, injectDoneAt;

	/** Left click injects (the clip's own sounds); at its inject event the server heals and uses one up. */
	private void healthshotFrame(Minecraft mc, dev.strafemc.weapon.HealthshotItem hs, long now) {
		if (injectAt != 0 && now >= injectAt) {
			injectAt = 0;
			if (ClientPlayNetworking.canSend(StrafePayloads.Action.TYPE)) ClientPlayNetworking.send(new StrafePayloads.Action("healthshot", ""));
		}
		if (now < injectDoneAt || now < deployUntil || mc.screen != null || !mc.options.keyAttack.isDown()) return;
		dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.play("inject");
		schedule(hs, "inject", now);
		injectAt = now + sec(dev.strafemc.weapon.HealthshotItem.INJECT_AT);
		injectDoneAt = now + sec(clipSeconds(hs, "inject", 1.9));
	}

	/** CS2's burning rag on a lit molotov (the throwcharge clip's particle event at attachment molotov_particle). */
	static final String MOLOTOV_HELD = "particles/weapons/cs_weapon_fx/weapon_molotov_held.vpcf";

	/** CS: the grenade leaves the hand this long after the throw starts, with the aim and speed of that moment (FLAGGED: CS:GO's 0.1 s). */
	private static final double RELEASE_DELAY = 0.1;
	private long releaseAt;

	/** Sends a started throw once its release time comes (every frame, whatever is held by then). */
	private void releaseGrenade(LocalPlayer p, long now) {
		if (releaseAt == 0 || now < releaseAt) return;
		releaseAt = 0;
		var v = dev.strafemc.client.movement.MovementController.INSTANCE.velocityUnits(p);
		if (ClientPlayNetworking.canSend(StrafePayloads.Throw.TYPE))
			ClientPlayNetworking.send(new StrafePayloads.Throw(throwStrength, p.getYRot(), p.getXRot(), (float) v.x, (float) v.y, (float) v.z));
	}

	private void throwGrenade(LocalPlayer p, dev.strafemc.weapon.GrenadeItem g, long now) {
		GrenadeClient.preview(null);
		dev.strafemc.client.particle.Effects.stopHeld("molotov");
		String anim = throwStrength >= 0.5f ? "throw_overhand" : "throw_underhand";
		dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.play(anim);
		schedule(g, anim, now);
		releaseAt = now + sec(RELEASE_DELAY);
		grenadeState = GRENADE_THROWN;
		throwDoneAt = now + sec(clipSeconds(g, anim, 0.5));
	}

	/**
	 * How soon a pulled pin can be thrown: the grenade's m_flCycleTime (CS2 weapons.vdata: 0.15 s with left, 0.3 s with
	 * right), not the whole pin animation, so a quick tap throws quickly (FLAGGED: our reading of what the cycle time gates).
	 */
	private static double pinSeconds(GunItem g, boolean primary) {
		var d = dev.strafemc.data.WeaponData.get(g.weaponClass);
		Object ct = d == null ? null : d.get("m_flCycleTime");
		if (ct instanceof java.util.List<?> l && !l.isEmpty()) ct = l.get(primary || l.size() < 2 ? 0 : 1);
		return ct instanceof Number n && n.doubleValue() > 0 ? n.doubleValue() : clipSeconds(g, "pullpin", 0.8);
	}

	private static double clipSeconds(GunItem g, String anim, double fallback) {
		String path = GunServer.clipPath(g.weaponClass, anim);
		AnimClip c = path == null ? null : AnimClip.peek(path);
		return c != null && c.duration() > 0 ? c.duration() : fallback;
	}
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
		if (hit.block() != null) {
			CsSounds.playOn("Weapon_Knife.HitWall", p, 1.0, false);
			dev.strafemc.client.impact.BulletHoles.onImpact(GunServer.impactMessage(p.level(), hit.block(), eye(p), IMPACT_RNG.nextInt(), true));
		}
		if (ClientPlayNetworking.canSend(StrafePayloads.Knife.TYPE))
			ClientPlayNetworking.send(new StrafePayloads.Knife(heavy, dev.strafemc.client.LagClock.viewTick(), hit.entity() != null ? hit.entity().getId() : -1));
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

	/** alt: the R8's alt fire. */
	private void shoot(LocalPlayer p, WeaponStats w, int roundsLeft, boolean alt) {
		// predicted exactly like the server will decide it: same formula, the server's seed, the next shot number
		double t = System.nanoTime() / 1e9;
		aimPunch.advance(t);
		int shot = shotNumber++;
		if (roundsLeft <= 0) emptied = true;
		Vec3 d = dev.strafemc.weapon.ShotMath.direction(p.getXRot(), p.getYRot(), aimPunch.angle[0], aimPunch.angle[1], penalty, w.spread(), shotSeed, shot);
		if (ClientPlayNetworking.canSend(StrafePayloads.Fire.TYPE))
			ClientPlayNetworking.send(new StrafePayloads.Fire(p.getYRot(), p.getXRot(), shot, (float) penalty, (float) aimPunch.angle[0], (float) aimPunch.angle[1],
					dev.strafemc.client.LagClock.viewTick(), claim(p, w, d), Math.min(zoom, 3) | (alt ? StrafePayloads.Fire.ALT : 0) | claimedGroup << StrafePayloads.Fire.GROUP_SHIFT,
						(float) p.getEyeHeight()));
		if (inspectUntil != 0) { // a shot cancels the inspect: its remaining foley too, and F works again right away
			inspectUntil = 0;
			sounds.clear();
			CsSounds.stopAnim();
		}
		if (StrafeConfig.debug) { // camera vs shot: where the view looks and where the bullet goes
			var cam = Minecraft.getInstance().gameRenderer.getMainCamera();
			double[] vo = viewOffset();
			StrafeMc.LOG.info("[strafemc] shotdbg client: {} shot {} camY={} eyeY={} camPitch={} xRot={} viewOff={} punch={} inacc={} dirPitch={}",
					p.getMainHandItem().getItem() instanceof GunItem gi ? gi.weaponClass : "?", recoilIndex, String.format("%.4f", cam.position().y), String.format("%.4f", eye(p).y), String.format("%.3f", cam.xRot()),
					String.format("%.3f", p.getXRot()), String.format("%.3f", vo[0]), String.format("%.3f", aimPunch.angle[0]), String.format("%.5f", penalty),
					String.format("%.3f", Math.toDegrees(-Math.asin(d.y))));
		}
		if (shell != null && reload != null) endShellReload(); // a shot stops a shotgun's shell-by-shell reload (the server too)
		String anim = shootAnim(w, roundsLeft, alt);
		dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.play(anim);
		// the shoot clip's own foley: pump-action and bolt-action guns work the action after the shot (Nova, MAG-7,
		// Sawed-Off, AWP, SSG 08); the shot itself is the weapon's shoot sound below
		if (p.getMainHandItem().getItem() instanceof GunItem held) schedule(held, anim, System.nanoTime());
		effects(p, w, d, anim);
		predictImpact(p, w, d, shot);
		String sound = GunServer.shootSound(w, penalty); // the Negev's focused sound once it's accurate
		if (sound != null) CsSounds.playOn(sound, p, 1.0, false);
		if (w.q().unzoomAfterShot() && zoom > 0) { // AWP / SSG 08: unscoped for the bolt, back after it
			resumeZoom = zoom;
			setZoom(p, w, 0, false);
		}
		if (w.nearlyEmptySound() != null && w.clip() > 0 && roundsLeft <= w.clip() * NEARLY_EMPTY_FRACTION)
			CsSounds.playOn(w.nearlyEmptySound(), p, 1.0, false);
		penalty += w.inaccuracyFire();
		dev.strafemc.weapon.Recoil rc = dev.strafemc.client.ClientGameplay.recoil(w.cls());
		if (rc != null) {
			int i = ((int) recoilIndex) & (dev.strafemc.weapon.Recoil.TABLE - 1);
			double[] vp = aimPunch.kick(t, rc.angle[w.mode()][i], rc.magnitude[w.mode()][i]);
			viewPunch[0] += vp[0];
			viewPunch[1] += vp[1];
			recoilIndex += 1;
		}
		if (StrafeConfig.debug) StrafeMc.LOG.info("[strafemc] fire {} inaccuracy={} spread={}", w.cls(), String.format("%.5f", penalty), w.spread());
	}

	/**
	 * Which shoot clip: the R8's alt fire shoot_alt; the Dual Berettas take turns, right (shoot1) then left (shoot_left),
	 * odd rounds left after the shot being the right gun's; the last round fires the gun's shoot_last (the slide stays back,
	 * where the gun has one).
	 */
	private static String shootAnim(WeaponStats w, int roundsLeft, boolean alt) {
		if (alt) return "shoot_alt";
		boolean left = w.cls().equals("weapon_elite") && roundsLeft % 2 == 0;
		if (roundsLeft <= 0) return left ? "shoot_left_last" : "shoot_last";
		return left ? "shoot_left" : "shoot1";
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
	private void effects(LocalPlayer p, WeaponStats w, Vec3 dir, String anim) {
		AnimClip c = AnimClip.peek(GunServer.clipPath(w.cls(), anim));
		if (c != null)
			for (AnimClip.Event e : c.events())
				if (e.kind().equals("CNmParticleEvent") && (e.extra().startsWith("muzzleflash") || e.name().contains("muzzleflash")))
					dev.strafemc.client.particle.Effects.view(e.name(), muzzleAttachment(e.extra()));
		java.util.Map<String, Object> vd = dev.strafemc.client.ClientGameplay.weapon(w.cls());
		if (vd == null) return;
		Object tracer = vd.get("m_szTracerParticle");
		int freq = w.tracerFrequency(); // a [normal, silenced] pair in CS2's data (the M4A1-S: 3, 0)
		if (tracer == null || freq <= 0 || ((int) recoilIndex) % freq != 0) return;
		Minecraft mc = Minecraft.getInstance();
		Vec3 eye = eye(p);
		Vec3 end = eye.add(dir.scale(w.range() / StrafeConfig.unitsPerBlock));
		var hit = p.level().clip(new net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.NONE, p));
		if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) end = hit.getLocation();
		Vec3 start = muzzleWorld(mc);
		dev.strafemc.client.particle.Effects.tracer(tracer.toString(), start != null ? start : eye, end);
	}

	/** The muzzle a flash comes from: the Dual Berettas' left gun flashes with config "alternate" at the second muzzle. */
	private static String muzzleAttachment(String extra) {
		var at = dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.attachmentTransforms;
		if (extra.contains("alternate"))
			for (String n : new String[] {"muzzle_flash2", "muzzle_flash_2", "muzzle_flash_left"}) if (at.containsKey(n)) return n;
		return "muzzle_flash";
	}

	private static final java.util.Random IMPACT_RNG = new java.util.Random();

	/** The body part claim() saw on the hit entity's model (Fire.GROUPS index + 1, 0 none). */
	private static int claimedGroup;

	/**
	 * The entity this shot hits on this screen (what the player saw), or -1: the server checks it against its history.
	 * Also measures which body part the bullet meets on its real model (ClientHitbox) into claimedGroup.
	 */
	private static int claim(LocalPlayer p, WeaponStats w, Vec3 dir) {
		claimedGroup = 0;
		Vec3 eye = eye(p), end = eye.add(dir.scale(w.range() / StrafeConfig.unitsPerBlock));
		var block = p.level().clip(new net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.NONE, p));
		if (block.getType() != net.minecraft.world.phys.HitResult.Type.MISS) end = block.getLocation();
		var ent = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(p, eye, end, new net.minecraft.world.phys.AABB(eye, end).inflate(1),
				e -> e != p && e.isPickable() && !e.isSpectator(), eye.distanceToSqr(end));
		if (ent == null) return -1;
		if (ent.getEntity() instanceof net.minecraft.world.entity.LivingEntity le) {
			String g = ClientHitbox.part(le, eye, dir, eye.distanceTo(end) + 2);
			if (g != null) claimedGroup = java.util.Arrays.asList(StrafePayloads.Fire.GROUPS).indexOf(g) + 1;
		}
		return ent.getEntity().getId();
	}

	/** The shooter's own bullet impact, shown immediately (as CS predicts it) instead of after the server round trip. */
	private static void predictImpact(LocalPlayer p, WeaponStats w, Vec3 dir, int shot) {
		Vec3 eye = eye(p), end = eye.add(dir.scale(w.range() / StrafeConfig.unitsPerBlock));
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
		AnimClip c = AnimClip.peek(GunServer.clipPath(cls, "shoot1"));
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
				dev.strafemc.client.impact.DecalLibrary.prefetchGroup(dev.strafemc.client.impact.BulletHoles.KNIFE_DECALS);
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

	/** Where the bullets go relative to the camera, MC degrees [pitch, yaw]: the full aim punch minus what the view shows. */
	public double[] crosshairRecoil() {
		double[] view = viewOffset();
		return new double[] {aimPunch.angle[0] - view[0], -aimPunch.angle[1] - view[1]};
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
		if (g.weaponClass.equals(dev.strafemc.weapon.ZeusRecharge.CLS)) { // the Zeus can't be reloaded: it recharges by itself
			if (byKey) cancelInspect();
			return;
		}
		// CS: R during an inspect cancels it when there's nothing to reload (full magazine, knife)
		WeaponStats w = dev.strafemc.client.ClientGameplay.stats(g.weaponClass);
		if (byKey && (g instanceof dev.strafemc.weapon.KnifeItem || w == null || g.clip(p.getMainHandItem()) >= w.clip())) {
			cancelInspect();
			return;
		}
		if (w == null) return;
		long now = System.nanoTime();
		if (reload != null || now < deployUntil || now < silencerUntil || g.clip(p.getMainHandItem()) >= w.clip()) {
			if (StrafeConfig.debug) StrafeMc.LOG.info("[strafemc] reload {} refused: reloading {}, deploying {}, silencer {}, clip {}/{}", g.weaponClass,
					reload != null, now < deployUntil, now < silencerUntil, g.clip(p.getMainHandItem()), w.clip());
			return;
		}
		if (infiniteAmmo() != 2 && !hasMagazine(p, g)) {
			if (StrafeConfig.debug) StrafeMc.LOG.info("[strafemc] reload {} refused: no magazine found (carried: {})", g.weaponClass,
					magazineCount(p, g));
			return;
		}
		reloadKeyHeld = byKey;
		if (zoom > 0 || resumeZoom > 0) { // reloading takes the scope off
			resumeZoom = 0;
			setZoom(p, w, 0, false);
		}
		burstLeft = 0;
		revolverReadyAt = 0;
		double silentEnd = 0;
		reloadSounds.clear();
		int loaded = g.clip(p.getMainHandItem());
		String anim = GunServer.reloadAnim(g.weaponClass, loaded); // an empty magazine: CS2's reload_empty, where the gun has one
		AnimClip rc = AnimClip.peek(GunServer.clipPath(g.weaponClass, anim));
		int missing = w.clip() - loaded;
		shell = w.q().singleShells()
				? dev.strafemc.weapon.ShellReload.of(rc, infiniteAmmo() == 2 ? missing : Math.min(missing, roundsAvailable(p, g))) : null;
		shellLastT = -1;
		if (rc != null)
			for (AnimClip.Event e : rc.events()) {
				if (e.name().equals("WPN_RELOAD_SILENT")) silentEnd = e.time() + e.duration();
				else if (e.isSound() && !e.name().isEmpty()) reloadSounds.add(new Object[] {e.name(), e.time()});
			}
		// the silent window is gameplay (it decides who hears the reload): the server's timing wins over the local clip
		silentEnd = dev.strafemc.client.ClientGameplay.timing(g.weaponClass, "reloadSilentEnd", silentEnd);
		reload = new dev.strafemc.weapon.ReloadTimeline(silentEnd, byKey);
		reloadLockout = shell != null ? 0 : w.reloadLockout(); // a shot can stop a shotgun's reload any time
		if (ClientPlayNetworking.canSend(StrafePayloads.Reload.TYPE))
			ClientPlayNetworking.send(new StrafePayloads.Reload(byKey ? StrafePayloads.Reload.START_HELD : StrafePayloads.Reload.START));
		sounds.clear();
		CsSounds.stopAnim();
		inspectUntil = 0;
		if (dev.strafemc.client.Placeholders.active()) dev.strafemc.client.Placeholders.reloadSound(p, false);
		dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.play(anim);
	}

	/** A shotgun's shell-by-shell reload (with reload), and the last reload time its foley was played up to. */
	private dev.strafemc.weapon.ShellReload shell;
	private double shellLastT;

	/** Rounds in the inventory for this gun (all its magazines / shells, mag pouches' too). */
	private static int roundsAvailable(LocalPlayer p, GunItem g) {
		int n = 0;
		for (ItemStack s : dev.strafemc.weapon.MagPouchItem.carried(p.getInventory())) {
			if (s.getItem() instanceof dev.strafemc.weapon.MagazineItem m && m.weaponClass.equals(g.weaponClass)) n += m.rounds(s) * s.getCount();
		}
		return n;
	}

	/** A shell-by-shell reload: the clip's loop part again per shell (viewmodel and foley), over after the outro. */
	private void updateShellReload(LocalPlayer p) {
		double t = reload.animTime;
		for (Object[] s : reloadSounds)
			for (double at : shell.times((Double) s[1])) if (at > shellLastT && at <= t) CsSounds.playOn((String) s[0], p, 1.0, reload.stealthy);
		shellLastT = t;
		dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.seek((float) shell.clipTime(t));
		if (t >= shell.end()) endShellReload();
	}

	private void endShellReload() {
		reload = null;
		shell = null;
		reloadSounds.clear();
		dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.setRate(1f);
	}

	public void inspect() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null || !(p.getMainHandItem().getItem() instanceof GunItem g)) return;
		long now = System.nanoTime();
		// CS2 lets an inspect take over the draw (firing still waits out the deploy); not during a reload, shell by shell
		// included
		if (reloadLocked() || shell != null || now < inspectUntil || now < Math.min(knifeNextLight, knifeNextHeavy)) return;
		// which inspect (knives: lookat01 / rare lookat02) comes from the weapon's CS2 inspect graph weights
		if (g instanceof dev.strafemc.weapon.GrenadeItem && grenadeState != GRENADE_IDLE) return;
		var entry = dev.strafemc.weapon.WeaponTable.get(g.weaponClass); // guns and knives: their inspect graph from the weapon table
		String anim = entry != null && entry.inspectGraph() != null ? dev.strafemc.data.InspectGraph.pick(entry.inspectGraph(), rng, "lookat01")
				: g instanceof dev.strafemc.weapon.GrenadeItem gi ? dev.strafemc.data.InspectGraph.pick(gi.type.inspectGraph, rng, "lookat01") : "lookat01";
		AnimClip c = AnimClip.peek(GunServer.clipPath(g.weaponClass, anim));
		if (c == null) return;
		inspectUntil = now + sec(c.duration());
		schedule(g, anim, now);
		dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.play(anim);
		// rare inspects: holding the key repeats the clip's WPN_INSPECT_LOOP part (CS2: the knife keeps being balanced)
		inspectLoop = null;
		if (!anim.equals("lookat01"))
			for (AnimClip.Event e : c.events())
				if (e.name().equals("WPN_INSPECT_LOOP") && e.duration() > 0.05) inspectLoop = new double[] {e.time(), e.time() + e.duration()};
		inspectItem = g;
		inspectAnim = anim;
	}

	private double[] inspectLoop;
	/** How long before an inspect loop a sound may start and still count as the loop's own (seconds). */
	private static final double LOOP_SOUND_LEAD = 0.1;
	private GunItem inspectItem;
	private String inspectAnim;

	/** While the inspect key is held at the end of a rare inspect's loop part, jump back to its start (and replay its sounds). */
	private void inspectHold(long now) {
		if (inspectLoop == null || inspectUntil == 0 || now >= inspectUntil) return;
		if (!INSPECT.isDown()) return;
		var vm = dev.strafemc.client.viewmodel.Viewmodel.INSTANCE;
		if (vm.time() < inspectLoop[1]) return;
		double back = vm.time() - inspectLoop[0];
		vm.seek((float) inspectLoop[0]);
		inspectUntil += sec(back);
		String path = GunServer.clipPath(inspectItem.weaponClass, inspectAnim);
		AnimClip c = path == null ? null : AnimClip.peek(path);
		if (c != null) for (AnimClip.Event e : c.events())
			// the loop's sounds, plus one starting just before it that runs through it (the Deagle's 1 s spin sound starts
			// 0.07 s before its 0.9 s loop): replayed on every loop, at once when it started before the loop
			if (e.isSound() && !e.name().isEmpty() && e.time() >= inspectLoop[0] - LOOP_SOUND_LEAD && e.time() < inspectLoop[1])
				sounds.add(new Scheduled(now + sec(Math.max(0, e.time() - inspectLoop[0])), e.name()));
	}

	/** Schedules the sound events of a weapon animation clip at their real times. */
	private void schedule(GunItem g, String anim, long start) {
		String path = GunServer.clipPath(g.weaponClass, anim);
		if (path == null) return;
		AnimClip c = AnimClip.peek(path);
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
		if (shell != null) {
			updateShellReload(p);
			return;
		}
		for (var it = reloadSounds.iterator(); it.hasNext(); ) {
			Object[] s = it.next();
			if (reload.animTime < (Double) s[1]) continue;
			it.remove();
			CsSounds.playOn((String) s[0], p, 1.0, reload.stealthy);
		}
		if (reload.animTime >= reloadLockout && reloadSounds.isEmpty()) {
			if (dev.strafemc.client.Placeholders.active()) dev.strafemc.client.Placeholders.reloadSound(p, true);
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

	/** How far the reload is (0..1), or -1 when not reloading. */
	public double reloadProgress() {
		var r = reload;
		return r == null || reloadLockout <= 0 ? -1 : Math.min(1, r.animTime / reloadLockout);
	}

	private static boolean hasMagazine(LocalPlayer p, GunItem g) {
		for (ItemStack s : dev.strafemc.weapon.MagPouchItem.carried(p.getInventory())) {
			if (s.getItem() instanceof MagazineItem m && m.weaponClass.equals(g.weaponClass) && m.rounds(s) > 0) return true;
		}
		return false;
	}

	public static int magazineCount(LocalPlayer p, GunItem g) {
		int n = 0;
		for (ItemStack s : dev.strafemc.weapon.MagPouchItem.carried(p.getInventory())) {
			if (s.getItem() instanceof MagazineItem m && m.weaponClass.equals(g.weaponClass) && m.rounds(s) > 0) n += s.getCount();
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
