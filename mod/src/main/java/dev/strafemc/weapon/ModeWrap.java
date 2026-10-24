package dev.strafemc.weapon;

import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Game modes, as CS2: a gun carries the finish of whoever bought it (or was handed it), so a gun you pick up keeps
 * its owner's skin, stickers and charm, and its StatTrak counts only for that owner. Outside game modes a gun wears
 * its holder's loadout as before.
 */
public final class ModeWrap {
	/** The owner's wrap for this gun (empty: no finish). */
	public static DataComponentType<ItemStack> WRAP;
	public static DataComponentType<UUID> OWNER;

	private ModeWrap() {}

	public static void register() {
		WRAP = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, Identifier.fromNamespaceAndPath("strafemc", "mode_wrap"),
				DataComponentType.<ItemStack>builder().persistent(ItemStack.OPTIONAL_CODEC).networkSynchronized(ItemStack.OPTIONAL_STREAM_CODEC).build());
		OWNER = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, Identifier.fromNamespaceAndPath("strafemc", "mode_wrap_owner"),
				DataComponentType.<UUID>builder().persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC).build());
	}

	/** A gun handed to a player in a game mode: it takes their finish for it from now on. */
	public static ItemStack stamp(ItemStack gun, Player owner) {
		if (gun.getItem() instanceof GunItem g) {
			gun.set(WRAP, Loadout.worn(owner, g.weaponClass).copy());
			gun.set(OWNER, owner.getUUID());
		}
		return gun;
	}

	/** The finish a player's held weapon of this slot shows: the held gun's own (game modes), else their loadout's. */
	public static ItemStack wrapOf(Player p, String slot) {
		ItemStack held = p.getMainHandItem();
		if (held.getItem() instanceof GunItem g && g.weaponClass.equals(slot) && held.has(WRAP)) return held.get(WRAP);
		return Loadout.worn(p, slot);
	}

	/** Whether a kill with the held gun of this slot may count on the player's own StatTrak (not on someone else's gun). */
	public static boolean ownGun(Player p, String slot) {
		ItemStack held = p.getMainHandItem();
		return !(held.getItem() instanceof GunItem g && g.weaponClass.equals(slot) && held.has(OWNER)) || p.getUUID().equals(held.get(OWNER));
	}

	/** After a StatTrak kill on the owner's loadout: the held gun shows the new count too. */
	static void refresh(Player p, String slot) {
		ItemStack held = p.getMainHandItem();
		if (held.getItem() instanceof GunItem g && g.weaponClass.equals(slot) && held.has(WRAP)) held.set(WRAP, Loadout.worn(p, slot).copy());
	}
}
