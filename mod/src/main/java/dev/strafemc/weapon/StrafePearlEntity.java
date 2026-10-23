package dev.strafemc.weapon;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A thrown Strafe Pearl (ours): where it lands it doesn't teleport its thrower like an ender pearl, it pushes them toward
 * where it landed (from where they are by then, so a pearl stasis launches you at it) with a little lift, so the Source
 * movement carries it like a rocket jump. FLAGGED ours: the push's strength.
 */
public class StrafePearlEntity extends ThrowableItemProjectile {
	public static EntityType<StrafePearlEntity> TYPE;
	/** The push toward the pearl at REACH blocks (blocks per tick: about 650 u/s) and the lift on top (about 280 u/s). */
	static final double PUSH = 0.8, LIFT = 0.35;
	/**
	 * The push grows in proportion to how far the thrower is from where it landed (PUSH at REACH blocks, never under half
	 * of PUSH). The lift stays whole: half of it would be 140 u/s, not over Source's NON_JUMP_VELOCITY, and ground
	 * friction would eat the push.
	 */
	static final double REACH = 16, MIN_SCALE = 0.5;
	/** Where it was thrown from (saved with the pearl): the push's direction when the thrower stands on the landing spot. */
	private Vec3 from;

	public StrafePearlEntity(EntityType<? extends StrafePearlEntity> type, Level level) {
		super(type, level);
	}

	public StrafePearlEntity(Level level, LivingEntity owner, ItemStack item) {
		super(TYPE, owner, level, item);
		from = owner.position();
	}

	/** Ticks until the chunk ticket around the pearl is renewed (as vanilla's ThrownEnderpearl). */
	private long ticketTimer;

	/**
	 * Keeps the chunks around the pearl loaded while it flies, as an ender pearl does (ENDER_PEARL ticket, radius 2,
	 * renewed when it runs out or the pearl enters a new chunk): without it a long throw stopped where the server no
	 * longer ran entities (past the simulation distance) and never landed, so no push.
	 */
	@Override
	public void tick() {
		int cx = net.minecraft.core.SectionPos.blockToSectionCoord(getX()), cz = net.minecraft.core.SectionPos.blockToSectionCoord(getZ());
		super.tick();
		if (!(level() instanceof ServerLevel level) || !isAlive() || !(getOwner() instanceof ServerPlayer)) return;
		var pos = net.minecraft.core.BlockPos.containing(position());
		if (--ticketTimer <= 0 || cx != net.minecraft.core.SectionPos.blockToSectionCoord(pos.getX())
				|| cz != net.minecraft.core.SectionPos.blockToSectionCoord(pos.getZ())) {
			level.resetEmptyTime();
			level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.ENDER_PEARL, chunkPosition(), 2);
			ticketTimer = net.minecraft.server.level.TicketType.ENDER_PEARL.timeout();
		}
	}

	@Override
	protected void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput out) {
		super.addAdditionalSaveData(out);
		out.storeNullable("strafemc_from", Vec3.CODEC, from);
	}

	@Override
	protected void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput in) {
		super.readAdditionalSaveData(in);
		from = in.read("strafemc_from", Vec3.CODEC).orElse(null);
	}

	@Override
	protected Item getDefaultItem() {
		return WeaponItems.STRAFE_PEARL;
	}

	@Override
	protected void onHit(HitResult hit) {
		super.onHit(hit);
		if (!(level() instanceof ServerLevel level) || isRemoved()) return;
		level.sendParticles(net.minecraft.core.particles.ParticleTypes.GUST_EMITTER_SMALL, getX(), getY(), getZ(), 1, 0, 0, 0, 0); // a gust where it lands
		level.playSound(null, getX(), getY(), getZ(), SoundEvents.WIND_CHARGE_BURST, SoundSource.PLAYERS, 1f, 1.2f);
		Entity owner = getOwner();
		if (owner instanceof LivingEntity who && who.isAlive() && who.level() == level) {
			Vec3 dir = hit.getLocation().subtract(who.position());
			double push = PUSH * Math.max(MIN_SCALE, dir.length() / REACH);
			if (dir.lengthSqr() < 1e-4 && from != null) dir = hit.getLocation().subtract(from); // on the spot: along its flight
			if (dir.lengthSqr() < 1e-4) dir = getDeltaMovement();
			if (dir.lengthSqr() > 1e-6) {
				dir = dir.normalize();
				who.push(dir.x * push, dir.y * push + LIFT, dir.z * push);
				who.hurtMarked = true; // sends the push to the player's client (their movement adds it to their speed)
				who.resetFallDistance();
				level.playSound(null, who.getX(), who.getY(), who.getZ(), SoundEvents.BREEZE_JUMP, SoundSource.PLAYERS, 0.8f, 1.1f);
				if (who instanceof ServerPlayer sp) Advancements.award(sp, "strafe_pearl");
			}
		}
		discard();
	}
}
