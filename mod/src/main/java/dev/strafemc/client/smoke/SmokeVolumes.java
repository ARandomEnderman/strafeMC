package dev.strafemc.client.smoke;

import dev.strafemc.weapon.SmokeVolume;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/**
 * The smokes being drawn (client): each one's simulation (SmokeVolume, stepped as the time comes, as CS2's client
 * steps the same simulation as its server), its 32^3 density texture with CS2's layout, its light, its fade (CS2's
 * client curves), plus the HE blasts, flashes and bullets that cut into or light smoke. SmokeRenderer draws them.
 */
public final class SmokeVolumes {
	private SmokeVolumes() {}

	/**
	 * Minecraft's lightmap colour -> the smoke's ambient light. FLAGGED: ours (CS2's light probes have their own scale);
	 * 1 keeps a fully lit smoke at CS2's grey.
	 */
	static final float LIGHT_SCALE = 1.0f;

	public record Blast(Vec3 at, float time) {}

	public record Tracer(Vec3 from, Vec3 to, float time, boolean glow) {}

	public record Sun(Vector3fc dir, float r, float g, float b) {}

	/** What SmokeRenderer needs of a smoke each frame. */
	public interface Drawn {
		int slot();

		Vec3 center();

		Vec3 min();

		Vec3 max();

		byte[] density();

		boolean densityDirty();

		byte[] light();

		boolean lightDirty();

		float age();

		float frameBlend();

		float[] tint();

		float tintAmount();

		float densityScale();

		float shrink();
	}

	private static final List<Smoke> SMOKES = new ArrayList<>();
	private static final List<Blast> BLASTS = new ArrayList<>();
	private static final List<Tracer> TRACERS = new ArrayList<>();
	private static Blast flash;
	/** CS2's team colours: smoke_grenade_ct_color 75 127 155, smoke_grenade_t_color 180 129 50 (replicated cvars). */
	private static final float[] CT = {75 / 255f, 127 / 255f, 155 / 255f}, T = {180 / 255f, 129 / 255f, 50 / 255f};

	public static boolean any() {
		synchronized (SMOKES) {
			return !SMOKES.isEmpty();
		}
	}

	/** A smoke went off at `at` (blocks): its flow is simulated from the same blocks the server sees. */
	public static void add(SmokeVolume volume, boolean t) {
		synchronized (SMOKES) {
			boolean[] used = new boolean[SmokeRenderer.MAX_SMOKES];
			for (Smoke s : SMOKES) used[s.slot] = true;
			int slot = -1;
			for (int i = 0; i < used.length && slot < 0; i++) if (!used[i]) slot = i;
			if (slot < 0) { // CS2 draws 16 at most ("Unable to render more than %d smokes"); here the oldest makes room
				Smoke oldest = SMOKES.remove(0);
				slot = oldest.slot;
			}
			SMOKES.add(new Smoke(volume, slot, t ? T : CT, SmokeRenderer.now()));
		}
	}

	public static void blast(Vec3 at) {
		synchronized (SMOKES) {
			for (Smoke s : SMOKES) s.volume.blast(at);
		}
		Blast b = new Blast(at, SmokeRenderer.now());
		synchronized (BLASTS) {
			BLASTS.add(b);
			if (BLASTS.size() > SmokeRenderer.MAX_BLASTS) BLASTS.remove(0);
		}
		flash = b;
	}

	/** A bullet from `from` to `to` (blocks); a tracer round (`glow`) also lights the smoke along its path. */
	public static void shot(Vec3 from, Vec3 to, boolean glow) {
		float now = SmokeRenderer.now();
		synchronized (TRACERS) {
			TRACERS.add(new Tracer(from, to, now, glow));
			if (TRACERS.size() > SmokeRenderer.MAX_TRACERS) TRACERS.remove(0);
		}
	}

	/**
	 * A flashbang went off: CS2's smoke shader lights smoke within 300 units white for 0.3 s from one point and time.
	 * FLAGGED: which event CS2 feeds it wasn't traced; a flashbang is our reading of it.
	 */
	public static void flash(Vec3 at) {
		flash = new Blast(at, SmokeRenderer.now());
	}

