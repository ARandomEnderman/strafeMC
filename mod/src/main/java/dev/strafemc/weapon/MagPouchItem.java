package dev.strafemc.weapon;

import net.minecraft.core.NonNullList;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;

/**
 * A mag pouch: a shulker-box-like bag for magazines and shells only (full or empty), opened by right-clicking it in the
 * main hand, and filled bundle-style with left clicks (it onto magazines or gunpowder, or them onto it). Reloading
 * takes full magazines from it after the inventory's own and puts the emptied one back in it. Its
 * last slot holds gunpowder: a second after an empty magazine is in the pouch, it's refilled with one gunpowder (the
 * crafting refill's cost).
 */
public class MagPouchItem extends Item {
	public static final int SIZE = 27, POWDER_SLOT = SIZE - 1;
	/** Ticks from an empty magazine being in the pouch to its refill (1 s). */
	private static final int REFILL_DELAY = 20;
	public static MenuType<ChestMenu> MENU;

	public MagPouchItem(Properties p) {
		super(p);
	}

	public static void register() {
		MENU = Registry.register(BuiltInRegistries.MENU, WeaponItems.id("mag_pouch"),
				new MenuType<>((id, inv) -> new Menu(id, inv, new Bag(null), inv.getSelectedSlot()), FeatureFlags.VANILLA_SET));
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS; // the open pouch is locked in the held slot
		ItemStack pouch = player.getItemInHand(hand);
		if (player instanceof ServerPlayer p) {
			int slot = p.getInventory().getSelectedSlot();
			p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new Menu(id, inv, new Bag(pouch), slot), pouch.getHoverName()));
		}
		return InteractionResult.SUCCESS;
	}

	/** What a pouch takes in: magazines (and shells), and gunpowder for its powder slot. */
	private static boolean fits(ItemStack s) {
		return !s.isEmpty() && (s.getItem() instanceof MagazineItem || s.is(net.minecraft.world.item.Items.GUNPOWDER));
	}

	/** Puts as much of the stack as fits into the pouch (gunpowder into its slot); true if any went in. */
	private static boolean take(ItemStack pouch, ItemStack s) {
		int before = s.getCount();
		NonNullList<ItemStack> list = contents(pouch);
		if (s.is(net.minecraft.world.item.Items.GUNPOWDER)) {
			ItemStack powder = list.get(POWDER_SLOT);
			if (powder.isEmpty()) list.set(POWDER_SLOT, s.copyAndClear());
			else if (powder.is(net.minecraft.world.item.Items.GUNPOWDER)) {
				int n = Math.min(s.getCount(), powder.getMaxStackSize() - powder.getCount());
				powder.grow(n);
				s.shrink(n);
			}
		} else insert(list, s);
		if (s.getCount() == before) return false;
		store(pouch, list);
		return true;
	}

	private static void sound(Player p, boolean in) {
		p.playSound(in ? net.minecraft.sounds.SoundEvents.BUNDLE_INSERT : net.minecraft.sounds.SoundEvents.BUNDLE_INSERT_FAIL, 0.8f,
				0.8f + p.level().getRandom().nextFloat() * 0.4f);
	}

	/** Like a bundle: the pouch on the cursor left-clicked onto magazines or gunpowder takes in as much as fits. */
	@Override
	public boolean overrideStackedOnOther(ItemStack pouch, Slot slot, net.minecraft.world.inventory.ClickAction action, Player p) {
		if (action != net.minecraft.world.inventory.ClickAction.PRIMARY || pouch.getCount() != 1) return false;
		ItemStack in = slot.getItem();
		if (!fits(in) || !slot.mayPickup(p)) return false;
		ItemStack moving = in.copy();
		boolean any = take(pouch, moving);
		if (any) slot.set(moving);
		sound(p, any);
		return true;
	}

	/** Like a bundle: magazines or gunpowder on the cursor left-clicked onto the pouch go in (what doesn't fit stays held). */
	@Override
	public boolean overrideOtherStackedOnMe(ItemStack pouch, ItemStack other, Slot slot, net.minecraft.world.inventory.ClickAction action, Player p,
											net.minecraft.world.entity.SlotAccess access) {
		if (action != net.minecraft.world.inventory.ClickAction.PRIMARY || pouch.getCount() != 1 || !fits(other) || !slot.allowModification(p)) return false;
		sound(p, take(pouch, other));
		return true;
	}

	/** A pouch's contents, SIZE slots long. */
	public static NonNullList<ItemStack> contents(ItemStack pouch) {
		NonNullList<ItemStack> list = NonNullList.withSize(SIZE, ItemStack.EMPTY);
		pouch.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(list);
		return list;
	}

	public static void store(ItemStack pouch, NonNullList<ItemStack> list) {
		pouch.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(list));
	}

	/** Every stack in the inventory and in its mag pouches (the pouches' are copies: for counting). */
	public static java.util.List<ItemStack> carried(Inventory inv) {
		java.util.List<ItemStack> out = new java.util.ArrayList<>();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			out.add(s);
			if (s.getItem() instanceof MagPouchItem) out.addAll(contents(s));
			// a round mode's gun carries its spare magazines itself: counted as that many full ones
			if (s.getItem() instanceof GunItem g && s.getOrDefault(WeaponItems.SPARE, 0) > 0 && WeaponItems.MAGS.get(g.weaponClass) != null)
				out.add(new ItemStack(WeaponItems.MAGS.get(g.weaponClass), s.getOrDefault(WeaponItems.SPARE, 0)));
		}
		return out;
	}

	/** True when this pouch is open in the player's screen: its menu owns the contents until it closes. */
	static boolean open(Player p, ItemStack pouch) {
		return p.containerMenu instanceof Menu m && m.bag.pouch == pouch;
	}

	/** Puts a magazine into the first pouch in the inventory with room for it; false if none has. */
	static boolean stow(Player p, ItemStack mag) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack pouch = inv.getItem(i);
			if (!(pouch.getItem() instanceof MagPouchItem) || open(p, pouch)) continue;
			NonNullList<ItemStack> list = contents(pouch);
			insert(list, mag);
			store(pouch, list);
			if (mag.isEmpty()) return true;
		}
		return false;
	}

	/** Into the magazine slots: onto matching stacks first, then empty slots; whatever doesn't fit stays in mag. */
	private static void insert(NonNullList<ItemStack> list, ItemStack mag) {
		for (int j = 0; j < POWDER_SLOT && !mag.isEmpty(); j++) {
			ItemStack s = list.get(j);
			if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, mag) && s.getCount() < s.getMaxStackSize()) {
				int n = Math.min(mag.getCount(), s.getMaxStackSize() - s.getCount());
				s.grow(n);
				mag.shrink(n);
			}
		}
		for (int j = 0; j < POWDER_SLOT && !mag.isEmpty(); j++)
			if (list.get(j).isEmpty()) list.set(j, mag.copyAndClear());
	}

	private static boolean refillable(ItemStack s) {
		return s.getItem() instanceof MagazineItem m && !m.shells() && m.empty(s);
	}

	/** The gunpowder refill: armed when an empty magazine and gunpowder are both in, done REFILL_DELAY ticks later. */
	@Override
	public void inventoryTick(ItemStack pouch, net.minecraft.server.level.ServerLevel level, net.minecraft.world.entity.Entity e,
							  net.minecraft.world.entity.EquipmentSlot slot) {
		if (!(e instanceof Player p) || open(p, pouch)) return;
		NonNullList<ItemStack> list = contents(pouch);
		ItemStack powder = list.get(POWDER_SLOT);
		boolean empties = false;
		for (int j = 0; j < POWDER_SLOT && !empties; j++) empties = refillable(list.get(j));
		Integer at = pouch.get(WeaponItems.REFILL_AT);
		int now = (int) level.getGameTime();
		if (!empties || !powder.is(net.minecraft.world.item.Items.GUNPOWDER)) {
			if (at != null) pouch.remove(WeaponItems.REFILL_AT);
			return;
		}
		if (at == null || at > now + REFILL_DELAY) { // armed now (or a stale time from another world)
			pouch.set(WeaponItems.REFILL_AT, now + REFILL_DELAY);
			return;
		}
		if (now < at) return;
		java.util.List<ItemStack> filled = new java.util.ArrayList<>();
		for (int j = 0; j < POWDER_SLOT && !powder.isEmpty(); j++) {
			ItemStack s = list.get(j);
			if (!refillable(s)) continue;
			int n = Math.min(s.getCount(), powder.getCount());
			ItemStack full = MagazineItem.withRounds(new ItemStack(s.getItem()), ((MagazineItem) s.getItem()).capacity());
			full.setCount(n);
			powder.shrink(n);
			s.shrink(n);
			filled.add(full); // onto the full ones already in the pouch, not left in the empty one's slot
		}
		for (ItemStack f : filled) {
			insert(list, f);
			if (!f.isEmpty() && !p.getInventory().add(f)) p.drop(f, false);
		}
		store(pouch, list);
		pouch.remove(WeaponItems.REFILL_AT);
	}

	/** The pouch's slots, written back to the pouch item on every change (server side; the client's has no item). */
	static final class Bag extends SimpleContainer {
		final ItemStack pouch;

		Bag(ItemStack pouch) {
			super(SIZE);
			this.pouch = pouch;
			if (pouch != null) {
				NonNullList<ItemStack> list = contents(pouch);
				for (int i = 0; i < SIZE; i++) super.setItem(i, list.get(i));
			}
		}

		@Override
		public void setChanged() {
			super.setChanged();
			if (pouch == null) return;
			NonNullList<ItemStack> list = NonNullList.withSize(SIZE, ItemStack.EMPTY);
			for (int i = 0; i < SIZE; i++) list.set(i, getItem(i).copy());
			store(pouch, list);
		}
	}

	/** A chest-sized menu whose slots take magazines only, with the held pouch locked in place while it's open. */
	static final class Menu extends ChestMenu {
		final Bag bag;
		final int locked;

		Menu(int id, Inventory inv, Bag bag, int locked) {
			super(MENU, id, inv, bag, 3);
			this.bag = bag;
			this.locked = locked;
			for (int i = 0; i < slots.size(); i++) {
				Slot s = slots.get(i), r;
				if (s.container == bag) r = new Slot(bag, s.getContainerSlot(), s.x, s.y) {
					@Override
					public boolean mayPlace(ItemStack st) {
						return getContainerSlot() == POWDER_SLOT ? st.is(net.minecraft.world.item.Items.GUNPOWDER) : st.getItem() instanceof MagazineItem;
					}

					/** The empty slot's outline, like the armor slots': the game's own powder one, our magazine (RuntimePack). */
					@Override
					public net.minecraft.resources.Identifier getNoItemIcon() {
						return getContainerSlot() == POWDER_SLOT ? net.minecraft.resources.Identifier.withDefaultNamespace("container/slot/redstone_dust")
								: WeaponItems.id("container/slot/mag");
					}
				};
				else if (s.container == inv && s.getContainerSlot() == locked) r = new Slot(inv, locked, s.x, s.y) {
					@Override
					public boolean mayPickup(Player p) {
						return false;
					}

					@Override
					public boolean mayPlace(ItemStack st) {
						return false;
					}
				};
				else continue;
				r.index = i;
				slots.set(i, r);
			}
		}

		@Override
		public void clicked(int slot, int button, ContainerInput input, Player p) {
			// number keys / offhand swap onto or off the held pouch would move it while it's open
			if (input == ContainerInput.SWAP && (button == locked || slot >= 0 && slot < slots.size() && slots.get(slot).container == p.getInventory()
					&& slots.get(slot).getContainerSlot() == locked)) return;
			super.clicked(slot, button, input, p);
		}

		@Override
		public boolean stillValid(Player p) {
			return bag.pouch == null || p.getInventory().getItem(locked) == bag.pouch;
		}
	}
}
