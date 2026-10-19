package dev.strafemc.weapon;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The CS loadout: per-player slots for gloves, an agent (later), a knife wrap and one wrap per gun type. Kept by the
 * server with the player (saved, copied to the respawned player, sent to everyone so others can see it later). Items in
 * it are never used up: taking one out gives it back. Operators choose whether it drops on death (strafemc_loadout_keep).
 */
public final class Loadout {
	private Loadout() {}

	/** What a slot takes: GLOVES any gloves, AGENT nothing yet, KNIFE knife wraps, GUN wraps that exist for {@link Def#weapon}. */
	public enum Kind {GLOVES, AGENT, KNIFE, GUN, SPRAY}

	/** CS2's graffiti wheel has six: loadout slots spray0..spray5, each an unsealed graffiti (its sprays get used up). */
	public static final int SPRAYS = 6;

	public static String sprayId(int i) {
		return "spray" + i;
	}

	public record Def(String id, Kind kind, String weapon, String label) {}

	private static List<Def> defs;

	/** The slots, in screen order: gloves, agent, knife, then every gun strafeMC has (registry order). */
	public static List<Def> defs() {
		if (defs == null) {
			List<Def> d = new ArrayList<>();
			d.add(new Def("gloves", Kind.GLOVES, null, "Gloves"));
			d.add(new Def("agent", Kind.AGENT, null, "Agent"));
			d.add(new Def("knife", Kind.KNIFE, null, "Knife"));
			// guns in CS2's buy menu order: pistols, SMGs, rifles, heavy (with the Zeus)
			List<WeaponTable.Entry> guns = new ArrayList<>(WeaponTable.guns());
			guns.sort(java.util.Comparator.comparingInt((WeaponTable.Entry e) -> WeaponTable.order(e.cls())));
			for (WeaponTable.Entry e : guns) d.add(new Def(e.cls(), Kind.GUN, e.cls(), WeaponTable.name(e.cls())));
			for (int i = 0; i < SPRAYS; i++) d.add(new Def(sprayId(i), Kind.SPRAY, null, "Graffiti " + (i + 1)));
			defs = List.copyOf(d);
		}
		return defs;
	}

