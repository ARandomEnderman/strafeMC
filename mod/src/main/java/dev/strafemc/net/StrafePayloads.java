package dev.strafemc.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client <-> Minecraft-server messages. Anything that changes server-side rules goes through here so it can be op-gated. */
public final class StrafePayloads {
	private StrafePayloads() {}

	/** Client asks the MC server to enable/disable noclip for itself. */
	public record NoclipRequest(boolean on) implements CustomPacketPayload {
		public static final Type<NoclipRequest> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "noclip_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, NoclipRequest> CODEC =
				StreamCodec.composite(ByteBufCodecs.BOOL, NoclipRequest::on, NoclipRequest::new);

		@Override
		public Type<NoclipRequest> type() {
			return TYPE;
		}
	}

	/** Client tells the MC server whether Source movement is driving it (stairs are ramps for these players). */
	public record SourceMovement(boolean on) implements CustomPacketPayload {
		public static final Type<SourceMovement> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "source_movement"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SourceMovement> CODEC =
				StreamCodec.composite(ByteBufCodecs.BOOL, SourceMovement::on, SourceMovement::new);

		@Override
		public Type<SourceMovement> type() {
			return TYPE;
		}
	}

	/** Client landed at this downward speed (u/s); the server applies CS2 fall damage (scale = sv_falldamage_scale). */
	public record FallDamage(float impactSpeed, float scale) implements CustomPacketPayload {
		public static final Type<FallDamage> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "fall_damage"));
		public static final StreamCodec<RegistryFriendlyByteBuf, FallDamage> CODEC =
				StreamCodec.composite(ByteBufCodecs.FLOAT, FallDamage::impactSpeed, ByteBufCodecs.FLOAT, FallDamage::scale, FallDamage::new);

		@Override
		public Type<FallDamage> type() {
			return TYPE;
		}
	}

	/** Client -> server: one CS2 console command for the server's own CS2 (server.ConsoleRelay checks permission). */
	public record ConsoleCommand(String line) implements CustomPacketPayload {
		public static final Type<ConsoleCommand> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "console_command"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ConsoleCommand> CODEC =
				StreamCodec.composite(ByteBufCodecs.stringUtf8(512), ConsoleCommand::line, ConsoleCommand::new);

		@Override
		public Type<ConsoleCommand> type() {
			return TYPE;
		}
	}

	/** Server -> client: a console line for the player who ran a relayed command (kind 0 output, 1 ok, 2 error). */
	public record ConsoleReply(String text, int kind) implements CustomPacketPayload {
		public static final Type<ConsoleReply> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "console_reply"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ConsoleReply> CODEC =
				StreamCodec.composite(ByteBufCodecs.stringUtf8(4096), ConsoleReply::text, ByteBufCodecs.VAR_INT, ConsoleReply::kind, ConsoleReply::new);

		@Override
		public Type<ConsoleReply> type() {
			return TYPE;
		}
	}

	/** Server -> client: the seed for this player's shot spread and the next shot number (for exact prediction). */
	public record ShotSeed(long seed, int next) implements CustomPacketPayload {
		public static final Type<ShotSeed> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "shot_seed"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ShotSeed> CODEC =
				StreamCodec.composite(ByteBufCodecs.VAR_LONG, ShotSeed::seed, ByteBufCodecs.VAR_INT, ShotSeed::next, ShotSeed::new);

		@Override
		public Type<ShotSeed> type() {
			return TYPE;
		}
	}

	/** Server -> shooter: shot n didn't go where the client predicted; drop its predicted impact (the real one follows). */
	public record ShotCorrection(int shot) implements CustomPacketPayload {
		public static final Type<ShotCorrection> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "shot_correction"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ShotCorrection> CODEC =
				StreamCodec.composite(ByteBufCodecs.VAR_INT, ShotCorrection::shot, ShotCorrection::new);

		@Override
		public Type<ShotCorrection> type() {
			return TYPE;
		}
	}

	/** Client -> server: throw the held grenade (strength 1 overhand, 0 underhand, 0.5 both buttons; velocity in units/s). */
	/**
	 * A movement sound (CS2 footstep, jump, landing) at a spot (blocks): client -> server from the player making it, then
	 * server -> nearby clients with that player's entity id. gain: the sound's own extra scale (landings).
	 */
	public record MoveSound(String event, double x, double y, double z, float gain, int entity) implements CustomPacketPayload {
		public static final Type<MoveSound> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "move_sound"));
		public static final StreamCodec<RegistryFriendlyByteBuf, MoveSound> CODEC = StreamCodec.of((b, v) -> {
			b.writeUtf(v.event, 96);
			b.writeDouble(v.x);
			b.writeDouble(v.y);
			b.writeDouble(v.z);
			b.writeFloat(v.gain);
			b.writeVarInt(v.entity);
		}, b -> new MoveSound(b.readUtf(96), b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readVarInt()));

		@Override
		public Type<MoveSound> type() {
			return TYPE;
		}
	}

	/** Server -> client: a death notice (killfeed): who killed whom with what, sides, and KillFeed's modifier flags. */
	public record KillNotice(String attacker, boolean attackerT, int attackerId, String victim, boolean victimT, int victimId, boolean victimPlayer,
							 String icon, int flags) implements CustomPacketPayload {
		public static final Type<KillNotice> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "kill_notice"));
		public static final StreamCodec<RegistryFriendlyByteBuf, KillNotice> CODEC = StreamCodec.of((b, v) -> {
			b.writeUtf(v.attacker, 64);
			b.writeBoolean(v.attackerT);
			b.writeVarInt(v.attackerId);
			b.writeUtf(v.victim, 64);
			b.writeBoolean(v.victimT);
			b.writeVarInt(v.victimId);
			b.writeBoolean(v.victimPlayer);
			b.writeUtf(v.icon, 48);
			b.writeVarInt(v.flags);
		}, b -> new KillNotice(b.readUtf(64), b.readBoolean(), b.readVarInt(), b.readUtf(64), b.readBoolean(), b.readVarInt(), b.readBoolean(),
				b.readUtf(48), b.readVarInt()));

		@Override
		public Type<KillNotice> type() {
			return TYPE;
		}
	}

	/** Client -> server: this player's CS side (true = T), from the console quick menu. */
	public record TeamChoice(boolean t) implements CustomPacketPayload {
		public static final Type<TeamChoice> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "team"));
		public static final StreamCodec<RegistryFriendlyByteBuf, TeamChoice> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, TeamChoice::t, TeamChoice::new);

		@Override
		public Type<TeamChoice> type() {
			return TYPE;
		}
	}

	public record Throw(float strength, float yaw, float pitch, float vx, float vy, float vz) implements CustomPacketPayload {
		public static final Type<Throw> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "throw"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Throw> CODEC = StreamCodec.of((b, v) -> {
			b.writeFloat(v.strength);
			b.writeFloat(v.yaw);
			b.writeFloat(v.pitch);
			b.writeFloat(v.vx);
			b.writeFloat(v.vy);
			b.writeFloat(v.vz);
		}, b -> new Throw(b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat()));

		@Override
		public Type<Throw> type() {
			return TYPE;
		}
	}

	/** Server -> client: your CS armor (0..100) and whether you have a helmet. */
	public record ArmorSync(int value, boolean helmet) implements CustomPacketPayload {
		public static final Type<ArmorSync> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "armor"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ArmorSync> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, ArmorSync::value,
				ByteBufCodecs.BOOL, ArmorSync::helmet, ArmorSync::new);

		@Override
		public Type<ArmorSync> type() {
			return TYPE;
		}
	}

	/** Server -> client: you were flashed: full white for `hold` seconds, gone after `fade` (CS2's Blind hold / fade times). */
	public record Flashed(float hold, float fade) implements CustomPacketPayload {
		public static final Type<Flashed> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "flashed"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Flashed> CODEC = StreamCodec.composite(ByteBufCodecs.FLOAT, Flashed::hold,
				ByteBufCodecs.FLOAT, Flashed::fade, Flashed::new);

		@Override
		public Type<Flashed> type() {
			return TYPE;
		}
	}

	/** Server -> client: a grenade went off here (kind = Grenade ordinal): show its effect, play its sound. */
	/** heading: the grenade's horizontal flight direction (radians, atan2(z, x) in Minecraft axes), NaN if none. */
	public record GrenadeFx(int kind, double x, double y, double z, float heading) implements CustomPacketPayload {
		public static final Type<GrenadeFx> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "grenade_fx"));
		public static final StreamCodec<RegistryFriendlyByteBuf, GrenadeFx> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, GrenadeFx::kind,
				ByteBufCodecs.DOUBLE, GrenadeFx::x, ByteBufCodecs.DOUBLE, GrenadeFx::y, ByteBufCodecs.DOUBLE, GrenadeFx::z, ByteBufCodecs.FLOAT,
				GrenadeFx::heading, GrenadeFx::new);

		@Override
		public Type<GrenadeFx> type() {
			return TYPE;
		}
	}

	/** Server -> client, every tick: the server's tick number (lag compensation: client.LagClock). */
	public record ServerTick(int tick) implements CustomPacketPayload {
		public static final Type<ServerTick> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "server_tick"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ServerTick> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, ServerTick::tick, ServerTick::new);

		@Override
		public Type<ServerTick> type() {
			return TYPE;
		}
	}

	/** Server -> client: you were hit; CS2 tagging slows you with the weapon's flinch velocity modifiers (large: first hit). */
	public record Tagged(float large, float small) implements CustomPacketPayload {
		public static final Type<Tagged> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "tagged"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Tagged> CODEC =
				StreamCodec.composite(ByteBufCodecs.FLOAT, Tagged::large, ByteBufCodecs.FLOAT, Tagged::small, Tagged::new);

		@Override
		public Type<Tagged> type() {
			return TYPE;
		}
	}

	/** Server -> client: the server's gameplay data as JSON (server/GameplaySync, client/ClientGameplay). */
	public record GameplaySync(String json) implements CustomPacketPayload {
		public static final Type<GameplaySync> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "gameplay_sync"));
		public static final StreamCodec<RegistryFriendlyByteBuf, GameplaySync> CODEC =
				StreamCodec.composite(ByteBufCodecs.stringUtf8(1 << 22), GameplaySync::json, GameplaySync::new);

		@Override
		public Type<GameplaySync> type() {
			return TYPE;
		}
	}

	/** Knife attack with the held CS knife: heavy = secondary (stab), else primary (slash); aimed along the player's view. */
	/** viewTick / claim: as in Fire. */
	public record Knife(boolean heavy, float viewTick, int claim) implements CustomPacketPayload {
		public static final Type<Knife> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "knife"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Knife> CODEC = StreamCodec.of((b, v) -> {
			b.writeBoolean(v.heavy);
			b.writeFloat(v.viewTick);
			b.writeVarInt(v.claim + 1);
		}, b -> new Knife(b.readBoolean(), b.readFloat(), b.readVarInt() - 1));

		@Override
		public Type<Knife> type() {
			return TYPE;
		}
	}

	/**
	 * Fire one shot of the held CS gun: the view angles, the shot number, and the client's inaccuracy and aim punch as it
	 * predicted them. The server decides the actual direction (weapon.ServerShots, weapon.ShotMath).
	 */
	/**
	 * viewTick: the server tick the shooter's screen showed (client.LagClock; -1 unknown), claim: the entity id their own
	 * trace hit (-1 none). Both only guide the server's rewind (server.LagHistory), which checks them.
	 */
	public record Fire(float yaw, float pitch, int shot, float inaccuracy, float punchPitch, float punchYaw, float viewTick, int claim)
			implements CustomPacketPayload {
		public static final Type<Fire> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "fire"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Fire> CODEC = StreamCodec.of((b, v) -> {
			b.writeFloat(v.yaw);
			b.writeFloat(v.pitch);
			b.writeVarInt(v.shot);
			b.writeFloat(v.inaccuracy);
			b.writeFloat(v.punchPitch);
			b.writeFloat(v.punchYaw);
			b.writeFloat(v.viewTick);
			b.writeVarInt(v.claim + 1);
		}, b -> new Fire(b.readFloat(), b.readFloat(), b.readVarInt(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readVarInt() - 1));

		@Override
		public Type<Fire> type() {
			return TYPE;
		}
	}

	/** A CS2 sound event another player made (shots, reload foley), played at its position with the real distance curve. */
	public record CsSound(String event, double x, double y, double z, boolean suppressed, int entity) implements CustomPacketPayload {
		public static final Type<CsSound> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "cs_sound"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CsSound> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, CsSound::event,
				ByteBufCodecs.DOUBLE, CsSound::x, ByteBufCodecs.DOUBLE, CsSound::y, ByteBufCodecs.DOUBLE, CsSound::z, ByteBufCodecs.BOOL,
				CsSound::suppressed, ByteBufCodecs.VAR_INT, CsSound::entity, CsSound::new);

		@Override
		public Type<CsSound> type() {
			return TYPE;
		}
	}

	/** A bullet hit a block: decal + impact sound/effect for the block's CS2 surface (seed picks the decal variant). */
	/** knife: a knife hit (CS2's knife scuff decal, no bullet ricochet sound) instead of a bullet. */
	public record BulletImpact(double x, double y, double z, int face, long block, String surface, int seed, float nx, float ny, float nz,
							   boolean knife)
			implements CustomPacketPayload {
		public static final Type<BulletImpact> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "bullet_impact"));
		public static final StreamCodec<RegistryFriendlyByteBuf, BulletImpact> CODEC = new StreamCodec<>() {
			@Override
			public BulletImpact decode(RegistryFriendlyByteBuf b) {
				return new BulletImpact(b.readDouble(), b.readDouble(), b.readDouble(), b.readByte(), b.readLong(), b.readUtf(), b.readInt(),
						b.readFloat(), b.readFloat(), b.readFloat(), b.readBoolean());
			}

			@Override
			public void encode(RegistryFriendlyByteBuf b, BulletImpact v) {
				b.writeDouble(v.x);
				b.writeDouble(v.y);
				b.writeDouble(v.z);
				b.writeByte(v.face);
				b.writeLong(v.block);
				b.writeUtf(v.surface);
				b.writeInt(v.seed);
				b.writeFloat(v.nx);
				b.writeFloat(v.ny);
				b.writeFloat(v.nz);
				b.writeBoolean(v.knife);
			}
		};

		@Override
		public Type<BulletImpact> type() {
			return TYPE;
		}
	}

	/** Reload the held CS gun: START (auto reload), START_HELD (reload key, still held: silent reload), RELEASE (key let go). */
	public record Reload(int action) implements CustomPacketPayload {
		public static final int START = 0, START_HELD = 1, RELEASE = 2;
		public static final Type<Reload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "reload"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Reload> CODEC =
				StreamCodec.composite(ByteBufCodecs.VAR_INT, Reload::action, Reload::new);

		@Override
		public Type<Reload> type() {
			return TYPE;
		}
	}

	/** A CS console command that acts on the Minecraft world (kill, explode, god, hurtme, give, impulse). */
	public record Action(String cmd, String arg) implements CustomPacketPayload {
		public static final Type<Action> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "console_action"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC =
				StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Action::cmd, ByteBufCodecs.STRING_UTF8, Action::arg, Action::new);

		@Override
		public Type<Action> type() {
			return TYPE;
		}
	}

	/** MC server's answer: the noclip state actually in effect, and why. */
	public record NoclipState(boolean on, String reason) implements CustomPacketPayload {
		public static final Type<NoclipState> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "noclip_state"));
		public static final StreamCodec<RegistryFriendlyByteBuf, NoclipState> CODEC =
				StreamCodec.composite(ByteBufCodecs.BOOL, NoclipState::on, ByteBufCodecs.STRING_UTF8, NoclipState::reason, NoclipState::new);

		@Override
		public Type<NoclipState> type() {
			return TYPE;
		}
	}
}
