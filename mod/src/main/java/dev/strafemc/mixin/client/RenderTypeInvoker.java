package dev.strafemc.mixin.client;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** RenderType.create is package-private: our viewmodel render types are made through it like vanilla's. */
@Mixin(RenderType.class)
public interface RenderTypeInvoker {
	@Invoker("create")
	static RenderType strafemc$create(String name, RenderSetup setup) {
		throw new AssertionError();
	}
}
