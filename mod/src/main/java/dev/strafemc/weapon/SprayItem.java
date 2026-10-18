package dev.strafemc.weapon;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * CS2's graffiti: new ones are sealed (CS2's "Sealed Graffiti": they have a worth, so they can be sold, upgraded and
 * stored); right-click opens a small screen to unseal it. Unsealed, right-click a block to spray it on that face for
 * everyone to see (never used up), and it has no worth any more. Which graffiti is the custom model data string;
 * standard ones carry a tint (WeaponItems.TINT).
 */
public class SprayItem extends Item {
	/** Seconds between sprays. FLAGGED ours (CS2's cooldown isn't in its files). */
	private static final long COOLDOWN_MS = 4000;
	/** Sprays an unsealed graffiti has (CS:GO's 50 charges, asked for). */
	public static final int USES = 50;
	private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();

	public SprayItem(Properties p) {
		super(p);
	}

	public static ItemStack stack(Item item, String spray, int tint) {
		ItemStack s = new ItemStack(item);
		s.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(spray), List.of()));
		Sprays.Spray sp = Sprays.get(spray);
		if (sp != null && sp.tintable() && tint > 0) s.set(WeaponItems.TINT, tint);
		return s;
	}

	public static String spray(ItemStack s) {
		CustomModelData d = s.get(DataComponents.CUSTOM_MODEL_DATA);
		return d == null ? null : d.getString(0);
	}

	public static boolean sealed(ItemStack s) {
		return !s.has(WeaponItems.OPENED);
	}

	/** Unseals the held graffiti (server; the unseal screen). */
	public static void unseal(ServerPlayer p) {
		for (var hand : net.minecraft.world.InteractionHand.values()) {
			ItemStack s = p.getItemInHand(hand);
			if (s.getItem() instanceof SprayItem && sealed(s)) {
				if (s.getCount() > 1) { // one off a stack
					ItemStack one = s.split(1);
					one.set(WeaponItems.OPENED, USES);
					if (!p.getInventory().add(one)) p.drop(one, false);
				} else s.set(WeaponItems.OPENED, USES);
				return;
			}
		}
	}

	public static int tint(ItemStack s) {
		Integer t = s.get(WeaponItems.TINT);
		return t == null ? 0 : t;
	}

	@Override
	public Component getName(ItemStack stack) {
		Sprays.Spray sp = Sprays.get(spray(stack));
		if (sp == null) return super.getName(stack);
		Sprays.Tint t = Sprays.tint(tint(stack));
		return Component.literal((sealed(stack) ? "Sealed Graffiti | " : "Graffiti | ") + sp.name() + (sp.tintable() && t != null ? " (" + t.label() + ")" : "")).withColor(sp.rarity().color);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
		Sprays.Spray sp = Sprays.get(spray(stack));
		if (sp != null) out.accept(Component.literal("§7" + sp.rarity().weaponName.replace(" Grade", "") + " Graffiti"));
		if (!sealed(stack)) out.accept(Component.literal("§7" + stack.get(WeaponItems.OPENED) + " uses left"));
		double worth = Worth.of(stack);
		if (worth >= 0) out.accept(Component.literal("§7Worth: §a" + Worth.format(worth)));
	}

	/** Sealed: right-click (in the air or on a block) opens the unseal screen. */
	@Override
	public InteractionResult use(net.minecraft.world.level.Level level, net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand) {
		if (sealed(player.getItemInHand(hand))) {
			if (level.isClientSide()) dev.strafemc.StrafeMc.openUnseal.run();
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	@Override
	public InteractionResult useOn(UseOnContext c) {
		ItemStack s = c.getItemInHand();
		if (sealed(s)) {
			if (c.getLevel().isClientSide()) dev.strafemc.StrafeMc.openUnseal.run();
			return InteractionResult.SUCCESS;
		}
		if (!(c.getPlayer() instanceof ServerPlayer p)) return InteractionResult.SUCCESS;
		if (!spray(p, s, c.getClickLocation(), c.getClickedFace(), c.getClickedPos())) return InteractionResult.FAIL;
		int left = s.getOrDefault(WeaponItems.OPENED, USES) - 1; // a spray used up; the last one takes the graffiti
		if (left <= 0) s.shrink(1);
		else s.set(WeaponItems.OPENED, left);
		return InteractionResult.SUCCESS;
	}

	/** Sprays this graffiti on a block face for everyone (with the can's sound), unless it's too soon after the last. True if sprayed. */
	static boolean spray(ServerPlayer p, ItemStack s, net.minecraft.world.phys.Vec3 hit, net.minecraft.core.Direction face, net.minecraft.core.BlockPos pos) {
		Sprays.Spray sp = Sprays.get(spray(s));
		if (sp == null) return false;
		long now = System.currentTimeMillis();
		Long last = LAST.get(p.getUUID());
		if (last != null && now - last < COOLDOWN_MS) {
			dev.strafemc.server.Toasts.warn(p, "You can spray again in " + (COOLDOWN_MS - (now - last) + 999) / 1000 + " s");
			return false;
		}
		LAST.put(p.getUUID(), now);
		var msg = new dev.strafemc.net.StrafePayloads.SprayPlaced(sp.id(), tint(s), hit.x, hit.y, hit.z, face.get3DDataValue(), pos.asLong(), p.getYRot(), p.getUUID(), 0);
		SprayLog.add(p.level(), msg); // for players who join later
		for (ServerPlayer o : p.level().players())
			if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(o, dev.strafemc.net.StrafePayloads.SprayPlaced.TYPE))
				net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(o, msg);
		GunServer.soundAt(p.level(), "SprayCan.Paint", hit, p);
		return true;
	}

	/**
	 * The graffiti wheel let go on loadout graffiti n: sprayed on the block you're looking at, within your reach (CS2's
	 * graffiti also go only on what's near); a spray used up, the last one empties the slot.
	 */
	public static void sprayFromLoadout(ServerPlayer p, int n) {
		if (n < 0 || n >= Loadout.SPRAYS) return;
		String slot = Loadout.sprayId(n);
		ItemStack s = Loadout.get(p, slot);
		if (!(s.getItem() instanceof SprayItem) || sealed(s)) return;
		var hit = p.pick(p.blockInteractionRange(), 1f, false);
		if (!(hit instanceof net.minecraft.world.phys.BlockHitResult b) || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
			dev.strafemc.server.Toasts.warn(p, "Too far away to spray");
			return;
		}
		if (!spray(p, s, b.getLocation(), b.getDirection(), b.getBlockPos())) return;
		ItemStack after = s.copy();
		int left = after.getOrDefault(WeaponItems.OPENED, USES) - 1;
		if (left <= 0) after = ItemStack.EMPTY;
		else after.set(WeaponItems.OPENED, left);
		Loadout.set(p, slot, after);
	}
}
