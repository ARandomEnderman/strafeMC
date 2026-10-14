package dev.strafemc.client.weapon;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * CS2's sniper scope (panorama hud/scope) while scoped with an AWP, SSG 08, SCAR-20 or G3SG1: the round view (scope_circle,
 * as high as the screen) in black, the lens tint over it, and the reticle as thin black lines across the screen, blurred
 * wider (scope_line_blur) the less accurate the gun is right now. The Minecraft crosshair is hidden meanwhile.
 * How wide the blur gets per unit of inaccuracy is our own choice (FLAGGED: CS2 does this in panorama code).
 */
public final class ScopeHud {
	private static final Identifier CIRCLE = Identifier.fromNamespaceAndPath("strafemc", "textures/gui/scope/circle.png");
	private static final Identifier LENS = Identifier.fromNamespaceAndPath("strafemc", "textures/gui/scope/lens.png");
	private static final Identifier BLUR = Identifier.fromNamespaceAndPath("strafemc", "textures/gui/scope/line_blur.png");
	/** Blur width in screen heights per unit of inaccuracy. */
	private static final double BLUR_PER_INACCURACY = 0.5;

	private ScopeHud() {}

	public static void register() {
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, old -> (g, delta) -> {
			if (!GunClient.INSTANCE.scopeOverlay()) old.extractRenderState(g, delta);
		});
		HudElementRegistry.attachElementBefore(VanillaHudElements.HOTBAR, Identifier.fromNamespaceAndPath("strafemc", "scope"), (g, delta) -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || !GunClient.INSTANCE.scopeOverlay()) return;
			// drawn in real pixels so the reticle stays one pixel thin at any GUI scale
			float k = (float) mc.getWindow().getGuiScale();
			int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
			var pose = g.pose();
			pose.pushMatrix();
			pose.scale(1 / k, 1 / k);
			int x0 = (w - h) / 2;
			boolean tex = dev.strafemc.client.assets.RuntimePack.hasTexture("gui/scope/circle.png");
			if (tex) { // the lens tint under the round mask, so the mask's edge stays black
				if (dev.strafemc.client.assets.RuntimePack.hasTexture("gui/scope/lens.png")) g.blit(RenderPipelines.GUI_TEXTURED, LENS, x0, 0, 0, 0, h, h, h, h);
				g.blit(RenderPipelines.GUI_TEXTURED, CIRCLE, x0, 0, 0, 0, h, h, h, h);
			}
			g.fill(0, 0, x0, h, 0xFF000000); // the sides past the round view
			g.fill(x0 + h, 0, w, h, 0xFF000000);
			int cx = w / 2, cy = h / 2;
			g.fill(0, cy, w, cy + 1, 0xFF000000);
			g.fill(cx, 0, cx + 1, h, 0xFF000000);
			int blur = (int) Math.round(GunClient.INSTANCE.inaccuracy() * h * BLUR_PER_INACCURACY);
			if (blur > 2 && dev.strafemc.client.assets.RuntimePack.hasTexture("gui/scope/line_blur.png")) {
				// the blur texture is a vertical line: as is for the vertical one, its columns stretched for the horizontal one
				g.blit(RenderPipelines.GUI_TEXTURED, BLUR, cx - blur / 2, 0, 0, 0, blur, h, 64, 64, 64, 64);
				pose.pushMatrix();
				pose.translate(0, cy + blur / 2f);
				pose.rotate((float) (-Math.PI / 2));
				g.blit(RenderPipelines.GUI_TEXTURED, BLUR, 0, 0, 0, 0, blur, w, 64, 64, 64, 64);
				pose.popMatrix();
			}
			pose.popMatrix();
		});
	}
}
