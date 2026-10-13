package dev.strafemc.client.weapon;

import com.mojang.blaze3d.platform.NativeImage;
import dev.strafemc.StrafeMc;
import dev.strafemc.config.StrafeConfig;
import dev.strafemc.weapon.Grenade;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CS2's grenade throw crosshair: a big "+" with ticks every 10 degrees of view angle and their labels (1x..5x across,
 * 1y..5y up/down) for lineups, shown once the pin has been pulled for cl_grenadecrosshairdelay_* seconds. CS2's
 * numbers: ticks to the half horizontal FOV / 10 (5 at CS2's 106 degrees), each tick round(20 s) px long, labels
 * round(20 s) x 1.28 px high, lines s px wide (rounded from 1 up), where s = cl_grenadecrosshair_tickscaling (0.5..2,
 * default 1.1); delay 0..5 s, default 2; on for every grenade by default; labels from CS2's own glyph atlas.
 * FLAGGED (ours): s is taken as screen pixels at 1080p and scaled with the screen height, label placement next to the
 * tick, and the white colour.
 */
public final class GrenadeCrosshair {
	static final String ATLAS = "materials/dev/grenade_crosshair_numerals_psd_2d59031b.vtex_c";
	static final double STEP_DEG = 10, TICK = 20, LABEL = 1.28;
	/** The atlas: 4 columns (-Nx, Nx, -Ny, Ny) x 5 rows (N = 5 at the top down to 1), white on black. */
	private static final int COLS = 4, ROWS = 5;

	private static volatile NativeImage atlasImage;
	private static Identifier atlas;
	private static volatile boolean loading;

	private GrenadeCrosshair() {}

	/** CS2's per-grenade cvar group: explosive, flash, smoke, fire, decoy. */
	public static int kind(Grenade g) {
		return switch (g) {
			case HE -> 0;
			case FLASHBANG -> 1;
			case SMOKE -> 2;
			case MOLOTOV, INCENDIARY -> 3;
			case DECOY -> 4;
		};
	}

	public static final String[] KINDS = {"explosive", "flash", "smoke", "fire", "decoy"};

	public static void render(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.hideGui || mc.player == null || !mc.options.getCameraType().isFirstPerson()) return;
		Grenade type = GunClient.INSTANCE.pinGrenade();
		double held = GunClient.INSTANCE.pinSeconds();
		if (type == null || held < 0) return;
		int k = kind(type);
		if (!StrafeConfig.grenadeCrosshair[k] || held < StrafeConfig.grenadeCrosshairDelay[k]) return;

		double gs = mc.getWindow().getGuiScale();
		double screenH = mc.getWindow().getHeight(), screenW = mc.getWindow().getWidth();
		double vfov = Math.toRadians(mc.options.fov().get());
		double focal = (screenH / 2) / Math.tan(vfov / 2); // px per unit of tan(angle)
		double hfovDeg = Math.toDegrees(2 * Math.atan(Math.tan(vfov / 2) * screenW / screenH));
		int n = (int) Math.floor(hfovDeg * 0.5 / STEP_DEG);
		if (n <= 0) return;
		double s = StrafeConfig.grenadeCrosshairTickScaling * screenH / 1080;
		double line = s >= 1 ? Math.round(s) : s, tick = Math.round(TICK * s), label = Math.round(TICK * s) * LABEL;

		float cx = g.guiWidth() / 2f, cy = g.guiHeight() / 2f;
		int col = 0xE6FFFFFF;
		double reach = focal * Math.tan(Math.toRadians(n * STEP_DEG));
		// the "+": across and up/down as far as the outermost ticks
		fill(g, cx - reach / gs, cy - line / 2 / gs, cx + reach / gs, cy + line / 2 / gs, col);
		fill(g, cx - line / 2 / gs, cy - reach / gs, cx + line / 2 / gs, cy + reach / gs, col);
		Identifier tex = labels(mc);
		for (int i = 1; i <= n; i++) {
			double d = focal * Math.tan(Math.toRadians(i * STEP_DEG)) / gs, t = tick / 2 / gs, w = line / 2 / gs;
			for (int sign = -1; sign <= 1; sign += 2) {
				fill(g, cx + sign * d - w, cy - t, cx + sign * d + w, cy + t, col); // across: ticks standing up
				fill(g, cx - t, cy + sign * d - w, cx + t, cy + sign * d + w, col); // up/down: ticks lying flat
				if (!StrafeConfig.grenadeCrosshairTickLabels || tex == null || i > ROWS) continue;
				double lh = label / gs, lw = lh * (256.0 / COLS) / (256.0 / ROWS);
				int row = ROWS - i;
				// x labels under their tick, y labels right of theirs (screen up is +y in CS2's labels)
				glyph(g, tex, sign < 0 ? 0 : 1, row, cx + sign * d - lw / 2, cy + t + 1, lw, lh, col);
				glyph(g, tex, sign < 0 ? 2 : 3, row, cx + t + 1, cy - sign * d - lh / 2, lw, lh, col);
			}
		}
	}

	private static void fill(GuiGraphicsExtractor g, double x0, double y0, double x1, double y1, int col) {
		g.fill((int) Math.round(x0), (int) Math.round(y0), (int) Math.max(Math.round(x0) + 1, Math.round(x1)), (int) Math.max(Math.round(y0) + 1, Math.round(y1)), col);
	}

	private static void glyph(GuiGraphicsExtractor g, Identifier tex, int c, int r, double x, double y, double w, double h, int col) {
		int cw = 256 / COLS, ch = 256 / ROWS;
		g.blit(RenderPipelines.GUI_TEXTURED, tex, (int) Math.round(x), (int) Math.round(y), c * cw, r * ch, (int) Math.round(w), (int) Math.round(h),
				cw, ch, 256, 256, col);
	}

	/** The glyph atlas (converted from CS2 once, in the background); null until ready or without CS2 files. */
	private static Identifier labels(Minecraft mc) {
		if (atlas != null) return atlas;
		NativeImage img = atlasImage;
		if (img != null) {
			atlas = Identifier.fromNamespaceAndPath("strafemc", "grenade_crosshair_numerals");
			mc.getTextureManager().register(atlas, new DynamicTexture(() -> "strafemc grenade crosshair", img));
			atlasImage = null;
			return atlas;
		}
		if (!loading && dev.strafemc.data.AssetCache.available()) {
			loading = true;
			Thread t = new Thread(GrenadeCrosshair::load, "strafemc-grenade-crosshair");
			t.setDaemon(true);
			t.start();
		}
		return null;
	}

	private static void load() {
		try {
			Path png = StrafeConfig.cacheDir().resolve(ATLAS.substring(0, ATLAS.length() - "vtex_c".length()) + "png");
			dev.strafemc.data.AssetCache.ensureDecompiled(ATLAS, png);
			try (var in = Files.newInputStream(png)) {
				NativeImage img = NativeImage.read(in);
				for (int y = 0; y < img.getHeight(); y++) // white glyphs on black: brightness is the coverage
					for (int x = 0; x < img.getWidth(); x++) {
						int c = img.getPixel(x, y), l = Math.max(c & 255, Math.max((c >> 8) & 255, (c >> 16) & 255));
						img.setPixel(x, y, l << 24 | 0xFFFFFF);
					}
				atlasImage = img;
			}
		} catch (Exception e) {
			StrafeMc.LOG.warn("[strafemc] grenade crosshair labels unavailable: {}", e.toString());
		}
	}
}
