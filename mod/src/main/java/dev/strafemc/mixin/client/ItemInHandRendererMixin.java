package dev.strafemc.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.strafemc.client.viewmodel.Viewmodel;
import dev.strafemc.weapon.GunItem;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While a CS gun is held, the CS2 viewmodel (real arms + weapon + animations) replaces Minecraft's first-person hand. */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
	@Inject(method = "renderHandsWithItems", at = @At("HEAD"), cancellable = true)
	private void strafemc$viewmodel(float partial, PoseStack ps, SubmitNodeCollector out, LocalPlayer player, int light, CallbackInfo ci) {
		if (!(player.getMainHandItem().getItem() instanceof GunItem g)) return;
		// snipers hide the viewmodel while scoped (m_bHideViewModelWhenZoomed)
		if (dev.strafemc.client.weapon.GunClient.INSTANCE.scopeOverlay() || Viewmodel.INSTANCE.render(g.weaponClass, ps, out, light)) ci.cancel();
	}
}
