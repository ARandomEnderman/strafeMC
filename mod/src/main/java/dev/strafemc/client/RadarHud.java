package dev.strafemc.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import dev.strafemc.net.StrafePayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.MapColor;

import java.util.HashMap;
import java.util.Map;

/**
 * CS2's radar (hudradar), drawn top left in game modes: its round radar (250 px at 1080p, a 1 px white edge, in a
 * 300 px panel 40 px down) that turns with your view, the map under it, teammates as dots (icon-on-map) in their
 * teammate colour in Competitive and Wingman (CS2's cl_teammate_color_1-5) with the colour's letter on it, else in
 * their side's colour (CT #1ed2ff, T #ffdd23), each with CS2's white arrow toward where they look; enemies the server
 * says are spotted as red diamonds, crosses where teammates died, the bomb (carried, dropped with CS2's pulsing ring,
 * planted blinking red), the bomb site letters, and teammates past the edge as arrows on it. A marker above or below
 * a dot when they're a floor up or down. What's on it comes from the server (mode/Radar); the map is read from the
 * blocks around the camera, as CS2 maps have a drawn overview and Minecraft worlds don't.
 * FLAGGED ours: the scale (RANGE blocks to the edge), how the block map looks, a spotted enemy fading for a second
 * once it's lost, the background's darkness, the letters (the colour's initial).
 */
public final class RadarHud {
	/** Blocks from the middle to the edge. */
	private static final double RANGE = 32;
	private static final int CT = 0xFF1ED2FF, T = 0xFFFFDD23, SITE = 0xFFFFCC00;
	/** CS2's cl_teammate_color_1-5 defaults: blue, green, yellow, orange, purple; and their letters. */
	private static final int[] COLOURS = {0xFF88CEF5, 0xFF009E80, 0xFFF1E441, 0xFFE6802A, 0xFFBD2C96};
	private static final String[] LETTERS = {"B", "G", "Y", "O", "P"};
	private static final long LOST_MS = 1000;

	private record Mark(int id, double x, double y, double z, float yaw, boolean t, boolean dead, boolean enemy, boolean bomb, int c) {}

	private static Map<Integer, Mark> now = new HashMap<>(), before = new HashMap<>();
	/** Spotted enemies no longer spotted: where they were, when they went. */
	private static final Map<Integer, Mark> lost = new HashMap<>();
	private static final Map<Integer, Long> lostAt = new HashMap<>();
	private static JsonObject bomb;
	private static com.google.gson.JsonArray sites = new com.google.gson.JsonArray();
	private static long receivedAt;
	/** Your teammate colour (-1: none, the side's). */
	private static int me = -1;

