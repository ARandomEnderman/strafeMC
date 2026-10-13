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
	/** Seconds from the throw until an HE grenade or flashbang goes off (CS2: 1.6 s, measured by the user). */
	static final double FUSE = 1.6;

	/** Velocity in blocks per second (Minecraft's delta movement is per tick and only mirrors it for interpolation). */
	private Vec3 vel = Vec3.ZERO;
	private double stepTime;
	private boolean resting;
	private int age;
	/** Client: spin (degrees, end over end) while flying and the flight heading (degrees) for drawing the model. */
	public float spin, prevSpin, heading;

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
		double g = gravity() * GRAVITY_SCALE / upb;
		if (resting && level().noCollision(this, getBoundingBox().move(0, -0.05, 0))) resting = false; // the floor went away
		if (!resting) {
			stepTime += 0.05;
			while (stepTime >= STEP && !resting) {
				step(g, upb);
				stepTime -= STEP;
			}
		}
		setDeltaMovement(resting ? Vec3.ZERO : vel.scale(0.05));
		prevSpin = spin;
		if (!resting) {
			double horizontal = Math.hypot(vel.x, vel.z);
			if (horizontal > 0.05) heading = (float) Math.toDegrees(Math.atan2(-vel.x, vel.z));
			spin += (float) Math.min(36, vel.length() * 3); // degrees per tick, faster the faster it flies
		}
		age++;
		if (level() instanceof ServerLevel sl) serverTick(sl);
	}

	private double gravity() {
		String v = dev.strafemc.bridge.Cs2Bridge.INSTANCE.cvar("sv_gravity");
		try {
			return v == null ? 800 : Double.parseDouble(v);
		} catch (NumberFormatException e) {
			return 800;
		}
	}

	/** One 1/64 s step: half the gravity before the move, half after (as Source), bounce on blocks. */
	private void step(double g, double upb) {
		vel = vel.add(0, -g * STEP * 0.5, 0);
		Vec3 from = position(), to = from.add(vel.scale(STEP));
		BlockHitResult hit = level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
		if (hit.getType() == HitResult.Type.BLOCK) {
			Vec3 n = Vec3.atLowerCornerOf(hit.getDirection().getUnitVec3i());
			double into = vel.dot(n);
			double speed = vel.length() * upb;
			vel = vel.subtract(n.scale(into * 2)).scale(ELASTICITY); // reflect, keep 45%
			setPos(hit.getLocation().add(n.scale(0.02)));
			if (level() instanceof ServerLevel sl && speed > 80) GunServer.soundAt(sl, grenade().sounds + ".Bounce", position(), this);
			if (n.y > 0.7 && vel.length() * upb < STOP_SPEED) {
				vel = Vec3.ZERO;
				resting = true;
				return;
			}
		} else setPos(to);
		vel = vel.add(0, -g * STEP * 0.5, 0);
	}

	private void serverTick(ServerLevel level) {
		switch (grenade()) {
			case HE -> {
				if (age >= FUSE * 20) {
					GrenadeEffects.explodeHE(level, this, getOwner() instanceof ServerPlayer sp ? sp : null);
					discard();
				}
			}
			default -> {
				if (age >= 30 * 20) discard(); // other types: their effect comes in later steps
			}
		}
	}
}
