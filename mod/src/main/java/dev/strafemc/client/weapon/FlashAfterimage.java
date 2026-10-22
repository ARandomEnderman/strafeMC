package dev.strafemc.client.weapon;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BlendFunction;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalInt;

/**
 * CS2's flashbang afterimage: the frame from the moment you were flashed, frozen and laid over the scene, fading
 * linearly over the whole flash (CS2's client: maxAlpha x timeLeft / duration), under the white-out.
 */
final class FlashAfterimage {
	private FlashAfterimage() {}

	private static final RenderPipeline PIPELINE = RenderPipelines.register(RenderPipeline.builder().withLocation("pipeline/strafemc_flash_afterimage")
			.withVertexShader("core/screenquad").withFragmentShader(Identifier.fromNamespaceAndPath("strafemc", "core/flash_afterimage"))
			.withSampler("InSampler").withUniform("FlashInfo", UniformType.UNIFORM_BUFFER)
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES).build());
	private static final ByteBuffer UPLOAD = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());

	private static TextureTarget frozen;
	private static GpuBuffer ubo;
	/** A flash just hit: take the frame on the next draw. */
	private static volatile boolean capture;
	private static boolean captured;

	/** Called at client start so the pipeline is registered with Minecraft's own. */
	static void init() {}

	static void flashed() {
		capture = true;
	}

	static void clear() {
		capture = false;
		captured = false;
	}

	/** Each frame of a flash, after the world is drawn: takes the frame (first time) and lays it over at this strength. */
	static void draw(float strength) {
		var main = Minecraft.getInstance().getMainRenderTarget();
		var device = RenderSystem.getDevice();
		var enc = device.createCommandEncoder();
		if (capture) {
			capture = false;
			if (frozen == null) frozen = new TextureTarget("strafemc flash afterimage", main.width, main.height, false);
			else if (frozen.width != main.width || frozen.height != main.height) frozen.resize(main.width, main.height);
			enc.copyTextureToTexture(main.getColorTexture(), frozen.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
			captured = true;
		}
		// the window was resized since: that frame no longer fits, so the flash goes on without it
		if (!captured || strength <= 0 || frozen.width != main.width || frozen.height != main.height) return;
		if (ubo == null) ubo = device.createBuffer(() -> "strafemc flash info", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16);
		UPLOAD.clear();
		Std140Builder.intoBuffer(UPLOAD).putVec4(Math.min(1, strength), 0, 0, 0);
		UPLOAD.position(0);
		UPLOAD.limit(16);
		enc.writeToBuffer(ubo.slice(), UPLOAD);
		GpuTextureView out = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : main.getColorTextureView();
		try (RenderPass pass = enc.createRenderPass(() -> "strafemc flash afterimage", out, OptionalInt.empty())) {
			pass.setPipeline(PIPELINE);
			pass.setUniform("FlashInfo", ubo);
			pass.bindTexture("InSampler", frozen.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.draw(0, 3);
		}
	}
}
