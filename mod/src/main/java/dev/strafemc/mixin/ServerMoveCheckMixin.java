package dev.strafemc.mixin;

import dev.strafemc.server.Noclip;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The server re-checks every move with vanilla collision and snaps the player back ("moved wrongly") when it disagrees.
 * Source movement's hull and step-ups differ from vanilla's, so at block edges and seams it disagreed with correct moves.
 * For Source-movement players that check is skipped, as it is for players without physics (spectators); movement
 * anti-cheat for Source players is a separate, later step.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerMoveCheckMixin {
	@Redirect(method = "handleMovePlayer", at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerPlayer;noPhysics:Z"))
	private boolean strafemc$sourceMoves(ServerPlayer p) {
		return p.noPhysics || Noclip.usesSource(p);
	}
}
