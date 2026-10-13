package dev.strafemc.weapon;

import dev.strafemc.config.StrafeConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A thrown CS2 grenade. Minecraft's thrown-item entity is only used for syncing and drawing (the item icon for now);
 * the flight is CS-style, simulated in 1/64 s steps on both sides (the server's position wins): grenade gravity,
 * bounces that keep 45% of the speed, stopping on the floor below 20 units/s. How it goes off depends on the type.
 * FLAGGED (not in CS2's data files; CS:GO's grenade code as we know it): gravity scale 0.4, elasticity 0.45, stop speed.
 */
public class GrenadeEntity extends ThrowableItemProjectile {
	public static EntityType<GrenadeEntity> TYPE;

	static final double GRAVITY_SCALE = 0.4, ELASTICITY = 0.45, STOP_SPEED = 20, STEP = 1 / 64.0;
	/** The grenade's collision radius in units: its centre stays this far from surfaces (matches the drawn model). */
	static final double RADIUS = 3.6;
	/** Seconds from the throw until an HE grenade or flashbang goes off (CS2: 1.6 s, measured by the user). */
	static final double FUSE = 1.6;

	/** Velocity in blocks per second (Minecraft's delta movement is per tick and only mirrors it for interpolation). */
	private Vec3 vel = Vec3.ZERO;
	private double stepTime;
	private boolean resting;
	private int age, restTicks, decoyNext, decoyShots;
	private boolean hitFloor;
	/** Decoy: the thrower's last held gun, whose shots it fakes. */
	public String decoyGun = "weapon_ak47";
	/**
	 * Tumble, as CS2 sets it at the throw: 600 deg/s pitch (end over end) and a random whole -1200..1200 deg/s yaw
	 * (server-chosen, synced), no roll. Each bounce slows it by the bounce factor (FLAGGED: CS:GO's behaviour); it stops at rest.
	 */
	private static final net.minecraft.network.syncher.EntityDataAccessor<Float> YAW_RATE =
			net.minecraft.network.syncher.SynchedEntityData.defineId(GrenadeEntity.class, net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
	private double spinScale = 1;
	/** Client: tumble angles (degrees) and the flight heading for drawing the model. */
	public float spin, prevSpin, spinYaw, prevSpinYaw, heading;

	public boolean resting() {
		return resting;
	}

	public GrenadeEntity(EntityType<? extends GrenadeEntity> type, Level level) {
		super(type, level);
	}

	public GrenadeEntity(Level level, LivingEntity owner, ItemStack item, Vec3 pos, Vec3 velocity) {
		super(TYPE, owner, level, item);
		setPos(pos);
		vel = velocity;
		setDeltaMovement(velocity.scale(0.05));
		getEntityData().set(YAW_RATE, (float) (level.getRandom().nextInt(2401) - 1200));
	}

	@Override
	protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder b) {
		super.defineSynchedData(b);
		b.define(YAW_RATE, 0f);
	}

	public Grenade grenade() {
		return getItem().getItem() instanceof GrenadeItem g ? g.type : Grenade.HE;
	}

	@Override
	protected Item getDefaultItem() {
		return WeaponItems.HEGRENADE;
	}

	@Override
	public void tick() {
		baseTick(); // not Projectile's tick: it would hit, stick or discard; the flight is ours
		if (vel == Vec3.ZERO && !resting && age == 0 && level().isClientSide()) vel = getDeltaMovement().scale(20); // client copy from the spawn packet
		double upb = StrafeConfig.unitsPerBlock;
		double g = GrenadeFlight.gravity();
		if (resting && level().clip(new ClipContext(position(), position().add(0, -(RADIUS / upb + 0.05), 0), ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, this)).getType() == HitResult.Type.MISS) resting = false; // the floor went away
		if (!resting) {
			stepTime += 0.05;
			while (stepTime >= STEP && !resting) {
				step(g);
				stepTime -= STEP;
			}
		}
		setDeltaMovement(resting ? Vec3.ZERO : vel.scale(0.05));
		prevSpin = spin;
		prevSpinYaw = spinYaw;
		if (!resting) {
			if (age == 0 && Math.hypot(vel.x, vel.z) > 0.05) heading = (float) Math.toDegrees(Math.atan2(-vel.x, vel.z));
			spin += (float) (600 * spinScale * 0.05);
			spinYaw += (float) (getEntityData().get(YAW_RATE) * spinScale * 0.05);
		}
		age++;
		if (level() instanceof ServerLevel sl) serverTick(sl);
	}

	/** Horizontal flight direction (radians, atan2(z, x)), NaN when (almost) not moving sideways. */
	public float flightHeading() {
		return Math.hypot(vel.x, vel.z) < 0.05 ? Float.NaN : (float) Math.atan2(vel.z, vel.x);
	}

	/** One 1/64 s step (GrenadeFlight, shared with the client's trajectory preview); bounce sounds on the server. */
	private void step(double gravity) {
		GrenadeFlight.Step st = GrenadeFlight.step(level(), this, age < 5 ? getOwner() : null, position(), vel, gravity);
		setPos(st.pos());
		vel = st.vel();
		if (st.normal() != null) {
			spinScale *= ELASTICITY;
			if (st.normal().y > 0.7) hitFloor = true;
			// CS2 has no Decoy.Bounce: the decoy uses the HE's bounce (FLAGGED)
			String bounce = grenade() == Grenade.DECOY ? "HEGrenade.Bounce" : grenade().sounds + ".Bounce";
			if (level() instanceof ServerLevel sl && st.impactSpeed() > 80) GunServer.soundAt(sl, bounce, position(), this);
		}
		if (st.resting()) resting = true;
	}

	/** Molotov / incendiary: CS's molotov_throw_detonate_time, after which one still in the air fizzles (FLAGGED: 2 s). */
	static final double MOLOTOV_AIR_TIME = 2.0;
	/** Decoy: fake gunfire for this long once it lies still, then it pops (FLAGGED: CS's 15 s). */
	static final double DECOY_TIME = 15;

	private void serverTick(ServerLevel level) {
		ServerPlayer owner = getOwner() instanceof ServerPlayer sp ? sp : null;
		restTicks = resting ? restTicks + 1 : 0;
		switch (grenade()) {
			case HE -> {
				if (age >= FUSE * 20) {
					GrenadeEffects.explodeHE(level, this, owner);
					discard();
				}
			}
			case FLASHBANG -> {
				if (age >= FUSE * 20) {
					GrenadeEffects.flash(level, this);
					discard();
				}
			}
			case SMOKE -> { // goes off once it has settled
				if (restTicks == 10 || age >= 60 * 20) GrenadeZones.smoke(level, position(), getOwner());
				if (restTicks >= 10 + GrenadeZones.SMOKE_TICKS || age >= 60 * 20) discard();
			}
			case MOLOTOV, INCENDIARY -> { // bursts into fire on the ground, or fizzles if still flying
				boolean inc = grenade() == Grenade.INCENDIARY;
				if (hitFloor) {
					Vec3 ground = position().add(0, -RADIUS / StrafeConfig.unitsPerBlock, 0);
					if (!GrenadeZones.fire(level, ground, owner, this, inc))
						GrenadeEffects.event(level, inc ? GrenadeEffects.EV_FIZZLE_INC : GrenadeEffects.EV_FIZZLE, position(), flightHeading());
					discard();
				} else if (age >= MOLOTOV_AIR_TIME * 20) {
					GrenadeEffects.event(level, inc ? GrenadeEffects.EV_FIZZLE_INC : GrenadeEffects.EV_FIZZLE, position(), flightHeading());
					discard();
				}
			}
			case DECOY -> { // once still: bursts of fake gunfire, then a pop
				if (restTicks == 0) return;
				if (restTicks >= DECOY_TIME * 20) {
					GrenadeEffects.event(level, GrenadeEffects.EV_DECOY_POP, position());
					discard();
					return;
				}
				if (restTicks >= decoyNext) {
					GrenadeEffects.decoyShot(level, this);
					var rnd = level.getRandom();
					if (decoyShots-- > 0) decoyNext = restTicks + 2 + rnd.nextInt(2); // a burst: shots ~0.1 s apart
					else {
						decoyShots = rnd.nextInt(4);
						decoyNext = restTicks + 10 + rnd.nextInt(30); // pause 0.5..2 s
					}
				}
			}
		}
	}
}
