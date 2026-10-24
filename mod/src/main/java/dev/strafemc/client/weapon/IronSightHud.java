package dev.strafemc.client.weapon;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * CS2's iron sight view (AUG, SG 553) as its controller draws it, while the sight comes up:
 * - the filter (scope_filter: black, a round see-through middle darkened ~47%) as a square screen height / 1080 x 900 px
 *   wide around the sight, cl_ironsight_filter_alpha 1, black past it;
 * - the dot (scope.vmat, scope_dot_white): (24 x v + 86 x inaccuracy / 0.35 + 12) x cl_ironsight_dot_scale (1) px at
 *   1080p, fading by 1 - size / 122 x 0.3 once over 12 px, each colour channel at least cl_ironsight_min_channel_color
 *   (0.3; white by default).
 * FLAGGED ours: centred on the screen (CS2 projects a point 2000 units down the sight, so it follows the gun's sway),
 * v taken as how far the sight still has to come up, the filter's strength following the sight amount, the dot alpha
 * blended (CS2's is additive at intensity 6).
 */
public final class IronSightHud {
	private static final Identifier FILTER = Identifier.fromNamespaceAndPath("strafemc", "textures/gui/ironsight/filter.png");
	private static final Identifier DOT = Identifier.fromNamespaceAndPath("strafemc", "textures/gui/ironsight/dot.png");
	/** cl_ironsight_filter_alpha, cl_ironsight_dot_scale (CS2's defaults). */
	private static final double FILTER_ALPHA = 1, DOT_SCALE = 1;

	private IronSightHud() {}

	public static void register() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.HOTBAR, Identifier.fromNamespaceAndPath("strafemc", "ironsight"), (g, delta) -> {
			Minecraft mc = Minecraft.getInstance();
			double aim = GunClient.INSTANCE.ironsight();
			if (mc.player == null || aim <= 0 || !dev.strafemc.client.assets.RuntimePack.hasTexture("gui/ironsight/filter.png")) return;
			// drawn in real pixels, as CS2 sizes it from the screen height
			float k = (float) mc.getWindow().getGuiScale();
			int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
			var pose = g.pose();
			pose.pushMatrix();
			pose.scale(1 / k, 1 / k);
			int size = (int) Math.round(h / 1080.0 * 900), x0 = (w - size) / 2, y0 = (h - size) / 2;
			int a = (int) Math.round(255 * FILTER_ALPHA * aim) << 24;
			g.blit(RenderPipelines.GUI_TEXTURED, FILTER, x0, y0, 0, 0, size, size, size, size, size, size, a | 0xFFFFFF);
			g.fill(0, 0, w, y0, a); // past the filter's square: black, as its own edge
			g.fill(0, y0 + size, w, h, a);
			g.fill(0, y0, x0, y0 + size, a);
			g.fill(x0 + size, y0, w, y0 + size, a);
			if (dev.strafemc.client.assets.RuntimePack.hasTexture("gui/ironsight/dot.png")) {
				double dot = (24 * (1 - aim) + 86 * GunClient.INSTANCE.inaccuracy() / 0.35 + 12) * DOT_SCALE;
				double fade = dot > 12 ? Math.max(0, Math.min(1, 1 - dot / 122 * 0.3)) : 1;
				int d = Math.max(1, (int) Math.round(dot * h / 1080.0));
				int da = (int) Math.round(255 * fade * aim) << 24;
				g.blit(RenderPipelines.GUI_TEXTURED, DOT, (w - d) / 2, (h - d) / 2, 0, 0, d, d, d, d, d, d, da | 0xFFFFFF);
			}
			pose.popMatrix();
		});
	}
}
