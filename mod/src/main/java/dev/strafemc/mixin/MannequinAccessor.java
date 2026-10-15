package dev.strafemc.mixin;

import net.minecraft.world.entity.decoration.Mannequin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Bots are mannequins: no "NPC" line under their name. */
@Mixin(Mannequin.class)
public interface MannequinAccessor {
	@Invoker("setHideDescription")
	void strafemc$setHideDescription(boolean hide);
}
