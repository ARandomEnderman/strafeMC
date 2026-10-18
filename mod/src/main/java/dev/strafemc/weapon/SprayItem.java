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
 * CS2's graffiti: right-click a block to spray it on that face, for everyone to see (never used up, as CS2's graffiti
 * no longer are). Which graffiti is the custom model data string; standard ones carry a tint (WeaponItems.TINT).
 */
public class SprayItem extends Item {
	/** Seconds between sprays. FLAGGED ours (CS2's cooldown isn't in its files). */
	private static final long COOLDOWN_MS = 4000;
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

	public static int tint(ItemStack s) {
		Integer t = s.get(WeaponItems.TINT);
		return t == null ? 0 : t;
	}

	@Override
	public Component getName(ItemStack stack) {
		Sprays.Spray sp = Sprays.get(spray(stack));
		if (sp == null) return super.getName(stack);
		Sprays.Tint t = Sprays.tint(tint(stack));
		return Component.literal("Graffiti | " + sp.name() + (sp.tintable() && t != null ? " (" + t.label() + ")" : "")).withColor(sp.rarity().color);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
		Sprays.Spray sp = Sprays.get(spray(stack));
		if (sp != null) out.accept(Component.literal("§7" + sp.rarity().weaponName.replace(" Grade", "") + " Graffiti"));
	}

	@Override
	public InteractionResult useOn(UseOnContext c) {
		if (!(c.getPlayer() instanceof ServerPlayer p)) return InteractionResult.SUCCESS;
		ItemStack s = c.getItemInHand();
		Sprays.Spray sp = Sprays.get(spray(s));
		if (sp == null) return InteractionResult.FAIL;
		long now = System.currentTimeMillis();
		Long last = LAST.get(p.getUUID());
		if (last != null && now - last < COOLDOWN_MS) {
			p.sendOverlayMessage(Component.literal("You can spray again in " + (COOLDOWN_MS - (now - last) + 999) / 1000 + " s"));
			return InteractionResult.FAIL;
		}
		LAST.put(p.getUUID(), now);
		var hit = c.getClickLocation();
		var msg = new dev.strafemc.net.StrafePayloads.SprayPlaced(sp.id(), tint(s), hit.x, hit.y, hit.z, c.getClickedFace().get3DDataValue(),
				c.getClickedPos().asLong(), p.getYRot(), p.getUUID());
		for (ServerPlayer o : p.level().players())
			if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(o, dev.strafemc.net.StrafePayloads.SprayPlaced.TYPE))
				net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(o, msg);
		GunServer.soundAt(p.level(), "SprayCan.Paint", hit, p);
		return InteractionResult.SUCCESS;
	}
}
