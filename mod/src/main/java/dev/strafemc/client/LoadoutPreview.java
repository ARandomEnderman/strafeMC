package dev.strafemc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;

/** The loadout's 3D preview: a picture-in-picture view of the worn gloves or a weapon in its wrap (Viewmodel), slowly turning. */
public final class LoadoutPreview {
	private LoadoutPreview() {}

	/** weapon: the weapon class to show (in its wrap's finish), null for the worn gloves. */
	public record State(int x0, int y0, int x1, int y1, float yaw, String weapon, ScreenRectangle scissorArea, ScreenRectangle bounds) implements PictureInPictureRenderState {
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
			var vm = dev.strafemc.client.viewmodel.Viewmodel.INSTANCE;
			if (s.weapon() == null) vm.previewArms(ps, bufferSource, s.x1() - s.x0(), s.y1() - s.y0(), s.yaw());
			else vm.previewWeapon(s.weapon(), ps, bufferSource, s.x1() - s.x0(), s.y1() - s.y0(), s.yaw());
			bufferSource.endBatch();
		}

		/** The picture's middle, not its bottom edge (vanilla's default, for entities standing on the ground). */
		@Override
		protected float getTranslateY(int height, int guiScale) {
			return height / 2f;
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

	/** Adds the preview to this frame's GUI in the given box: the worn gloves (weapon null) or a weapon in its wrap's finish. */
	public static void draw(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, String weapon) {
		float yaw = (float) Math.sin(System.nanoTime() / 1e9 * 0.6) * 25; // a slow sway, to show it's 3D
		ScreenRectangle box = new ScreenRectangle(x0, y0, x1 - x0, y1 - y0);
		((dev.strafemc.mixin.client.GuiGraphicsExtractorAccessor) g).strafemc$renderState()
				.addPicturesInPictureState(new State(x0, y0, x1, y1, yaw, weapon, null, box));
	}
}
