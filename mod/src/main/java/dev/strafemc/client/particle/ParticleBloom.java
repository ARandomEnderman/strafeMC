package dev.strafemc.client.particle;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bloom for world effects, as CS2's bloom does it: the glowing (additive) layers, including the ones CS2 draws only into
 * its bloom pass, are drawn again into a bloom buffer (at a quarter, so overbright colours up to 4 fit in 8 bits),
 * tested against the scene's depth; that buffer is taken down five levels with CS2's 13-tap downsample, back up with
 * its tent upsample, and added over the scene.
 */
public final class ParticleBloom {
	private ParticleBloom() {}

	/** Colours go into the bloom buffer at this scale (and come out x 1 / this). */
	static final float IN = 0.25f;
	static final int LEVELS = 5;

	private static final RenderPipeline ADD = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
			.withLocation("pipeline/strafemc_particle_bloom")
			.withVertexShader("core/entity")
			.withFragmentShader("core/entity")
			.withShaderDefine("EMISSIVE")
			.withShaderDefine("NO_OVERLAY")
			.withShaderDefine("NO_CARDINAL_LIGHTING")
			.withSampler("Sampler0")
			.withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
			.withCull(false)
			.withVertexFormat(DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS)
			.withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
			.build());
	private static final RenderPipeline DOWN_FIRST = quad("bloom_down_first", "bloom_down", true, null);
	private static final RenderPipeline DOWN = quad("bloom_down", "bloom_down", false, null);
	private static final RenderPipeline UP = quad("bloom_up", "bloom_up", false, BlendFunction.TRANSLUCENT);
	private static final RenderPipeline COMPOSITE = quad("bloom_composite", "bloom_composite", false, BlendFunction.ADDITIVE);

	private static RenderPipeline quad(String name, String shader, boolean first, BlendFunction blend) {
		RenderPipeline.Builder b = RenderPipeline.builder().withLocation("pipeline/strafemc_" + name)
				.withVertexShader("core/screenquad").withFragmentShader(Identifier.fromNamespaceAndPath("strafemc", "core/" + shader))
				.withSampler("InSampler").withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES);
		if (first) b.withShaderDefine("FIRST");
		b.withColorTargetState(blend != null ? new ColorTargetState(blend) : new ColorTargetState(java.util.Optional.empty(), ColorTargetState.WRITE_ALL));
		return RenderPipelines.register(b.build());
	}

	private static TextureTarget target;
	private static final TextureTarget[] LEVEL = new TextureTarget[LEVELS];
	private static boolean drawn;
	private static final OutputTarget OUTPUT = new OutputTarget("strafemc_bloom", () -> target);
	private static final Map<Identifier, RenderType> TYPES = new ConcurrentHashMap<>();

	/** Called at client start so the pipelines are registered with Minecraft's own. */
	public static void init() {}

	/** An additive layer's texture drawn into the bloom buffer. */
	static RenderType type(Identifier texture) {
		return TYPES.computeIfAbsent(texture, t -> RenderType.create("strafemc_particle_bloom",
				RenderSetup.builder(ADD).withTexture("Sampler0", t).setOutputTarget(OUTPUT).createRenderSetup()));
	}

	static boolean ready() {
		return target != null;
	}

	/** Before the world effects draw: the bloom buffer at the screen's size, cleared, with the scene's depth. */
	static void begin() {
		var main = Minecraft.getInstance().getMainRenderTarget();
		if (target == null) target = new TextureTarget("strafemc bloom", main.width, main.height, true);
		else if (target.width != main.width || target.height != main.height) target.resize(main.width, main.height);
		var enc = RenderSystem.getDevice().createCommandEncoder();
		enc.clearColorTexture(target.getColorTexture(), 0);
		target.copyDepthFrom(main);
		drawn = false;
	}

	static void markDrawn() {
		drawn = true;
	}

	/** After they drew: blurred down and up, added over the scene. */
	static void apply() {
		if (target == null || !drawn) return;
		var main = Minecraft.getInstance().getMainRenderTarget();
		var enc = RenderSystem.getDevice().createCommandEncoder();
		var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		int w = main.width, h = main.height;
		for (int i = 0; i < LEVELS; i++) {
			w = Math.max(1, w / 2);
			h = Math.max(1, h / 2);
			if (LEVEL[i] == null) LEVEL[i] = new TextureTarget("strafemc bloom " + i, w, h, false);
			else if (LEVEL[i].width != w || LEVEL[i].height != h) LEVEL[i].resize(w, h);
		}
		GpuTextureView src = target.getColorTextureView();
		for (int i = 0; i < LEVELS; i++) {
			final int n = i;
			try (RenderPass pass = enc.createRenderPass(() -> "strafemc bloom down " + n, LEVEL[i].getColorTextureView(), OptionalInt.of(0))) {
				pass.setPipeline(i == 0 ? DOWN_FIRST : DOWN);
				pass.bindTexture("InSampler", src, linear);
				pass.draw(0, 3);
			}
			src = LEVEL[i].getColorTextureView();
		}
		for (int i = LEVELS - 1; i > 0; i--) {
			final int n = i;
			try (RenderPass pass = enc.createRenderPass(() -> "strafemc bloom up " + n, LEVEL[i - 1].getColorTextureView(), OptionalInt.empty())) {
				pass.setPipeline(UP);
				pass.bindTexture("InSampler", LEVEL[i].getColorTextureView(), linear);
				pass.draw(0, 3);
			}
		}
		GpuTextureView out = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : main.getColorTextureView();
		try (RenderPass pass = enc.createRenderPass(() -> "strafemc bloom composite", out, OptionalInt.empty())) {
			pass.setPipeline(COMPOSITE);
			pass.bindTexture("InSampler", LEVEL[0].getColorTextureView(), linear);
			pass.draw(0, 3);
		}
	}
}
