package dev.strafemc.mixin.client;

import dev.strafemc.client.assets.RuntimePack;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Adds the locally generated CS2 asset pack to the client's resource packs, always enabled (required, on top). */
@Mixin(PackRepository.class)
public abstract class PackRepositoryMixin {
	@Inject(method = "discoverAvailable", at = @At("RETURN"), cancellable = true)
	private void strafemc$cs2Assets(CallbackInfoReturnable<Map<String, Pack>> cir) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || (Object) this != mc.getResourcePackRepository() || !Files.exists(RuntimePack.DIR.resolve("pack.mcmeta"))) return;
		Pack pack = Pack.readMetaAndCreate(
				new PackLocationInfo("strafemc_cs2", Component.literal("strafeMC CS2 assets"), PackSource.BUILT_IN, Optional.empty()),
				new PathPackResources.PathResourcesSupplier(RuntimePack.DIR), PackType.CLIENT_RESOURCES,
				new PackSelectionConfig(true, Pack.Position.TOP, false));
		if (pack == null) return;
		Map<String, Pack> m = new HashMap<>(cir.getReturnValue());
		m.put("strafemc_cs2", pack);
		cir.setReturnValue(m);
	}
}
