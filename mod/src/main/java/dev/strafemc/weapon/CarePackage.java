package dev.strafemc.weapon;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * CS2's Weekly Care Package (rankup_redemption_store), every hour of play instead of every week and without XP ranks:
 * four items (weapon cases and gun wraps), of which you claim two (CS2's redeemable balance). Unclaimed items can be
 * claimed later, until the next package replaces them ("gone forever", as in CS2). Kept with the player, sent to them only.
 */
public final class CarePackage {
	/** One hour of play, in ticks. */
	public static final int INTERVAL = 72_000;
	/** Items offered and how many you may claim (CS2: 4 shown, a balance of 2, each costs 1). */
	public static final int OFFERED = 4, BALANCE = 2;
	/** Our mix: each offered item is a case this often, else a wrap. FLAGGED: CS2's drop pool weights live on Valve's servers. */
	static final double CASE_SHARE = 0.3;

	/** items: the offer (empty list: none yet); claimed: bit per item; balance: claims left; progress: ticks toward the next. */
	public record State(List<ItemStack> items, int claimed, int balance, int progress) {
		public static final State NONE = new State(List.of(), 0, 0, 0);

		static final Codec<State> CODEC = RecordCodecBuilder.create(i -> i.group(
				ItemStack.OPTIONAL_CODEC.listOf().fieldOf("items").forGetter(State::items),
				Codec.INT.fieldOf("claimed").forGetter(State::claimed),
				Codec.INT.fieldOf("balance").forGetter(State::balance),
				Codec.INT.fieldOf("progress").forGetter(State::progress)).apply(i, State::new));

		static final StreamCodec<RegistryFriendlyByteBuf, State> STREAM = StreamCodec.composite(
				ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()), State::items,
				ByteBufCodecs.VAR_INT, State::claimed,
				ByteBufCodecs.VAR_INT, State::balance,
				ByteBufCodecs.VAR_INT, State::progress, State::new);

		public boolean claimed(int i) {
			return (claimed >> i & 1) != 0;
		}
	}

	public static final AttachmentType<State> DATA = AttachmentRegistry.create(Identifier.fromNamespaceAndPath("strafemc", "care_package"),
			b -> b.persistent(State.CODEC).copyOnDeath().initializer(() -> State.NONE).syncWith(State.STREAM, AttachmentSyncPredicate.targetOnly()));

	private CarePackage() {}

	public static void register() {
		// the attachment registers when this class loads
	}

	public static State get(net.minecraft.world.entity.player.Player p) {
		return p.getAttachedOrElse(DATA, State.NONE);
	}

	/** Ticks of play toward the next package, counted every second (saved, not synced: DATA carries a copy each minute). */
	private static final AttachmentType<Integer> PROGRESS = AttachmentRegistry.create(Identifier.fromNamespaceAndPath("strafemc", "care_package_progress"),
			b -> b.persistent(Codec.INT).copyOnDeath().initializer(() -> 0));

	/** Every second: an hour of play brings a new package (replacing the old one). The client counts down between syncs. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			int progress = p.getAttachedOrElse(PROGRESS, 0) + 20;
			if (progress >= INTERVAL) {
				p.setAttached(PROGRESS, 0);
				give(p);
			} else {
				p.setAttached(PROGRESS, progress);
				if (progress % 1200 == 0) syncProgress(p);
			}
		}
	}

	/** Copies the progress into the synced state (each minute, and on join). */
	public static void syncProgress(ServerPlayer p) {
		State s = get(p);
		p.setAttached(DATA, new State(s.items(), s.claimed(), s.balance(), p.getAttachedOrElse(PROGRESS, 0)));
	}

	/** A new package now (also the operators' strafemc_drop): four fresh items, a full balance, progress from zero. */
	public static void give(ServerPlayer p) {
		Random rng = new Random();
		List<ItemStack> items = new ArrayList<>();
		for (int i = 0; i < OFFERED; i++) items.add(roll(rng));
		p.setAttached(PROGRESS, 0);
		p.setAttached(DATA, new State(List.copyOf(items), 0, BALANCE, 0));
		p.sendSystemMessage(Component.literal("Care Package: you've earned free rewards. Open the strafeMC menu to claim them.").withColor(0xEABE54));
	}

	private static ItemStack roll(Random rng) {
		List<Cases.Case> cases = Cases.all();
		if (cases.isEmpty()) return ItemStack.EMPTY;
		if (rng.nextDouble() < CASE_SHARE) return CaseItem.stack(WeaponItems.WEAPON_CASE, cases.get(rng.nextInt(cases.size())).id());
		for (int tries = 0; tries < 8; tries++) {
			ItemStack s = Cases.rollWrap(cases.get(rng.nextInt(cases.size())), rng);
			if (!s.isEmpty()) return s;
		}
		return CaseItem.stack(WeaponItems.WEAPON_CASE, cases.get(rng.nextInt(cases.size())).id());
	}

	/** The player claims these items (indices into the offer): as many as the balance allows, each once. */
	public static void claim(ServerPlayer p, String arg) {
		State s = get(p);
		if (s.items().isEmpty()) return;
		int claimed = s.claimed(), balance = s.balance(), got = 0;
		for (String a : arg.trim().split(" ")) {
			int i;
			try {
				i = Integer.parseInt(a);
			} catch (NumberFormatException e) {
				continue;
			}
			if (i < 0 || i >= s.items().size() || (claimed >> i & 1) != 0 || balance <= 0) continue;
			ItemStack it = s.items().get(i).copy();
			if (it.isEmpty()) continue;
			claimed |= 1 << i;
			balance--;
			got++;
			if (!p.getInventory().add(it)) p.drop(it, false);
		}
		if (got == 0) return;
		p.setAttached(DATA, new State(s.items(), claimed, balance, s.progress()));
		Advancements.award(p, "care_package");
	}
}
