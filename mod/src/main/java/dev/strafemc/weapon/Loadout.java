package dev.strafemc.weapon;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
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
	public enum Kind {GLOVES, AGENT, KNIFE, GUN}

	public record Def(String id, Kind kind, String weapon, String label) {}

	private static List<Def> defs;

	/** The slots, in screen order: gloves, agent, knife, then every gun strafeMC has (registry order). */
	public static List<Def> defs() {
		if (defs == null) {
			List<Def> d = new ArrayList<>();
			d.add(new Def("gloves", Kind.GLOVES, null, "Gloves"));
			d.add(new Def("agent", Kind.AGENT, null, "Agent"));
			d.add(new Def("knife", Kind.KNIFE, null, "Knife"));
			for (Item item : BuiltInRegistries.ITEM)
				if (item instanceof GunItem g && !(item instanceof KnifeItem) && !(item instanceof GrenadeItem) && !(item instanceof HealthshotItem))
					d.add(new Def(g.weaponClass, Kind.GUN, g.weaponClass, item.getName(new ItemStack(item)).getString()));
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
	}

	public static boolean accepts(Def d, ItemStack s) {
		return switch (d.kind) {
			case GLOVES -> s.getItem() instanceof GloveItem;
			case AGENT -> false;
			case KNIFE -> s.getItem() instanceof SkinWrapItem w && w.knife() && SkinWraps.get(SkinWrapItem.skin(s)) != null;
			case GUN -> s.getItem() instanceof SkinWrapItem w && !w.knife() && SkinWraps.fits(SkinWrapItem.skin(s), d.weapon);
		};
	}

	public static void open(ServerPlayer p) {
		p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new Menu(id, inv, (ServerPlayer) pl), Component.literal("Loadout")));
	}

	/** Right-clicked gloves: wear them (they go into the gloves slot, the ones worn before come back). */
	public static void wearGloves(ServerPlayer p, ItemStack held) {
		ItemStack old = get(p, "gloves");
		set(p, "gloves", held.copyWithCount(1));
		held.shrink(1);
		if (!old.isEmpty() && !p.getInventory().add(old)) p.drop(old, false);
		p.sendSystemMessage(Component.literal("Now wearing: ").append(get(p, "gloves").getHoverName()));
	}

	/** Sneak + right-click with gloves: take off the worn ones (back to your side's default gloves). */
	public static void takeOffGloves(ServerPlayer p) {
		ItemStack old = get(p, "gloves");
		if (old.isEmpty()) return;
		set(p, "gloves", ItemStack.EMPTY);
		if (!p.getInventory().add(old)) p.drop(old, false);
		p.sendSystemMessage(Component.literal("Now wearing your side's default gloves"));
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
		public static final int WIDTH = 320, HEIGHT = 248, TILE_W = 148, TILE_H = 24, TILES_X = 8, TILES_Y = 22, INV_X = 79, INV_Y = 160;

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
			SimpleContainer c = new SimpleContainer(d.size()) {
				@Override
				public void setChanged() { // every change goes straight into the player's (synced) loadout
					super.setChanged();
					if (owner != null) for (int i = 0; i < d.size(); i++) set(owner, d.get(i).id, getItem(i));
				}
			};
			if (owner != null) for (int i = 0; i < d.size(); i++) c.setItem(i, get(owner, d.get(i).id).copy());
			loadout = c;
			for (int i = 0; i < d.size(); i++) {
				Def def = d.get(i);
				int x = TILES_X + (i % 2) * (TILE_W + 8) + 4, y = TILES_Y + (i / 2) * (TILE_H + 4) + 4;
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
