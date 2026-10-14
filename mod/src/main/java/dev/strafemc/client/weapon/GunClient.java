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
		BUY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.strafemc.buymenu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, ConsoleKeys.CATEGORY));
		// CS: F inspects. While a CS gun is held, Minecraft's swap-hands key (F by default) inspects instead.
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			if (mc.player == null || !(mc.player.getMainHandItem().getItem() instanceof GunItem)) return;
			while (mc.options.keySwapOffhand.consumeClick()) INSTANCE.inspect();
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
		releaseGrenade(p, now);
		ItemStack held = p.getMainHandItem();
		int slot = p.getInventory().getSelectedSlot();
		if (slot != lastSlot || !ItemStack.isSameItem(held, lastHeld)) {
			lastSlot = slot;
			lastHeld = held;
			grenadeState = GRENADE_IDLE;
			GrenadeClient.preview(null);
			dev.strafemc.client.particle.Effects.stopHeld("molotov");
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
				} else if (w.fullAuto() && reload == null) reload(false); // and when a full-auto spray empties it, trigger still held
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
					var l = dev.strafemc.weapon.GrenadeFlight.launch(p.level(), p, p.getEyePosition(), p.getYRot(), p.getXRot(), throwStrength,
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
			dev.strafemc.client.impact.BulletHoles.onImpact(GunServer.impactMessage(p.level(), hit.block(), p.getEyePosition(), IMPACT_RNG.nextInt(), true));
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

	private void shoot(LocalPlayer p, WeaponStats w, int roundsLeft) {
		// predicted exactly like the server will decide it: same formula, the server's seed, the next shot number
		double t = System.nanoTime() / 1e9;
		aimPunch.advance(t);
		int shot = shotNumber++;
		Vec3 d = dev.strafemc.weapon.ShotMath.direction(p.getXRot(), p.getYRot(), aimPunch.angle[0], aimPunch.angle[1], penalty, w.spread(), shotSeed, shot);
		if (ClientPlayNetworking.canSend(StrafePayloads.Fire.TYPE))
			ClientPlayNetworking.send(new StrafePayloads.Fire(p.getYRot(), p.getXRot(), shot, (float) penalty, (float) aimPunch.angle[0], (float) aimPunch.angle[1],
					dev.strafemc.client.LagClock.viewTick(), claim(p, w, d)));
		if (inspectUntil != 0) { // a shot cancels the inspect: its remaining foley too, and F works again right away
			inspectUntil = 0;
			sounds.clear();
			CsSounds.stopAnim();
		}
		dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.play("shoot1");
		// the shoot clip's own foley: pump-action and bolt-action guns work the action after the shot (Nova, MAG-7,
		// Sawed-Off, AWP, SSG 08); the shot itself is the weapon's shoot sound below
		if (p.getMainHandItem().getItem() instanceof GunItem held) schedule(held, "shoot1", System.nanoTime());
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
		AnimClip c = AnimClip.peek(GunServer.clipPath(w.cls(), "shoot1"));
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

	/** The entity this shot hits on this screen (what the player saw), or -1: the server checks it against its history. */
	private static int claim(LocalPlayer p, WeaponStats w, Vec3 dir) {
		Vec3 eye = p.getEyePosition(), end = eye.add(dir.scale(w.range() / StrafeConfig.unitsPerBlock));
		var block = p.level().clip(new net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.NONE, p));
		if (block.getType() != net.minecraft.world.phys.HitResult.Type.MISS) end = block.getLocation();
		var ent = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(p, eye, end, new net.minecraft.world.phys.AABB(eye, end).inflate(1),
				e -> e != p && e.isPickable() && !e.isSpectator(), eye.distanceToSqr(end));
		return ent == null ? -1 : ent.getEntity().getId();
	}

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
		if (reload != null || now < deployUntil || g.clip(p.getMainHandItem()) >= w.clip()) return;
		if (infiniteAmmo() != 2 && !hasMagazine(p, g)) return;
		reloadKeyHeld = byKey;
		double silentEnd = 0;
		reloadSounds.clear();
		AnimClip rc = AnimClip.peek(GunServer.clipPath(g.weaponClass, "reload"));
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
		if (dev.strafemc.client.Placeholders.active()) dev.strafemc.client.Placeholders.reloadSound(p, false);
		dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.play("reload");
	}

	public void inspect() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null || !(p.getMainHandItem().getItem() instanceof GunItem g)) return;
		long now = System.nanoTime();
		if (reloadLocked() || now < deployUntil || now < inspectUntil || now < Math.min(knifeNextLight, knifeNextHeavy)) return;
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
		if (!Minecraft.getInstance().options.keySwapOffhand.isDown()) return;
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
