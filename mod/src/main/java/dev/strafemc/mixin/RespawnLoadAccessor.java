package dev.strafemc.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * A death sets the connection's waitingForRespawn; only the respawn button's handler clears it (with this). Respawns
 * by a game mode call it too: while it's set the server ignores the player's moves and they can't be hurt.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public interface RespawnLoadAccessor {
	@Invoker("restartClientLoadTimerAfterRespawn")
	void strafemc$restartClientLoadTimerAfterRespawn();
}
