package dev.strafemc.client.viewmodel;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.strafemc.StrafeMc;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * The first-person viewmodel skinned on the GPU: each mesh part goes up once as a static vertex / index buffer (position,
 * UV, normal, four joints and weights), and each frame only its joint matrices are written; the vertex shader
 * (strafemc:core/viewmodel_skinned) skins it and hands viewmodel.fsh the same inputs as the CPU path. Replaces skinning
 * ~40k triangles on the CPU and streaming them through a vertex consumer every frame. Any failure turns it off for the
 * session and the viewmodel falls back to the CPU path.
 */
public final class GpuSkin {
	static final int MAX_JOINTS = 128;
	private static final int VERTEX_SIZE = 64; // pos 3f, uv 2f, normal 3f, joints 4f, weights 4f
	private static final int UBO_SIZE = MAX_JOINTS * 64 + 16;

	private static VertexFormat format;

	private static RenderPipeline pipeline;
	/** The joint upload, reused every draw (render thread only). */
	private static final ByteBuffer UPLOAD = ByteBuffer.allocateDirect(UBO_SIZE).order(ByteOrder.nativeOrder());
	/** Off after a failure (or when the backend can't do it): the CPU path draws instead. */
	static volatile boolean disabled;

	private GpuSkin() {}

	/** Frees a part's buffers (render thread, between frames); it uploads again if it is drawn later. */
	static void free(VmModel.Part p) {
		if (p.gpu instanceof Mesh m) {
			m.vertices.close();
			m.indices.close();
			m.skin.close();
		}
		p.gpu = null;
	}

	/** A part's static buffers and its own joint uniform buffer. */
	static final class Mesh {
		final GpuBuffer vertices, indices, skin;
		final int indexCount, jointsNeeded;

		Mesh(GpuBuffer vertices, GpuBuffer indices, GpuBuffer skin, int indexCount, int jointsNeeded) {
			this.vertices = vertices;
			this.indices = indices;
			this.skin = skin;
			this.indexCount = indexCount;
			this.jointsNeeded = jointsNeeded;
		}
	}

	/** Our vertex format: three float elements of our own on free element ids (other mods may hold some). */
	private static VertexFormat format() {
		if (format == null) {
			VertexFormatElement[] own = new VertexFormatElement[3];
			int[] counts = {3, 4, 4};
			int k = 0;
			for (int id = VertexFormatElement.MAX_COUNT - 1; id >= 0 && k < 3; id--)
				if (VertexFormatElement.byId(id) == null) own[k] = VertexFormatElement.register(id, 0, VertexFormatElement.Type.FLOAT, false, counts[k++]);
			if (k < 3) throw new IllegalStateException("no free vertex format element ids");
			format = VertexFormat.builder().add("Position", VertexFormatElement.POSITION).add("UV0", VertexFormatElement.UV0).add("SkinNormal", own[0])
					.add("SkinJoints", own[1]).add("SkinWeights", own[2]).build();
		}
		return format;
	}

	private static RenderPipeline pipeline() {
		if (pipeline == null) {
			var snippet = dev.strafemc.mixin.client.RenderPipelinesAccessor.strafemc$entitySnippet();
			var b = RenderPipeline.builder(snippet).withLocation(Identifier.fromNamespaceAndPath("strafemc", "pipeline/viewmodel_skinned"))
					.withVertexShader(Identifier.fromNamespaceAndPath("strafemc", "core/viewmodel_skinned"))
					.withFragmentShader(Identifier.fromNamespaceAndPath("strafemc", "core/viewmodel")).withShaderDefine("MAX_JOINTS", MAX_JOINTS)
					.withVertexFormat(format(), VertexFormat.Mode.TRIANGLES).withUniform("Skin", UniformType.UNIFORM_BUFFER).withCull(false);
			var samplers = snippet.samplers().orElse(java.util.List.of());
			if (!samplers.contains("Sampler0")) b.withSampler("Sampler0");
			if (!samplers.contains("Sampler2")) b.withSampler("Sampler2");
			pipeline = b.build();
		}
		return pipeline;
	}

	/** Whether this part can be drawn here: opaque (lit), with a skeleton that fits the joint buffer. */
	static boolean supports(VmModel.Part p, int skinJoints) {
		return !disabled && dev.strafemc.config.StrafeConfig.gpuSkinning && !p.alphaMode.equals("MASK") && skinJoints <= MAX_JOINTS && maxJoint(p) < MAX_JOINTS;
	}

	private static int maxJoint(VmModel.Part p) {
		int m = 0;
		if (p.jnt != null) for (int j : p.jnt) m = Math.max(m, j);
		return m;
	}

