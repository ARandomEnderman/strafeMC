package dev.strafemc.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalInt;

/**
 * CS2's death camera colour, on the world under the HUD (CS2's post-processing layers death_cam_phase1 and
 * death_cam_phase2): phase 1's colour table keeps red and takes green and blue to 0.58 (a red tint), phase 2's makes
 * everything black. The world's frame is multiplied by that colour in one full-screen pass.
 */
public final class DeathFade {
	private DeathFade() {}

	private static final RenderPipeline PIPELINE = RenderPipelines.register(RenderPipeline.builder().withLocation("pipeline/strafemc_death_fade")
			.withVertexShader("core/screenquad").withFragmentShader(Identifier.fromNamespaceAndPath("strafemc", "core/death_fade"))
			.withUniform("FadeInfo", UniformType.UNIFORM_BUFFER)
			// multiply: the frame x the colour, its alpha left as it was
			.withColorTargetState(new ColorTargetState(new BlendFunction(SourceFactor.ZERO, DestFactor.SRC_COLOR, SourceFactor.ZERO, DestFactor.ONE)))
			.withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES).build());
	/** The same colour laid over the frame at its alpha (the flashbang's white-out when the HUD is hidden). */
	private static final RenderPipeline OVER = RenderPipelines.register(RenderPipeline.builder().withLocation("pipeline/strafemc_screen_overlay")
			.withVertexShader("core/screenquad").withFragmentShader(Identifier.fromNamespaceAndPath("strafemc", "core/death_fade"))
			.withUniform("FadeInfo", UniformType.UNIFORM_BUFFER).withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES).build());
	private static final ByteBuffer UPLOAD = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
	private static GpuBuffer ubo;

	/** Called at client start so the pipeline is registered with Minecraft's own. */
	static void init() {}

	/** After the world is drawn (a HUD element runs then, before the HUD): the frame times red, green, blue. */
	static void draw(float r, float g, float b) {
		if (r >= 0.999f && g >= 0.999f && b >= 0.999f) return;
		pass(PIPELINE, r, g, b, 1);
	}

	/** A colour over the whole frame at alpha a. */
	public static void overlay(float r, float g, float b, float a) {
		if (a <= 0.001f) return;
		pass(OVER, r, g, b, Math.min(1, a));
	}

	private static void pass(RenderPipeline pipeline, float r, float g, float b, float a) {
		var main = Minecraft.getInstance().getMainRenderTarget();
		var device = RenderSystem.getDevice();
		var enc = device.createCommandEncoder();
		if (ubo == null) ubo = device.createBuffer(() -> "strafemc death fade", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16);
		UPLOAD.clear();
		Std140Builder.intoBuffer(UPLOAD).putVec4(r, g, b, a);
		UPLOAD.position(0);
		UPLOAD.limit(16);
		enc.writeToBuffer(ubo.slice(), UPLOAD);
		GpuTextureView out = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : main.getColorTextureView();
		try (RenderPass pass = enc.createRenderPass(() -> "strafemc death fade", out, OptionalInt.empty())) {
			pass.setPipeline(pipeline);
			pass.setUniform("FadeInfo", ubo);
			pass.draw(0, 3);
		}
	}
}
