package dev.strafemc.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * strafeMC's keybinds win over other mods': a key press reaches every bind on that key, so when one of ours (the
 * strafemc category) is on the key, other mods' binds there are skipped. Vanilla's (the minecraft categories) still work.
 */
@Mixin(KeyMapping.class)
public abstract class KeyPriorityMixin {
	@Shadow
	@Final
	private static Map<InputConstants.Key, List<KeyMapping>> MAP;

	@Inject(method = "forAllKeyMappings", at = @At("HEAD"), cancellable = true)
	private static void strafemc$priority(InputConstants.Key key, Consumer<KeyMapping> action, CallbackInfo ci) {
		List<KeyMapping> list = MAP.get(key);
		if (list == null || list.size() < 2) return;
		boolean ours = false;
		for (KeyMapping k : list) ours |= k.getCategory().id().getNamespace().equals("strafemc");
		if (!ours) return;
		for (KeyMapping k : List.copyOf(list)) {
			String ns = k.getCategory().id().getNamespace();
			if (ns.equals("strafemc") || ns.equals("minecraft")) action.accept(k);
		}
		ci.cancel();
	}
}
