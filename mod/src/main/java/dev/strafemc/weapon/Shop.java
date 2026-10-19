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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * The shop (ours): a balance in dollars, skins sold into it at SELL_RATE of their worth, and ten offers bought
 * from it (cases in a stock of 1-3, a sticker capsule 2-6, a graffiti box 1-3, the rest once), until the shop rotates after half an hour of the player's own play. Every player's offers are their
 * own: worked out from the player and the rotation number (client and server alike), so nothing rerolls them.
 */
public final class Shop {
	public static final int ROTATION = 36_000, OFFERS = 11; // 30 minutes of play
	/** Selling pays this share of a skin's worth. */
	public static final double SELL_RATE = 0.75;
	/** Offer prices: the item's worth times 80-110%. */
	private static final double PRICE_MIN = 0.8, PRICE_SPAN = 0.3;
	/** CS2's key price (US store), for the Universal Key. */
	private static final double KEY_PRICE = 2.49;
	/** A Charm Detachment's price. FLAGGED ours. */
	private static final double DETACHMENT_PRICE = 0.5;
	/** A Name Tag: CS2's store price (FLAGGED: the market trades it near this). */
	private static final double NAME_TAG_PRICE = 1.99;
	/**
	 * A case costs this many times what opening one is worth on average: keys are cheap to craft, so a case alone must
	 * lose. With the 80-110% price roll an opening returns ln(1.1/0.8)/0.3/1.2 = 88.5% on average (90% or
	 * under); about $4.30 for a typical case. FLAGGED ours.
	 */
	private static final double CASE_SHARE = 1.2;

	/** cents: the balance; rotation: which shop; bought: how many of each offer were bought, 3 bits each; progress: ticks into the rotation (synced each minute). */
	public record State(long cents, int rotation, long bought, int progress) {
		public static final State NONE = new State(0, 0, 0, 0);

