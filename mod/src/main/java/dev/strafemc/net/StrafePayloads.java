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
							 String icon, int flags, String assister, boolean assisterT) implements CustomPacketPayload {
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
			b.writeUtf(v.assister, 64);
			b.writeBoolean(v.assisterT);
		}, b -> new KillNotice(b.readUtf(64), b.readBoolean(), b.readVarInt(), b.readUtf(64), b.readBoolean(), b.readVarInt(), b.readBoolean(),
				b.readUtf(48), b.readVarInt(), b.readUtf(64), b.readBoolean()));

		@Override
		public Type<KillNotice> type() {
			return TYPE;
		}
	}

	/** Client -> server: this player's CS side (true = T), from the console quick menu. */
	/** What the player can still refund (the buy command's items, newest last): sent by the server whenever it changes. */
	/** Cases opened (1-5 at once): per drop its kit on an item, rarity ordinal (-1: rare special item), name, pattern (-1: none). */
	/**
	 * Server -> the player who died: CS2's death panel. weapon: its name with the killer's skin ("AK-47 | Redline");
	 * cls / kit: for its picture (kit empty: the plain weapon); hp: the killer's CS health left; damage both ways.
	 */
	public record DeathInfo(String killer, int killerId, boolean self, String weapon, String cls, String kit, int hp, int given, int hitsGiven, int taken,
							int hitsTaken) implements CustomPacketPayload {
		public static final Type<DeathInfo> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "death_info"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DeathInfo> CODEC = StreamCodec.of((b, v) -> {
			b.writeUtf(v.killer(), 64);
			b.writeVarInt(v.killerId());
			b.writeBoolean(v.self());
			b.writeUtf(v.weapon(), 128);
			b.writeUtf(v.cls(), 64);
			b.writeUtf(v.kit(), 96);
			b.writeVarInt(v.hp());
			b.writeVarInt(v.given());
			b.writeVarInt(v.hitsGiven());
			b.writeVarInt(v.taken());
			b.writeVarInt(v.hitsTaken());
		}, b -> new DeathInfo(b.readUtf(64), b.readVarInt(), b.readBoolean(), b.readUtf(128), b.readUtf(64), b.readUtf(96), b.readVarInt(), b.readVarInt(),
				b.readVarInt(), b.readVarInt(), b.readVarInt()));

		@Override
		public Type<DeathInfo> type() {
			return TYPE;
		}
	}

	/** Server -> everyone in the level: a graffiti sprayed (where, which face, the sprayer's yaw for floors, who). */
	/** Server -> client: a strafeMC notice for the bottom-left toasts (color: its edge, ARGB). */
	public record Toast(String text, int color) implements CustomPacketPayload {
		public static final Type<Toast> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "toast"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Toast> CODEC = StreamCodec.of((b, v) -> {
			b.writeUtf(v.text(), 512);
			b.writeInt(v.color());
		}, b -> new Toast(b.readUtf(512), b.readInt()));

		@Override
		public Type<Toast> type() {
			return TYPE;
		}
	}

	/** ageS: how long ago it was sprayed (0 when new; more for the ones a joining player is sent). */
	public record SprayPlaced(String spray, int tint, double x, double y, double z, int face, long block, float yaw, java.util.UUID owner, int ageS)
			implements CustomPacketPayload {
		public static final Type<SprayPlaced> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "spray_placed"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SprayPlaced> CODEC = StreamCodec.of((b, v) -> {
			b.writeUtf(v.spray(), 96);
			b.writeVarInt(v.tint());
			b.writeDouble(v.x());
			b.writeDouble(v.y());
			b.writeDouble(v.z());
			b.writeVarInt(v.face());
			b.writeLong(v.block());
			b.writeFloat(v.yaw());
			b.writeUUID(v.owner());
			b.writeVarInt(v.ageS());
		}, b -> new SprayPlaced(b.readUtf(96), b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble(), b.readVarInt(), b.readLong(), b.readFloat(), b.readUUID(),
				b.readVarInt()));

		@Override
		public Type<SprayPlaced> type() {
			return TYPE;
		}
	}

	/** Server -> client: what a trade up contract gave. */
	public record TradeUpResult(net.minecraft.world.item.ItemStack item) implements CustomPacketPayload {
		public static final Type<TradeUpResult> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "tradeup_result"));
		public static final StreamCodec<RegistryFriendlyByteBuf, TradeUpResult> CODEC = StreamCodec.composite(net.minecraft.world.item.ItemStack.STREAM_CODEC,
				TradeUpResult::item, TradeUpResult::new);

		@Override
		public Type<TradeUpResult> type() {
			return TYPE;
		}
	}

	/** Server -> client: an upgrade's outcome (which offer, won or not), for the upgrader's needle. */
	public record UpgradeResult(int offer, boolean win) implements CustomPacketPayload {
		public static final Type<UpgradeResult> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "upgrade_result"));
		public static final StreamCodec<RegistryFriendlyByteBuf, UpgradeResult> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, UpgradeResult::offer,
				ByteBufCodecs.BOOL, UpgradeResult::win, UpgradeResult::new);

		@Override
		public Type<UpgradeResult> type() {
			return TYPE;
		}
	}

	public record CaseResult(String caseId, java.util.List<Drop> drops) implements CustomPacketPayload {
		/** pattern -1: none; wear -1: none (gloves). */
		public record Drop(String kit, String item, int rarity, String name, int pattern, float wear) {}

		public static final Type<CaseResult> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "case_result"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CaseResult> CODEC = StreamCodec.of((b, v) -> {
			b.writeUtf(v.caseId(), 64);
			b.writeVarInt(v.drops().size());
			for (Drop d : v.drops()) {
				b.writeUtf(d.kit(), 128);
				b.writeUtf(d.item(), 64);
				b.writeVarInt(d.rarity() + 1);
				b.writeUtf(d.name(), 128);
				b.writeVarInt(d.pattern() + 1);
				b.writeFloat(d.wear());
			}
		}, b -> {
			String id = b.readUtf(64);
			int n = Math.min(5, b.readVarInt());
			java.util.List<Drop> drops = new java.util.ArrayList<>();
			for (int k = 0; k < n; k++) drops.add(new Drop(b.readUtf(128), b.readUtf(64), b.readVarInt() - 1, b.readUtf(128), b.readVarInt() - 1, b.readFloat()));
			return new CaseResult(id, drops);
		});

		@Override
		public Type<CaseResult> type() {
			return TYPE;
		}
	}

	/** A bulk opening's rolls: each drop with the case it came from (BulkOpener; up to BulkOpener.MAX). */
	public record BulkCaseResult(java.util.List<String> caseIds, java.util.List<CaseResult.Drop> drops) implements CustomPacketPayload {
		public static final Type<BulkCaseResult> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "bulk_case_result"));
		public static final StreamCodec<RegistryFriendlyByteBuf, BulkCaseResult> CODEC = StreamCodec.of((b, v) -> {
			b.writeVarInt(v.drops().size());
			for (int k = 0; k < v.drops().size(); k++) {
				CaseResult.Drop d = v.drops().get(k);
				b.writeUtf(v.caseIds().get(k), 64);
				b.writeUtf(d.kit(), 128);
				b.writeUtf(d.item(), 64);
				b.writeVarInt(d.rarity() + 1);
				b.writeUtf(d.name(), 128);
				b.writeVarInt(d.pattern() + 1);
				b.writeFloat(d.wear());
			}
		}, b -> {
			int n = Math.min(dev.strafemc.weapon.BulkOpener.MAX, b.readVarInt());
			java.util.List<String> ids = new java.util.ArrayList<>();
			java.util.List<CaseResult.Drop> drops = new java.util.ArrayList<>();
			for (int k = 0; k < n; k++) {
				ids.add(b.readUtf(64));
				drops.add(new CaseResult.Drop(b.readUtf(128), b.readUtf(64), b.readVarInt() - 1, b.readUtf(128), b.readVarInt() - 1, b.readFloat()));
			}
			return new BulkCaseResult(ids, drops);
		});

		@Override
		public Type<BulkCaseResult> type() {
			return TYPE;
		}
	}

	public record Refundable(java.util.List<String> items) implements CustomPacketPayload {
		public static final Type<Refundable> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "refundable"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Refundable> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(64)), Refundable::items, Refundable::new);

		@Override
		public Type<Refundable> type() {
			return TYPE;
		}
	}

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

	/**
	 * Server -> client: a fire (CS2 inferno) as it is now. Its start (blocks), kind, how long it burns and how long it has
	 * burnt (seconds), whether it is over (ended: out of time; extinguished: by a smoke), and its flames, 10 floats each:
	 * position and parent position relative to the start (blocks), surface normal (Minecraft axes), burning (1/0).
	 */
	public record InfernoState(int id, boolean incendiary, double x, double y, double z, float lifetime, float age, int end, float[] fires)
			implements CustomPacketPayload {
		public static final int BURNING = 0, ENDED = 1, EXTINGUISHED = 2, FLOATS = 10;
		public static final Type<InfernoState> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "inferno"));
		public static final StreamCodec<RegistryFriendlyByteBuf, InfernoState> CODEC = StreamCodec.of((b, v) -> {
			b.writeVarInt(v.id);
			b.writeBoolean(v.incendiary);
			b.writeDouble(v.x);
			b.writeDouble(v.y);
			b.writeDouble(v.z);
			b.writeFloat(v.lifetime);
			b.writeFloat(v.age);
			b.writeVarInt(v.end);
			b.writeVarInt(v.fires.length);
			for (float f : v.fires) b.writeFloat(f);
		}, b -> {
			int id = b.readVarInt();
			boolean inc = b.readBoolean();
			double x = b.readDouble(), y = b.readDouble(), z = b.readDouble();
			float life = b.readFloat(), age = b.readFloat();
			int end = b.readVarInt(), n = Math.min(b.readVarInt(), 64 * FLOATS);
			float[] f = new float[n];
			for (int i = 0; i < n; i++) f[i] = b.readFloat();
			return new InfernoState(id, inc, x, y, z, life, age, end, f);
		});

		@Override
		public Type<InfernoState> type() {
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
	/** flags: the zoom level (bits 0-1, ZOOM_MASK) and ALT (the R8's alt fire). eye: the shooter's eye height above their feet (blocks). */
	public record Fire(float yaw, float pitch, int shot, float inaccuracy, float punchPitch, float punchYaw, float viewTick, int claim, int flags, float eye)
			implements CustomPacketPayload {
		public static final int ZOOM_MASK = 3, ALT = 4;
		/**
		 * The body part the shooter's client saw the bullet hit on the claimed entity's animated model (ClientHitbox), in
		 * bits 4-6: 0 none, then GROUPS' index + 1. The server checks it against the hit.
		 */
		public static final int GROUP_SHIFT = 4, GROUP_MASK = 7 << GROUP_SHIFT;
		public static final String[] GROUPS = {"head", "chest", "stomach", "arms", "legs"};
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
			b.writeVarInt(v.flags);
			b.writeFloat(v.eye);
		}, b -> new Fire(b.readFloat(), b.readFloat(), b.readVarInt(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readVarInt() - 1,
				b.readVarInt(), b.readFloat()));

		@Override
		public Type<Fire> type() {
			return TYPE;
		}
	}

	/** The world's game mode zones (Zones), as JSON. */
	public record ZoneList(String json) implements CustomPacketPayload {
		public static final Type<ZoneList> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "zone_list"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ZoneList> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, ZoneList::json, ZoneList::new);

		@Override
		public Type<ZoneList> type() {
			return TYPE;
		}
	}

	/** The running game mode as this player sees it (strafeGM), as JSON; "{}" when none runs. */
	public record ModeState(String json) implements CustomPacketPayload {
		public static final Type<ModeState> TYPE = new Type<>(Identifier.fromNamespaceAndPath("strafemc", "mode_state"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ModeState> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, ModeState::json, ModeState::new);

		@Override
		public Type<ModeState> type() {
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
