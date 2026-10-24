package dev.strafemc.client.weapon;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalInt;

/**
 * CS2's sniper scope while scoped with an AWP, SSG 08, SCAR-20 or G3SG1: CS2 draws it as one pass over the frame
 * (materials/effects/legacy_sniper_scope.vmat, shader legacy_sniper_scope.vfx), ported in core/sniper_scope.fsh: the
 * round lens with its warp, fringes and black outside, and the reticle lines that blur wider and lighten with the gun's
 * inaccuracy. Drawn after the world (under the HUD, kept with F1 like CS2's), the Minecraft crosshair hidden.
 * Inputs as CS2 fills them: line width cl_crosshair_sniper_width (1: the single-pixel hairline), the spread gap only
 * with cl_sniper_show_inaccuracy (off here), the hurt blur (inaccuracy()). FLAGGED ours: the scope inaccuracy itself
 * (the gun's current inaccuracy) and no scope bob (CS2: two weapon values x 77.14).
 */
public final class ScopeHud {
	private ScopeHud() {}

	private static final RenderPipeline PIPELINE = RenderPipelines.register(RenderPipeline.builder().withLocation("pipeline/strafemc_sniper_scope")
			.withVertexShader("core/screenquad").withFragmentShader(Identifier.fromNamespaceAndPath("strafemc", "core/sniper_scope"))
			.withSampler("InSampler").withUniform("ScopeInfo", UniformType.UNIFORM_BUFFER)
			.withColorTargetState(ColorTargetState.DEFAULT) // replaces the frame (the scope's view is the whole picture)
			.withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES).build());
	private static final ByteBuffer UPLOAD = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
	/** cl_crosshair_sniper_width: "1 for single-pixel hairline" (CS2's help text). */
	private static final float LINE_WIDTH = 1;

	private static TextureTarget frame;
	private static GpuBuffer ubo;

	public static void register() {
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, old -> (g, delta) -> {
			if (!GunClient.INSTANCE.scopeOverlay() && !Crosshair.render(g)) old.extractRenderState(g, delta); // CS2's crosshair with a CS weapon
		});
	}

	/**
	 * The scope's inaccuracy input as CS2 makes it: the scope inaccuracy (here the gun's current inaccuracy) plus 0.05
	 * right after you're hurt, gone 0.5 s later (time since m_flTimeOfLastInjury x 2, clamped), the sum clamped to 0..1.
	 * Minecraft's hurt animation lasts 10 ticks, the same 0.5 s.
	 */
	private static double inaccuracy(net.minecraft.client.player.LocalPlayer p) {
		double since = p.hurtTime > 0 ? (p.hurtDuration - p.hurtTime) / 20.0 : 1;
		double hurt = 0.05 - 0.05 * Math.max(0, Math.min(1, since * 2));
		return Math.max(0, Math.min(1, GunClient.INSTANCE.inaccuracy() + hurt));
	}

	/** After the world each frame: the scope over it while scoped. */
	public static void afterLevel() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !GunClient.INSTANCE.scopeOverlay()) return;
		var main = mc.getMainRenderTarget();
		var device = RenderSystem.getDevice();
		var enc = device.createCommandEncoder();
		if (frame == null) frame = new TextureTarget("strafemc sniper scope", main.width, main.height, false);
		else if (frame.width != main.width || frame.height != main.height) frame.resize(main.width, main.height);
		enc.copyTextureToTexture(main.getColorTexture(), frame.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
		if (ubo == null) ubo = device.createBuffer(() -> "strafemc scope info", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 32);
		UPLOAD.clear();
		Std140Builder.intoBuffer(UPLOAD).putVec4(main.width, main.height, (float) inaccuracy(mc.player), LINE_WIDTH).putVec4(0, 0, 0, 0);
		UPLOAD.position(0);
		UPLOAD.limit(32);
		enc.writeToBuffer(ubo.slice(), UPLOAD);
		GpuTextureView out = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : main.getColorTextureView();
		try (RenderPass pass = enc.createRenderPass(() -> "strafemc sniper scope", out, OptionalInt.empty())) {
			pass.setPipeline(PIPELINE);
			pass.setUniform("ScopeInfo", ubo);
			pass.bindTexture("InSampler", frame.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.draw(0, 3);
		}
	}
}