	private static Mesh mesh(VmModel.Part p) {
		if (p.gpu instanceof Mesh m) return m;
		int n = p.pos.length / 3;
		ByteBuffer v = ByteBuffer.allocateDirect(n * VERTEX_SIZE).order(ByteOrder.nativeOrder());
		boolean skinned = p.jnt != null && p.wts != null;
		for (int i = 0; i < n; i++) {
			v.putFloat(p.pos[i * 3]).putFloat(p.pos[i * 3 + 1]).putFloat(p.pos[i * 3 + 2]);
			v.putFloat(p.uv[i * 2]).putFloat(p.uv[i * 2 + 1]);
			v.putFloat(p.nrm[i * 3]).putFloat(p.nrm[i * 3 + 1]).putFloat(p.nrm[i * 3 + 2]);
			for (int k = 0; k < 4; k++) v.putFloat(skinned ? p.jnt[i * 4 + k] : 0);
			for (int k = 0; k < 4; k++) v.putFloat(skinned ? p.wts[i * 4 + k] : k == 0 ? 1 : 0);
		}
		v.flip();
		ByteBuffer ix = ByteBuffer.allocateDirect(p.idx.length * 4).order(ByteOrder.nativeOrder());
		for (int i : p.idx) ix.putInt(i);
		ix.flip();
		var device = RenderSystem.getDevice();
		Mesh m = new Mesh(device.createBuffer(() -> "strafemc viewmodel vertices", GpuBuffer.USAGE_VERTEX, v),
				device.createBuffer(() -> "strafemc viewmodel indices", GpuBuffer.USAGE_INDEX, ix),
				device.createBuffer(() -> "strafemc viewmodel joints", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE), p.idx.length,
				skinned ? maxJoint(p) + 1 : 1);
		p.gpu = m;
		return m;
	}

	/**
	 * Draws one part (render thread, inside its submitted geometry callback): joints = inverse(model-view) x cam x
	 * toView x skin (as the CPU path's pose x skinned vertex), the model-view applied by the shader as vanilla does.
	 * True if drawn; false (and off for the session) if it failed.
	 */
	static boolean draw(VmModel.Part p, Matrix4f[] skin, Matrix4f toView, Matrix4f cam, int light, Identifier texture) {
		if (disabled) return false;
		try {
			Mesh m = mesh(p);
			Matrix4f place = new Matrix4f(RenderSystem.getModelViewMatrix()).invert().mul(cam).mul(toView);
			ByteBuffer u = UPLOAD;
			u.clear();
			Matrix4f j = new Matrix4f();
			int count = Math.max(1, Math.max(skin.length, m.jointsNeeded));
			for (int i = 0; i < count; i++) {
				// joints past the skeleton (a mesh bound to fewer joints than it names) reuse the last, as the CPU path clamps
				Matrix4f s = skin.length == 0 ? null : skin[Math.min(i, skin.length - 1)];
				j.set(place);
				if (s != null) j.mul(s);
				j.get(i * 64, u);
			}
			int a = Viewmodel.pearlColor(p.pearl) >>> 24;
			u.position(MAX_JOINTS * 64);
			u.putFloat(light & 0xFFFF).putFloat(light >>> 16 & 0xFFFF).putFloat(a / 255f).putFloat(p.uOffset);
			u.position(0);
			var device = RenderSystem.getDevice();
			var encoder = device.createCommandEncoder();
			encoder.writeToBuffer(m.skin.slice(), u);

			var mc = Minecraft.getInstance();
			RenderTarget target = mc.getMainRenderTarget();
			GpuTextureView color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : target.getColorTextureView();
			var tex = mc.getTextureManager().getTexture(texture);
			var transform = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrix(), new Vector4f(1, 1, 1, 1), new Vector3f(),
					new Matrix4f());
			try (RenderPass pass = encoder.createRenderPass(() -> "strafemc viewmodel", color, OptionalInt.empty(),
					target.useDepth ? target.getDepthTextureView() : null, OptionalDouble.empty())) {
				pass.setPipeline(pipeline());
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DynamicTransforms", transform);
				pass.setUniform("Skin", m.skin);
				pass.bindTexture("Sampler0", tex.getTextureView(), tex.getSampler());
				pass.bindTexture("Sampler2", mc.gameRenderer.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
				pass.setVertexBuffer(0, m.vertices);
				pass.setIndexBuffer(m.indices, VertexFormat.IndexType.INT);
				pass.drawIndexed(0, 0, m.indexCount, 1);
			}
			return true;
		} catch (RuntimeException | LinkageError e) {
			disabled = true;
			StrafeMc.LOG.error("[strafemc] GPU viewmodel skinning failed, drawing on the CPU instead", e);
			return false;
		}
	}
}
