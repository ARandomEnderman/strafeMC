package dev.strafemc.weapon;

import dev.strafemc.config.StrafeConfig;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A planted C4: it beeps faster as its timer (CS2's mp_c4timer, 40 s) runs out and then explodes: 500 damage with a
 * Gaussian falloff over 3.5 x 500 units (sigma = radius / 3, as the HE), only to whoever the blast can see. Defusing is
 * holding use on it: 10 s, 5 s with a defuse kit; the defuser keeps going while their client keeps telling the server
 * (C4Client), so looking away or letting go stops it. CS2's planted bomb does the rest: a beep and LED flash
 * every max(0.1 + 0.9 x fraction left, 0.15) s while more than 1 s is left, the _10sec beep on top in the last 11 s,
 * the trigger's click and flash under 1 s, the arm_bomb warning once the timer passes 0; the beep is heard further as
 * time runs out (its public.distance_multiplier min(0.2 + 0.8 x fraction left, 1)), and the site's own beep (B for
 * bomb site B). The blast comes a second after the timer (CS2: m_flC4Blow + 1), and a defuse still going when the
 * timer runs out stops. FLAGGED: the damage falloff (CS:GO's).
 */
public class PlantedC4 extends Entity {
	public static EntityType<PlantedC4> TYPE;
	public static final double DAMAGE = 500, RADIUS = DAMAGE * 3.5, DEFUSE = 10, DEFUSE_KIT = 5;
	/** Keypad mode: the fastest a typed defuse can finish (FLAGGED ours: only against instant clients). */
	public static final double KEYPAD_DEFUSE = 0.5;
	/** Keypad mode: how many keys the defuse takes, without and with a defuse kit (FLAGGED ours). */
	public static final int KEYPAD_KEYS = 14, KEYPAD_KEYS_KIT = 7;
	/** Ticks without a defuse message before the defuse stops (the client sends one every 4 ticks while holding). */
	private static final int DEFUSE_GRACE = 10;

	/** Synced: ticks until it blows (counting down), the defuser (entity id, -1: none), the defuse's start and length (ticks), defused. */
	private static final EntityDataAccessor<Integer> LEFT = SynchedEntityData.defineId(PlantedC4.class, EntityDataSerializers.INT),
			TOTAL = SynchedEntityData.defineId(PlantedC4.class, EntityDataSerializers.INT),
			DEFUSER = SynchedEntityData.defineId(PlantedC4.class, EntityDataSerializers.INT),
			DEFUSE_DONE = SynchedEntityData.defineId(PlantedC4.class, EntityDataSerializers.INT),
			DEFUSE_LEN = SynchedEntityData.defineId(PlantedC4.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Boolean> DEFUSED = SynchedEntityData.defineId(PlantedC4.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<ItemStack> ITEM = SynchedEntityData.defineId(PlantedC4.class, EntityDataSerializers.ITEM_STACK);

	/** Client: when it last beeped (System.nanoTime), so its LED blinks. */
	public long lastBeepNs;
	private ServerPlayer planter;
	private double nextBeep;
	private boolean tripped, warned;
	/** Which site's beep it plays: CS2's C4.PlantSound for bomb site A (and anywhere without one), C4.PlantSoundB for B. */
	private boolean siteB;
	private int lastPing, defuseStart, gone = -1;
	/** Ticks since the timer ran out: CS2 blows the bomb a second after (BLOW_DELAY). */
	private int afterTimer;
	private static final int BLOW_DELAY = 20;

	public PlantedC4(EntityType<? extends PlantedC4> type, Level level) {
		super(type, level);
	}

	public PlantedC4(Level level, ServerPlayer planter, ItemStack item) {
		this(TYPE, level);
		this.planter = planter;
		int ticks = (int) Math.round(timer() * 20);
		getEntityData().set(LEFT, ticks);
		getEntityData().set(TOTAL, ticks);
		getEntityData().set(ITEM, item);
	}

	/** CS2's mp_c4timer (from the CS2 server when there is one), else its default 40 s. */
	static double timer() {
		String v = dev.strafemc.bridge.Cs2Bridge.INSTANCE.cvar("mp_c4timer");
		try {
			return v == null ? 40 : Math.max(10, Math.min(90, Double.parseDouble(v)));
		} catch (NumberFormatException e) {
			return 40;
		}
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder b) {
		b.define(LEFT, 800).define(TOTAL, 800).define(DEFUSER, -1).define(DEFUSE_DONE, 0).define(DEFUSE_LEN, 0).define(DEFUSED, false).define(ITEM, ItemStack.EMPTY);
	}

	/** The C4 item it was planted from (its stickers and charm), for drawing it. */
	public ItemStack item() {
		return getEntityData().get(ITEM);
	}

	/** Seconds until it blows. */
	public double secondsLeft() {
		return getEntityData().get(LEFT) / 20.0;
	}

	/** The bomb site it was planted at ("A", "B" or "" for none: A's beep). */
	public void setSite(String letter) {
		siteB = "B".equals(letter);
	}

	public double fractionLeft() {
		return getEntityData().get(LEFT) / (double) Math.max(1, getEntityData().get(TOTAL));
	}

	/** The entity id of whoever is defusing it, or -1. */
	public int defuser() {
		return getEntityData().get(DEFUSER);
	}

	/** How far the defuse is (0-1), counting the ticks since this side last heard of it. */
	public double defuseProgress() {
		int len = getEntityData().get(DEFUSE_LEN);
		return len <= 0 ? 0 : Math.min(1, getEntityData().get(DEFUSE_DONE) / (double) len);
	}

	/** Seconds the current defuse takes. */
	public double defuseSeconds() {
		return getEntityData().get(DEFUSE_LEN) / 20.0;
	}

	public boolean defused() {
		return getEntityData().get(DEFUSED);
	}

	@Override
	public boolean isPickable() {
		return !defused();
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	public void tick() {
		super.tick();
		if (!onGround()) { // it settles on what's below
			setDeltaMovement(getDeltaMovement().add(0, -0.04, 0));
			move(net.minecraft.world.entity.MoverType.SELF, getDeltaMovement());
			setDeltaMovement(getDeltaMovement().scale(0.9));
		}
		if (!(level() instanceof ServerLevel sl)) return;
		if (gone >= 0) { // defused: lies there a while, then goes
			if (--gone <= 0) discard();
			return;
		}
		int left = getEntityData().get(LEFT) - 1;
		getEntityData().set(LEFT, Math.max(0, left));
		double t = tickCount / 20.0, fraction = fractionLeft();
		if (t >= nextBeep && left > 20) { // beeps (and the LED) only while more than a second is left
			boolean last11 = left <= 220; // CS2 adds the _10sec beep with 11 s to go
			GrenadeEffects.event(sl, siteB ? (last11 ? GrenadeEffects.EV_C4_BEEP_B_10 : GrenadeEffects.EV_C4_BEEP_B)
					: (last11 ? GrenadeEffects.EV_C4_BEEP_10 : GrenadeEffects.EV_C4_BEEP), position());
			nextBeep = t + Math.max(0.1 + 0.9 * fraction, 0.15);
		}
		if (!tripped && left <= 20) { // under a second: the trigger trips (C4.ExplodeTriggerTrip and its flash on the LED)
			tripped = true;
			GrenadeEffects.event(sl, GrenadeEffects.EV_C4_TRIP, position());
		}
		if (!warned && left <= 0) { // the timer has run out: C4.ExplodeWarning (arm_bomb)
			warned = true;
			GunServer.soundAt(sl, "C4.ExplodeWarning", position(), this);
		}
		if (left <= 0) { // the timer has run out: no more defusing, and the blast a second later
			if (afterTimer++ == 0 && defuser() >= 0) stopDefuse();
			if (afterTimer >= BLOW_DELAY && gone < 0) explode(sl);
			return;
		}
		tickDefuse(sl);
	}

	private void tickDefuse(ServerLevel sl) {
		int id = defuser();
		if (id < 0) return;
		if (tickCount - lastPing > DEFUSE_GRACE || !(sl.getEntity(id) instanceof LivingEntity p) || p.distanceTo(this) > 3) {
			stopDefuse();
			return;
		}
		getEntityData().set(DEFUSE_DONE, tickCount - defuseStart);
		// keypad mode: only typing the keys defuses it
		if ((!dev.strafemc.server.StrafeSettings.c4Keypad() || !(p instanceof ServerPlayer)) && tickCount - defuseStart >= getEntityData().get(DEFUSE_LEN))
			defuse(sl, p);
	}

	private void stopDefuse() {
		getEntityData().set(DEFUSER, -1);
		getEntityData().set(DEFUSE_DONE, 0);
		getEntityData().set(DEFUSE_LEN, 0);
	}

	/** A defuse message from p (holding use on it): starts their defuse, or keeps it going. */
	public void defusePing(LivingEntity p) {
		if (getEntityData().get(LEFT) <= 0) return; // out of time: CS2 stops defusing when the timer runs out
		if (defused() || gone >= 0 || p.distanceTo(this) > 3 || !p.isAlive()) return;
		int id = defuser();
		if (id >= 0 && id != p.getId()) return; // someone else is on it
		lastPing = tickCount;
		if (id < 0) {
			defuseStart = tickCount;
			getEntityData().set(DEFUSER, p.getId());
			getEntityData().set(DEFUSE_LEN, (int) Math.round((p instanceof ServerPlayer sp && hasKit(sp) ? DEFUSE_KIT : DEFUSE) * 20));
			getEntityData().set(DEFUSE_DONE, 0);
			GunServer.soundAt((ServerLevel) level(), "c4.disarmstart", position(), this);
		}
	}

	/** Keypad mode: p typed the defuse code; it's defused if they've been at it long enough. */
	public void defuseTyped(ServerPlayer p) {
		if (getEntityData().get(LEFT) <= 0) return; // out of time
		if (!dev.strafemc.server.StrafeSettings.c4Keypad() || defuser() != p.getId() || !(level() instanceof ServerLevel sl)) return;
		if ((tickCount - defuseStart) / 20.0 >= KEYPAD_DEFUSE) defuse(sl, p);
	}

	public static boolean hasKit(net.minecraft.world.entity.player.Player p) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(WeaponItems.DEFUSE_KIT)) return true;
		return false;
	}

	private void defuse(ServerLevel sl, LivingEntity by) {
		getEntityData().set(DEFUSED, true);
		getEntityData().set(DEFUSE_DONE, getEntityData().get(DEFUSE_LEN));
		gone = 60;
		GunServer.soundAt(sl, "c4.disarmfinish", position(), this);
		dev.strafemc.mode.GameModes.defused(by, this);
		if (by instanceof ServerPlayer p) dev.strafemc.server.Toasts.send(p, "The bomb has been defused.", dev.strafemc.server.Toasts.GOOD);
	}

	private void explode(ServerLevel level) {
		double upb = StrafeConfig.unitsPerBlock, sigma = RADIUS / 3;
		Vec3 at = position().add(0, 0.2, 0);
		ServerPlayer owner = planter;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(RADIUS / upb), LivingEntity::isAlive)) {
			Vec3 spot = e.getBoundingBox().getCenter();
			double dist = spot.distanceTo(at) * upb;
			if (dist > RADIUS) continue;
			if (level.clip(new ClipContext(at, spot, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() != HitResult.Type.MISS) continue;
			double dmg = DAMAGE * Math.exp(-(dist * dist) / (2 * sigma * sigma));
			if (dmg < 1) continue;
			Vec3 motion = e.getDeltaMovement();
			boolean marked = e.hurtMarked;
			e.invulnerableTime = 0;
			dmg = CsArmor.apply(e, dmg, 1.0, "chest");
			boolean wasAlive = e.isAlive();
			if (owner != null) KillFeed.damaged(owner, e, Math.min(dmg, e.getHealth() / StrafeConfig.hpScale));
			e.hurtServer(level, level.damageSources().explosion(this, owner), (float) (dmg * StrafeConfig.hpScale));
			if (wasAlive && e.isDeadOrDying()) KillFeed.notice(owner, e, "planted_c4", owner != null ? KillFeed.attackerFlags(owner) : 0);
			if (!dev.strafemc.server.StrafeSettings.mcKnockback()) {
				e.setDeltaMovement(motion);
				e.hurtMarked = marked;
			}
		}
		GrenadeEffects.event(level, GrenadeEffects.EV_C4, at);
		dev.strafemc.mode.GameModes.exploded(this);
		discard();
	}

	@Override
	protected void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput in) {
	}

	@Override
	protected void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput out) {
	}
}
