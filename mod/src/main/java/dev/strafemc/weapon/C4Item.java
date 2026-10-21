package dev.strafemc.weapon;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * CS2's C4 (weapon_c4): hold left click on the ground to plant it. The plant clip arms it 3.0 s in (its
 * WPN_BLOCK_INSPECT window); letting go, jumping or switching away cancels. Planted, it is a PlantedC4.
 * With strafemc_c4_keypad on, a keypad pops up instead and holding alone never arms it: typing CS2's code does (at
 * least KEYPAD_PLANT seconds in, against instant clients).
 */
public class C4Item extends GunItem {
	public static final String CLS = "weapon_c4";
	public static final String MODEL = "weapons/models/c4/weapon_c4.vmdl_c";
	/** Seconds of holding until it's armed (the plant clip's WPN_BLOCK_INSPECT window). */
	public static final double PLANT_TIME = 3.0;
	/** The keypad's code: the one CS2's C4 shows. */
	public static final String CODE = "7355608";
	/** Keypad mode: the fastest a typed plant can arm (FLAGGED ours). */
	public static final double KEYPAD_PLANT = 0.5;
	/** Lag allowance on the server's plant timing (s). */
	private static final double SLACK = 0.25;

	/** Players planting: when they started (server time, s). */
	private static final Map<UUID, Double> PLANTING = new ConcurrentHashMap<>();

	public C4Item(Properties p) {
		super(p, CLS, null);
	}

	private static double now(ServerPlayer p) {
		return p.level().getGameTime() / 20.0;
	}

	/** The client started planting (holding the C4, on the ground). */
	public static void start(ServerPlayer p) {
		if (!(p.getMainHandItem().getItem() instanceof C4Item) || !p.onGround()) return;
		String no = dev.strafemc.mode.GameModes.plantRefusal(p);
		if (no != null && !no.isEmpty()) {
			dev.strafemc.server.Toasts.warn(p, no);
			return;
		}
		PLANTING.put(p.getUUID(), now(p));
	}

	public static void cancel(ServerPlayer p) {
		PLANTING.remove(p.getUUID());
	}

	/** The client's plant finished (typed: by the keypad's code): if it really held on long enough, the bomb is planted. */
	public static void plant(ServerPlayer p, boolean typed) {
		Double at = PLANTING.remove(p.getUUID());
		ItemStack held = p.getMainHandItem();
		if (at == null || !(held.getItem() instanceof C4Item) || !p.onGround()) return;
		boolean keypad = dev.strafemc.server.StrafeSettings.c4Keypad();
		if (keypad && !typed) return; // keypad mode: only typing the code arms it
		double need = keypad ? KEYPAD_PLANT : PLANT_TIME;
		if (now(p) - at < need - SLACK) return;
		String no = dev.strafemc.mode.GameModes.plantRefusal(p);
		if (no != null && !no.isEmpty()) return;
		PlantedC4 bomb = new PlantedC4(p.level(), p, held.copyWithCount(1));
		bomb.setPos(p.getX(), p.getY(), p.getZ());
		bomb.setYRot(p.getYRot());
		p.level().addFreshEntity(bomb);
		if (!p.getAbilities().instabuild) held.shrink(1);
		GunServer.soundAt(p.level(), "c4.plant", p.position(), bomb);
		dev.strafemc.mode.GameModes.planted(p, bomb);
		dev.strafemc.server.Toasts.send(p, "The bomb has been planted.", dev.strafemc.server.Toasts.GOOD);
	}

	@Override
	public Component getName(ItemStack stack) {
		String tag = StickerSlots.nameTag(stack);
		return tag != null ? Component.literal("''" + tag + "''") : super.getName(stack); // a name tag shows in quotes, as in CS2
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
		StickerSlots.tooltip(stack, out);
	}
}
