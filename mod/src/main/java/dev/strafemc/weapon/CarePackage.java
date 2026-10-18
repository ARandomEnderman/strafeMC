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
 * CS2's XP rank and Care Package (rankup_redemption_store, items like CS2's drops: see CASES): kills with CS weapons earn CS2 XP (by what died, times 1.5 for
 * each modifier: headshot, no-scope, in the air, wallbang, through smoke, while blind, knife, Zeus), ranks 1-40 with
 * CS2's icons and names, each rank costing more like Minecraft's levels (need()). XP never goes down; every rank up
 * (and past 40, every rank-40's worth of XP) queues one package. You
 * redeem them from the strafeMC menu one at a time: four items (weapon cases and gun wraps), of which you claim two (CS2's
 * redeemable balance); the next one can be redeemed once its rewards are claimed. Kept with the player, sent to them only.
 */
public final class CarePackage {
	/** Items offered and how many you may claim (CS2: 4 shown, a balance of 2, each costs 1). */
	public static final int OFFERED = 4, BALANCE = 2;
	/**
	 * CS2's drops are weapon finishes from its drop-pool collections (the map collections, not cases) and weapon cases. Our
	 * mix, FLAGGED (CS2's pool and weights live on Valve's servers): one case and three finishes from the collections no
	 * case has, a grade by the case odds moved down one (Consumer 79.92%, Industrial 15.98%, Mil-Spec 3.2%, Restricted
	 * 0.64%, Classified the rest).
	 */
	static final int CASES = 1;
	private static final double[] GRADE_ODDS = {0.7992, 0.1598, 0.032, 0.0064, 0.0026};
	/** CS2's ranks go up to 40 (past it, every rank 40's worth of XP still queues a package). */
	public static final int MAX_RANK = 40;

	/**
	 * XP from this rank to the next: Minecraft's level curve (2L+7, 5L-38, 9L-158 for level L = rank - 1) scaled so rank
	 * 1 -> 2 costs 1000. FLAGGED ours: CS2 uses a flat 5000 per rank; this grows like Minecraft's levels.
	 */
	public static int need(int rank) {
		int l = Math.min(rank, MAX_RANK) - 1;
		int mc = l >= 30 ? 9 * l - 158 : l >= 15 ? 5 * l - 38 : 2 * l + 7;
		return Math.round(mc * 1000f / 7);
	}

	/** Packages earned by this much XP: one per rank passed, then one per rank 40's cost. */
	static int ranksBy(int xp) {
		int n = 0;
		for (int r = 1; ; r++) {
			int c = need(r);
			if (xp < c) return n;
			xp -= c;
			n++;
		}
	}
	/**
	 * XP for killing a player or bot, per point of Minecraft XP a hostile mob drops (zombie 5 -> 100), the most a mob
	 * gives, and the multiplier per modifier. FLAGGED ours: CS2's match XP formula lives on Valve's servers.
	 */
	/** A 360 kill's XP multiplier (3x). */
	static final double SPIN_XP = 3;
	static final double PLAYER_XP = 100, HOSTILE_PER_MC_XP = 20, MOB_MAX = 2000, MODIFIER = 1.5;

	/**
	 * items: the open package (empty: none); claimed: bit per item; balance: claims left in it; queued: packages earned and
	 * not redeemed yet; xp: all CS2 XP ever earned (the rank comes from it).
	 */
	public record State(List<ItemStack> items, int claimed, int balance, int queued, int xp) {
		public static final State NONE = new State(List.of(), 0, 0, 0, 0);

		/** The level: CS2's ranks 1-40, then on and on, every one past 40 costing what 40 did; its icon and name stay 40's. */
		public int rank() {
			return 1 + ranksBy(xp);
		}

		/** XP into the current rank (past 40: into the next package's worth). */
		public int rankXp() {
			int left = xp;
			for (int r = 1; left >= need(r); r++) left -= need(r);
			return left;
		}

		static final Codec<State> CODEC = RecordCodecBuilder.create(i -> i.group(
				ItemStack.OPTIONAL_CODEC.listOf().fieldOf("items").forGetter(State::items),
				Codec.INT.fieldOf("claimed").forGetter(State::claimed),
				Codec.INT.fieldOf("balance").forGetter(State::balance),
				Codec.INT.optionalFieldOf("queued", 0).forGetter(State::queued),
				Codec.INT.optionalFieldOf("xp", 0).forGetter(State::xp)).apply(i, State::new));

		static final StreamCodec<RegistryFriendlyByteBuf, State> STREAM = StreamCodec.composite(
				ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()), State::items,
				ByteBufCodecs.VAR_INT, State::claimed,
				ByteBufCodecs.VAR_INT, State::balance,
				ByteBufCodecs.VAR_INT, State::queued,
				ByteBufCodecs.VAR_INT, State::xp, State::new);

		public boolean claimed(int i) {
			return (claimed >> i & 1) != 0;
		}

		/** An opened package with rewards left to claim. */
		public boolean open() {
			return !items.isEmpty() && balance > 0;
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

	/**
	 * A kill the killfeed announced: CS2 XP for the killer by what died (players and bots PLAYER_XP, hostile mobs 20x
	 * their Minecraft XP, peaceful ones just their Minecraft XP: a pig is 1-3), times 1.5 per modifier.
	 */
	static void kill(ServerPlayer p, net.minecraft.world.entity.LivingEntity victim, String icon, int flags) {
		double base;
		if (victim instanceof ServerPlayer || Bots.isBot(victim)) base = PLAYER_XP;
		else {
			int mc = victim.level() instanceof net.minecraft.server.level.ServerLevel l ? victim.getExperienceReward(l, p) : 0;
			base = victim instanceof net.minecraft.world.entity.monster.Enemy ? Math.min(MOB_MAX, Math.max(1, mc) * HOSTILE_PER_MC_XP) : Math.max(1, mc);
		}
		int mods = Integer.bitCount(flags & (KillFeed.HEADSHOT | KillFeed.NOSCOPE | KillFeed.IN_AIR | KillFeed.PENETRATE | KillFeed.THROUGH_SMOKE
				| KillFeed.ATTACKER_BLIND)) + (icon.contains("knife") || icon.equals("bayonet") ? 1 : 0) + (icon.equals("taser") ? 1 : 0);
		if ((flags & KillFeed.SPIN) != 0) base *= SPIN_XP; // a 360: 3x on top
		addXp(p, (int) Math.round(base * Math.pow(MODIFIER, mods)));
	}

	/** Adds CS2 XP (never taken away); each 5000 passed queues a care package. */
	public static void addXp(ServerPlayer p, int amount) {
		if (amount <= 0) return;
		State s = get(p);
		int xp = (int) Math.min(Integer.MAX_VALUE, (long) s.xp() + amount), ranks = ranksBy(xp) - ranksBy(s.xp());
		boolean drops = dev.strafemc.server.StrafeSettings.drops(); // off on this server: ranks still go up, no packages
		State n = new State(s.items(), s.claimed(), s.balance(), s.queued() + (drops ? ranks : 0), xp);
		p.setAttached(DATA, n);
		if (ranks > 0) {
			if (n.rank() >= 10) Advancements.award(p, "rank_10");
			if (n.rank() >= MAX_RANK) Advancements.award(p, "rank_40");
			String name = rankName(n.rank());
			dev.strafemc.server.Toasts.send(p, "Rank up! " + (name.isEmpty() ? "" : name + " ") + "(" + n.rank() + ")." + (drops ? " You've earned "
					+ (ranks == 1 ? "a Care Package" : ranks + " Care Packages") + ": redeem it in the strafeMC menu." : ""), dev.strafemc.server.Toasts.GOOD);
		}
	}

	/** CS2's rank names (csgo_english SFUI_XP_RankName_1..40). */
	private static final String[] RANK_NAMES = {"", "Recruit", "Private", "Private", "Private", "Corporal", "Corporal", "Corporal", "Corporal",
			"Sergeant", "Sergeant", "Sergeant", "Sergeant", "Master Sergeant", "Master Sergeant", "Master Sergeant", "Master Sergeant",
			"Sergeant Major", "Sergeant Major", "Sergeant Major", "Sergeant Major", "Lieutenant", "Lieutenant", "Lieutenant", "Lieutenant",
			"Captain", "Captain", "Captain", "Captain", "Major", "Major", "Major", "Major", "Colonel", "Colonel", "Colonel", "Brigadier General",
			"Major General", "Lieutenant General", "General", "Global General"};

	public static String rankName(int rank) {
		return rank >= 0 ? RANK_NAMES[Math.min(rank, RANK_NAMES.length - 1)] : ""; // past 40: still Global General
	}

	/** Queues packages for a player (the operators' strafemc_drop / drop_everyone). */
	public static void queue(ServerPlayer p, int n) {
		State s = get(p);
		p.setAttached(DATA, new State(s.items(), s.claimed(), s.balance(), s.queued() + n, s.xp()));
		dev.strafemc.server.Toasts.send(p, (n == 1 ? "You've earned a Care Package." : "You've earned " + n + " Care Packages.")
				+ " Redeem it in the strafeMC menu.", dev.strafemc.server.Toasts.GOOD);
	}

	/** Opens the next queued package (replacing a fully claimed one): four fresh items and a full balance. */
	public static void redeem(ServerPlayer p) {
		State s = get(p);
		if (s.queued() <= 0 || s.open() || !dev.strafemc.server.StrafeSettings.drops()) return;
		Random rng = new Random();
		p.setAttached(DATA, new State(List.copyOf(roll(rng)), 0, BALANCE, s.queued() - 1, s.xp()));
	}

	/** The drop pool: gun wraps from collections no case has, by grade (COMMON .. LEGENDARY). */
	private static List<List<SkinWraps.Wrap>> pool;

	private static synchronized List<List<SkinWraps.Wrap>> pool() {
		if (pool != null) return pool;
		java.util.Set<String> inCases = new java.util.HashSet<>();
		for (Cases.Case c : Cases.all()) {
			for (var list : c.guns().values()) for (Cases.Entry e : list) inCases.add(e.item() + "|" + e.kit());
			for (Cases.Entry e : c.special()) inCases.add(e.item() + "|" + e.kit());
		}
		List<List<SkinWraps.Wrap>> out = new ArrayList<>();
		for (int i = 0; i < GRADE_ODDS.length; i++) out.add(new ArrayList<>());
		for (SkinWraps.Wrap w : SkinWraps.all()) {
			if (w.knife() || w.kits().size() != 1 || w.rarity() == null || w.rarity().ordinal() >= GRADE_ODDS.length) continue;
			var e = w.kits().entrySet().iterator().next();
			if (!inCases.contains(e.getKey() + "|" + e.getValue())) out.get(w.rarity().ordinal()).add(w);
		}
		return pool = out;
	}

	/** One finish from the drop pool: a grade by GRADE_ODDS (one it has none of falls to the next lower), a pattern and wear. */
	private static ItemStack dropWrap(Random rng) {
		List<List<SkinWraps.Wrap>> pool = pool();
		double x = rng.nextDouble();
		int grade = 0;
		for (double acc = GRADE_ODDS[0]; grade < GRADE_ODDS.length - 1 && x >= acc; acc += GRADE_ODDS[++grade]) ;
		for (int g = grade; g >= 0; g--) {
			List<SkinWraps.Wrap> list = pool.get(g);
			if (list.isEmpty()) continue;
			SkinWraps.Wrap w = list.get(rng.nextInt(list.size()));
			String kit = w.kits().values().iterator().next();
			float wear = dev.strafemc.server.StrafeSettings.wearFor(w.rarity()) ? Wear.roll(kit, rng) : 0;
			return SkinWrapItem.stack(WeaponItems.GUN_WRAP, w.id(), rng.nextInt(1001), wear);
		}
		return ItemStack.EMPTY;
	}

	/** A finish slot is a graffiti this often (CS2's drops include graffiti). FLAGGED ours. */
	static final double GRAFFITI_SHARE = 0.2;

	/**
	 * A graffiti at the same grade odds as the drop's finishes (GRADE_ODDS: Base Grade like Consumer, High Grade like
	 * Mil-Spec, and so on; a grade with no graffiti falls to the next lower one), the standard ones in a random CS2 tint.
	 */
	private static ItemStack graffiti(Random rng) {
		List<Sprays.Spray> all = Sprays.all();
		if (all.isEmpty()) return ItemStack.EMPTY;
		double x = rng.nextDouble();
		int grade = 0;
		for (double acc = GRADE_ODDS[0]; grade < GRADE_ODDS.length - 1 && x >= acc; acc += GRADE_ODDS[++grade]) ;
		List<Sprays.Spray> pick = new ArrayList<>();
		for (int g = grade; g >= 0 && pick.isEmpty(); g--)
			for (Sprays.Spray s : all) if (s.rarity().ordinal() == g) pick.add(s);
		if (pick.isEmpty()) pick = all;
		Sprays.Spray sp = pick.get(rng.nextInt(pick.size()));
		List<Sprays.Tint> tints = Sprays.tints();
		return SprayItem.stack(WeaponItems.SPRAY, sp.id(), tints.isEmpty() ? 0 : tints.get(rng.nextInt(tints.size())).id());
	}

	/** The case slot is two sticker capsules this often instead. FLAGGED ours. */
	static final double CAPSULE_SHARE = 0.25;
	/** Any other slot is two sticker capsules this often. FLAGGED ours. */
	static final double CAPSULE_SLOT_SHARE = 0.15;

	/** A package: CASES weapon cases (now and then two sticker capsules) and finishes for the rest, in random order. */
	private static List<ItemStack> roll(Random rng) {
		List<Cases.Case> cases = Cases.all();
		List<ItemStack> items = new ArrayList<>();
		for (int i = 0; i < OFFERED; i++) {
			List<Cases.Case> capsules = Cases.capsules("sticker");
			// the case slot: a case, or two sticker capsules; the rest: two capsules, a graffiti or a finish
			boolean caseSlot = i < CASES || pool().stream().allMatch(List::isEmpty);
			double x = rng.nextDouble();
			ItemStack s = caseSlot || x < CAPSULE_SLOT_SHARE ? ItemStack.EMPTY : x < CAPSULE_SLOT_SHARE + GRAFFITI_SHARE ? graffiti(rng) : dropWrap(rng);
			if (s.isEmpty() && !capsules.isEmpty() && (!caseSlot || rng.nextDouble() < CAPSULE_SHARE)) {
				s = CaseItem.stack(WeaponItems.WEAPON_CASE, capsules.get(rng.nextInt(capsules.size())).id());
				s.setCount(2);
			}
			if (s.isEmpty() && !cases.isEmpty()) s = CaseItem.stack(WeaponItems.WEAPON_CASE, cases.get(rng.nextInt(cases.size())).id());
			items.add(s);
		}
		java.util.Collections.shuffle(items, rng);
		return items;
	}

	/** The player claims these items (indices into the open package): as many as the balance allows, each once. */
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
		p.setAttached(DATA, new State(s.items(), claimed, balance, s.queued(), s.xp()));
		Advancements.award(p, "care_package");
	}
}