		static final Codec<State> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.LONG.optionalFieldOf("cents", 0L).forGetter(State::cents),
				Codec.INT.optionalFieldOf("rotation", 0).forGetter(State::rotation),
				Codec.LONG.optionalFieldOf("bought", 0L).forGetter(State::bought),
				Codec.INT.optionalFieldOf("progress", 0).forGetter(State::progress)).apply(i, State::new));

		static final StreamCodec<RegistryFriendlyByteBuf, State> STREAM = StreamCodec.composite(
				ByteBufCodecs.VAR_LONG, State::cents, ByteBufCodecs.VAR_INT, State::rotation, ByteBufCodecs.VAR_LONG, State::bought,
				ByteBufCodecs.VAR_INT, State::progress, State::new);

		/** How many of offer i were bought this rotation. */
		public int bought(int i) {
			return (int) (bought >> 3 * i & 7);
		}
	}

	public static final AttachmentType<State> DATA = AttachmentRegistry.create(Identifier.fromNamespaceAndPath("strafemc", "shop"),
			b -> b.persistent(State.CODEC).copyOnDeath().initializer(() -> State.NONE).syncWith(State.STREAM, AttachmentSyncPredicate.targetOnly()));
	private static final AttachmentType<Integer> PROGRESS = AttachmentRegistry.create(Identifier.fromNamespaceAndPath("strafemc", "shop_progress"),
			b -> b.persistent(Codec.INT).copyOnDeath().initializer(() -> 0));

	/** item: what you get; cents: its price; stock: how many can be bought this rotation (cases 1-3, sticker capsules 2-6, graffiti boxes 1-3, the rest 1). */
	public record Offer(ItemStack item, long cents, int stock) {}

	private Shop() {}

	public static void register() {
		// the attachments register when this class loads
	}

	public static State get(Player p) {
		return p.getAttachedOrElse(DATA, State.NONE);
	}

	public static String money(long cents) {
		return Worth.format(cents / 100.0);
	}

	/** Every second: half an hour of the player's play rotates their shop. The progress is synced each minute (and on join). */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			int progress = p.getAttachedOrElse(PROGRESS, 0) + 20;
			State s = get(p);
			if (progress >= ROTATION) {
				p.setAttached(PROGRESS, 0);
				p.setAttached(DATA, new State(s.cents(), s.rotation() + 1, 0, 0));
				dev.strafemc.server.Toasts.info(p, "The shop has new items.");
			} else {
				p.setAttached(PROGRESS, progress);
				if (progress % 1200 == 0) sync(p);
			}
		}
	}

	public static void sync(ServerPlayer p) {
		State s = get(p);
		p.setAttached(DATA, new State(s.cents(), s.rotation(), s.bought(), p.getAttachedOrElse(PROGRESS, 0)));
	}

	/** The rotation's offers for this player: three cases, a sticker capsule, a graffiti box, a Universal Key and four high-grade skins (knives and gloves too). */
	public static List<Offer> offers(UUID player, int rotation) {
		Random rng = new Random((player.getMostSignificantBits() * 31 + player.getLeastSignificantBits()) * 1_000_003L + rotation);
		List<Offer> out = new ArrayList<>();
		List<Cases.Case> cases = new ArrayList<>(Cases.all());
		java.util.Collections.shuffle(cases, rng);
		for (int i = 0; i < 3 && i < cases.size(); i++)
			out.add(new Offer(CaseItem.stack(WeaponItems.WEAPON_CASE, cases.get(i).id()), price(caseWorth(cases.get(i)), rng), 1 + rng.nextInt(3)));
		// a sticker capsule (2-6 of it) and a graffiti box or, half the time, a charm collection (1-3): priced like cases,
		// their average opening x CASE_SHARE
		List<Cases.Case> stickers = Cases.capsules("sticker"), charms = Cases.capsules("charm");
		List<Cases.Case> boxes = !charms.isEmpty() && rng.nextBoolean() ? charms : Cases.capsules("spray");
		if (!stickers.isEmpty()) {
			Cases.Case c = stickers.get(rng.nextInt(stickers.size()));
			out.add(new Offer(CaseItem.stack(WeaponItems.WEAPON_CASE, c.id()), price(capsuleWorth(c), rng), 2 + rng.nextInt(5)));
		}
		if (!boxes.isEmpty()) {
			Cases.Case c = boxes.get(rng.nextInt(boxes.size()));
			out.add(new Offer(CaseItem.stack(WeaponItems.WEAPON_CASE, c.id()), price(capsuleWorth(c), rng), 1 + rng.nextInt(3)));
		}
		out.add(new Offer(CaseKeys.universal(), price(KEY_PRICE, rng), 1));
		out.add(new Offer(new ItemStack(WeaponItems.CHARM_DETACHMENT), price(DETACHMENT_PRICE, rng), 1 + rng.nextInt(3)));
		out.add(new Offer(new ItemStack(WeaponItems.NAME_TAG), price(NAME_TAG_PRICE, rng), 1 + rng.nextInt(2)));
		List<SkinWraps.Wrap> wraps = new ArrayList<>();
		for (SkinWraps.Wrap w : SkinWraps.all())
			if (w.knife() || w.kits().size() == 1 && w.rarity() != null && w.rarity().ordinal() >= SkinWraps.Rarity.MYTHICAL.ordinal()) wraps.add(w);
		while (out.size() < OFFERS && !wraps.isEmpty()) {
			ItemStack s;
			if (rng.nextDouble() < 0.15 && !GloveSkins.ALL.isEmpty()) { // gloves now and then
				GloveSkins.Skin g = GloveSkins.ALL.get(rng.nextInt(GloveSkins.ALL.size()));
				double[] gr = Wear.range(g.kit());
				s = GloveItem.stack(WeaponItems.GLOVES.get(g.kind()), g.kit(), (float) (gr[0] + rng.nextDouble() * (gr[1] - gr[0])));
			} else {
				SkinWraps.Wrap w = wraps.get(rng.nextInt(wraps.size()));
				double[] r = Wear.range(w.kits().values().iterator().next());
				float wear = (float) (r[0] + rng.nextDouble() * (r[1] - r[0]));
				s = SkinWrapItem.stack(w.knife() ? WeaponItems.KNIFE_WRAP : WeaponItems.GUN_WRAP, w.id(), rng.nextInt(1001), wear);
			}
			double v = Worth.of(s);
			if (v > 0) out.add(new Offer(s, price(v, rng), 1));
		}
		return out;
	}

	private static long price(double dollars, Random rng) {
		return Math.max(1, Math.round(dollars * (PRICE_MIN + rng.nextDouble() * PRICE_SPAN) * 100));
	}

	/** A case's price base: what one opening is worth on average (its tiers' odds x their skins' average Field-Tested worth) x CASE_SHARE. */
	static double caseWorth(Cases.Case c) {
		double[] odds = {0.7992, 0.1598, 0.032, 0.0064};
		SkinWraps.Rarity[] tiers = {SkinWraps.Rarity.RARE, SkinWraps.Rarity.MYTHICAL, SkinWraps.Rarity.LEGENDARY, SkinWraps.Rarity.ANCIENT};
		double ev = 0;
		for (int t = 0; t < tiers.length; t++) {
			var list = c.guns().get(tiers[t]);
			if (list == null || list.isEmpty()) continue;
			double sum = 0;
			int n = 0;
			for (Cases.Entry e : list) {
				ItemStack s = Cases.stack(e, 0, 0.25f);
				double v = Worth.of(s);
				if (v > 0) {
					sum += v;
					n++;
				}
			}
			if (n > 0) ev += odds[t] * sum / n;
		}
		if (!c.special().isEmpty()) ev += 0.0026 * 375;
		return Math.max(0.03, ev * CASE_SHARE);
	}

	/** A sticker capsule's / graffiti box's price base: its average opening x CASE_SHARE (about $1.20 / $1.00, see Worth). */
	static double capsuleWorth(Cases.Case c) {
		return Math.max(0.03, Worth.capsuleOpening(c) * CASE_SHARE);
	}

	/** What the shop buys: skins (with a worth) and money vouchers (with a value). */
	public static boolean sellable(ItemStack s) {
		return Upgrader.stakeable(s) || s.getItem() instanceof VoucherItem && VoucherItem.dollars(s) > 0;
	}

	/** What selling it pays: a voucher its full dollars, a skin SELL_RATE of its worth. */
	public static long sellCents(ItemStack s) {
		if (s.getItem() instanceof VoucherItem) return VoucherItem.dollars(s) * 100L * s.getCount();
		return Math.round(Worth.of(s) * s.getCount() * SELL_RATE * 100);
	}

	/**
	 * Sells this many of the items at each ref (inventory slots or storage unit places, Upgrader refs): skins at
	 * SELL_RATE, vouchers at full value.
	 */
	public static void sell(ServerPlayer p, java.util.Map<Integer, Integer> refs) {
		if (refs.isEmpty()) return;
		long cents = 0;
		for (var e : refs.entrySet()) {
			ItemStack s = Upgrader.at(p, e.getKey());
			if (!sellable(s) || e.getValue() < 1 || e.getValue() > s.getCount()) return;
			cents += sellCents(s.copyWithCount(e.getValue()));
		}
		Upgrader.take(p, refs);
		State s = get(p);
		p.setAttached(DATA, new State(s.cents() + cents, s.rotation(), s.bought(), p.getAttachedOrElse(PROGRESS, 0)));
		dev.strafemc.StrafeMc.LOG.info("[strafemc] shop: {} sold {} item(s) for {}", p.getScoreboardName(), refs.size(), money(cents));
	}

	/** Takes whole dollars off the balance as a money voucher (to keep or give away), if the balance covers it. */
	public static void withdraw(ServerPlayer p, int dollars) {
		State s = get(p);
		if (dollars < 1 || s.cents() < dollars * 100L) {
			dev.strafemc.server.Toasts.warn(p, "Not enough balance.");
			return;
		}
		p.setAttached(DATA, new State(s.cents() - dollars * 100L, s.rotation(), s.bought(), p.getAttachedOrElse(PROGRESS, 0)));
		ItemStack v = VoucherItem.stack(WeaponItems.VOUCHER_ITEM, dollars);
		if (!p.getInventory().add(v)) p.drop(v, false);
		dev.strafemc.StrafeMc.LOG.info("[strafemc] shop: {} withdrew ${}", p.getScoreboardName(), dollars);
	}

	/** Adds to the player's balance. */
	public static void deposit(ServerPlayer p, long cents) {
		State s = get(p);
		p.setAttached(DATA, new State(s.cents() + Math.max(0, cents), s.rotation(), s.bought(), p.getAttachedOrElse(PROGRESS, 0)));
	}

	/** Buys n of offer i of this rotation (as many as are in stock and the balance covers). */
	public static void buy(ServerPlayer p, int i, int n) {
		for (int k = 0; k < Math.max(1, Math.min(8, n)); k++) {
			State before = get(p);
			buy(p, i);
			if (get(p).bought() == before.bought()) return; // out of stock or money
		}
	}

	/** Buys offer i of this rotation, once, if the balance covers it. */
	public static void buy(ServerPlayer p, int i) {
		State s = get(p);
		List<Offer> offers = offers(p.getUUID(), s.rotation());
		if (i < 0 || i >= offers.size() || s.bought(i) >= offers.get(i).stock()) return;
		Offer o = offers.get(i);
		if (s.cents() < o.cents()) {
			dev.strafemc.server.Toasts.warn(p, "Not enough balance.");
			return;
		}
		p.setAttached(DATA, new State(s.cents() - o.cents(), s.rotation(), s.bought() + (1L << 3 * i), p.getAttachedOrElse(PROGRESS, 0)));
		ItemStack item = o.item().copy();
		if (!p.getInventory().add(item)) p.drop(item, false);
		dev.strafemc.StrafeMc.LOG.info("[strafemc] shop: {} bought {} for {}", p.getScoreboardName(), o.item().getHoverName().getString(), money(o.cents()));
	}
}
