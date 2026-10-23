package dev.strafemc.mode;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
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
 * The Zone Tool: draws a game mode's zones (Zones). Left-click switches the kind (bomb site A / B, T / CT buy zone).
 * Right-click a block for the first corner and another for the second: each corner is the space in front of the
 * clicked face (the floor's top: where you stand), and the box between the two, both included, becomes a zone, so
 * its height is where you click. Sneak and right-click inside a zone to remove it. Held, it shows every zone.
 * Operators (or players in creative) only.
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

	/**
	 * Left-click with the tool in hand (sent by the client: a click in the air reaches the server as nothing): the next
	 * kind. It used to be sneak + right-click in the air, one slip from sneak + right-click on a block (remove).
	 */
	public static void next(ServerPlayer p) {
		ItemStack s = p.getMainHandItem();
		if (!(s.getItem() instanceof ZoneToolItem) || !allowed(p)) return;
		Zones.Type next = Zones.Type.values()[(kind(s).ordinal() + 1) % Zones.Type.values().length];
		s.set(KIND, next.id);
		FIRST.remove(p.getUUID());
		p.sendOverlayMessage(Component.literal("Zone Tool: " + next.label));
	}

	@Override
	public boolean canDestroyBlock(ItemStack stack, net.minecraft.world.level.block.state.BlockState state, Level level, BlockPos pos,
			net.minecraft.world.entity.LivingEntity user) {
		return false; // left-click switches the kind, it never breaks the map
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
		BlockPos pos = ctx.getClickedPos().relative(ctx.getClickedFace()); // the space in front of the clicked face
		if (player.isShiftKeyDown()) { // remove the zones here
			int n = Zones.removeAt(sp, pos.getCenter());
			sp.sendOverlayMessage(Component.literal(n > 0 ? "Removed " + n + " zone" + (n > 1 ? "s" : "") : "No zone here"));
			return InteractionResult.SUCCESS;
		}
		Zones.Type type = kind(ctx.getItemInHand());
		BlockPos first = FIRST.remove(sp.getUUID());
		if (first == null) {
			FIRST.put(sp.getUUID(), pos);
			sp.sendOverlayMessage(Component.literal(type.label + ": first corner set, now right-click the opposite corner (left-click: another kind)"));
		} else {
			Zones.Zone z = Zones.add(sp, type, first, pos);
			sp.sendOverlayMessage(Component.literal(type.label + " added (" + (z.x1() - z.x0() + 1) + " x " + (z.y1() - z.y0() + 1) + " x "
					+ (z.z1() - z.z0() + 1) + " blocks)"));
		}
		return InteractionResult.SUCCESS;
	}

	/** Where the player's first corner is (for the outline while they pick the second), or null. */
	public static BlockPos first(UUID player) {
		return FIRST.get(player);
	}
}
