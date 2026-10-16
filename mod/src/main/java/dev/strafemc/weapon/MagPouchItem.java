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
 * main hand. Reloading takes full magazines from it after the inventory's own and puts the emptied one back in it.
 */
public class MagPouchItem extends Item {
	public static final int SIZE = 27;
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
			for (int j = 0; j < SIZE && !mag.isEmpty(); j++) {
				ItemStack s = list.get(j);
				if (s.isEmpty()) {
					list.set(j, mag.copyAndClear());
				} else if (ItemStack.isSameItemSameComponents(s, mag) && s.getCount() < s.getMaxStackSize()) {
					int n = Math.min(mag.getCount(), s.getMaxStackSize() - s.getCount());
					s.grow(n);
					mag.shrink(n);
				}
			}
			store(pouch, list);
			if (mag.isEmpty()) return true;
		}
		return false;
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
						return st.getItem() instanceof MagazineItem;
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