	static List<Blast> blasts() {
		float now = SmokeRenderer.now();
		synchronized (BLASTS) {
			BLASTS.removeIf(b -> now - b.time > 30);
			return new ArrayList<>(BLASTS);
		}
	}

	static List<Tracer> tracers() {
		float now = SmokeRenderer.now();
		synchronized (TRACERS) {
			TRACERS.removeIf(t -> now - t.time > 1.0f); // the shader's tracer light is gone after 0.2 s, the tunnel closes by 1 s
			return new ArrayList<>(TRACERS);
		}
	}

	static Blast lastFlash() {
		return flash;
	}

	/**
	 * The sun: Minecraft's sun direction; its colour by how high it is (FLAGGED: our colour and strength, CS2's comes
	 * from the map's light), gone at night and dimmed by rain.
	 */
	static Sun sun(Minecraft mc) {
		var level = mc.level;
		if (level == null) return new Sun(new Vector3f(0, 1, 0), 0, 0, 0);
		float a = mc.gameRenderer.getMainCamera().attributeProbe().getValue(net.minecraft.world.attribute.EnvironmentAttributes.SUN_ANGLE, 1.0f)
				* (float) (Math.PI / 180);
		Vector3f dir = new Vector3f((float) -Math.sin(a), (float) Math.cos(a), 0);
		float day = Math.clamp(dir.y * 3 + 0.2f, 0, 1) * (1 - level.getRainLevel(1.0f) * 0.8f);
		float k = 2.0f * day;
		return new Sun(dir, k, k * 0.96f, k * 0.88f);
	}

	public static void clear() {
		synchronized (SMOKES) {
			SMOKES.clear();
		}
		synchronized (BLASTS) {
			BLASTS.clear();
		}
		synchronized (TRACERS) {
			TRACERS.clear();
		}
		flash = null;
	}

	/** Every client tick: smokes past their lifetime are dropped. */
	public static void tick() {
		float now = SmokeRenderer.now();
		synchronized (SMOKES) {
			SMOKES.removeIf(s -> now - s.start > GONE);
		}
	}

