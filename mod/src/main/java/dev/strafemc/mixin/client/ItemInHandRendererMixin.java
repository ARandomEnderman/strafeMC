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
		var holder = Viewmodel.holder(); // you, or the player you watch while dead
		if (!(holder != null && holder.getMainHandItem().getItem() instanceof GunItem g)) {
			if (Viewmodel.watching()) ci.cancel(); // never your own (empty) hands over their view
			return;
		}
		// snipers hide the viewmodel while scoped (m_bHideViewModelWhenZoomed)
		long t = dev.strafemc.client.perf.Perf.start();
		boolean drawn = dev.strafemc.client.weapon.GunClient.INSTANCE.scopeOverlay() || Viewmodel.INSTANCE.render(g.weaponClass, ps, out, light);
		dev.strafemc.client.perf.Perf.end("viewmodel", t);
		if (drawn) ci.cancel();
	}
}
