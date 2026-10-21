package dev.strafemc.mode;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Zone Tool: draws a game mode's zones (Zones). Right-click a block for the first corner and another for the
 * second: the box between them (floor up to Zones.HEADROOM above) becomes a zone of the tool's kind. Sneak and
 * right-click the air to switch the kind (bomb site A / B, T / CT buy zone); sneak and right-click a block inside a zone
 * to remove it. Held, it shows every zone. Operators (or players in creative) only.
 */
public class ZoneToolItem extends Item {
	public static DataComponentType<String> KIND;
	/** Each player's first corner, waiting for the second. */
	private static final Map<UUID, BlockPos> FIRST = new ConcurrentHashMap<>();

	public ZoneToolItem(Properties p) {
		super(p);
	}

	public static void registerComponent() {
		KIND = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, net.minecraft.resources.Identifier.fromNamespaceAndPath("strafemc", "zone_kind"), DataComponentType.<String>builder()
				.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).build());
	}

	public static Zones.Type kind(ItemStack s) {
		String k = s.get(KIND);
		return Zones.Type.of(k == null ? "site_a" : k);
	}

	private static boolean allowed(Player p) {
		return p.getAbilities().instabuild || p instanceof ServerPlayer sp
				&& (sp.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER) || dev.strafemc.server.StrafeOps.contains(sp));
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!player.isShiftKeyDown()) return InteractionResult.PASS;
		ItemStack s = player.getItemInHand(hand);
		if (!level.isClientSide() && allowed(player)) {
			Zones.Type next = Zones.Type.values()[(kind(s).ordinal() + 1) % Zones.Type.values().length];
			s.set(KIND, next.id);
			FIRST.remove(player.getUUID());
			player.sendOverlayMessage(Component.literal("Zone Tool: " + next.label));
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public InteractionResult useOn(UseOnContext ctx) {
		Player player = ctx.getPlayer();
		if (player == null) return InteractionResult.PASS;
		if (ctx.getLevel().isClientSide()) return InteractionResult.SUCCESS;
		if (!(player instanceof ServerPlayer sp) || !allowed(player)) {
			player.sendOverlayMessage(Component.literal("Only operators can draw zones."));
			return InteractionResult.FAIL;
		}
		BlockPos pos = ctx.getClickedPos();
		if (player.isShiftKeyDown()) { // remove the zones here
			int n = Zones.removeAt(sp, pos.getCenter().add(0, 1, 0));
			sp.sendOverlayMessage(Component.literal(n > 0 ? "Removed " + n + " zone" + (n > 1 ? "s" : "") : "No zone here"));
			return InteractionResult.SUCCESS;
		}
		Zones.Type type = kind(ctx.getItemInHand());
		BlockPos first = FIRST.remove(sp.getUUID());
		if (first == null) {
			FIRST.put(sp.getUUID(), pos);
			sp.sendOverlayMessage(Component.literal(type.label + ": first corner set, now right-click the opposite corner"));
		} else {
			Zones.Zone z = Zones.add(sp, type, first, pos);
			sp.sendOverlayMessage(Component.literal(type.label + " added (" + (z.x1() - z.x0() + 1) + " x " + (z.z1() - z.z0() + 1) + " blocks)"));
		}
		return InteractionResult.SUCCESS;
	}

	/** Where the player's first corner is (for the outline while they pick the second), or null. */
	public static BlockPos first(UUID player) {
		return FIRST.get(player);
	}
}