	/** The smokes to draw this frame (render thread): their frames advanced and light refreshed. */
	static List<Drawn> drawn() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return List.of();
		float now = SmokeRenderer.now();
		List<Drawn> out = new ArrayList<>();
		synchronized (SMOKES) {
			for (Smoke s : SMOKES) {
				s.update(mc, now);
				if (s.densityScale() > 0) out.add(s);
			}
		}
		return out;
	}

	/** CS2's smoothstep on 0..1: (3 - 2t) t^2. */
	private static float smooth(float t) {
		t = Math.clamp(t, 0, 1);
		return (3 - 2 * t) * t * t;
	}

	/** Seconds after the detonation when a smoke is gone (CS2's client fades it from 17 s to 22 s). */
	static final float GONE = 22;

	private static final class Smoke implements Drawn {
		final SmokeVolume volume;
		final int slot;
		final float[] tint;
		final float start;
		final byte[] density = new byte[32 * 32 * 32 * 4], light = new byte[32 * 32 * 32 * 4];
		Vec3 min, max;
		boolean densityDirty = true, lightDirty = true;
		float frameTime, lightTime = -1, age;

		Smoke(SmokeVolume volume, int slot, float[] tint, float start) {
			this.volume = volume;
			this.slot = slot;
			this.tint = tint;
			this.start = start;
			this.min = volume.origin;
			this.max = volume.origin;
		}

		void update(Minecraft mc, float now) {
			age = now - start;
			// each 0.1 s step: last step's values move over, this step's go in (CS2's client writes its texture per step)
			while ((volume.steps() + 1) * SmokeVolume.STEP_SECONDS <= age) {
				volume.step();
				int[] b = volume.writeTexture(density);
				densityDirty = true;
				frameTime = (float) volume.seconds();
				if (b != null) {
					min = corner(b[0] - 2, b[1] - 2, b[2] - 2);
					max = corner(b[3] + 2, b[4] + 2, b[5] + 2);
				}
			}
			if (lightTime < 0 || now - lightTime > 0.25f) {
				lightTime = now;
				refreshLight(mc);
			}
		}

		/** A voxel's lowest corner (CS2 axes: y north) in blocks. */
		private Vec3 corner(int x, int y, int z) {
			double s = SmokeVolume.VOXEL / SmokeRenderer.UPB;
			return volume.origin.add((x - 16) * s, (z - 16) * s, -(y - 16) * s);
		}

		/** Per voxel: block light, sky light (both /15, looked up in the lightmap by the shader) and sky openness. */
		private void refreshLight(Minecraft mc) {
			var level = mc.level;
			BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
			for (int z = 0; z < 32; z++)
				for (int y = 0; y < 32; y++)
					for (int x = 0; x < 32; x++) {
						Vec3 p = volume.center(x, y, z);
						bp.set(p.x, p.y, p.z);
						int block = level.getBrightness(LightLayer.BLOCK, bp), sky = level.getBrightness(LightLayer.SKY, bp);
						int o = (x + 32 * (y + 32 * z)) * 4;
						light[o] = (byte) (block * 17);
						light[o + 1] = (byte) (sky * 17);
						light[o + 2] = 0;
						// FLAGGED: the sun's visibility (CS2: its shadow map) as how open the voxel is to the sky
						float open = sky / 15f;
						light[o + 3] = (byte) (open * open * 255);
					}
			// solid voxels (inside blocks, dark) take their brightest open neighbour's light, so the shader's blending
			// between voxels doesn't darken smoke against walls (FLAGGED: ours)
			for (int z = 0; z < 32; z++)
				for (int y = 0; y < 32; y++)
					for (int x = 0; x < 32; x++) {
						if (!volume.solid(x, y, z)) continue;
						int o = (x + 32 * (y + 32 * z)) * 4, best = -1;
						for (int[] d : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
							int qx = x + d[0], qy = y + d[1], qz = z + d[2];
							if (qx < 0 || qy < 0 || qz < 0 || qx > 31 || qy > 31 || qz > 31 || volume.solid(qx, qy, qz)) continue;
							int q = (qx + 32 * (qy + 32 * qz)) * 4;
							if (best < 0 || (light[q] & 255) + (light[q + 1] & 255) > (light[best] & 255) + (light[best + 1] & 255)) best = q;
						}
						if (best >= 0) System.arraycopy(light, best, light, o, 4);
					}
			lightDirty = true;
		}

		@Override
		public int slot() {
			return slot;
		}

		@Override
		public Vec3 center() {
			return volume.origin;
		}

		@Override
		public Vec3 min() {
			return min;
		}

		@Override
		public Vec3 max() {
			return max;
		}

		@Override
		public byte[] density() {
			densityDirty = false;
			return density;
		}

		@Override
		public boolean densityDirty() {
			return densityDirty;
		}

		@Override
		public byte[] light() {
			lightDirty = false;
			return light;
		}

		@Override
		public boolean lightDirty() {
			return lightDirty;
		}

		@Override
		public float age() {
			return age;
		}

		@Override
		public float frameBlend() {
			return Math.clamp((age - frameTime) / (float) SmokeVolume.STEP_SECONDS, 0, 1);
		}

		@Override
		public float[] tint() {
			return tint;
		}

		/** CS2: the team colour gives way to grey from 4 s to 18 s. */
		@Override
		public float tintAmount() {
			return smooth((age - 4) / 14);
		}

		/** CS2: full until 17 s, gone at 22 s. */
		@Override
		public float densityScale() {
			return smooth((GONE - age) / 5);
		}

		/** CS2: the smoke blooms out from its middle from 0.1 s to 2 s. */
		@Override
		public float shrink() {
			return smooth((age - 0.1f) / 1.9f);
		}
	}
}
