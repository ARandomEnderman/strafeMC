package dev.strafemc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;

/** The loadout's 3D glove preview: a picture-in-picture view of the worn gloves (Viewmodel.previewArms), slowly turning. */
public final class LoadoutPreview {
	private LoadoutPreview() {}

	public record State(int x0, int y0, int x1, int y1, float yaw, ScreenRectangle scissorArea, ScreenRectangle bounds) implements PictureInPictureRenderState {
		@Override
		public float scale() {
			return 1;
		}
	}

	public static final class Renderer extends PictureInPictureRenderer<State> {
		public Renderer(MultiBufferSource.BufferSource buffers) {
			super(buffers);
		}

		@Override
		public Class<State> getRenderStateClass() {
			return State.class;
		}

		@Override
		protected void renderToTexture(State s, PoseStack ps) {
			Minecraft.getInstance().gameRenderer.getLighting().setupFor(com.mojang.blaze3d.platform.Lighting.Entry.ENTITY_IN_UI);
			dev.strafemc.client.viewmodel.Viewmodel.INSTANCE.previewArms(ps, bufferSource, s.x1() - s.x0(), s.y1() - s.y0(), s.yaw());
			bufferSource.endBatch();
		}

		@Override
		protected String getTextureLabel() {
			return "strafemc loadout preview";
		}
	}

	public static void register() {
		net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry.register(
				ctx -> new Renderer(ctx.minecraft().renderBuffers().bufferSource()));
	}

	/** Adds the preview to this frame's GUI in the given box. */
	public static void draw(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
		float yaw = (float) Math.sin(System.nanoTime() / 1e9 * 0.6) * 25; // a slow sway, to show it's 3D
		ScreenRectangle box = new ScreenRectangle(x0, y0, x1 - x0, y1 - y0);
		((dev.strafemc.mixin.client.GuiGraphicsExtractorAccessor) g).strafemc$renderState()
				.addPicturesInPictureState(new State(x0, y0, x1, y1, yaw, null, box));
	}
}
