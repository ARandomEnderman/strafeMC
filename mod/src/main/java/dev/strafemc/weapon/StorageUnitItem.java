package dev.strafemc.weapon;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * CS2's Storage Unit (tools/casket): holds up to 1,000 strafeMC collectibles (the strafemc:storable tag: guns, knives,
 * wraps, gloves, cases, keys; later sprays, stickers, capsules). Right-click it in the main hand to open a scrollable
 * six-row view; shift-click from the inventory files an item into the first free place in the whole unit, and so
 * does a left click with the unit onto an item (or the item onto the unit), like the mag pouch.
 */
public class StorageUnitItem extends Item {
	/** CS2: "store up to 1,000 of your surplus items". */
	public static final int CAPACITY = 1000, COLS = 9, ROWS = 6, PAGE = COLS * ROWS, TOTAL_ROWS = (CAPACITY + COLS - 1) / COLS;
	public static final TagKey<Item> STORABLE = TagKey.create(Registries.ITEM, WeaponItems.id("storable"));
	/** The contents, by place (empty places kept, trailing ones dropped). */
	public static DataComponentType<List<ItemStack>> CONTENTS;
	public static MenuType<Menu> MENU;

	public StorageUnitItem(Properties p) {
		super(p);
	}

	public static void register() {
		CONTENTS = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, WeaponItems.id("storage"), DataComponentType.<List<ItemStack>>builder()
				.persistent(ItemStack.OPTIONAL_CODEC.listOf()).networkSynchronized(ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list())).build());
		MENU = Registry.register(BuiltInRegistries.MENU, WeaponItems.id("storage_unit"),
				new MenuType<>((id, inv) -> new Menu(id, inv, null, inv.getSelectedSlot()), FeatureFlags.VANILLA_SET));
	}

	public static boolean storable(ItemStack s) {
		return !s.isEmpty() && s.is(STORABLE);
	}

	/** The unit's places, CAPACITY long. */
	static List<ItemStack> contents(ItemStack unit) {
		List<ItemStack> list = new ArrayList<>(unit.getOrDefault(CONTENTS, List.<ItemStack>of()));
		for (int i = 0; i < list.size(); i++) list.set(i, list.get(i).copy());
		while (list.size() < CAPACITY) list.add(ItemStack.EMPTY);
		return list;
	}

	static void store(ItemStack unit, List<ItemStack> list) {
		int end = list.size();
		while (end > 0 && list.get(end - 1).isEmpty()) end--;
		List<ItemStack> out = new ArrayList<>(end);
		for (int i = 0; i < end; i++) out.add(list.get(i).copy());
		if (out.isEmpty()) unit.remove(CONTENTS);
		else unit.set(CONTENTS, List.copyOf(out));
	}

	public static int count(ItemStack unit) {
		int n = 0;
		for (ItemStack s : unit.getOrDefault(CONTENTS, List.<ItemStack>of())) if (!s.isEmpty()) n++;
		return n;
	}

	/** Files a stack into the unit (onto the same item first, then the first free places); true if any went in. */
	private static boolean take(ItemStack unit, ItemStack s) {
		if (!storable(s)) return false;
		int before = s.getCount();
		List<ItemStack> all = contents(unit);
		for (int i = 0; i < CAPACITY && !s.isEmpty(); i++) {
			ItemStack in = all.get(i);
			if (!in.isEmpty() && ItemStack.isSameItemSameComponents(in, s) && in.getCount() < in.getMaxStackSize()) {
				int n = Math.min(s.getCount(), in.getMaxStackSize() - in.getCount());
				in.grow(n);
				s.shrink(n);
			}
		}
		for (int i = 0; i < CAPACITY && !s.isEmpty(); i++) if (all.get(i).isEmpty()) all.set(i, s.copyAndClear());
		if (s.getCount() == before) return false;
		store(unit, all);
		return true;
	}

	private static void sound(Player p, boolean in) {
		p.playSound(in ? net.minecraft.sounds.SoundEvents.BUNDLE_INSERT : net.minecraft.sounds.SoundEvents.BUNDLE_INSERT_FAIL, 0.8f,
				0.8f + p.level().getRandom().nextFloat() * 0.4f);
	}

	/** Like the mag pouch: the unit on the cursor left-clicked onto a storable item takes it in. */
	@Override
	public boolean overrideStackedOnOther(ItemStack unit, Slot slot, net.minecraft.world.inventory.ClickAction action, Player p) {
		if (action != net.minecraft.world.inventory.ClickAction.PRIMARY || unit.getCount() != 1) return false;
		ItemStack in = slot.getItem();
		if (!storable(in) || !slot.mayPickup(p)) return false;
		ItemStack moving = in.copy();
		boolean any = take(unit, moving);
		if (any) slot.set(moving);
		sound(p, any);
		return true;
	}

	/** A storable item on the cursor left-clicked onto the unit goes in (what doesn't fit stays held). */
	@Override
	public boolean overrideOtherStackedOnMe(ItemStack unit, ItemStack other, Slot slot, net.minecraft.world.inventory.ClickAction action, Player p,
											net.minecraft.world.entity.SlotAccess access) {
		if (action != net.minecraft.world.inventory.ClickAction.PRIMARY || unit.getCount() != 1 || !storable(other) || !slot.allowModification(p)) return false;
		sound(p, take(unit, other));
		return true;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS; // the open unit is locked in the held slot
		ItemStack unit = player.getItemInHand(hand);
		if (player instanceof ServerPlayer p) {
			int slot = p.getInventory().getSelectedSlot();
			p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new Menu(id, inv, unit, slot), unit.getHoverName()));
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
		out.accept(Component.literal("§7Items: " + count(stack) + " / " + CAPACITY));
	}

	/**
	 * The open unit: PAGE view slots onto the places from row * COLS (the row is a data slot, set by the screen's scroll
	 * through clickMenuButton), then the player's inventory with the held unit locked.
	 */
	public static final class Menu extends AbstractContainerMenu {
		private final ItemStack unit;
		private final List<ItemStack> all;
		private final SimpleContainer view;
		private final DataSlot row = DataSlot.standalone(), stored = DataSlot.standalone();
		final int locked;
		private boolean loading;

		Menu(int id, Inventory inv, ItemStack unit, int locked) {
			super(MENU, id);
			this.unit = unit;
			this.locked = locked;
			this.all = unit == null ? null : contents(unit);
			this.view = new SimpleContainer(PAGE) {
				@Override
				public void setChanged() {
					super.setChanged();
					if (Menu.this.unit != null && !loading) writeBack();
				}
			};
			addDataSlot(row);
			addDataSlot(stored);
			for (int r = 0; r < ROWS; r++)
				for (int c = 0; c < COLS; c++) {
					int i = r * COLS + c;
					addSlot(new Slot(view, i, 8 + c * 18, 18 + r * 18) {
						@Override
						public boolean mayPlace(ItemStack s) {
							return storable(s) && row.get() * COLS + getContainerSlot() < CAPACITY;
						}
					});
				}
			int invY = 18 + ROWS * 18 + 14;
			for (int r = 0; r < 3; r++)
				for (int c = 0; c < 9; c++) addSlot(lockable(inv, 9 + r * 9 + c, 8 + c * 18, invY + r * 18));
			for (int c = 0; c < 9; c++) addSlot(lockable(inv, c, 8 + c * 18, invY + 58));
			load();
		}

		private Slot lockable(Inventory inv, int index, int x, int y) {
			if (index != locked) return new Slot(inv, index, x, y);
			return new Slot(inv, index, x, y) {
				@Override
				public boolean mayPickup(Player p) {
					return false;
				}

				@Override
				public boolean mayPlace(ItemStack s) {
					return false;
				}
			};
		}

		public int row() {
			return row.get();
		}

		public int stored() {
			return stored.get();
		}

		/** The view from the unit's places at the current row (server). */
		private void load() {
			if (all == null) return;
			loading = true;
			for (int i = 0; i < PAGE; i++) {
				int at = row.get() * COLS + i;
				view.setItem(i, at < CAPACITY ? all.get(at).copy() : ItemStack.EMPTY);
			}
			loading = false;
			count();
		}

		/** The view's slots back into the places, and the places onto the unit item (server). */
		private void writeBack() {
			for (int i = 0; i < PAGE; i++) {
				int at = row.get() * COLS + i;
				if (at < CAPACITY) all.set(at, view.getItem(i).copy());
			}
			store(unit, all);
			count();
		}

		private void count() {
			int n = 0;
			for (ItemStack s : all) if (!s.isEmpty()) n++;
			stored.set(n);
		}

		/** The screen scrolled: the view moves to this row. */
		@Override
		public boolean clickMenuButton(Player p, int id) {
			if (all == null) return false;
			int r = Math.max(0, Math.min(TOTAL_ROWS - ROWS, id));
			if (r == row.get()) return true;
			writeBack();
			row.set(r);
			load();
			broadcastChanges();
			return true;
		}

		@Override
		public ItemStack quickMoveStack(Player player, int index) {
			Slot from = index >= 0 && index < slots.size() ? slots.get(index) : null;
			if (from == null || !from.hasItem()) return ItemStack.EMPTY;
			ItemStack stack = from.getItem(), before = stack.copy();
			if (index < PAGE) { // out of the unit
				if (!moveItemStackTo(stack, PAGE, slots.size(), true)) return ItemStack.EMPTY;
			} else { // into the unit: the first place anywhere in it (stacking onto the same item first)
				if (all == null || !storable(stack)) return ItemStack.EMPTY;
				for (int i = 0; i < CAPACITY && !stack.isEmpty(); i++) {
					ItemStack s = all.get(i);
					if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, stack) && s.getCount() < s.getMaxStackSize()) {
						int n = Math.min(stack.getCount(), s.getMaxStackSize() - s.getCount());
						s.grow(n);
						stack.shrink(n);
					}
				}
				for (int i = 0; i < CAPACITY && !stack.isEmpty(); i++)
					if (all.get(i).isEmpty()) all.set(i, stack.copyAndClear());
				if (stack.getCount() == before.getCount()) return ItemStack.EMPTY; // full
				store(unit, all);
				load();
			}
			if (stack.isEmpty()) from.setByPlayer(ItemStack.EMPTY);
			else from.setChanged();
			return ItemStack.EMPTY; // one move per click (the unit's places aren't slots to repeat into)
		}

		@Override
		public void clicked(int slot, int button, ContainerInput input, Player p) {
			// number keys / offhand swap onto or off the held unit would move it while it's open
			if (input == ContainerInput.SWAP && (button == locked || slot >= 0 && slot < slots.size() && slots.get(slot).container == p.getInventory()
					&& slots.get(slot).getContainerSlot() == locked)) return;
			super.clicked(slot, button, input, p);
		}

		@Override
		public boolean stillValid(Player p) {
			return unit == null || p.getInventory().getItem(locked) == unit;
		}
	}
}
