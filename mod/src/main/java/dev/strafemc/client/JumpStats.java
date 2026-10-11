package dev.strafemc.client;

import dev.strafemc.client.movement.SourceMovement;
import dev.strafemc.config.StrafeConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * KZ-style jump stats (cl_jumpstats) and a speed graph (cl_velgraph). Distance follows the KZ convention:
 * horizontal takeoff-to-landing distance + 32 units (the hull width). Sync = % of air ticks that gained speed.
 */
public final class JumpStats {
	public static final JumpStats INSTANCE = new JumpStats();
	private static final int GRAPH = 192; // 3 s at 64 Hz

	private final float[] graph = new float[GRAPH];
	private int graphPos;
	private boolean inJump;
	private double sx, sy, sz, pre, max, lastSpeed;
	private int airTicks, gainTicks, strafes, lastSide;
	private String last;
	private long lastUntil;

	private JumpStats() {}

	/** Called after every sim step. */
	public void afterStep(SourceMovement s, double side) {
		double sp = s.horizontalSpeed();
		graph[graphPos] = (float) sp;
		graphPos = (graphPos + 1) % GRAPH;
		if (s.jumpedThisStep && !s.onLadder) {
			inJump = true;
			sx = s.x;
			sy = s.y;
			sz = s.z;
			pre = sp;
			max = sp;
			airTicks = gainTicks = strafes = 0;
			lastSide = 0;
			lastSpeed = sp;
			return;
		}
		if (!inJump) return;
		if (s.onLadder || s.noclip || s.waterLevel >= 2) {
			inJump = false;
			return;
		}
		if (!s.onGround) {
			airTicks++;
			if (sp > lastSpeed + 1e-6) gainTicks++;
			int sd = (int) Math.signum(side);
			if (sd != 0 && sd != lastSide) strafes++;
			if (sd != 0) lastSide = sd;
			max = Math.max(max, sp);
			lastSpeed = sp;
		}
		if (s.landedThisStep) {
			inJump = false;
			double dist = Math.hypot(s.x - sx, s.z - sz) + 32.0;
			double dh = s.y - sy;
			int sync = airTicks == 0 ? 0 : Math.round(100f * gainTicks / airTicks);
			last = String.format("%.1f u  |  pre %.0f  max %.0f  |  %d strafes  %d%% sync%s", dist, pre, max, strafes, sync,
					Math.abs(dh) > 0.5 ? String.format("  |  %+.0f u height", dh) : "");
			lastUntil = System.currentTimeMillis() + 4000;
		}
	}

	public static void register() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("strafemc", "jumpstats"), (g, delta) -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || mc.options.hideGui) return;
			JumpStats js = INSTANCE;
			int w = g.guiWidth(), h = g.guiHeight();
			if (StrafeConfig.jumpStats && js.last != null && System.currentTimeMillis() < js.lastUntil) {
				g.centeredText(mc.font, js.last, w / 2, h / 2 + 24, 0xFFFFE08A);
			}
			if (StrafeConfig.velGraph) {
				int gw = GRAPH, gh = 40, x0 = (w - gw) / 2, y0 = h - 62;
				g.fill(x0 - 1, y0 - gh - 1, x0 + gw + 1, y0 + 1, 0x60000000);
				double top = 400;
				for (int i = 0; i < GRAPH; i++) top = Math.max(top, js.graph[i]);
				for (int i = 0; i < GRAPH; i++) {
					float v = js.graph[(js.graphPos + i) % GRAPH];
					int bh = (int) Math.round(gh * v / top);
					g.fill(x0 + i, y0 - bh, x0 + i + 1, y0, 0xC08CE68C);
				}
				g.text(mc.font, String.format("%.0f", js.graph[(js.graphPos + GRAPH - 1) % GRAPH]), x0 + gw + 3, y0 - 8, 0xFFFFFFFF, true);
			}
		});
	}
}