	/** Slot id -> item (only non-empty slots are stored). Immutable: every change sets a new map, which is what syncs it. */
	public static final AttachmentType<Map<String, ItemStack>> DATA = AttachmentRegistry.create(Identifier.fromNamespaceAndPath("strafemc", "loadout"),
			b -> b.persistent(Codec.unboundedMap(Codec.STRING, ItemStack.CODEC)).copyOnDeath().initializer(Map::of)
					.syncWith(ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ItemStack.STREAM_CODEC), AttachmentSyncPredicate.all()));

	public static MenuType<Menu> MENU;

	public static void register() {
		MENU = Registry.register(BuiltInRegistries.MENU, Identifier.fromNamespaceAndPath("strafemc", "loadout"),
				new MenuType<>(Menu::new, FeatureFlags.VANILLA_SET));
	}

	public static ItemStack get(Player p, String slot) {
		Map<String, ItemStack> m = p.getAttached(DATA);
		ItemStack s = m == null ? null : m.get(slot);
		return s == null ? ItemStack.EMPTY : s;
	}

	public static void set(Player p, String slot, ItemStack stack) {
		Map<String, ItemStack> m = new HashMap<>(p.getAttachedOrElse(DATA, Map.of()));
		if (stack.isEmpty()) m.remove(slot);
		else m.put(slot, stack.copy());
		p.setAttached(DATA, Map.copyOf(m));
		if (p instanceof ServerPlayer sp && stack.getItem() instanceof SkinWrapItem) Advancements.award(sp, "wrap_on");
		if (p instanceof ServerPlayer sp && stack.getItem() instanceof GloveItem) Advancements.award(sp, "gloves");
	}

	public static boolean accepts(Def d, ItemStack s) {
		return switch (d.kind) {
			case GLOVES -> s.getItem() instanceof GloveItem;
			case AGENT -> false;
			case KNIFE -> s.getItem() instanceof SkinWrapItem w && w.knife() && SkinWraps.get(SkinWrapItem.skin(s)) != null;
			case GUN -> s.getItem() instanceof SkinWrapItem w && !w.knife() && SkinWraps.fits(SkinWrapItem.skin(s), d.weapon);
			case SPRAY -> s.getItem() instanceof SprayItem && !SprayItem.sealed(s); // sealed ones are unsealed first
		};
	}

	public static void open(ServerPlayer p) {
		p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new Menu(id, inv, (ServerPlayer) pl), Component.literal("Loadout")));
	}

	/** Right-clicked gloves: wear them (they go into the gloves slot, the ones worn before come back). */
	public static void wearGloves(ServerPlayer p, ItemStack held) {
		equip(p, "gloves", held);
	}

	/** Right-clicked knife wrap: into the knife slot (its only slot), swapping like armor. */
	public static void equipKnifeWrap(ServerPlayer p, ItemStack held) {
		equip(p, "knife", held);
	}

	/**
	 * Right-clicked gun wrap: it goes on its gun's slot, swapping like armor (a gun wrap is one gun's skin). A wrap from
	 * before the split (several guns) goes on the gun in the other hand if it fits; otherwise the loadout opens.
	 */
	public static void equipGunWrap(ServerPlayer p, ItemStack held) {
		SkinWraps.Wrap w = SkinWraps.get(SkinWrapItem.skin(held));
		if (w == null) return;
		String gun = w.kits().size() == 1 ? w.kits().keySet().iterator().next() : null;
		if (gun == null)
			for (InteractionHand h : InteractionHand.values())
				if (p.getItemInHand(h).getItem() instanceof GunItem g && w.kits().containsKey(g.weaponClass)) gun = g.weaponClass;
		if (gun == null) {
			open(p);
			return;
		}
		equip(p, gun, held);
	}

	/**
	 * Puts one of the held item into a loadout slot, swapping like armor: the item that was there goes into your hand
	 * (or the inventory when more are held).
	 */
	private static void equip(ServerPlayer p, String slot, ItemStack held) {
		ItemStack old = get(p, slot);
		set(p, slot, held.copyWithCount(1));
		if (held.getCount() == 1 && !old.isEmpty()) {
			for (InteractionHand h : InteractionHand.values())
				if (p.getItemInHand(h) == held) {
					p.setItemInHand(h, old);
					return;
				}
		}
		held.shrink(1);
		if (!old.isEmpty() && !p.getInventory().add(old)) p.drop(old, false);
	}

	/** Sneak + right-click with gloves: take off the worn ones (back to your side's default gloves). */
	public static void takeOffGloves(ServerPlayer p) {
		ItemStack old = get(p, "gloves");
		if (old.isEmpty()) return;
		set(p, "gloves", ItemStack.EMPTY);
		if (!p.getInventory().add(old)) p.drop(old, false);
	}

	/** On death: drop the loadout unless the server keeps it (strafemc_loadout_keep) or keepInventory is on. */
	public static void onDeath(ServerPlayer p) {
		if (dev.strafemc.server.StrafeSettings.loadoutKeep() || keepInventory(p)) return;
		Map<String, ItemStack> m = p.getAttachedOrElse(DATA, Map.of());
		if (m.isEmpty()) return;
		for (ItemStack s : m.values()) p.drop(s.copy(), true, false);
		p.setAttached(DATA, Map.of());
	}

	private static boolean keepInventory(ServerPlayer p) {
		return p.level().getGameRules().get(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY);
	}

	/** The loadout screen's menu: the loadout slots, then the player's inventory (27 + 9 hotbar). */
	public static final class Menu extends AbstractContainerMenu {
		/** Layout (also used by the screen): panel size, loadout tiles in two columns, the inventory below. */
		/**
		 * Layout (also used by the screen): four tiles two by two (gloves and knife, then the T and CT agents; the agents are the
		 * client's settings, not items), the gun slots below with a row
		 * per group (pistols, SMGs, rifles, heavy), the inventory under that.
		 */
		/** Gun rows: one per group, plus one for guns in none. */
		public static final int ROWS = WeaponTable.GROUPS.size() + (WeaponTable.guns().stream().anyMatch(e -> WeaponTable.group(e.cls()) >= WeaponTable.GROUPS.size()) ? 1 : 0);
		public static final int WIDTH = 320, TILE_W = 149, TILE_H = 24, TILES_X = 8, TILES_Y = 22, GUNS_X = 79, GUNS_Y = TILES_Y + 2 * (TILE_H + 4) + 20, INV_X = 79;
		public static final int INV_Y = GUNS_Y + 18 * ROWS + 22, HEIGHT = INV_Y + 84;

		/** The graffiti ring, left of the gun grid: its centre and radius. */
		public static final int RING_X = 39, RING_Y = GUNS_Y + 34, RING_R = 23;

		/** Which graffiti slot loadout slot i is (0-5), or -1. */
		public static int spray(int i) {
			int first = 3 + WeaponTable.guns().size();
			return i >= first && i < first + SPRAYS ? i - first : -1;
		}

		/** Top-left of loadout slot i (the item, inside its tile for the first three; the graffiti in a ring). */
		public static int slotX(int i) {
			int sp = spray(i);
			if (sp >= 0) return RING_X - 8 + (int) Math.round(Math.cos(-Math.PI / 2 + sp * 2 * Math.PI / SPRAYS) * RING_R);
			if (i == 1) return -10000; // the agent slot holds nothing: the agent tiles open the agent menu
			return i < 3 ? tileX(i == 0 ? 0 : 3) + 4 : GUNS_X + gunCell(i)[1] * 18;
		}

		public static int slotY(int i) {
			int sp = spray(i);
			if (sp >= 0) return RING_Y - 8 + (int) Math.round(Math.sin(-Math.PI / 2 + sp * 2 * Math.PI / SPRAYS) * RING_R);
			return i < 3 ? tileY(i == 0 ? 0 : 3) + 4 : GUNS_Y + gunCell(i)[0] * 18;
		}

		/** Left of tile col (0 gloves, 1 T agent, 2 CT agent, 3 knife), two by two: gloves and knife on top, the agents below. */
		public static int tileX(int col) {
			return TILES_X + (col == 2 || col == 3 ? TILE_W + 6 : 0);
		}

		/** Top of tile col (as tileX). */
		public static int tileY(int col) {
			return TILES_Y + (col == 1 || col == 2 ? TILE_H + 4 : 0);
		}

		/** Gun slot i's row (its group) and column (its place in the group). */
		static int[] gunCell(int i) {
			String cls = defs().get(i).weapon();
			int g = WeaponTable.group(cls), col = 0;
			for (int k = 3; k < i; k++) if (defs().get(k).kind() == Kind.GUN && WeaponTable.group(defs().get(k).weapon()) == g) col++;
			return new int[] {Math.min(g, ROWS - 1), col};
		}

		private final Container loadout;
		private final ServerPlayer owner;

		/** Client side: an empty container the server's slot updates fill. */
		public Menu(int id, Inventory inv) {
			this(id, inv, null);
		}

		Menu(int id, Inventory inv, ServerPlayer owner) {
			super(MENU, id);
			this.owner = owner;
			List<Def> d = defs();
			boolean[] filled = {false};
			SimpleContainer c = new SimpleContainer(d.size()) {
				@Override
				public void setChanged() { // every change goes straight into the player's (synced) loadout
					super.setChanged();
					// not while it's being filled from the loadout: half-filled, it would save empty slots over the rest
					if (owner != null && filled[0]) for (int i = 0; i < d.size(); i++) set(owner, d.get(i).id, getItem(i));
				}
			};
			if (owner != null) for (int i = 0; i < d.size(); i++) c.setItem(i, get(owner, d.get(i).id).copy());
			filled[0] = true;
			loadout = c;
			for (int i = 0; i < d.size(); i++) {
				Def def = d.get(i);
				int x = slotX(i), y = slotY(i);
				addSlot(new Slot(c, i, x, y) {
					@Override
					public boolean mayPlace(ItemStack s) {
						return accepts(def, s);
					}

					@Override
					public int getMaxStackSize() {
						return 1;
					}
				});
			}
			for (int r = 0; r < 3; r++)
				for (int col = 0; col < 9; col++) addSlot(new Slot(inv, 9 + r * 9 + col, INV_X + col * 18, INV_Y + r * 18));
			for (int col = 0; col < 9; col++) addSlot(new Slot(inv, col, INV_X + col * 18, INV_Y + 58));
		}

		public Def def(int slotIndex) {
			return slotIndex < defs().size() ? defs().get(slotIndex) : null;
		}

		@Override
		public ItemStack quickMoveStack(Player player, int index) {
			int n = defs().size();
			Slot from = index >= 0 && index < this.slots.size() ? this.slots.get(index) : null;
			if (from == null || !from.hasItem()) return ItemStack.EMPTY;
			ItemStack stack = from.getItem(), before = stack.copy();
			if (index < n) { // loadout -> inventory
				if (!moveItemStackTo(stack, n, this.slots.size(), true)) return ItemStack.EMPTY;
			} else { // inventory -> the first loadout slot that takes it
				boolean moved = false;
				for (int i = 0; i < n && !moved; i++) {
					Slot s = this.slots.get(i);
					if (!s.hasItem() && s.mayPlace(stack)) {
						s.setByPlayer(stack.split(1));
						moved = true;
					}
				}
				if (!moved) return ItemStack.EMPTY;
			}
			if (stack.isEmpty()) from.setByPlayer(ItemStack.EMPTY);
			else from.setChanged();
			return before;
		}

		@Override
		public boolean stillValid(Player player) {
			return owner == null || !owner.isRemoved();
		}
	}
}
