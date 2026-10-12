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
	public record Knife(boolean heavy) implements CustomPacketPayload {
		public static final Type<Knife> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "knife"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Knife> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, Knife::heavy, Knife::new);

		@Override
		public Type<Knife> type() {
			return TYPE;
		}
	}

	/** Fire one shot of the held CS gun in this direction (client applied CS inaccuracy/spread). */
	public record Fire(float yaw, float pitch) implements CustomPacketPayload {
		public static final Type<Fire> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "fire"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Fire> CODEC =
				StreamCodec.composite(ByteBufCodecs.FLOAT, Fire::yaw, ByteBufCodecs.FLOAT, Fire::pitch, Fire::new);

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
	public record BulletImpact(double x, double y, double z, int face, long block, String surface, int seed, float nx, float ny, float nz)
			implements CustomPacketPayload {
		public static final Type<BulletImpact> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "bullet_impact"));
		public static final StreamCodec<RegistryFriendlyByteBuf, BulletImpact> CODEC = new StreamCodec<>() {
			@Override
			public BulletImpact decode(RegistryFriendlyByteBuf b) {
				return new BulletImpact(b.readDouble(), b.readDouble(), b.readDouble(), b.readByte(), b.readLong(), b.readUtf(), b.readInt(),
						b.readFloat(), b.readFloat(), b.readFloat());
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
