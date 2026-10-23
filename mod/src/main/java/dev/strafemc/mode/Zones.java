package dev.strafemc.mode;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.strafemc.StrafeMc;
import dev.strafemc.net.StrafePayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Map zones drawn with the Zone Tool: bomb sites A and B and each side's buy zone, as boxes of blocks, saved in
 * <world>/strafemc_zones.json and sent to every player (the tool shows them). Round modes use them where the world
 * has any: the C4 plants inside a site, players buy inside their side's buy zone; without them, the Bomb Site and
 * Spawn Point blocks' ranges (strafemc_bombsite, strafemc_buyzone) stand in.
 */
public final class Zones {
	public enum Type {
		SITE_A("site_a", "Bomb site A", 0xFFFF4040), SITE_B("site_b", "Bomb site B", 0xFFFFA020), BUY_T("buy_t", "Terrorist buy zone", 0xFFEAD18A),
		BUY_CT("buy_ct", "Counter-Terrorist buy zone", 0xFF7FB2FF);

		public final String id, label;
		public final int color;

		Type(String id, String label, int color) {
			this.id = id;
			this.label = label;
			this.color = color;
		}

		public static Type of(String id) {
			for (Type t : values()) if (t.id.equals(id)) return t;
			return SITE_A;
		}

		public boolean site() {
			return this == SITE_A || this == SITE_B;
		}
	}

	/** A zone: its world, type and box (block corners, both included). */
	public record Zone(String dim, Type type, int x0, int y0, int z0, int x1, int y1, int z1) {
		public boolean contains(Entity e) {
			return e.level().dimension().identifier().toString().equals(dim) && contains(e.position());
		}

		public boolean contains(Vec3 p) {
			return p.x >= x0 && p.x < x1 + 1 && p.y >= y0 && p.y < y1 + 1 && p.z >= z0 && p.z < z1 + 1;
		}

		public Vec3 floorCentre() {
			return new Vec3((x0 + x1 + 1) / 2.0, y0, (z0 + z1 + 1) / 2.0);
		}
	}

	private static final Gson GSON = new Gson();
	private static final List<Zone> ALL = new ArrayList<>();
	private static MinecraftServer server;

	private Zones() {}

	public static void register() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(Zones::load);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			synchronized (ALL) {
				ALL.clear();
			}
			server = null;
		});
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> send(h.getPlayer()));
	}

	private static Path file(MinecraftServer s) {
		return s.getWorldPath(LevelResource.ROOT).resolve("strafemc_zones.json");
	}

	private static void load(MinecraftServer s) {
		server = s;
		synchronized (ALL) {
			ALL.clear();
			try {
				Path f = file(s);
				if (Files.exists(f))
					for (var e : GSON.fromJson(Files.readString(f), JsonArray.class)) {
						JsonObject o = e.getAsJsonObject();
						ALL.add(new Zone(o.get("dim").getAsString(), Type.of(o.get("type").getAsString()), o.get("x0").getAsInt(), o.get("y0").getAsInt(),
								o.get("z0").getAsInt(), o.get("x1").getAsInt(), o.get("y1").getAsInt(), o.get("z1").getAsInt()));
					}
			} catch (Exception e) {
				StrafeMc.LOG.warn("[strafemc] zones unreadable: {}", e.toString());
			}
		}
	}

	private static String json() {
		JsonArray a = new JsonArray();
		synchronized (ALL) {
			for (Zone z : ALL) {
				JsonObject o = new JsonObject();
				o.addProperty("dim", z.dim());
				o.addProperty("type", z.type().id);
				o.addProperty("x0", z.x0());
				o.addProperty("y0", z.y0());
				o.addProperty("z0", z.z0());
				o.addProperty("x1", z.x1());
				o.addProperty("y1", z.y1());
				o.addProperty("z1", z.z1());
				a.add(o);
			}
		}
		return a.toString();
	}

	private static void changed() {
		if (server == null) return;
		try {
			Files.writeString(file(server), json());
		} catch (Exception e) {
			StrafeMc.LOG.warn("[strafemc] zones not saved: {}", e.toString());
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) send(p);
	}

	static void send(ServerPlayer p) {
		if (ServerPlayNetworking.canSend(p, StrafePayloads.ZoneList.TYPE)) ServerPlayNetworking.send(p, new StrafePayloads.ZoneList(json()));
	}

	public static List<Zone> all() {
		synchronized (ALL) {
			return List.copyOf(ALL);
		}
	}

	/** The box between two corner blocks, both included (the Zone Tool's corners are the spaces in front of what was clicked). */
	public static Zone add(ServerPlayer by, Type type, BlockPos a, BlockPos b) {
		Zone z = new Zone(by.level().dimension().identifier().toString(), type, Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
				Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
		synchronized (ALL) {
			ALL.add(z);
		}
		changed();
		return z;
	}

	/** Removes the zones that hold a point (the block above where the tool was used); how many went. */
	public static int removeAt(ServerPlayer by, Vec3 at) {
		String dim = by.level().dimension().identifier().toString();
		int n;
		synchronized (ALL) {
			int before = ALL.size();
			ALL.removeIf(z -> z.dim().equals(dim) && z.contains(at));
			n = before - ALL.size();
		}
		if (n > 0) changed();
		return n;
	}

	public static int clear() {
		int n;
		synchronized (ALL) {
			n = ALL.size();
			ALL.clear();
		}
		changed();
		return n;
	}

	/** The zone of a kind an entity stands in, or null. */
	static Zone in(Entity e, java.util.function.Predicate<Type> kind) {
		for (Zone z : all()) if (kind.test(z.type()) && z.contains(e)) return z;
		return null;
	}

	static boolean any(java.util.function.Predicate<Type> kind) {
		for (Zone z : all()) if (kind.test(z.type())) return true;
		return false;
	}
}