	private RadarHud() {}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(StrafePayloads.RadarState.TYPE, (msg, ctx) -> ctx.client().execute(() -> receive(msg.json())));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> {
			now = new HashMap<>();
			before = new HashMap<>();
			receivedAt = 0;
		});
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("strafemc", "radar"), (g, delta) -> draw(g, delta.getGameTimeDeltaPartialTick(false)));
	}

	private static void receive(String json) {
		JsonObject o;
		try {
			o = JsonParser.parseString(json).getAsJsonObject();
		} catch (Exception e) {
			return;
		}
		Map<Integer, Mark> next = new HashMap<>();
		for (JsonElement el : o.getAsJsonArray("p")) {
			JsonObject x = el.getAsJsonObject();
			Mark m = new Mark(x.get("id").getAsInt(), x.get("x").getAsDouble(), x.get("y").getAsDouble(), x.get("z").getAsDouble(), x.get("yaw").getAsFloat(),
					x.get("t").getAsBoolean(), x.has("dead"), x.has("enemy"), x.has("bomb"), x.has("c") ? x.get("c").getAsInt() : -1);
			next.put(m.id, m);
			lost.remove(m.id);
		}
		long t = System.currentTimeMillis();
		for (Mark m : now.values())
			if (m.enemy && !next.containsKey(m.id)) {
				lost.put(m.id, m);
				lostAt.put(m.id, t);
			}
		before = now;
		now = next;
		bomb = o.has("bomb") ? o.getAsJsonObject("bomb") : null;
		me = o.has("me") ? o.get("me").getAsInt() : -1;
		sites = o.has("sites") ? o.getAsJsonArray("sites") : new com.google.gson.JsonArray();
		receivedAt = t;
	}

	private static Identifier tex(String name) {
		return Identifier.fromNamespaceAndPath("strafemc", "textures/gui/radar/" + name + ".png");
	}

	private static boolean has(String name) {
		return dev.strafemc.client.assets.RuntimePack.hasTexture("gui/radar/" + name + ".png");
	}

	private static void draw(GuiGraphicsExtractor g, float pt) {
		var mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || !ModeHud.active() || System.currentTimeMillis() - receivedAt > 2000) return;
		if (ModeHud.state().has("blackout")) return;
		Entity cam = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
		float u = g.guiHeight() / 1080f;
		int size = Math.round(250 * u); // the round radar, centred in its 300 px panel at y 40
		float cx = 150 * u, cy = (40 + 150) * u, s = (float) (size / 2.0 / RANGE), rad = size / 2f;
		double camX = net.minecraft.util.Mth.lerp(pt, cam.xo, cam.getX()), camZ = net.minecraft.util.Mth.lerp(pt, cam.zo, cam.getZ()), camY = cam.getY();
		float yaw = cam.getViewYRot(pt);
		double yr = Math.toRadians(yaw), cos = Math.cos(yr), sin = Math.sin(yr);
		Map3.update(mc.level, cam);
		// the disc: drawn a strip at a time, each cut to the circle's width there (the background, then the map)
		int top = Math.round(cy - rad), bottom = Math.round(cy + rad);
		for (int y = top; y < bottom; y++) {
			float dy = y + 0.5f - cy, half = (float) Math.sqrt(Math.max(0, rad * rad - dy * dy));
			g.fill(Math.round(cx - half), y, Math.round(cx + half), y + 1, 0xA0101214);
		}
		// the map: world (dx, dz) to the radar is a turn by (pi - yaw), so where you look is up
		if (Map3.ready)
			for (int y = top; y < bottom; y += 2) {
				float dy = y + 1 - cy, half = (float) Math.sqrt(Math.max(0, rad * rad - dy * dy));
				if (half < 1) continue;
				g.enableScissor(Math.round(cx - half), y, Math.round(cx + half), y + 2);
				var pose = g.pose();
				pose.pushMatrix();
				pose.translate(cx, cy);
				pose.rotate((float) (Math.PI - yr));
				pose.scale(s, s);
				pose.translate((float) (Map3.shownX - camX), (float) (Map3.shownZ - camZ));
				g.blit(RenderPipelines.GUI_TEXTURED, Map3.ID, 0, 0, 0f, 0f, Map3.N, Map3.N, Map3.N, Map3.N, 0xD0FFFFFF);
				pose.popMatrix();
				g.disableScissor();
			}
		ring(g, cx, cy, rad, 0xFFFFFFFF);
		long t = System.currentTimeMillis();
		float f = Math.min(1, (t - receivedAt) / 200f); // between the last two snapshots (sent every 4 ticks)
		// bomb sites
		for (JsonElement el : sites) {
			JsonObject st = el.getAsJsonObject();
			float[] p = screen(st.get("x").getAsDouble() - camX, st.get("z").getAsDouble() - camZ, cos, sin, s);
			String n = "bomb-zone-" + st.get("l").getAsString().toLowerCase();
			float h = 0.06f * 300 * u, w = h * 24 / 38f;
			if (inside(p, size)) {
				if (has(n)) icon(g, tex(n), 24, 38, cx + p[0], cy + p[1], w, h, 0, (SITE & 0x00FFFFFF) | 0xB3000000);
				else g.text(mc.font, st.get("l").getAsString(), Math.round(cx + p[0] - 3), Math.round(cy + p[1] - 4), SITE, true);
			}
		}
		// teammates, enemies, crosses
		for (Mark m : now.values()) {
			Mark b = before.get(m.id);
			double mx = m.x, my = m.y, mz = m.z;
			float myaw = m.yaw;
			Entity e = m.dead ? null : mc.level.getEntity(m.id);
			if (e != null) { // loaded here: its own smooth movement
				mx = net.minecraft.util.Mth.lerp(pt, e.xo, e.getX());
				mz = net.minecraft.util.Mth.lerp(pt, e.zo, e.getZ());
				my = e.getY();
				myaw = e.getYHeadRot();
			} else if (b != null && !m.dead && !b.dead) {
				mx = b.x + (m.x - b.x) * f;
				mz = b.z + (m.z - b.z) * f;
			}
			drawMark(g, m, mx, my, mz, myaw, camX, camY, camZ, yaw, cos, sin, s, cx, cy, size, u, 1f);
		}
		for (var it = lost.entrySet().iterator(); it.hasNext();) {
			var en = it.next();
			float a = 1 - (t - lostAt.getOrDefault(en.getKey(), 0L)) / (float) LOST_MS;
			if (a <= 0) {
				it.remove();
				continue;
			}
			Mark m = en.getValue();
			drawMark(g, m, m.x, m.y, m.z, m.yaw, camX, camY, camZ, yaw, cos, sin, s, cx, cy, size, u, a);
		}
		// the bomb
		if (bomb != null) {
			String state = bomb.get("s").getAsString();
			float[] p = screen(bomb.get("x").getAsDouble() - camX, bomb.get("z").getAsDouble() - camZ, cos, sin, s);
			clamp(p, size, 10 * u);
			float bx = cx + p[0], by = cy + p[1];
			if (state.equals("planted")) { // PlantedBombAnimateRed: a red pulse every 0.65 s
				float pulse = (float) (0.55 + 0.45 * Math.sin(t / 650.0 * Math.PI * 2));
				Identifier c4 = Identifier.fromNamespaceAndPath("strafemc", "textures/gui/mode/bomb_planted.png");
				icon(g, c4, 64, 64, bx, by, 24 * u * 1.4f, 24 * u * 1.4f, 0, (Math.round(255 * pulse) << 24) | 0xFF3030);
			} else if (!state.equals("carried")) {
				// CS2's DroppedBomb ring: grows from 0.1 to full over 0.75 s, fading at the end
				float ph = (t % 750) / 750f, r = 40 * u * Math.max(0.1f, ph);
				ring(g, bx, by, r, ((int) (255 * (ph < 0.75f ? 1 : (1 - ph) * 4)) << 24) | 0xFFFFFF);
				if (has("c4_sml")) icon(g, tex("c4_sml"), 55, 39, bx, by, 16 * u * 1.4f, 12 * u * 1.4f, 0, 0xFFFFFFFF);
				else g.fill(Math.round(bx - 3), Math.round(by - 2), Math.round(bx + 3), Math.round(by + 2), 0xFFFF4040);
			}
		}
		// you: the middle, looking up, with CS2's faint view cone
		boolean meT = ModeHud.state().has("t") && ModeHud.state().get("t").getAsBoolean();
		boolean ffa = ModeHud.state().has("ffa") && ModeHud.state().get("ffa").getAsBoolean();
		int mine = me >= 0 ? COLOURS[me] : ffa ? 0xFFFFFFFF : meT ? T : CT;
		if (has("view_angle")) icon(g, tex("view_angle"), 128, 64, cx, cy - 32 * u * 1.4f, 128 * u * 1.4f, 64 * u * 1.4f, 0, 0x14FFFFFF);
		dot(g, cx, cy, 0, mine, me >= 0 ? LETTERS[me] : null, u, 1f);
	}

	private static void drawMark(GuiGraphicsExtractor g, Mark m, double mx, double my, double mz, float myaw, double camX, double camY, double camZ,
			float yaw, double cos, double sin, float s, float cx, float cy, int size, float u, float alpha) {
		float[] p = screen(mx - camX, mz - camZ, cos, sin, s);
		int col = m.enemy ? 0xFFFF1919 : m.c >= 0 ? COLOURS[m.c] : m.t ? T : CT;
		String letter = m.c >= 0 && !m.enemy ? LETTERS[m.c] : null;
		float rot = (float) Math.toRadians(myaw - yaw);
		if (m.dead) {
			if (!inside(p, size)) return;
			if (has("death")) icon(g, tex("death"), 38, 38, cx + p[0], cy + p[1], 19 * u * 1.2f, 19 * u * 1.2f, 0, withAlpha(col, alpha));
			else g.text(Minecraft.getInstance().font, "x", Math.round(cx + p[0] - 2), Math.round(cy + p[1] - 4), withAlpha(col, alpha), true);
			return;
		}
		if (!inside(p, size)) { // past the edge: an arrow on it (teammates; enemies just aren't shown)
			if (m.enemy) return;
			float ang = (float) Math.atan2(p[1], p[0]);
			clamp(p, size, 9 * u);
			if (has("offmap")) icon(g, tex("offmap"), 45, 44,cx + p[0], cy + p[1], 22 * u, 22 * u, ang + (float) (Math.PI / 2), withAlpha(col, 0.8f * alpha));
			else dot(g, cx + p[0], cy + p[1], 0, col, null, u * 0.7f, alpha);
			return;
		}
		float x = cx + p[0], y = cy + p[1];
		if (m.enemy) {
			if (has("enemy-on-map")) icon(g, tex("enemy-on-map"), 32, 32, x, y, 13 * u * 1.4f, 13 * u * 1.4f, 0, withAlpha(0xFFFFFFFF, 0.95f * alpha));
			else dot(g, x, y, 0, col, null, u, alpha);
		} else dot(g, x, y, rot, col, letter, u, alpha);
		double dy = my - camY;
		if (Math.abs(dy) > 2.5) { // a floor up or down
			String n = dy > 0 ? "above" : "below";
			if (has(n)) icon(g, tex(n), 33, 28, x, y + (dy > 0 ? -8 : 8) * u * 1.4f, 13 * u * 1.2f, 11 * u * 1.2f, 0, withAlpha(0xFFCCCCCC, 0.65f * alpha));
		}
		if (m.bomb && has("c4_sml")) icon(g, tex("c4_sml"), 55, 39, x + 9 * u, y + 7 * u, 16 * u, 12 * u, 0, withAlpha(0xFFFFFFFF, alpha));
	}

	/** A player's dot (icon-on-map washed in their colour, its letter on it) and CS2's arrow toward where they look. */
	private static void dot(GuiGraphicsExtractor g, float x, float y, float rot, int col, String letter, float u, float alpha) {
		float d = 11 * u * 1.4f;
		if (has("direction")) {
			var pose = g.pose();
			pose.pushMatrix();
			pose.translate(x, y);
			pose.rotate(rot);
			icon(g, tex("direction"), 40, 40, 0, -8 * u * 1.4f, 11 * u * 1.4f, 11 * u * 1.4f, 0, withAlpha(0xFFFFFFFF, alpha));
			pose.popMatrix();
		}
		if (has("on-map")) icon(g, tex("on-map"), 20, 20, x, y, d, d, 0, withAlpha(col, alpha));
		else g.fill(Math.round(x - d / 2), Math.round(y - d / 2), Math.round(x + d / 2), Math.round(y + d / 2), withAlpha(col, alpha));
		if (letter != null) { // PI_Text: 10 px, black, in the middle
			var font = Minecraft.getInstance().font;
			var pose = g.pose();
			pose.pushMatrix();
			pose.translate(x, y);
			float k = 10 * u * 1.4f / font.lineHeight;
			pose.scale(k, k);
			g.text(font, letter, -font.width(letter) / 2, -font.lineHeight / 2 + 1, withAlpha(0xFF000000, alpha), false);
			pose.popMatrix();
		}
	}

	private static int withAlpha(int argb, float a) {
		return (Math.round(((argb >>> 24) & 0xFF) * Math.max(0, Math.min(1, a))) << 24) | (argb & 0x00FFFFFF);
	}

	/** A world offset (blocks) to the radar's offset from its middle (GUI units): where you look is up. */
	private static float[] screen(double dx, double dz, double cos, double sin, float s) {
		return new float[] {(float) ((-dx * cos - dz * sin) * s), (float) ((dx * sin - dz * cos) * s)};
	}

	private static boolean inside(float[] p, int size) {
		return p[0] * p[0] + p[1] * p[1] <= size * size / 4f;
	}

	/** Pulls a point back onto the circle's edge (minus a margin) along its direction from the middle. */
	private static void clamp(float[] p, int size, float margin) {
		float lim = size / 2f - margin, m = (float) Math.sqrt(p[0] * p[0] + p[1] * p[1]);
		if (m > lim) {
			p[0] *= lim / m;
			p[1] *= lim / m;
		}
	}

	/** A texture of tw x th pixels drawn w x h GUI units, centred on (x, y), turned by rot. */
	private static void icon(GuiGraphicsExtractor g, Identifier id, int tw, int th, float x, float y, float w, float h, float rot, int color) {
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		if (rot != 0) pose.rotate(rot);
		pose.scale(w / tw, h / th);
		g.blit(RenderPipelines.GUI_TEXTURED, id, -tw / 2, -th / 2, 0f, 0f, tw, th, tw, th, color);
		pose.popMatrix();
	}

	/** A thin ring: short segments around a circle. */
	private static void ring(GuiGraphicsExtractor g, float x, float y, float r, int color) {
		int n = Math.max(32, Math.round(r));
		var pose = g.pose();
		for (int i = 0; i < n; i++) {
			double a = i * Math.PI * 2 / n;
			pose.pushMatrix();
			pose.translate((float) (x + Math.cos(a) * r), (float) (y + Math.sin(a) * r));
			pose.rotate((float) (a + Math.PI / 2));
			float len = (float) (Math.PI * 2 * r / n) + 0.5f;
			pose.scale(len, 1);
			g.fill(0, 0, 1, 1, color);
			pose.popMatrix();
		}
	}

	/**
	 * The map under the radar: N x N blocks around the camera, one pixel each, read a few rows a frame into a back image
	 * and shown once it's whole. Each column is the first solid block from head height down to 12 below the feet: at
	 * body height a wall (light), else the floor, darker the further down it is; nothing there stays clear.
	 */
	private static final class Map3 {
		static final int N = 128, ROWS = 16;
		static final Identifier ID = Identifier.fromNamespaceAndPath("strafemc", "radar_map");
		static DynamicTexture shown;
		static NativeImage back;
		static boolean ready;
		static int shownX, shownZ, buildX, buildZ, buildY, row = -1;

		static void update(Level level, Entity cam) {
			if (shown == null) {
				shown = new DynamicTexture(() -> "strafemc radar map", N, N, true);
				Minecraft.getInstance().getTextureManager().register(ID, shown);
				back = new NativeImage(N, N, true);
			}
			if (row < 0) { // a new picture around where the camera is now
				buildX = net.minecraft.util.Mth.floor(cam.getX()) - N / 2;
				buildZ = net.minecraft.util.Mth.floor(cam.getZ()) - N / 2;
				buildY = net.minecraft.util.Mth.floor(cam.getY() + 0.01);
				row = 0;
			}
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			for (int r = 0; r < ROWS && row < N; r++, row++)
				for (int i = 0; i < N; i++) back.setPixel(i, row, column(level, pos, buildX + i, buildZ + row, buildY));
			if (row >= N) {
				shown.getPixels().copyFrom(back);
				shown.upload();
				shownX = buildX;
				shownZ = buildZ;
				ready = true;
				row = -1;
			}
		}

		private static int column(Level level, BlockPos.MutableBlockPos pos, int x, int z, int feet) {
			for (int y = feet + 1; y >= feet - 12; y--) {
				pos.set(x, y, z);
				var st = level.getBlockState(pos);
				if (st.isAir() || st.getCollisionShape(level, pos).isEmpty() && st.getFluidState().isEmpty()) continue;
				MapColor mc = st.getMapColor(level, pos);
				if (mc == MapColor.NONE) continue;
				int rgb = mc.col;
				int r = (rgb >> 16) & 0xFF, gr = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
				double k;
				if (y >= feet) { // a wall at body height: lighter, as an overview draws them
					k = 1.0;
					r = (r + 255) / 2;
					gr = (gr + 255) / 2;
					b = (b + 255) / 2;
				} else k = Math.max(0.3, 0.72 + 0.05 * (y - (feet - 1))); // the floor, darker the further down
				return 0xFF000000 | (clamp255(r * k) << 16) | (clamp255(gr * k) << 8) | clamp255(b * k);
			}
			return 0;
		}

		private static int clamp255(double v) {
			return (int) Math.max(0, Math.min(255, Math.round(v)));
		}
	}
}
